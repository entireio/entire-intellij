package io.entire.app.rows

import io.entire.app.repo.RepoContext
import io.entire.app.repo.TrailDirectory
import io.entire.app.repo.TrailsState
import io.entire.app.repo.ViewState
import io.entire.core.cli.TrailSummary
import io.entire.core.format.headline
import io.entire.core.format.joinParts
import io.entire.core.format.relativeTime
import io.entire.core.trails.TrailFilter
import io.entire.core.trails.TrailListStatus
import io.entire.core.trails.isCheckedOut
import io.entire.core.trails.noTrailsText
import io.entire.core.trails.primaryChange

fun trailRowId(root: String, number: Long): String = "$root|trail:$number"

/**
 * What the Trails tab shows: the trails that touch each repository that
 * Entire hosts, a page at a time, most recent activity first. The filter
 * selects every trail, the open ones, the closed ones, or the open ones in
 * one phase.
 */
fun trailsView(repos: List<RepoContext>, directory: TrailDirectory, now: Long): RowsView {
    val single = repos.singleOrNull()
    val rows = if (single != null) {
        repoTrails(single, directory, nested = false, now)
    } else {
        repos.map { repo ->
            RowNode(
                id = repoRowId(repo.root),
                kind = RowKind.REPO,
                label = repo.name,
                description = repo.branch.orEmpty(),
                repoRoot = repo.root,
                children = repoTrails(repo, directory, nested = true, now),
            )
        }
    }
    val state = directory.state(repos)
    return RowsView(
        state = if (state == TrailsState.LOADING) ViewState.LOADING else ViewState.READY,
        rows = rows,
        notice = null,
        empty = emptyText(state, directory.filter),
    )
}

/** What the tab says in place of rows. With one repository, a list with no rows leaves the tab empty and this says why. */
private fun emptyText(state: TrailsState, filter: TrailFilter): EmptyText? =
    when (state) {
        TrailsState.LOADING, TrailsState.READY -> null
        TrailsState.LOGGED_OUT -> EmptyText(
            listOf("Trails need a login to Entire."),
            listOf(EmptyLink.LOG_IN, EmptyLink.RETRY),
        )
        TrailsState.NO_ACCESS -> EmptyText(
            listOf(
                "This repository's project is not on Entire, or the saved login cannot see it.",
                "Run 'entire auth status' in a terminal to check which account is logged in.",
            ),
            listOf(EmptyLink.RETRY),
        )
        TrailsState.FAILED -> EmptyText(
            listOf("Entire could not load the trails of this repository.", "The log has the details."),
            listOf(EmptyLink.RETRY, EmptyLink.SHOW_LOG),
        )
        // With every trail on show there is no filter to change.
        TrailsState.EMPTY -> EmptyText(
            listOf(noTrailsText(filter)),
            if (filter.status == TrailListStatus.ALL && filter.phase.isNullOrEmpty()) emptyList() else listOf(EmptyLink.CHANGE_FILTER),
        )
    }

/**
 * The trails of one repository. When the repository is alone in the tab and
 * has no rows, the tab stays empty and its own text says why. Under a
 * repository row there is no such text, so a row says it.
 */
private fun repoTrails(repo: RepoContext, directory: TrailDirectory, nested: Boolean, now: Long): List<RowNode> {
    fun note(id: String, text: String): List<RowNode> = if (nested) listOf(messageRow("${repo.root}|trails:$id", text, repo.root)) else emptyList()
    val list = repo.trails
    if (list == null || (list.loading && list.trails.isEmpty())) return note("loading", "Loading…")
    val filter = directory.filter
    val shown = directory.shown(list)
    val moreToRead = list.nextPageToken != null
    if (shown.isEmpty() && !moreToRead) {
        return list.failure?.let { note("failed", it.message) } ?: note("empty", noTrailsText(filter))
    }
    // The CLI's total counts every trail of the status. With a filter on a
    // phase it is how far there is to read, not how many rows there will be.
    val byPhase = !filter.phase.isNullOrEmpty()
    return paged(
        repo,
        list.list,
        "Trails",
        shown,
        moreToRead = moreToRead,
        sourceTotal = if (byPhase) null else list.total,
        scanned = if (byPhase) list.trails.size to list.total else null,
    ) { trailRow(repo, it, now) }
}

private fun trailRow(repo: RepoContext, trail: TrailSummary, now: Long): RowNode {
    val hosted = repo.entireRepo
    // A trail can have a branch in several repositories. The row describes the one here.
    val change = if (hosted == null) null else trail.changes.getOrNull(primaryChange(trail.changes, hosted, repo.branch))
    val checkedOut = change != null && hosted != null && isCheckedOut(change, hosted, repo.branch)
    val branch = change?.branch?.ifEmpty { null }
    val closed = trail.status == "closed" || trail.phase == "closed"
    return RowNode(
        id = trailRowId(repo.root, trail.number),
        kind = RowKind.TRAIL,
        label = headline(trail.title).ifEmpty { "Trail ${trail.number}" },
        description = joinParts(
            "#${trail.number}",
            trail.phase ?: trail.status,
            if (branch == null) null else if (checkedOut) "$branch (checked out)" else branch,
            relativeTime(trail.lastActivityAt, now),
        ),
        repoRoot = repo.root,
        trail = TrailRow(
            number = trail.number,
            id = trail.id,
            icon = when {
                closed -> TrailIcon.CLOSED
                trail.phase == "complete" -> TrailIcon.COMPLETE
                trail.phase == "planning" -> TrailIcon.PLANNING
                else -> TrailIcon.OPEN
            },
            checkedOut = checkedOut,
        ),
        // Trails are listed only for repositories that Entire hosts, so each has a page on the web.
        menu = RowMenu(web = true),
        hover = hover(
            headline(trail.title, 200),
            "Trail ${trail.number}" + joinParts(trail.phase ?: trail.status, trail.type, trail.priority?.takeIf { it != "none" }).let { if (it.isEmpty()) "" else ", $it" },
            *trail.changes.map { listed ->
                val into = listed.base?.let { " into $it" }.orEmpty()
                val here = if (hosted != null && isCheckedOut(listed, hosted, repo.branch)) ", checked out here" else ""
                "${listed.repository} ${listed.number}: ${listed.branch ?: "no branch"}$into$here"
            }.toTypedArray(),
            if (trail.assignees.isNotEmpty()) "Assigned to ${trail.assignees.joinToString(", ")}" else null,
            localTime(trail.lastActivityAt)?.let { "Last activity $it" },
        ),
    )
}
