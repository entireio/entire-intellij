package io.entire.app.rows

import io.entire.app.repo.RepoContext
import io.entire.app.search.SearchResults
import io.entire.core.FailureKind
import io.entire.core.cli.SearchHit
import io.entire.core.format.headline
import io.entire.core.format.joinParts
import io.entire.core.format.relativeTime

/** What a row of Entire's search results opens. */
data class SearchRef(
    val type: String,
    val id: String,
    val commitSha: String? = null,
    val sessionId: String? = null,
    /** For a session: the checkpoint whose content matched. */
    val checkpointId: String? = null,
    /** For a commit: its page on its forge, when Entire knows it. */
    val url: String? = null,
)

/** Which hits a tab lists: commits and checkpoints in the Checkpoints tab, sessions in the Sessions tab. */
enum class SearchKind { CHECKPOINTS, SESSIONS }

fun searchGroupRowId(root: String): String = "$root|search"

/** The list name of a repository's search results, for their Load More row. */
const val SEARCH_LIST = "search"

/**
 * The "On Entire" group of a repository: what Entire's search found that
 * this tab lists, with a Load More row while Entire has more pages. Null
 * when no search has been asked for.
 */
fun searchGroup(repo: RepoContext, kind: SearchKind, now: Long): RowNode? {
    val results = repo.search ?: return null
    val id = searchGroupRowId(repo.root)
    val hits = results.hits.filter { if (kind == SearchKind.SESSIONS) it is SearchHit.Session else it !is SearchHit.Session }
    val children = ArrayList<RowNode>()
    hits.forEach { children.add(hitRow(repo, id, it, now)) }
    val failure = results.failure
    when {
        results.loading && hits.isEmpty() -> children.add(messageRow("$id|loading", "Searching Entire…", repo.root))
        failure != null -> children.add(
            messageRow("$id|failed", failure.message, repo.root).let {
                if (failure.kind == FailureKind.NOT_LOGGED_IN) it.copy(link = EmptyLink.LOG_IN, label = "${failure.message} Log in to search.") else it
            },
        )
        hits.isEmpty() && !results.more ->
            children.add(messageRow("$id|none", if (kind == SearchKind.SESSIONS) "Entire found no sessions." else "Entire found no commits or checkpoints.", repo.root))
    }
    if (results.more) {
        children.add(
            RowNode(
                id = loadMoreRowId(repo.root, SEARCH_LIST),
                kind = RowKind.LOAD_MORE,
                label = if (results.loading) "Loading more results…" else "Load more results",
                description = "page ${results.page} of ${results.totalPages}",
                repoRoot = repo.root,
                list = SEARCH_LIST,
                // A search is asked for, so its next page is too.
                auto = false,
            ),
        )
    }
    return RowNode(
        id = id,
        kind = RowKind.SEARCH_GROUP,
        label = "On Entire",
        // A count is given once there is one: a search that is under way or failed has found nothing yet.
        description = "'${headline(results.query, 40)}'" +
            if (hits.isEmpty() && (results.loading || failure != null)) "" else " · ${count(hits.size)} ${if (hits.size == 1) "result" else "results"}",
        repoRoot = repo.root,
        children = children,
        hover = Hover(null, listOf("What Entire's search finds for '${results.query}' in this repository, on every branch.")),
    )
}

private fun hitRow(repo: RepoContext, parentId: String, hit: SearchHit, now: Long): RowNode =
    when (hit) {
        is SearchHit.Commit -> RowNode(
            id = "$parentId|commit:${hit.sha}",
            kind = RowKind.SEARCH_HIT,
            label = headline(hit.subject).ifEmpty { hit.sha.take(7) },
            description = joinParts(hit.sha.take(7), hit.branch, hit.author, relativeTime(hit.createdAt, now)),
            repoRoot = repo.root,
            commitSha = hit.sha,
            search = SearchRef("commit", hit.sha, commitSha = hit.sha, url = hit.url),
            menu = RowMenu(web = repo.entireRepo != null || hit.url != null),
            hover = hover(
                hit.subject,
                "Commit ${hit.sha}",
                hit.branch?.let { "Branch $it" },
                joinParts(hit.author, localTime(hit.createdAt)),
                hit.filesChanged?.let { "${count(it)} files changed, +${hit.additions ?: 0} −${hit.deletions ?: 0}" },
            ),
        )
        is SearchHit.Checkpoint -> RowNode(
            id = "$parentId|checkpoint:${hit.checkpointId}",
            kind = RowKind.SEARCH_HIT,
            label = headline(hit.title).ifEmpty { hit.checkpointId },
            description = joinParts(hit.branch, hit.author, relativeTime(hit.createdAt, now)),
            repoRoot = repo.root,
            commitSha = hit.commitSha,
            checkpointId = hit.checkpointId,
            search = SearchRef("checkpoint", hit.checkpointId, commitSha = hit.commitSha, sessionId = hit.sessionId),
            menu = RowMenu(sessionId = hit.sessionId, web = repo.entireRepo != null),
            hover = hover(hit.title?.let { headline(it, 200) }, "Checkpoint ${hit.checkpointId}", hit.branch?.let { "Branch $it" }, joinParts(hit.author, localTime(hit.createdAt))),
        )
        is SearchHit.Session -> RowNode(
            id = "$parentId|session:${hit.sessionId}",
            kind = RowKind.SEARCH_HIT,
            label = headline(hit.name ?: hit.prompt).ifEmpty { "${hit.agent ?: "Agent"} session" },
            description = joinParts(hit.agent, hit.branch, hit.author, relativeTime(hit.createdAt, now)),
            repoRoot = repo.root,
            sessionId = hit.sessionId,
            checkpointId = hit.matchedCheckpointId,
            search = SearchRef("session", hit.sessionId, sessionId = hit.sessionId, checkpointId = hit.matchedCheckpointId),
            menu = RowMenu(sessionId = hit.sessionId, web = repo.entireRepo != null),
            hover = hover(
                hit.name?.let { headline(it, 200) },
                hit.prompt?.let { "Opening prompt: ${headline(it, 200)}" },
                "Session ${hit.sessionId}",
                joinParts(hit.agent, hit.model),
                hit.branch?.let { "Branch $it" },
                joinParts(hit.author, localTime(hit.createdAt)),
                hit.steps?.let { "${count(it)} steps" },
            ),
        )
    }
