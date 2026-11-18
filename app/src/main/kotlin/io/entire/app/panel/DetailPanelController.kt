package io.entire.app.panel

import io.entire.app.actions.Actions
import io.entire.app.repo.RepoContext
import io.entire.app.repo.RepoManager
import io.entire.app.repo.SessionEntry
import io.entire.core.CallResult
import io.entire.core.FailureKind
import io.entire.core.checkpoint.CheckpointTree
import io.entire.core.checkpoint.StoredSession
import io.entire.core.cli.CheckpointDetail
import io.entire.core.cli.totalTokens
import io.entire.core.format.headline
import io.entire.core.git.CommitRecord
import io.entire.core.protocol.CopyTarget
import io.entire.core.protocol.DetailMode
import io.entire.core.protocol.DetailTarget
import io.entire.core.protocol.HeaderCommit
import io.entire.core.protocol.HeaderFile
import io.entire.core.protocol.HeaderLocalSession
import io.entire.core.protocol.HeaderPosition
import io.entire.core.protocol.HeaderSession
import io.entire.core.protocol.HostMessage
import io.entire.core.protocol.PanelHeader
import io.entire.core.protocol.TranscriptInfo
import io.entire.core.protocol.TranscriptSource
import io.entire.core.protocol.WebviewMessage
import io.entire.core.protocol.readWebviewMessage
import io.entire.core.transcript.FullBlock
import io.entire.core.transcript.FullTurn
import io.entire.core.transcript.ParsedTranscript
import io.entire.core.transcript.WireTurn
import io.entire.core.transcript.openingPrompt
import io.entire.core.transcript.parseCliText
import io.entire.core.transcript.parseCompactTranscript
import io.entire.core.transcript.toWireTurn
import io.entire.core.transcript.toolInputText
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val TURNS_PER_MESSAGE = 100

/** Tool inputs and results beyond this size open in an editor instead. */
private const val BODY_LIMIT = 256 * 1024
private const val IMAGE_LIMIT = 8 * 1024 * 1024
private val IMAGE_TYPES = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")
private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Where a panel's messages go: the page that draws it, and the tab around the page. */
interface PanelSink {
    fun post(message: JsonObject)

    fun setTitle(title: String)

    /** The panel now shows this. The IDE saves it, so the tab can come back after a restart. */
    fun showing(target: DetailTarget)
}

/** Everything the IDE keeps for the panel's current target. */
private class Loaded(
    val repo: RepoContext,
    var header: PanelHeader,
    val info: TranscriptInfo,
    val turns: List<FullTurn>,
    val wire: List<WireTurn>,
    val detail: CheckpointDetail? = null,
    val checkpointId: String? = null,
    val commit: CommitRecord? = null,
    /** Slot of the selected session inside the checkpoint. */
    val sessionIndex: Int? = null,
    val sessionId: String? = null,
    /** The checkpoints just before and after this one in the selected session. */
    val previous: String? = null,
    val next: String? = null,
    val tree: CheckpointTree? = null,
    val stored: StoredSession? = null,
)

private class Transcript(
    val parsed: ParsedTranscript,
    val source: TranscriptSource,
    /** The transcript holds the session from its start, so its first prompt is the one that opened the session. */
    val fromSessionStart: Boolean,
    val notice: String? = null,
)

/** What a panel shows, for a test or a check that cannot look at the page. */
class PanelSnapshot(val header: PanelHeader?, val transcript: TranscriptInfo?, val turns: Int, val error: String?)

/**
 * Drives one detail panel: loads its data and answers the page's requests.
 *
 * Everything here runs on the manager's own thread. The page only names
 * positions: which session, which turn, which block. The checkpoint to step
 * to, the commit to diff, and the file to open all come from what this class
 * loaded itself.
 */
class DetailPanelController(
    private val scope: CoroutineScope,
    private val manager: RepoManager,
    private val actions: Actions,
    private val sink: PanelSink,
    private var target: DetailTarget,
    /** Called when the tab moves on by itself, to another checkpoint of the session or another session of the checkpoint. */
    private val onShown: (DetailTarget) -> Unit = {},
) {
    private var loaded: Loaded? = null
    private var loadJob: Job? = null
    private var loadSequence = 0
    private var ready = false
    private var error: HostMessage.Error? = null

    /** A session the user picked in the panel, which overrides the target's preselection. */
    private var pickedPosition: Int? = null
    private var disposed = false

    fun start() {
        scope.launch(manager.dispatcher) { load() }
    }

    /** Points the panel at something else, reusing the tab. */
    fun show(next: DetailTarget) {
        scope.launch(manager.dispatcher) { retarget(next) }
    }

    /** A message from the page, as it arrived. Anything that is not one of the page's messages is dropped. */
    fun receive(raw: JsonElement?) {
        val message = readWebviewMessage(raw) ?: return
        scope.launch(manager.dispatcher) { onMessage(message) }
    }

    /** The page was loaded again, so it has nothing and will ask for everything. */
    fun pageReset() {
        scope.launch(manager.dispatcher) { ready = false }
    }

    fun dispose() {
        scope.launch(manager.dispatcher) {
            disposed = true
            loadJob?.cancel()
        }
    }

    suspend fun snapshot(): PanelSnapshot = kotlinx.coroutines.withContext(manager.dispatcher) {
        val current = loaded
        PanelSnapshot(current?.header, current?.info, current?.wire?.size ?: 0, error?.message)
    }

    private fun retarget(next: DetailTarget) {
        target = next
        pickedPosition = null
        load()
    }

    /** Moves to the checkpoint before or after this one in the selected session, when there is one. */
    private fun step(next: Boolean) {
        val current = loaded ?: return
        // The checkpoint comes from the IDE's own index, never from the page.
        val checkpointId = (if (next) current.next else current.previous) ?: return
        retarget(DetailTarget(current.repo.root, DetailMode.CHECKPOINT, checkpointId = checkpointId, sessionId = current.sessionId))
        onShown(target)
    }

    /** Shows another session of the checkpoint: the one at `position` among its sessions. */
    private fun selectSession(position: Int) {
        val current = loaded ?: return
        val session = current.detail?.sessions?.getOrNull(position) ?: return
        val checkpointId = current.checkpointId ?: return
        pickedPosition = position
        load()
        onShown(DetailTarget(current.repo.root, DetailMode.CHECKPOINT, checkpointId, current.commit?.sha, session.sessionId))
    }

    private fun post(message: HostMessage) {
        if (ready && !disposed) sink.post(message.toJson())
    }

    private fun sendLoaded() {
        val current = loaded ?: return
        sink.showing(target)
        post(HostMessage.Init(current.header, current.info, target))
        if (current.wire.isEmpty()) {
            post(HostMessage.Turns(emptyList(), done = true))
            return
        }
        var from = 0
        while (from < current.wire.size) {
            val until = minOf(from + TURNS_PER_MESSAGE, current.wire.size)
            post(HostMessage.Turns(current.wire.subList(from, until), done = until >= current.wire.size))
            from = until
        }
    }

    private fun showError(message: String, canRetry: Boolean) {
        loaded = null
        error = HostMessage.Error(message, canRetry).also { post(it) }
    }

    private fun finish(next: Loaded) {
        loaded = next
        sink.setTitle(shortTitle(next.header.title))
        sendLoaded()
    }

    private fun load() {
        val sequence = ++loadSequence
        loadJob?.cancel()
        loaded = null
        error = null
        post(HostMessage.Loading("Loading"))
        loadJob = scope.launch(manager.dispatcher) { read(sequence) }
    }

    private suspend fun read(sequence: Int) {
        fun stale() = sequence != loadSequence || disposed

        manager.whenLoaded()
        if (stale()) return
        val repo = manager.repoFor(target.repoRoot)
        if (repo == null) {
            showError("The repository for this tab is not open in this project.", false)
            return
        }

        val wantedSession = target.sessionId
        val entry = if (target.mode == DetailMode.SESSION && wantedSession != null) manager.sessions.entry(repo, wantedSession) else null
        var checkpointId = if (target.mode == DetailMode.CHECKPOINT) {
            target.checkpointId
        } else {
            // A session that only Entire's search found starts at the checkpoint that matched.
            entry?.checkpoints?.firstOrNull()?.checkpointId ?: entry?.local?.lastCheckpointId ?: target.checkpointId
        }
        if (checkpointId == null && entry != null && (entry.listedCheckpoints ?: 0) > 0) {
            // A session of another branch: the clone has none of its checkpoints, and Entire lists them.
            checkpointId = manager.sessions.checkpoints(repo, entry.sessionId, wait = true).firstOrNull()?.checkpointId
            if (stale()) return
        }

        if (checkpointId == null) {
            finish(sessionOnly(repo, entry))
            return
        }

        val detail = manager.detail(repo, checkpointId)
        if (stale()) return
        if (detail is CallResult.Failed) {
            if (detail.failure.kind == FailureKind.NOT_FOUND) {
                // The checkpoint's data was never uploaded. The commit is still here, so show that.
                val commit = findCommit(repo, checkpointId)
                val files = files(repo, emptyList(), commit)
                if (stale()) return
                finish(withoutData(repo, checkpointId, commit, files, entry))
            } else {
                showError(detail.failure.message, true)
            }
            return
        }
        val value = (detail as CallResult.Ok).value

        val sessions = value.sessions
        val position = selectPosition(value)
        val slot = sessions.getOrNull(position)

        val commit = findCommit(repo, checkpointId)
        if (stale()) return
        val touched = if (slot != null && slot.filesTouched.isNotEmpty()) slot.filesTouched else value.filesTouched
        val files = files(repo, touched, commit)
        if (stale()) return

        val tree = manager.store.locate(repo.root, checkpointId)
        val stored = if (tree != null && slot != null) manager.store.sessions(repo.root, tree).getOrNull(slot.index) else null
        if (stale()) return
        val transcript = transcript(repo, checkpointId, tree, stored, isLatestSession = position == sessions.size - 1)
        if (stale()) return

        val headerSessions = sessions.map { session ->
            HeaderSession(
                index = session.index,
                sessionId = session.sessionId,
                agent = session.agent,
                model = session.model,
                tokens = session.tokenUsage?.let { totalTokens(it) },
                createdAt = session.createdAt,
                intent = session.summary?.intent,
                outcome = session.summary?.outcome,
            )
        }

        val row = repo.index?.row(checkpointId)
        val sessionId = slot?.sessionId ?: if (target.mode == DetailMode.SESSION) target.sessionId else null
        // The transcript opens with the prompt that names a session Entire has
        // not named. The Sessions view is told, so that its row has the name too.
        val opening = if (transcript.fromSessionStart) openingPrompt(transcript.parsed.turns) else null
        val slotSession = slot?.sessionId
        if (opening != null && slotSession != null) manager.sessions.noteSessionName(repo, slotSession, opening)
        val searched = target.sessionId?.takeIf { entry == null }?.let { manager.searchedSessionName(repo, it) }
        val sessionTitle = if (target.mode == DetailMode.SESSION) headline(entry?.name ?: opening ?: entry?.lastPrompt ?: searched, 120) else ""
        val title = sessionTitle.ifEmpty { null }
            ?: commit?.subject?.ifEmpty { null }
            ?: headline(row?.message, 120).ifEmpty { null }
            ?: checkpointId

        // The transcript does not say who sent its prompts. The CLI takes that
        // to be the author of the commit that carries the checkpoint.
        val promptAuthor = commit?.author?.ifEmpty { null } ?: repo.checkpointAuthors[checkpointId]

        // A panel on one checkpoint says where that checkpoint sits in its
        // session and can step to the ones around it. A panel on a session shows
        // the whole conversation, so it has no place to report.
        val place = if (target.mode == DetailMode.CHECKPOINT) slot?.sessionId?.let { repo.index?.position(it, checkpointId) } else null

        val header = PanelHeader(
            mode = target.mode,
            title = title,
            checkpointId = checkpointId,
            commit = commit?.let(::headerCommit),
            otherCommits = maxOf(0, (repo.index?.commitsFor(checkpointId)?.size ?: 0) - 1),
            branch = value.branch,
            sessions = headerSessions,
            selected = position,
            files = files,
            position = place?.let { HeaderPosition(it.ordinal, it.total, it.mayHaveEarlier, it.previous != null, it.next != null) },
            localSession = entry?.let(::localSession),
            promptAuthor = promptAuthor,
            canOpenOnWeb = repo.entireRepo != null && (commit != null || sessionId != null),
            canShowCommitDiff = commit != null,
            canOpenRaw = slot != null,
            notice = if (value.partial) "The Entire CLI could read only part of this checkpoint." else null,
        )

        val wire = transcript.parsed.turns.mapIndexed { index, turn -> toWireTurn(turn, index) }
        val info = TranscriptInfo(
            source = transcript.source,
            total = wire.size,
            // A session view shows the whole conversation, not one checkpoint's part of it.
            start = if (target.mode == DetailMode.SESSION) 0 else transcript.parsed.startTurn,
            notice = transcript.notice,
        )

        val next = Loaded(
            repo = repo,
            header = header,
            info = info,
            turns = transcript.parsed.turns,
            wire = wire,
            detail = value,
            checkpointId = checkpointId,
            commit = commit,
            sessionIndex = slot?.index,
            sessionId = sessionId,
            previous = place?.previous,
            next = place?.next,
            tree = tree,
            stored = stored,
        )
        finish(next)
        if (promptAuthor == null && wire.isNotEmpty()) findPromptAuthor(repo, checkpointId, next)
    }

    /**
     * Asks the CLI who the author of a checkpoint is, and puts the name on the
     * prompts already drawn. This is for a checkpoint that no commit in the
     * history read so far carries, such as one made on another branch. The
     * call takes a second or two, so the transcript does not wait for it.
     */
    private suspend fun findPromptAuthor(repo: RepoContext, checkpointId: String, shown: Loaded) {
        val author = (manager.cli?.checkpointAuthor(repo.root, checkpointId) as? CallResult.Ok)?.value
        if (author.isNullOrEmpty()) return
        repo.checkpointAuthors[checkpointId] = author
        if (loaded !== shown || disposed) return
        shown.header = shown.header.copy(promptAuthor = author)
        post(HostMessage.PromptAuthor(author))
    }

    /** A local session that no checkpoint references yet: facts only, no transcript view. */
    private fun sessionOnly(repo: RepoContext, entry: SessionEntry?): Loaded {
        val sessionId = target.sessionId.orEmpty()
        return Loaded(
            repo = repo,
            header = PanelHeader(
                mode = DetailMode.SESSION,
                title = headline(entry?.name ?: entry?.lastPrompt, 120).ifEmpty { "${entry?.agent ?: "Agent"} session" },
                localSession = entry?.let(::localSession),
                canOpenOnWeb = repo.entireRepo != null && sessionId.isNotEmpty(),
                canOpenRaw = entry?.local != null,
            ),
            info = TranscriptInfo(
                TranscriptSource.NONE,
                0,
                0,
                if (entry != null) {
                    "This session has no checkpoint yet. Its transcript appears here after a commit that includes its work."
                } else {
                    "Entire has no record of this session in this repository."
                },
            ),
            turns = emptyList(),
            wire = emptyList(),
            sessionId = sessionId.ifEmpty { null },
        )
    }

    private fun selectPosition(detail: CheckpointDetail): Int {
        val count = detail.sessions.size
        if (count == 0) return 0
        pickedPosition?.takeIf { it < count }?.let { return it }
        target.sessionId?.let { wanted ->
            val found = detail.sessions.indexOfFirst { it.sessionId == wanted }
            if (found >= 0) return found
        }
        // The latest session is the one the CLI itself shows by default.
        return count - 1
    }

    private suspend fun findCommit(repo: RepoContext, checkpointId: String): CommitRecord? {
        val carriers = repo.index?.commitsFor(checkpointId) ?: emptyList()
        val wanted = target.commitSha ?: return carriers.firstOrNull()
        return carriers.firstOrNull { it.sha == wanted } ?: manager.git.commit(repo.root, wanted)
    }

    /**
     * A checkpoint that a commit names but whose session data the CLI cannot
     * find. The panel shows what git knows about the commit and says why there
     * is no transcript.
     */
    private fun withoutData(
        repo: RepoContext,
        checkpointId: String,
        commit: CommitRecord?,
        files: List<HeaderFile>,
        /** The session the tab was opened for, when it was opened for a session and not for the checkpoint. */
        session: SessionEntry?,
    ): Loaded {
        val hosted = repo.entireRepo != null
        // A session that only Entire's search found is named as the search named it.
        val searched = target.sessionId?.takeIf { session == null && target.mode == DetailMode.SESSION }?.let { manager.searchedSessionName(repo, it) }
        val sessionTitle = if (session != null) headline(session.name ?: session.lastPrompt, 120) else headline(searched, 120)
        return Loaded(
            repo = repo,
            header = PanelHeader(
                mode = if (session != null) DetailMode.SESSION else DetailMode.CHECKPOINT,
                title = sessionTitle.ifEmpty { null } ?: commit?.subject?.ifEmpty { null } ?: checkpointId,
                checkpointId = checkpointId,
                commit = commit?.let(::headerCommit),
                otherCommits = maxOf(0, (repo.index?.commitsFor(checkpointId)?.size ?: 0) - 1),
                files = files,
                // A session has a page on entire.com whether or not a commit here carries its checkpoint.
                canOpenOnWeb = hosted && (commit != null || session != null),
                canShowCommitDiff = commit != null,
                canOpenRaw = false,
            ),
            info = TranscriptInfo(
                TranscriptSource.NONE,
                0,
                0,
                if (session != null && hosted && session.listedCheckpoints != null) {
                    // Entire lists the session, so its data is there, even though the CLI did not find it.
                    "The Entire CLI could not find the session data of this session's latest checkpoint, on this machine or on the remote. " +
                        "Entire lists the session, so its page on entire.com may have the transcript."
                } else {
                    "Entire has no session data for this checkpoint. The commit records the checkpoint's ID, but the session data is uploaded " +
                        "separately, and this checkpoint's is not on this machine or on the remote. If its author still has it, running " +
                        "'entire checkpoint push' on their machine uploads it."
                },
            ),
            turns = emptyList(),
            wire = emptyList(),
            checkpointId = checkpointId,
            commit = commit,
            sessionId = session?.sessionId,
        )
    }

    /** Files the session touched, marked with a status when the commit changed them. */
    private suspend fun files(repo: RepoContext, touched: List<String>, commit: CommitRecord?): List<HeaderFile> {
        val changes = if (commit != null) manager.git.commitFiles(repo.root, commit.sha) else emptyList()
        val status = changes.associate { it.path to it.status }
        val paths = touched.ifEmpty { changes.map { it.path } }
        return paths.map { filePath ->
            // Agents record absolute paths at times. Show them relative to the repository.
            val relative = relativeTo(repo.root, filePath)
            HeaderFile(relative, status[relative]?.ifEmpty { null })
        }
    }

    private suspend fun transcript(
        repo: RepoContext,
        checkpointId: String,
        tree: CheckpointTree?,
        stored: StoredSession?,
        isLatestSession: Boolean,
    ): Transcript {
        if (tree != null && stored != null) {
            val text = manager.store.compactTranscript(repo.root, tree, stored)
            if (text != null) {
                val meta = manager.store.sessionMeta(repo.root, tree, stored)
                return Transcript(
                    parsed = parseCompactTranscript(text, meta?.compactTranscriptStart),
                    source = TranscriptSource.COMPACT,
                    // Without a start line, the file holds only this checkpoint's part of the session.
                    fromSessionStart = meta?.compactTranscriptStart != null,
                )
            }
        }
        // The CLI's text view covers only the latest session of a checkpoint.
        val cli = manager.cli
        if (isLatestSession && cli != null) {
            val text = cli.fullText(repo.root, checkpointId)
            if (text is CallResult.Ok) {
                return Transcript(
                    parsed = parseCliText(text.value),
                    source = TranscriptSource.CLI_TEXT,
                    // The CLI's text view is of the whole session.
                    fromSessionStart = true,
                    notice = "Text view from the Entire CLI. This session has no normalized transcript, so tool inputs and results are not shown.",
                )
            }
        }
        return Transcript(
            parsed = ParsedTranscript(emptyList(), 0),
            source = TranscriptSource.NONE,
            fromSessionStart = false,
            notice = "No transcript preview is available for this session. Open the raw transcript to read it.",
        )
    }

    private suspend fun onMessage(message: WebviewMessage) {
        when (message) {
            is WebviewMessage.Ready -> {
                ready = true
                when {
                    loaded != null -> sendLoaded()
                    error != null -> error?.let { post(it) }
                    else -> post(HostMessage.Loading("Loading"))
                }
                return
            }
            WebviewMessage.Retry -> {
                load()
                return
            }
            is WebviewMessage.OpenLink -> {
                actions.openExternal(message.href)
                return
            }
            else -> {}
        }

        val current = loaded ?: return
        val repo = current.repo

        when (message) {
            is WebviewMessage.SelectSession -> selectSession(message.position)
            is WebviewMessage.StepSession -> step(message.next)
            is WebviewMessage.RequestToolBody -> {
                val block = current.turns.getOrNull(message.turn)?.blocks?.getOrNull(message.block) as? FullBlock.Tool ?: return
                val input = toolInputText(block.input)
                val output = block.output.orEmpty()
                post(
                    HostMessage.ToolBody(
                        requestId = message.requestId,
                        input = input.take(BODY_LIMIT),
                        output = output.take(BODY_LIMIT),
                        truncated = input.length > BODY_LIMIT || output.length > BODY_LIMIT,
                    ),
                )
            }
            is WebviewMessage.OpenToolBody -> {
                val block = current.turns.getOrNull(message.turn)?.blocks?.getOrNull(message.block) as? FullBlock.Tool ?: return
                val parts = listOf("# ${block.name}", "", "## Input", "", toolInputText(block.input), "", "## Result", "", block.output.orEmpty())
                actions.openText(block.name, parts.joinToString("\n"))
            }
            is WebviewMessage.RequestImage -> post(image(current, message.requestId, message.turn, message.block))
            WebviewMessage.OpenOnWeb -> {
                val commit = current.commit
                val sessionId = current.sessionId
                when {
                    commit != null && current.header.mode == DetailMode.CHECKPOINT -> actions.openCommitOnWeb(repo, commit.sha)
                    sessionId != null -> actions.openSessionOnWeb(repo, sessionId)
                    commit != null -> actions.openCommitOnWeb(repo, commit.sha)
                }
            }
            WebviewMessage.ShowCommitDiff -> current.commit?.let { actions.showCommitDiff(repo, it.sha) }
            is WebviewMessage.OpenFile -> current.header.files.getOrNull(message.index)?.let { actions.openFile(repo, current.commit?.sha, it.path) }
            WebviewMessage.OpenRawTranscript -> {
                val checkpointId = current.checkpointId
                val sessionIndex = current.sessionIndex
                val sessionId = current.sessionId
                if (checkpointId != null && sessionIndex != null) {
                    actions.openRawCheckpointTranscript(repo, checkpointId, sessionIndex)
                } else if (sessionId != null) {
                    actions.openRawSessionTranscript(repo, sessionId)
                }
            }
            is WebviewMessage.Copy -> {
                val (value, label) = when (message.target) {
                    CopyTarget.CHECKPOINT_ID -> current.checkpointId to "checkpoint ID"
                    CopyTarget.COMMIT_SHA -> current.commit?.sha to "commit SHA"
                    CopyTarget.SESSION_ID -> current.sessionId to "session ID"
                }
                if (!value.isNullOrEmpty()) actions.copy(value, label)
            }
            else -> {}
        }
    }

    private suspend fun image(current: Loaded, requestId: Int, turn: Int, blockIndex: Int): HostMessage {
        val unavailable = HostMessage.ImageUnavailable(requestId)
        val block = current.turns.getOrNull(turn)?.blocks?.getOrNull(blockIndex) as? FullBlock.Image ?: return unavailable

        var base64 = block.base64
        var mediaType = block.mediaType
        val assetPath = block.assetPath
        if (base64.isNullOrEmpty() && !assetPath.isNullOrEmpty() && current.tree != null && current.stored != null) {
            val data = manager.store.asset(current.repo.root, current.tree, current.stored, assetPath)
            base64 = data?.let { Base64.getEncoder().encodeToString(it) }
            if (mediaType == null) mediaType = IMAGE_TYPES[extension(assetPath)]
        }
        // SVG is left out on purpose: it is the one image type that can carry script.
        if (base64.isNullOrEmpty() || base64.length > IMAGE_LIMIT || mediaType == null || mediaType !in IMAGE_TYPES.values) return unavailable
        return HostMessage.Image(requestId, mediaType, base64)
    }

    private fun headerCommit(commit: CommitRecord) = HeaderCommit(
        sha = commit.sha,
        shortSha = commit.shortSha,
        subject = commit.subject,
        author = commit.author,
        date = ISO_MILLIS.format(Instant.ofEpochSecond(commit.authorTime)),
    )
}

private fun localSession(entry: SessionEntry) = HeaderLocalSession(
    sessionId = entry.sessionId,
    status = entry.status,
    agent = entry.agent,
    model = entry.model,
    turns = entry.local?.turns,
    pendingTurns = entry.local?.pendingTurns,
    tokens = entry.local?.tokens?.total,
    startedAt = entry.local?.startedAt,
    lastActive = entry.lastActive,
)

/** The path as the repository names it, for a path an agent recorded from the root of the disk. */
private fun relativeTo(root: String, filePath: String): String =
    try {
        val path = Path.of(filePath)
        if (path.isAbsolute) Path.of(root).relativize(path.normalize()).toString() else filePath
    } catch (e: InvalidPathException) {
        filePath
    } catch (e: IllegalArgumentException) {
        filePath
    }

/** The letters after the last dot of a file's name, in lower case. Empty for a name with no dot, or with one only at its start. */
private fun extension(path: String): String {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
}

private fun shortTitle(title: String): String = if (title.length > 40) title.take(39) + "…" else title
