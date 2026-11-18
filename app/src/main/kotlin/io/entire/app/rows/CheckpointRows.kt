package io.entire.app.rows

import io.entire.app.repo.Lists
import io.entire.app.repo.RepoContext
import io.entire.app.repo.ViewState
import io.entire.core.CallResult
import io.entire.core.cli.CheckpointRow
import io.entire.core.cli.CheckpointSession
import io.entire.core.cli.totalTokens
import io.entire.core.format.formatPosition
import io.entire.core.format.formatTokens
import io.entire.core.format.headline
import io.entire.core.format.joinParts
import io.entire.core.format.relativeTime
import io.entire.core.git.CommitRecord
import io.entire.core.protocol.DetailMode
import io.entire.core.protocol.DetailTarget
import java.util.Locale

enum class RowKind { REPO, COMMIT, CHECKPOINT, SLOT, OTHER_GROUP, LOAD_MORE, SESSION_GROUP, SESSION, TRAIL, MESSAGE, SEARCH_GROUP, SEARCH_HIT, SEARCH_ACTION }

/** How a trail row is drawn, which follows the trail's phase. */
enum class TrailIcon { CLOSED, COMPLETE, PLANNING, OPEN }

/** What a row's menu can act on, beyond what opening the row needs. */
data class RowMenu(
    /**
     * The session that Copy Session ID copies. For a commit or a checkpoint
     * row it is the session that the row's agent and message describe.
     */
    val sessionId: String? = null,
    /** The slot of that session inside the checkpoint, when the row knows it, for the raw transcript. */
    val sessionIndex: Int? = null,
    /** Entire hosts the repository, and the row has a page there. */
    val web: Boolean = false,
)

/** What a trail row stands for. */
data class TrailRow(
    /** The trail's number in its project. */
    val number: Long,
    /** The trail's ID on Entire, when the list reported it. */
    val id: String?,
    val icon: TrailIcon,
    /** The trail's branch in this repository is the one checked out. */
    val checkedOut: Boolean,
)

/**
 * What opening a row does besides showing its children. Some children are
 * read only when their row is opened: the sessions of a checkpoint that holds
 * several, and the checkpoints of a session that only Entire lists.
 */
enum class OnOpen {
    NOTHING,

    /** The row's children have not all been read. Opening the row reads them, and so does a row that is already open. */
    READ,

    /** The read failed. Closing the row and opening it again repeats the read. */
    RETRY,
}

/**
 * One row of a tree, as text. It holds no reference to what it was made
 * from, so the IDE can keep it and compare it with the row that replaces it.
 */
data class RowNode(
    /** The same row has the same ID each time the tree is built. */
    val id: String,
    val kind: RowKind,
    val label: String,
    val description: String,
    val repoRoot: String,
    val children: List<RowNode> = emptyList(),
    val commitSha: String? = null,
    val checkpointId: String? = null,
    /** For a Load More row: the list it extends. */
    val list: String? = null,
    /** The row is drawn dimmed: its checkpoint has no session data. */
    val muted: Boolean = false,
    /**
     * For a session row, its session. For a checkpoint row under a session, the session it is listed under.
     * For the row of one session of a checkpoint that holds several, that session.
     */
    val sessionId: String? = null,
    /** For a session row: `active`, `idle`, or null for a session that is not running. */
    val status: String? = null,
    val onOpen: OnOpen = OnOpen.NOTHING,
    /** For a trail row, its trail. */
    val trail: TrailRow? = null,
    val menu: RowMenu = RowMenu(),
    /** For a Load More row: its list loads the next page when the row scrolls into view. */
    val auto: Boolean = false,
    /** What hovering over the row says. Null for a row with nothing to add. */
    val hover: Hover? = null,
    /** For a row of Entire's search results, what it opens. */
    val search: SearchRef? = null,
    /** For a message row, what opening it does. */
    val link: EmptyLink? = null,
) {
    /** Whether the row opens a tab, and so can open one of its own. A commit that carries several checkpoints opens none. */
    val openable: Boolean
        get() = when (kind) {
            RowKind.COMMIT -> checkpointId != null
            RowKind.CHECKPOINT, RowKind.SLOT, RowKind.SESSION, RowKind.TRAIL, RowKind.SEARCH_HIT -> true
            else -> false
        }

    /** Whether the CLI can write the raw transcript the row stands for. */
    val hasTranscript: Boolean get() = kind == RowKind.SESSION || (kind != RowKind.SEARCH_HIT && checkpointId != null && menu.sessionIndex != null)

    /**
     * Whether this row is the one to have selected while a detail tab shows
     * `target`. A commit row stands for its only checkpoint, and a checkpoint
     * row for its checkpoint on that commit. A row that names a session, such
     * as a session of a checkpoint that holds several, stands for the
     * checkpoint in that session only.
     */
    fun standsFor(target: DetailTarget): Boolean {
        if (repoRoot != target.repoRoot) return false
        // A session row stands for its session, as opened from that row.
        if (kind == RowKind.SEARCH_HIT) return false
        if (target.mode == DetailMode.SESSION) return kind == RowKind.SESSION && sessionId == target.sessionId
        if (kind != RowKind.COMMIT && kind != RowKind.CHECKPOINT && kind != RowKind.SLOT) return false
        if (target.checkpointId == null || checkpointId != target.checkpointId) return false
        if (target.commitSha != null && commitSha != null && commitSha != target.commitSha) return false
        return sessionId == null || target.sessionId == null || sessionId == target.sessionId
    }

    /** What the row opens in the detail panel, or null for a row that opens nothing. */
    fun detailTarget(): DetailTarget? = when (kind) {
        RowKind.SESSION -> sessionId?.let { DetailTarget(repoRoot = repoRoot, mode = DetailMode.SESSION, sessionId = it) }
        RowKind.COMMIT, RowKind.CHECKPOINT, RowKind.SLOT -> checkpointId?.let {
            DetailTarget(repoRoot = repoRoot, mode = DetailMode.CHECKPOINT, checkpointId = it, commitSha = commitSha, sessionId = sessionId)
        }
        else -> null
    }
}

internal const val NO_DATA = "no session data"

/*
 * How a row's ID is spelled. A row keeps its ID each time the tree is built,
 * and the search for the row of a tab spells the ID the same way.
 */

fun repoRowId(root: String): String = "$root|repo"

fun commitRowId(root: String, sha: String): String = "$root|commit:$sha"

fun otherGroupRowId(root: String): String = "$root|other"

fun checkpointRowId(parentId: String, checkpointId: String): String = "$parentId|cp:$checkpointId"

fun slotRowId(parentId: String, index: Int): String = "$parentId|slot:$index"

fun sessionGroupRowId(root: String, active: Boolean): String = "$root|group:" + (if (active) "active" else "recent")

fun sessionRowId(root: String, sessionId: String): String = "$root|session:$sessionId"

fun loadMoreRowId(root: String, list: String): String = "$root|more:$list"

/** Something a tab with no rows offers to do about it. */
enum class EmptyLink { RETRY, COPY_LOGIN_COMMAND, SHOW_LOG, CHANGE_FILTER, LOG_IN, SET_UP }

/** What a tab says in place of its rows, when that depends on more than [ViewState]. */
data class EmptyText(val lines: List<String>, val links: List<EmptyLink> = emptyList())

/** What one tab of the tool window shows. */
data class RowsView(
    val state: ViewState,
    val rows: List<RowNode>,
    /** Shown above the rows when something went wrong that leaves rows to show. */
    val notice: String?,
    /** A count to show beside the tab's name. Zero shows nothing. */
    val badge: Int = 0,
    /** What the tab says while it has no rows. Null for a tab whose state says it all. */
    val empty: EmptyText? = null,
    /** What the notice above the rows offers to do about what it says. */
    val noticeLink: EmptyLink? = null,
)

/** How a Load More row's label starts. The rest is the list's noun, such as "checkpoints". */
internal const val LOAD_MORE = "Load more "

internal fun count(value: Int): String = String.format(Locale.US, "%,d", value)

internal fun count(value: Long): String = String.format(Locale.US, "%,d", value)

/** How many sessions a checkpoint holds, as far as the CLI's row says. Zero when there is no row. */
internal fun sessionCount(row: CheckpointRow?): Int = if (row == null) 0 else row.sessionCount ?: row.sessionIds.size

/** A row that only says something: why a list is empty, or that it is being read. */
internal fun messageRow(id: String, text: String, root: String): RowNode =
    RowNode(id = id, kind = RowKind.MESSAGE, label = text, description = "", repoRoot = root)

/** The rows under a checkpoint that holds several sessions, and what opening the checkpoint's row does. */
internal class SlotRows(val rows: List<RowNode>, val onOpen: OnOpen)

/**
 * The sessions of a checkpoint that holds several, one row each. The CLI
 * lists them the first time the checkpoint's row is opened, so until then the
 * row holds one line that says they are being read.
 */
internal fun slotRows(repo: RepoContext, checkpointId: String, commit: CommitRecord?, parentId: String): SlotRows {
    val failure = repo.slotFailures[checkpointId]
    return when (val outcome = repo.details[checkpointId]?.outcome) {
        is CallResult.Ok -> {
            val sessions = outcome.value.sessions
            if (sessions.isEmpty()) {
                SlotRows(listOf(messageRow("$parentId|empty", "This checkpoint has no recorded sessions.", repo.root)), OnOpen.NOTHING)
            } else {
                SlotRows(sessions.map { slotRow(repo, checkpointId, it, commit, parentId) }, OnOpen.NOTHING)
            }
        }
        is CallResult.Failed -> SlotRows(listOf(messageRow("$parentId|error", outcome.failure.message, repo.root)), OnOpen.RETRY)
        null ->
            if (failure != null) {
                SlotRows(listOf(messageRow("$parentId|error", failure, repo.root)), OnOpen.RETRY)
            } else {
                SlotRows(listOf(messageRow("$parentId|loading", "Loading…", repo.root)), OnOpen.READ)
            }
    }
}

private fun slotRow(repo: RepoContext, checkpointId: String, session: CheckpointSession, commit: CommitRecord?, parentId: String): RowNode =
    RowNode(
        id = slotRowId(parentId, session.index),
        kind = RowKind.SLOT,
        label = joinParts(session.agent ?: "Agent session", session.model),
        description = joinParts(
            positionText(repo, session.sessionId, checkpointId),
            session.tokenUsage?.let { "${formatTokens(totalTokens(it))} tokens" },
        ),
        repoRoot = repo.root,
        commitSha = commit?.sha,
        checkpointId = checkpointId,
        sessionId = session.sessionId,
        menu = RowMenu(
            sessionId = session.sessionId,
            sessionIndex = session.index,
            web = repo.entireRepo != null && (commit != null || session.sessionId != null),
        ),
        hover = hover(
            session.summary?.intent?.let { headline(it, 200) },
            session.sessionId?.let { "Session $it" },
            localTime(session.createdAt),
            if (session.filesTouched.isNotEmpty()) "${count(session.filesTouched.size)} files touched" else null,
            session.tokenUsage?.let { "${count(totalTokens(it))} tokens since the previous checkpoint" },
        ),
    )

/** "12 of 249": where a checkpoint sits among the checkpoints of a session. */
private fun positionText(repo: RepoContext, sessionId: String?, checkpointId: String): String? {
    val position = sessionId?.let { repo.index?.position(it, checkpointId) } ?: return null
    return formatPosition(position.ordinal, position.total, position.mayHaveEarlier)
}

/** What the Checkpoints tab shows for these repositories. */
fun checkpointsView(state: ViewState, enabledRepos: List<RepoContext>, now: Long): RowsView {
    if (state != ViewState.READY) return RowsView(state, emptyList(), null)
    val single = enabledRepos.singleOrNull()
    // One repository shows its rows at the top. Several show a row each, with their rows under it.
    val rows = if (single != null) {
        repoChildren(single, now)
    } else {
        enabledRepos.map { repo ->
            RowNode(
                id = repoRowId(repo.root),
                kind = RowKind.REPO,
                label = repo.name,
                description = repo.branch.orEmpty(),
                repoRoot = repo.root,
                children = repoChildren(repo, now),
            )
        }
    }
    return RowsView(state, rows, enabledRepos.firstNotNullOfOrNull { it.gitError })
}

private fun repoChildren(repo: RepoContext, now: Long): List<RowNode> {
    val index = repo.index ?: return emptyList()
    val nodes = ArrayList(
        paged(repo, Lists.COMMITS, "Checkpoints", index.commits, moreToRead = !index.exhausted) { commitRow(repo, it, now) },
    )
    val unlinked = index.unlinkedRows()
    if (unlinked.isNotEmpty()) {
        val id = otherGroupRowId(repo.root)
        nodes.add(
            RowNode(
                id = id,
                kind = RowKind.OTHER_GROUP,
                label = "Other checkpoints",
                description = count(unlinked.size),
                repoRoot = repo.root,
                hover = Hover(null, listOf(OTHER_CHECKPOINTS_HOVER)),
                children = paged(repo, Lists.OTHER_CHECKPOINTS, "Checkpoints", unlinked, moreToRead = false) { row ->
                    checkpointRow(repo, row.checkpointId, row, null, id, now)
                },
            ),
        )
    }
    return nodes
}

/**
 * The rows of a list that are currently shown, followed by a Load More row
 * when there are more.
 */
internal fun <T> paged(
    repo: RepoContext,
    list: String,
    noun: String,
    items: List<T>,
    moreToRead: Boolean,
    /** The total the list's source reports, for a list whose source knows it. */
    sourceTotal: Long? = null,
    /** For a list that keeps only some of what its source lists: how far the source has been read, and how far there is to read. */
    scanned: Pair<Int, Long?>? = null,
    toNode: (T) -> RowNode,
): List<RowNode> {
    val nodes = items.take(repo.limit(list)).map(toNode)
    if (items.size <= nodes.size && !moreToRead) return nodes
    // A list that can still read more does not know its total from its rows.
    // A total the source reported earlier can fall behind the rows read since.
    val total: Long? = if (moreToRead) sourceTotal?.takeIf { it > nodes.size } else items.size.toLong()
    val shown = when {
        // A filtered list cannot say how many rows are left, only how far it has looked.
        scanned != null && moreToRead ->
            "${count(nodes.size)} shown · ${count(scanned.first)}${scanned.second?.let { " of ${count(it)}" }.orEmpty()} read"
        total == null -> "${count(nodes.size)} shown"
        else -> "${count(nodes.size)} of ${count(total)} shown"
    }
    return nodes + RowNode(
        id = loadMoreRowId(repo.root, list),
        kind = RowKind.LOAD_MORE,
        label = LOAD_MORE + noun.lowercase(Locale.ROOT),
        description = shown,
        repoRoot = repo.root,
        list = list,
        // A list whose last automatic load found no new row waits for a click.
        auto = list !in repo.autoLoadPaused,
    )
}

private fun commitRow(repo: RepoContext, commit: CommitRecord, now: Long): RowNode {
    val id = commitRowId(repo.root, commit.sha)
    val sole = commit.checkpointIds.singleOrNull()
    val row = sole?.let { repo.index?.row(it) }
    val sessions = sessionCount(row)
    val noData = sole != null && sole in repo.withoutData
    // Rows under a commit exist to tell things apart: its checkpoints when it
    // carries several, or its sessions when its checkpoint holds several. A
    // checkpoint with one session has neither, and the commit row stands for
    // it. Otherwise one long session would appear as a row of its own under
    // every commit it touched.
    val slots = if (sole != null && sessions > 1) slotRows(repo, sole, commit, id) else null
    val children = when {
        sole == null -> commit.checkpointIds.map { checkpointId -> checkpointRow(repo, checkpointId, repo.index?.row(checkpointId), commit, id, now) }
        slots != null -> slots.rows
        else -> emptyList()
    }
    return RowNode(
        id = id,
        kind = RowKind.COMMIT,
        label = commit.subject.ifEmpty { "(no subject)" },
        description = joinParts(
            commit.shortSha,
            when {
                noData -> NO_DATA
                sole == null -> "${commit.checkpointIds.size} checkpoints"
                sessions > 1 -> "$sessions sessions"
                else -> row?.agent
            },
            if (sole != null && sessions == 1) positionText(repo, row?.sessionId, sole) else null,
            relativeTime(commit.authorTime * 1000, now),
        ),
        repoRoot = repo.root,
        children = children,
        commitSha = commit.sha,
        checkpointId = sole,
        muted = noData,
        onOpen = slots?.onOpen ?: OnOpen.NOTHING,
        // The only session of a checkpoint is in its first slot.
        menu = RowMenu(sessionId = row?.sessionId, sessionIndex = if (sessions == 1) 0 else null, web = repo.entireRepo != null),
        hover = hover(
            commit.subject,
            "Commit ${commit.sha}",
            "${commit.author}, ${localTime(commit.authorTime * 1000)}",
            *commit.checkpointIds.map { "Checkpoint $it" }.toTypedArray(),
            *(if (sole != null && sessions == 1) sessionPlace(repo, row?.sessionId, sole) else emptyList()).toTypedArray(),
            if (noData) NO_DATA_HOVER else null,
        ),
    )
}

private fun checkpointRow(
    repo: RepoContext,
    checkpointId: String,
    row: CheckpointRow?,
    commit: CommitRecord?,
    parentId: String,
    now: Long,
): RowNode {
    val sessions = sessionCount(row)
    // The place is in the checkpoint's own session, when it has exactly one.
    val within = if (sessions == 1) row?.sessionId else null
    val noData = checkpointId in repo.withoutData
    val id = checkpointRowId(parentId, checkpointId)
    val slots = if (sessions > 1) slotRows(repo, checkpointId, commit, id) else null
    return RowNode(
        id = id,
        kind = RowKind.CHECKPOINT,
        // A ULID is never shortened: sibling IDs share their leading characters.
        label = headline(row?.message).ifEmpty { checkpointId },
        description = joinParts(
            when {
                noData -> NO_DATA
                sessions > 1 -> "$sessions sessions"
                else -> row?.agent
            },
            positionText(repo, within, checkpointId),
            relativeTime(row?.date, now),
            commit?.shortSha,
        ),
        repoRoot = repo.root,
        children = slots?.rows ?: emptyList(),
        commitSha = commit?.sha,
        checkpointId = checkpointId,
        muted = noData,
        onOpen = slots?.onOpen ?: OnOpen.NOTHING,
        menu = RowMenu(
            sessionId = row?.sessionId,
            sessionIndex = if (sessions == 1) 0 else null,
            web = repo.entireRepo != null && (commit != null || row?.sessionId != null),
        ),
        hover = hover(
            row?.message?.let { headline(it, 200) },
            "Checkpoint $checkpointId",
            if (commit != null) "Commit ${commit.sha}" else "No commit on this branch carries this checkpoint.",
            localTime(row?.date),
            *sessionPlace(repo, within, checkpointId).toTypedArray(),
            if (noData) NO_DATA_HOVER else null,
        ),
    )
}
