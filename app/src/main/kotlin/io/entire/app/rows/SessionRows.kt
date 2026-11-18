package io.entire.app.rows

import io.entire.app.repo.Lists
import io.entire.app.repo.RepoContext
import io.entire.app.repo.SessionDirectory
import io.entire.app.repo.SessionEntry
import io.entire.app.repo.ViewState
import io.entire.core.checkpoint.SessionCheckpoint
import io.entire.core.checkpoint.SessionPosition
import io.entire.core.format.formatPosition
import io.entire.core.format.headline
import io.entire.core.format.joinParts
import io.entire.core.format.relativeTime

/**
 * How many checkpoints a session has. For a session that Entire lists it is
 * Entire's count, as on entire.com, unless the clone knows of more. Once
 * Entire's list of the session's checkpoints has been read, it is the number
 * in that list, which is what the session's page on entire.com shows and can
 * be fewer than the count.
 */
private fun checkpointCount(session: SessionEntry): Int =
    if (session.checkpointsFromEntire) session.checkpoints.size else maxOf(session.listedCheckpoints ?: 0, session.checkpoints.size)

/** "36 checkpoints", with a plus when the clone may not know them all and Entire has not counted them. */
private fun checkpointCountText(session: SessionEntry): String? {
    val count = checkpointCount(session)
    if (count == 0) return null
    val open = if (session.listedCheckpoints == null && session.mayHaveEarlier) "+" else ""
    return "${count(count)}$open ${if (count == 1) "checkpoint" else "checkpoints"}"
}

/**
 * What the Sessions tab shows for these repositories: the sessions running
 * now, then the rest by recency, each with its checkpoints under it.
 *
 * Building the rows asks for the names of the sessions on show, which are
 * read from git in the background.
 */
fun sessionsView(state: ViewState, enabledRepos: List<RepoContext>, directory: SessionDirectory, now: Long): RowsView {
    if (state != ViewState.READY) return RowsView(state, emptyList(), null)
    val single = enabledRepos.singleOrNull()
    val rows = if (single != null) {
        repoChildren(single, directory, now)
    } else {
        enabledRepos.map { repo ->
            RowNode(
                id = repoRowId(repo.root),
                kind = RowKind.REPO,
                label = repo.name,
                description = repo.branch.orEmpty(),
                repoRoot = repo.root,
                children = repoChildren(repo, directory, now),
            )
        }
    }
    // With no row to show and a first read still out, the tab says it is loading, and not that there are no sessions.
    val reading = enabledRepos.any { it.sessionRowsLoading || it.remoteSessions?.loading == true }
    if (rows.isEmpty() && reading) return RowsView(ViewState.LOADING, emptyList(), null)
    val active = enabledRepos.sumOf { directory.sessions(it).active.size }
    val notice = directory.notice(enabledRepos)
    // The notice says only that no one is logged in, and the Setup tab logs in.
    return RowsView(state, rows, notice, badge = active, noticeLink = if (notice != null) EmptyLink.LOG_IN else null)
}

private fun repoChildren(repo: RepoContext, directory: SessionDirectory, now: Long): List<RowNode> {
    val lists = directory.sessions(repo)
    // Group only when something is running. Otherwise the list is flat.
    if (lists.active.isEmpty()) return sessionRows(repo, directory, lists.recent, Lists.RECENT_SESSIONS, now)
    val groups = ArrayList<RowNode>()
    groups.add(
        RowNode(
            id = sessionGroupRowId(repo.root, active = true),
            kind = RowKind.SESSION_GROUP,
            label = "Active",
            description = "",
            repoRoot = repo.root,
            children = sessionRows(repo, directory, lists.active, Lists.ACTIVE_SESSIONS, now),
        ),
    )
    if (lists.recent.isNotEmpty()) {
        groups.add(
            RowNode(
                id = sessionGroupRowId(repo.root, active = false),
                kind = RowKind.SESSION_GROUP,
                label = "Recent",
                description = "",
                repoRoot = repo.root,
                children = sessionRows(repo, directory, lists.recent, Lists.RECENT_SESSIONS, now),
            ),
        )
    }
    return groups
}

private fun sessionRows(repo: RepoContext, directory: SessionDirectory, sessions: List<SessionEntry>, list: String, now: Long): List<RowNode> {
    // A session that Entire does not list is named by the prompt that opened
    // it. That is read from the session's checkpoints, for the rows on show
    // and not for the rest.
    directory.nameSessions(repo, sessions.take(repo.limit(list)))
    // Entire may list more sessions than have been read, and then the list can grow past its rows.
    val more = list == Lists.RECENT_SESSIONS && directory.moreOnEntire(repo)
    return paged(repo, list, "Sessions", sessions, moreToRead = more) { sessionRow(repo, directory, it, now) }
}

private fun sessionRow(repo: RepoContext, directory: SessionDirectory, session: SessionEntry, now: Long): RowNode {
    val id = sessionRowId(repo.root, session.sessionId)
    val count = checkpointCount(session)
    val list = Lists.sessionCheckpoints(session.sessionId)
    val fromEntire = session.checkpointsFromEntire
    val known = session.checkpoints
    var children = paged(repo, list, "Checkpoints", known, moreToRead = false) { checkpoint ->
        // With Entire's list, the place is counted in it, and not in what the clone knows.
        val ordinal = if (fromEntire) known.size - known.indexOf(checkpoint) else null
        sessionCheckpointRow(repo, session, checkpoint, id, ordinal, known.size, now)
    }
    // Entire is asked for the session's checkpoints, when the row is opened, if it counts more than the clone knows.
    val listed = session.listedCheckpoints
    val short = listed != null && listed > known.size && !fromEntire && repo.entireRepo != null
    val failure = if (short) directory.checkpointsFailure(repo, session.sessionId) else null
    val onOpen = when {
        !short -> OnOpen.NOTHING
        failure != null -> OnOpen.RETRY
        else -> OnOpen.READ
    }
    if (children.isEmpty() && count > 0) {
        // Entire counts checkpoints that the clone has none of.
        children = listOf(if (failure != null) messageRow("$id|error", failure.message, repo.root) else messageRow("$id|loading", "Loading…", repo.root))
    }
    return RowNode(
        id = id,
        kind = RowKind.SESSION,
        // The name is the one entire.com shows, for a session that Entire lists,
        // and otherwise the prompt that opened the session. Until either is
        // known, the row falls back to the latest prompt or to the newest
        // checkpoint's message.
        label = headline(session.name ?: session.lastPrompt ?: session.title).ifEmpty { "${session.agent ?: "Agent"} session" },
        description = joinParts(
            session.agent,
            if (session.live) session.status else null,
            checkpointCountText(session),
            relativeTime(session.lastActive, now),
        ),
        repoRoot = repo.root,
        children = children,
        sessionId = session.sessionId,
        status = session.status?.takeIf { session.live },
        onOpen = onOpen,
        menu = RowMenu(sessionId = session.sessionId, web = repo.entireRepo != null),
        hover = sessionHover(session),
    )
}

private fun sessionHover(session: SessionEntry): Hover? {
    val named = session.name ?: session.lastPrompt ?: session.title
    val latest = session.lastPrompt
    return hover(
        named?.let { headline(it, 200) },
        session.opening?.let { "Opening prompt: ${headline(it, 200)}" },
        // A session that is still going has moved on from the prompt that opened it.
        if (session.name != null && latest != null && latest != session.name && latest != session.opening) "Latest prompt: ${headline(latest, 200)}" else null,
        "Session ${session.sessionId}",
        joinParts(session.agent, session.model, session.status),
        session.branch?.let { "Branch $it" },
        session.local?.turns?.let { "${count(it)} turns" },
        session.local?.tokens?.total?.let { "${count(it)} tokens" },
        localTime(session.lastActive)?.let { "Last active $it" },
        checkpointCountText(session) ?: "No checkpoints",
    )
}

private fun sessionCheckpointRow(
    repo: RepoContext,
    session: SessionEntry,
    checkpoint: SessionCheckpoint,
    parentId: String,
    ordinal: Int?,
    total: Int,
    now: Long,
): RowNode {
    val checkpointId = checkpoint.checkpointId
    val commit = repo.index?.commitsFor(checkpointId)?.firstOrNull()
    // A checkpoint of another branch has no row from the CLI. Entire's list says what it is.
    val row = repo.index?.row(checkpointId)
    val message = row?.message ?: checkpoint.message
    val date = row?.date ?: checkpoint.date
    val noData = checkpointId in repo.withoutData
    val position = if (ordinal != null) {
        formatPosition(ordinal, total, false)
    } else {
        repo.index?.position(session.sessionId, checkpointId)?.let { formatPosition(it.ordinal, it.total, it.mayHaveEarlier) }
    }
    val id = checkpointRowId(parentId, checkpointId)
    // A checkpoint that holds several sessions lists them under its row, as in the Checkpoints tab.
    val slots = if (sessionCount(row) > 1) slotRows(repo, checkpointId, commit, id) else null
    return RowNode(
        id = id,
        kind = RowKind.CHECKPOINT,
        // A ULID is never shortened: sibling IDs share their leading characters.
        label = headline(message).ifEmpty { checkpointId },
        description = joinParts(if (noData) NO_DATA else null, position, relativeTime(date, now), commit?.shortSha),
        repoRoot = repo.root,
        children = slots?.rows ?: emptyList(),
        commitSha = commit?.sha,
        checkpointId = checkpointId,
        muted = noData,
        sessionId = session.sessionId,
        onOpen = slots?.onOpen ?: OnOpen.NOTHING,
        menu = RowMenu(sessionId = session.sessionId, sessionIndex = if (sessionCount(row) == 1) 0 else null, web = repo.entireRepo != null),
        hover = hover(
            message?.let { headline(it, 200) },
            "Checkpoint $checkpointId",
            if (commit != null) "Commit ${commit.sha}" else "No commit on this branch carries this checkpoint.",
            localTime(date),
            // With Entire's list, the place is counted in it, and not in what the clone knows.
            *sessionPlace(repo, session.sessionId, checkpointId, ordinal?.let { SessionPosition(it, total, false) }).toTypedArray(),
            if (noData) NO_DATA_HOVER else null,
        ),
    )
}
