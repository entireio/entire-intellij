package io.entire.app.repo

import io.entire.app.ports.HostLog
import io.entire.core.CallResult
import io.entire.core.Failure
import io.entire.core.FailureKind
import io.entire.core.checkpoint.CheckpointStore
import io.entire.core.checkpoint.SessionCheckpoint
import io.entire.core.cli.EntireCli
import io.entire.core.cli.REMOTE_SESSIONS_PAGE
import io.entire.core.cli.RemoteCheckpoint
import io.entire.core.cli.RemoteSession
import io.entire.core.cli.RemoteSessionPage
import io.entire.core.cli.SessionRow
import io.entire.core.format.parseTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Entire's list of a repository's sessions, as far as it has been read. It
 * is the list on the Sessions page of entire.com, and it names each session.
 */
class RemoteSessionList {
    /** By session ID, in Entire's order: most recent activity first. */
    val sessions = LinkedHashMap<String, RemoteSession>()

    /** Asks Entire for the next page. Null once the last page has been read. */
    var nextCursor: String? = null

    /** The first page is being read. */
    var loading = false

    /** Why the last read failed. */
    var failure: Failure? = null
}

/** The checkpoints Entire lists for one session. `checkpoints` is set once the read has answered. */
class RemoteCheckpointRead(val read: Deferred<List<RemoteCheckpoint>?>) {
    var checkpoints: List<RemoteCheckpoint>? = null

    /** When the read failed. It is not tried again for a while, or until a manual refresh. */
    var failedAt: Long? = null

    /** Why it failed. */
    var failure: Failure? = null
}

/** The opening prompt that was read for a session, or null when none was found among that many checkpoints. */
class SessionName(val name: String?, val checkpoints: Int)

class SessionEntry(val sessionId: String) {
    var agent: String? = null
    var model: String? = null

    /** `active`, `idle`, `ended`, or null when only checkpoints mention it. */
    var status: String? = null
    var branch: String? = null

    /**
     * The session's name. For a session that Entire lists, it is the name
     * entire.com shows: one a person set, or else the title Entire wrote. For
     * any other, it is the prompt that opened the session, known once the
     * transcript of one of its checkpoints has been read.
     */
    var name: String? = null

    /** The prompt that opened the session, when that is known and is not its name. */
    var opening: String? = null

    /** How many checkpoints Entire counts for the session. Set for a session that Entire lists. */
    var listedCheckpoints: Int? = null

    /** Entire's own list of the session's checkpoints has been read, and `checkpoints` holds it. */
    var checkpointsFromEntire = false

    /** The latest prompt, known only when local session state exists. */
    var lastPrompt: String? = null

    /** Message of the newest checkpoint: its commit subject, or its prompt when no commit carries it. */
    var title: String? = null

    /** ISO time of the last activity or the newest checkpoint. */
    var lastActive: String? = null

    /** The row from `entire session list`, when local state exists. */
    var local: SessionRow? = null

    /** Newest first. */
    var checkpoints: MutableList<SessionCheckpoint> = ArrayList()

    /** The session may have earlier checkpoints than the ones listed here. */
    var mayHaveEarlier = false

    val live: Boolean get() = status == "active" || status == "idle"
}

class SessionLists(val active: List<SessionEntry>, val recent: List<SessionEntry>)

/**
 * How long a failed read of a session's checkpoints stands. The Sessions
 * view asks again each time it redraws, which it does every few seconds.
 */
private const val REMOTE_CHECKPOINTS_RETRY_MS = 60_000L

/** Pages of sessions one read may ask Entire for, so a long list cannot be read to its end in one go. */
private const val MAX_REMOTE_SESSION_PAGES_PER_READ = 4

private fun instant(date: String?): Long = date?.let { parseTime(it) } ?: 0

private fun addRemoteSessions(list: RemoteSessionList, page: RemoteSessionPage, asked: String? = null) {
    // A session moves to the front when it sees activity, so it can turn up again on a later page.
    for (session in page.sessions) list.sessions.putIfAbsent(session.sessionId, session)
    // A cursor that leads back to itself would be asked for without end.
    list.nextCursor = page.nextCursor?.takeIf { it != asked }
}

/**
 * The sessions of the open repositories: what the clone knows of them, what
 * Entire lists, and the names read from their transcripts.
 *
 * Two of its reads reach the network, through the CLI's saved login: Entire's
 * list of sessions, and Entire's list of one session's checkpoints. Neither
 * runs until the Sessions tab asks. Everything here runs on the manager's own
 * thread.
 */
class SessionDirectory(
    private val scope: CoroutineScope,
    private val state: CoroutineDispatcher,
    private val cli: () -> EntireCli?,
    private val store: () -> CheckpointStore,
    private val log: HostLog,
    private val now: () -> Long,
    private val changed: () -> Unit,
    private val repoFor: (String) -> RepoContext?,
) {
    private val naming = HashSet<String>()
    private val nameQueue = ArrayDeque<suspend () -> Unit>()
    private var nameWorkers = 0
    private var namesRedraw: Job? = null

    /** Sessions for the Sessions view: running ones first, then the rest by recency. */
    fun sessions(repo: RepoContext): SessionLists {
        val entries = LinkedHashMap<String, SessionEntry>()
        fun entry(sessionId: String): SessionEntry = entries.getOrPut(sessionId) { SessionEntry(sessionId) }

        for (indexed in repo.index?.sessions() ?: emptyList()) {
            val target = entry(indexed.sessionId)
            target.checkpoints = indexed.checkpoints.toMutableList()
            if (indexed.mayHaveEarlier) target.mayHaveEarlier = true
            indexed.agent?.ifEmpty { null }?.let { target.agent = it }
            indexed.lastDate?.ifEmpty { null }?.let { target.lastActive = it }
            indexed.checkpoints.firstOrNull()?.message?.ifEmpty { null }?.let { target.title = it }
        }

        for (row in repo.sessionRows ?: emptyList()) {
            val target = entry(row.sessionId)
            target.local = row
            row.agent?.let { target.agent = it }
            row.model?.let { target.model = it }
            row.status?.let { target.status = it }
            row.lastPrompt?.let { target.lastPrompt = it }
            val lastActive = row.lastActive ?: row.endedAt ?: row.startedAt
            if (lastActive != null) {
                val known = target.lastActive
                val newer = known == null || (parseTime(lastActive) ?: Long.MIN_VALUE) > (parseTime(known) ?: Long.MAX_VALUE)
                if (newer) target.lastActive = lastActive
            }
            val last = row.lastCheckpointId
            if (last != null && target.checkpoints.none { it.checkpointId == last }) target.checkpoints.add(SessionCheckpoint(last))
        }

        for (live in repo.status?.activeSessions ?: emptyList()) {
            val target = entry(live.sessionId ?: continue)
            target.status = live.status ?: "active"
            live.agent?.let { target.agent = it }
            live.model?.let { target.model = it }
            live.branch?.let { target.branch = it }
        }

        // Entire's list names its sessions, counts their checkpoints, and adds
        // the ones this clone has no data for, such as those of other branches.
        for (listed in repo.remoteSessions?.sessions?.values ?: emptyList()) {
            val target = entry(listed.sessionId)
            listed.name?.let { target.name = it }
            if (listed.prompt != null && listed.prompt != listed.name) target.opening = listed.prompt
            if (target.agent == null) target.agent = listed.agent
            if (target.model == null) target.model = listed.model
            listed.checkpointCount?.let { target.listedCheckpoints = it }
            val lastActive = listed.lastActivityAt ?: listed.startedAt
            if (lastActive != null && instant(lastActive) > instant(target.lastActive)) target.lastActive = lastActive
        }
        for ((sessionId, read) in repo.remoteCheckpoints) {
            val target = entries[sessionId] ?: continue
            val listed = read.checkpoints ?: continue
            val known = target.checkpoints.mapTo(HashSet()) { it.checkpointId }
            for (checkpoint in listed) {
                if (checkpoint.checkpointId in known) continue
                target.checkpoints.add(SessionCheckpoint(checkpoint.checkpointId, checkpoint.date, checkpoint.message))
            }
            target.checkpoints.sortByDescending { instant(it.date) }
            target.checkpointsFromEntire = true
            // Entire lists the session from its start.
            target.mayHaveEarlier = false
        }

        val all = entries.values.toList()
        for (session in all) {
            val opening = repo.sessionNames[session.sessionId]?.name ?: continue
            // The opening prompt names a session that Entire has not named.
            if (session.name == null) session.name = opening else if (session.name != opening && session.opening == null) session.opening = opening
        }
        return SessionLists(
            active = all.filter { it.live }.sortedByDescending { instant(it.lastActive) },
            recent = all.filter { !it.live }.sortedByDescending { instant(it.lastActive) },
        )
    }

    fun entry(repo: RepoContext, sessionId: String): SessionEntry? {
        val lists = sessions(repo)
        return (lists.active + lists.recent).firstOrNull { it.sessionId == sessionId }
    }

    /**
     * Loads the full session list. The CLI prunes session state idle for more
     * than seven days while it lists, so this runs only when the Sessions view
     * is first shown and on a manual refresh.
     */
    suspend fun loadSessionRows(repo: RepoContext) {
        val current = cli()
        if (current == null || !repo.enabled) {
            repo.sessionRowsLoading = false
            return
        }
        val rows = current.sessionList(repo.root)
        repo.sessionRowsLoading = false
        when (rows) {
            is CallResult.Ok -> repo.sessionRows = rows.value
            is CallResult.Failed -> {
                if (repo.sessionRows == null) repo.sessionRows = emptyList()
                log.warn("${repo.name}: ${rows.failure.message} ${rows.failure.detail.orEmpty()}".trim())
            }
        }
        changed()
    }

    /** Starts Entire's list of a repository's sessions over from its first page. */
    suspend fun loadRemoteSessions(repo: RepoContext) {
        val current = cli()
        if (current == null || !repo.enabled || repo.entireRepo == null) return
        val read = ++repo.remoteSessionReads
        val list = RemoteSessionList().also { it.loading = true }
        // A refresh keeps the names on screen until the fresh ones arrive.
        val previous = repo.remoteSessions
        if (previous == null) {
            repo.remoteSessions = list
            changed()
        }
        val page = current.remoteSessions(repo.root, REMOTE_SESSIONS_PAGE)
        if (read != repo.remoteSessionReads) return
        list.loading = false
        when (page) {
            is CallResult.Ok -> addRemoteSessions(list, page.value)
            is CallResult.Failed -> {
                list.failure = page.failure
                log.warn("${repo.name}: ${page.failure.message} ${page.failure.detail.orEmpty()}".trim())
            }
        }
        if (page is CallResult.Failed && previous != null && previous.sessions.isNotEmpty()) {
            // The names read before still hold. Only the reason for the failure is new.
            previous.failure = page.failure
        } else {
            repo.remoteSessions = list
        }
        changed()
        // A list that had grown past its first page keeps its rows named.
        readMoreRemoteSessions(repo)
    }

    /**
     * Asks Entire for pages until its list holds as many sessions as the
     * Sessions view shows rows, or Entire has no more. Entire lists the most
     * recent sessions first, and so does the view, so that many sessions name
     * every row on show that Entire knows.
     *
     * Reads of one list run one after another, because each continues from
     * the cursor the one before it returned.
     */
    suspend fun readMoreRemoteSessions(repo: RepoContext): Boolean {
        val list = repo.remoteSessions ?: return true
        fun enough() = list.nextCursor == null || list.sessions.size >= repo.limit(Lists.RECENT_SESSIONS)
        if (enough()) return true
        return repo.remotePages.withLock {
            repeat(MAX_REMOTE_SESSION_PAGES_PER_READ) {
                // The list was replaced by a fresh read while this waited.
                if (repo.remoteSessions !== list || enough()) return@withLock true
                val current = cli()
                val cursor = list.nextCursor
                if (current == null || cursor == null) return@withLock false
                val page = current.remoteSessions(repo.root, REMOTE_SESSIONS_PAGE, cursor)
                if (repo.remoteSessions !== list) return@withLock true
                when (page) {
                    is CallResult.Failed -> {
                        log.warn("${repo.name}: ${page.failure.message} ${page.failure.detail.orEmpty()}".trim())
                        return@withLock false
                    }
                    is CallResult.Ok -> {
                        addRemoteSessions(list, page.value, cursor)
                        // Names appear as each page arrives.
                        changed()
                    }
                }
            }
            true
        }
    }

    /** Whether Entire lists more sessions of the repository than have been read. */
    fun moreOnEntire(repo: RepoContext): Boolean = repo.remoteSessions?.nextCursor != null

    /**
     * What the Sessions view says above its rows, when Entire could not name
     * them for a reason the reader can do something about.
     */
    fun notice(repos: List<RepoContext>): String? {
        val loggedOut = repos.any { it.remoteSessions?.failure?.kind == FailureKind.NOT_LOGGED_IN }
        return if (loggedOut) {
            "The Entire CLI is not logged in, so each session shows its opening prompt as its name."
        } else {
            null
        }
    }

    /**
     * The checkpoints of a session, newest first. The clone knows the ones on
     * the checked-out branch. When Entire counts more than that, its list of
     * the session's checkpoints is read as well, once, and the two are shown
     * as one list.
     *
     * Entire sends that list with the session's transcript, which takes
     * seconds for a long session. So the checkpoints the clone knows are
     * returned at once, and the views are redrawn when Entire's arrive. With
     * `wait`, or for a session the clone knows no checkpoint of, the call
     * waits for Entire instead.
     */
    suspend fun checkpoints(repo: RepoContext, sessionId: String, wait: Boolean = false): List<SessionCheckpoint> {
        val entry = entry(repo, sessionId) ?: return emptyList()
        val listed = entry.listedCheckpoints
        val short = listed != null && listed > entry.checkpoints.size
        if (!short || entry.checkpointsFromEntire) return entry.checkpoints
        val read = readRemoteCheckpoints(repo, sessionId)
        if (!wait && entry.checkpoints.isNotEmpty()) return entry.checkpoints
        read.await()
        return entry(repo, sessionId)?.checkpoints ?: entry.checkpoints
    }

    /**
     * Why Entire's list of a session's checkpoints could not be read, while
     * that read is not yet tried again. Null when no read failed lately.
     */
    fun checkpointsFailure(repo: RepoContext, sessionId: String): Failure? {
        val read = repo.remoteCheckpoints[sessionId] ?: return null
        val failedAt = read.failedAt ?: return null
        return if (now() - failedAt < REMOTE_CHECKPOINTS_RETRY_MS) read.failure else null
    }

    /**
     * Entire's list of a session's checkpoints, read once. The views are
     * redrawn when it arrives. A read that fails is tried again after a while.
     */
    private fun readRemoteCheckpoints(repo: RepoContext, sessionId: String): Deferred<List<RemoteCheckpoint>?> {
        val known = repo.remoteCheckpoints[sessionId]
        val failedAt = known?.failedAt
        if (known != null && (failedAt == null || now() - failedAt < REMOTE_CHECKPOINTS_RETRY_MS)) return known.read
        val current = cli()
        if (current == null || repo.entireRepo == null) return CompletableDeferred(null)
        var slot: RemoteCheckpointRead? = null
        // The read belongs to the directory. A panel that stops waiting does not stop it for the tree.
        val read = scope.async(state, start = CoroutineStart.LAZY) {
            val result = current.remoteSessionCheckpoints(repo.root, sessionId)
            val mine = slot
            if (mine == null || repo.remoteCheckpoints[sessionId] !== mine) return@async null
            when (result) {
                is CallResult.Failed -> {
                    mine.failedAt = now()
                    mine.failure = result.failure
                    log.warn("Session $sessionId: ${result.failure.message} ${result.failure.detail.orEmpty()}".trim())
                    // The session's row says why its checkpoints are not listed.
                    changed()
                    null
                }
                is CallResult.Ok -> {
                    mine.checkpoints = result.value
                    changed()
                    result.value
                }
            }
        }
        slot = RemoteCheckpointRead(read)
        repo.remoteCheckpoints[sessionId] = slot
        read.start()
        return read
    }

    /**
     * Reads, in the background, the opening prompt of each of these sessions
     * that has a checkpoint and no name yet. The Sessions view asks for the
     * rows it shows, and the names appear as they arrive.
     */
    fun nameSessions(repo: RepoContext, sessions: List<SessionEntry>) {
        // Entire is about to name most of these. The rest are read once it has answered.
        if (repo.remoteSessions?.loading == true) return
        for (session in sessions) {
            if (repo.remoteSessions?.sessions?.get(session.sessionId)?.name != null) continue
            val count = session.checkpoints.size
            val known = repo.sessionNames[session.sessionId]
            // A session with no name is tried again only once it has checkpoints that were not looked at.
            if (count == 0 || (known != null && (known.name != null || known.checkpoints >= count))) continue
            val key = repo.root + "\u0000" + session.sessionId
            if (!naming.add(key)) continue
            nameQueue.addLast {
                val name = try {
                    readSessionName(repo, session)
                } finally {
                    naming.remove(key)
                }
                // A panel may have named the session in the meantime. A name that was read stands.
                if (repoFor(repo.root) === repo && repo.sessionNames[session.sessionId]?.name == null) {
                    repo.sessionNames[session.sessionId] = SessionName(name, count)
                    if (name != null) namesChanged()
                }
            }
        }
        runNameQueue()
    }

    /** Records the opening prompt of a session whose transcript a panel has read. */
    fun noteSessionName(repo: RepoContext, sessionId: String, name: String) {
        if (repo.sessionNames[sessionId]?.name == name) return
        repo.sessionNames[sessionId] = SessionName(name, Int.MAX_VALUE)
        namesChanged()
    }

    /**
     * The opening prompt of a session, from the start of the transcript that
     * one of its checkpoints stores. A transcript that holds the session from
     * its start gives the same prompt whichever checkpoint stores it. The
     * oldest known checkpoint is read first, and then the newest, because an
     * old checkpoint can predate stored transcripts.
     *
     * An older transcript holds only its checkpoint's part. Its first prompt
     * is taken for the session's only when the checkpoint is known to be the
     * session's first.
     */
    private suspend fun readSessionName(repo: RepoContext, session: SessionEntry): String? {
        val ids = session.checkpoints.map { it.checkpointId }
        val oldest = ids.lastOrNull()
        val tries = LinkedHashSet(listOf(oldest, ids.firstOrNull(), ids.getOrNull(ids.size / 2)))
        var ofFirstCheckpoint: String? = null
        for (checkpointId in tries) {
            if (checkpointId.isNullOrEmpty()) continue
            val tree = store().locate(repo.root, checkpointId) ?: continue
            val prompt = store().openingPrompt(repo.root, tree, session.sessionId) ?: continue
            if (prompt.fromSessionStart) return prompt.text
            if (checkpointId == oldest && !session.mayHaveEarlier) ofFirstCheckpoint = prompt.text
        }
        return ofFirstCheckpoint
    }

    /** Runs the queued name reads two at a time, so that they do not hold up the git calls of a click. */
    private fun runNameQueue() {
        while (nameWorkers < 2 && nameQueue.isNotEmpty()) {
            val task = nameQueue.removeFirst()
            nameWorkers += 1
            scope.launch(state) {
                try {
                    task()
                } finally {
                    nameWorkers -= 1
                    runNameQueue()
                }
            }
        }
    }

    /** Redraws the views once for the names that arrive close together. */
    private fun namesChanged() {
        if (namesRedraw?.isActive == true) return
        namesRedraw = scope.launch(state) {
            delay(60)
            changed()
        }
    }
}
