package io.entire.core.checkpoint

import io.entire.core.cli.CheckpointRow
import io.entire.core.format.parseTime
import io.entire.core.git.CommitRecord

/** Commits scanned per `git log` call. */
const val WINDOW_SIZE = 2000

/** Rows a view shows per page, and how many more linked commits one page of scanning looks for. */
const val PAGE_SIZE = 50

/** Upper bound on windows per page, so a repo with no linked commits stops scanning. */
const val MAX_WINDOWS_PER_PAGE = 10

/** How many checkpoint rows to ask the Entire CLI for. */
const val LIST_LIMIT = 500

/** How many commits of the current branch the Entire CLI walks when it lists checkpoints. */
private const val CLI_SCAN_DEPTH = 500

/** A list whose oldest commit lies past this share of the CLI's walk is taken to have hit its limit. */
private const val EDGE_FRACTION = 0.8

/** Sessions on this many of the oldest listed commits count as being at the edge of the list. */
private const val EDGE_COMMITS = 5

data class SessionCheckpoint(val checkpointId: String, val date: String? = null, val message: String? = null)

/** A session as seen through the checkpoints that reference it. */
data class IndexedSession(
    val sessionId: String,
    val agent: String?,
    /** Newest first. */
    val checkpoints: List<SessionCheckpoint>,
    val lastDate: String?,
    /** See [SessionPosition.mayHaveEarlier]. */
    val mayHaveEarlier: Boolean,
)

/** Where one checkpoint sits among the checkpoints of a session. */
data class SessionPosition(
    /** 1 for the oldest checkpoint of the session that the CLI lists. */
    val ordinal: Int,
    /** How many checkpoints of the session the CLI lists. */
    val total: Int,
    /**
     * The session may have checkpoints older than the listed ones, which makes
     * `total` a lower bound and `ordinal` a count from the oldest listed one.
     */
    val mayHaveEarlier: Boolean,
    /** The session's checkpoint just before this one, if the CLI lists it. */
    val previous: String? = null,
    /** The session's checkpoint just after this one. */
    val next: String? = null,
)

/**
 * The commit-to-checkpoint map for one branch tip.
 *
 * Git history is the source of truth for which commits carry checkpoints.
 * Rows from the Entire CLI are joined on by checkpoint ID and only add detail
 * such as the agent and the sessions.
 */
class CheckpointIndex(val tipSha: String) {
    private val linked = ArrayList<CommitRecord>()

    /** Commits that carry at least one checkpoint, newest first. */
    val commits: List<CommitRecord> get() = linked

    /** How many commits of history have been scanned, linked or not. */
    var scanned = 0
        private set

    /** True once the scan has reached the root commit. */
    var exhausted = false
        private set

    private val byCheckpoint = HashMap<String, MutableList<CommitRecord>>()

    /** How many commits lie between the branch tip and each linked commit. */
    private val depths = HashMap<String, Int>()
    private var rows: Map<String, CheckpointRow> = emptyMap()

    /** Derived from the rows and commits, and rebuilt when either changes. */
    private var sessionCache: SessionIndex? = null

    private class SessionIndex(val list: List<IndexedSession>, val byId: Map<String, IndexedSession>)

    /** Adds one scanned window and returns how many linked commits it contained. */
    fun addWindow(records: List<CommitRecord>, requested: Int): Int {
        var added = 0
        records.forEachIndexed { offset, record ->
            if (record.checkpointIds.isEmpty()) return@forEachIndexed
            linked.add(record)
            depths[record.sha] = scanned + offset
            added += 1
            for (id in record.checkpointIds) byCheckpoint.getOrPut(id) { ArrayList() }.add(record)
        }
        scanned += records.size
        if (records.size < requested) exhausted = true
        sessionCache = null
        return added
    }

    fun setRows(rows: List<CheckpointRow>) {
        // When an ID repeats, the later row wins and keeps the place of the first.
        val byId = LinkedHashMap<String, CheckpointRow>()
        for (row in rows) byId[row.checkpointId] = row
        this.rows = byId
        sessionCache = null
    }

    fun row(checkpointId: String): CheckpointRow? = rows[checkpointId]

    fun allRows(): List<CheckpointRow> = rows.values.toList()

    /** Every scanned commit that carries the checkpoint, newest first. */
    fun commitsFor(checkpointId: String): List<CommitRecord> = byCheckpoint[checkpointId] ?: emptyList()

    /** CLI rows whose checkpoint is on no scanned commit: imported, or on another branch. */
    fun unlinkedRows(): List<CheckpointRow> = rows.values.filter { it.checkpointId !in byCheckpoint }

    /** Sessions referenced by the CLI rows, most recently checkpointed first. */
    fun sessions(): List<IndexedSession> = sessionIndex().list

    /**
     * Where a checkpoint sits in one of its sessions, or null when the CLI
     * rows do not place it there.
     */
    fun position(sessionId: String, checkpointId: String): SessionPosition? {
        val session = sessionIndex().byId[sessionId] ?: return null
        // `checkpoints` is newest first, so the one after it in the list is the one before it in time.
        val at = session.checkpoints.indexOfFirst { it.checkpointId == checkpointId }
        if (at < 0) return null
        return SessionPosition(
            ordinal = session.checkpoints.size - at,
            total = session.checkpoints.size,
            mayHaveEarlier = session.mayHaveEarlier,
            previous = session.checkpoints.getOrNull(at + 1)?.checkpointId?.ifEmpty { null },
            next = session.checkpoints.getOrNull(at - 1)?.checkpointId?.ifEmpty { null },
        )
    }

    private class Building(val sessionId: String, val mayHaveEarlier: Boolean) {
        var agent: String? = null
        val checkpoints = ArrayList<SessionCheckpoint>()
    }

    private fun sessionIndex(): SessionIndex {
        sessionCache?.let { return it }
        val edge = sessionsAtTheEdge()
        val building = LinkedHashMap<String, Building>()
        for (row in rows.values) {
            for (sessionId in row.sessionIds) {
                val session = building.getOrPut(sessionId) { Building(sessionId, sessionId in edge) }
                session.checkpoints.add(SessionCheckpoint(row.checkpointId, row.date?.ifEmpty { null }, row.message?.ifEmpty { null }))
                // A row's agent describes its primary session only.
                if (!row.agent.isNullOrEmpty() && row.sessionId == sessionId) session.agent = row.agent
            }
        }
        // Compare instants, not text: the CLI's dates can carry different UTC offsets.
        fun instant(date: String?): Long = date?.let { parseTime(it) } ?: 0
        val sessions = building.values.map { session ->
            val newestFirst = session.checkpoints.sortedByDescending { instant(it.date) }
            IndexedSession(
                sessionId = session.sessionId,
                agent = session.agent,
                checkpoints = newestFirst,
                lastDate = newestFirst.firstOrNull()?.date,
                mayHaveEarlier = session.mayHaveEarlier,
            )
        }
        val list = sessions.sortedByDescending { instant(it.lastDate) }
        return SessionIndex(list, list.associateBy { it.sessionId }).also { sessionCache = it }
    }

    /**
     * Sessions that may have checkpoints older than the ones the CLI listed.
     *
     * The CLI lists the checkpoints of the newest 500 commits of the current
     * branch. On a feature branch it stops at the branch point, far short of
     * that. When the oldest commit it listed lies near that limit and history
     * goes on beyond it, a session on those oldest commits may have begun
     * earlier. This is inferred from the limit, since the CLI does not say
     * whether it stopped early, so it errs toward marking a session open-ended.
     */
    private fun sessionsAtTheEdge(): Set<String> {
        val edge = HashSet<String>()
        val listed = linked.filter { commit -> commit.checkpointIds.any { it in rows } }
        val oldest = listed.lastOrNull() ?: return edge
        val hitLimit = (depths[oldest.sha] ?: 0) >= CLI_SCAN_DEPTH * EDGE_FRACTION || rows.size >= LIST_LIMIT
        val historyBeyond = !exhausted || linked.lastOrNull() !== oldest
        if (!hitLimit || !historyBeyond) return edge
        for (commit in listed.takeLast(EDGE_COMMITS)) {
            for (id in commit.checkpointIds) {
                for (sessionId in rows[id]?.sessionIds ?: emptyList()) edge.add(sessionId)
            }
        }
        return edge
    }
}

/**
 * Scans fixed-size windows until the index has `target` more linked commits,
 * history ends, or the window budget runs out. Windows are fixed-size so one
 * call has a bounded cost in a repo with long history and few checkpoints.
 *
 * Returns false when a window could not be read.
 */
suspend fun loadPage(
    index: CheckpointIndex,
    target: Int = PAGE_SIZE,
    fetchWindow: suspend (skip: Int, count: Int) -> List<CommitRecord>?,
): Boolean {
    var added = 0
    var windows = 0
    while (windows < MAX_WINDOWS_PER_PAGE && !index.exhausted && added < target) {
        val records = fetchWindow(index.scanned, WINDOW_SIZE) ?: return false
        added += index.addWindow(records, WINDOW_SIZE)
        windows += 1
    }
    return true
}
