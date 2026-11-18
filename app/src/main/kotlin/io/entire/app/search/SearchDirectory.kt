package io.entire.app.search

import io.entire.app.ports.HostLog
import io.entire.app.repo.RepoContext
import io.entire.core.CallResult
import io.entire.core.Failure
import io.entire.core.cli.EntireCli
import io.entire.core.cli.SEARCH_PAGE
import io.entire.core.cli.SearchHit

/** What Entire's search found for one query in one repository, as far as its pages have been read. */
class SearchResults(val query: String) {
    /** Best match first, across the pages read. */
    val hits: MutableList<SearchHit> = ArrayList()

    /** The last page read. 0 before the first arrives. */
    var page = 0
    var totalPages = 0

    /** How many results the query has in all, of every type, including the ones the plugin does not list. */
    var total = 0L

    /** A page is being read. */
    var loading = false

    /** Why the last page could not be read. */
    var failure: Failure? = null

    val more: Boolean get() = page in 1 until totalPages
}

/**
 * Searches Entire for the query typed into the Checkpoints or Sessions tab,
 * with `entire search`. It reads Entire over the network with the CLI's saved
 * login, and runs only when asked: on Enter, or on a click of its row.
 *
 * Everything here runs on the manager's own thread.
 */
class SearchDirectory(
    private val cli: () -> EntireCli?,
    private val log: HostLog,
    private val changed: () -> Unit,
) {
    /** The query the results are for. Null when there is none. */
    var query: String? = null
        private set

    /** Starts a search over: the first page, for each of these repositories. */
    suspend fun search(text: String, repos: List<RepoContext>) {
        query = text
        for (repo in repos) {
            repo.searchReads += 1
            repo.search = SearchResults(text).also { it.loading = true }
        }
        changed()
        for (repo in repos) readPage(repo)
    }

    /** Reads the next page of a repository's results. */
    suspend fun more(repo: RepoContext): Boolean {
        val results = repo.search ?: return true
        if (results.loading || !results.more) return true
        results.loading = true
        changed()
        return readPage(repo)
    }

    /** Drops the results, and any answer still on its way. */
    fun clear(repos: List<RepoContext>) {
        if (query == null && repos.all { it.search == null }) return
        query = null
        for (repo in repos) {
            repo.search = null
            repo.searchReads += 1
        }
        changed()
    }

    private suspend fun readPage(repo: RepoContext): Boolean {
        val results = repo.search ?: return true
        val read = repo.searchReads
        val current = cli()
        if (current == null) {
            results.loading = false
            results.failure = Failure(io.entire.core.FailureKind.CLI_MISSING, "The Entire CLI is not available.")
            changed()
            return false
        }
        val answer = current.search(repo.root, results.query, SEARCH_PAGE, results.page + 1)
        // A newer search, or a cleared one, makes this answer stale.
        if (read != repo.searchReads || repo.search !== results) return true
        results.loading = false
        return when (answer) {
            is CallResult.Ok -> {
                results.failure = null
                val known = results.hits.toHashSet()
                results.hits.addAll(answer.value.hits.filter { it !in known })
                results.page = answer.value.page
                results.totalPages = answer.value.totalPages
                results.total = answer.value.total
                changed()
                true
            }
            is CallResult.Failed -> {
                results.failure = answer.failure
                log.warn("${repo.name}: ${answer.failure.message} ${answer.failure.detail.orEmpty()}".trim())
                changed()
                false
            }
        }
    }
}
