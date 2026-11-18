package io.entire.app.repo

import io.entire.app.ports.HostLog
import io.entire.core.CallResult
import io.entire.core.Failure
import io.entire.core.FailureKind
import io.entire.core.checkpoint.PAGE_SIZE
import io.entire.core.cli.EntireCli
import io.entire.core.cli.TrailDetail
import io.entire.core.cli.TrailScope
import io.entire.core.cli.TrailSummary
import io.entire.core.fail
import io.entire.core.trails.DEFAULT_TRAIL_FILTER
import io.entire.core.trails.TrailFilter
import io.entire.core.trails.TrailListStatus
import io.entire.core.trails.TrailPages
import io.entire.core.trails.TrailRef
import io.entire.core.trails.addTrailPage
import io.entire.core.trails.inTrailFilter
import io.entire.core.trails.readTrailPages
import io.entire.core.trails.trailFilterKey
import io.entire.core.trails.trailProject
import io.entire.core.trails.trailRepo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** What the Trails tab shows: rows, or the reason it has none. */
enum class TrailsState { LOADING, LOGGED_OUT, NO_ACCESS, FAILED, EMPTY, READY }

/**
 * The trails of one repository, as far as they have been read: the ones the
 * CLI lists for a status. The tab's filter may show only some of them. See
 * [TrailDirectory.shown].
 */
class TrailList(
    /** The list's name for paging. See [Lists.trails]. */
    var list: String,
    /** What the CLI was asked to list: open trails, closed trails, or all. */
    val status: TrailListStatus,
) : TrailPages() {
    /** The first page is being read. */
    var loading = false

    /** Why the last read failed. */
    var failure: Failure? = null
}

/** What a trail panel says while trails are turned off in the settings. */
const val TRAILS_OFF = "Trails are turned off. Turn on Show Trails under Settings > Tools > Entire to read them from Entire."

/** What a trail panel says for a repository that Entire does not host. */
const val TRAILS_NEED_ENTIRE_ORIGIN = "Trails are available when the origin remote uses the entire:// scheme."

/**
 * The trails that touch the open repositories. Trails live on entire.io, and
 * every read here goes through the CLI with the login it has saved. Nothing
 * is read until the Trails tab asks, and nothing at all while trails are
 * turned off in the settings. Everything here runs on the manager's own
 * thread.
 */
class TrailDirectory(
    private val scope: CoroutineScope,
    private val state: CoroutineDispatcher,
    private val cli: () -> EntireCli?,
    /** Whether the settings allow trails to be read. */
    val enabled: () -> Boolean,
    private val log: HostLog,
    private val changed: () -> Unit,
) {
    /** The filter of the Trails tab, shared by every repository in it. */
    var filter: TrailFilter = DEFAULT_TRAIL_FILTER
        private set

    /** Repositories whose first page is on its way, so that a redraw does not start a second read. */
    private val starting = HashSet<String>()

    /**
     * The trails of a list that the filter shows. The CLI lists by status, so
     * a filter on a phase keeps the trails in that phase out of the ones read
     * so far.
     */
    fun shown(list: TrailList): List<TrailSummary> =
        if (filter.phase.isNullOrEmpty()) list.trails else list.trails.filter { inTrailFilter(it, filter) }

    /**
     * What the Trails tab shows. With one repository, a list with no rows
     * leaves the tab empty and this says why. With several, each repository
     * has a row of its own and says so under it.
     */
    fun state(repos: List<RepoContext>): TrailsState {
        val only = repos.singleOrNull() ?: return TrailsState.READY
        val list = only.trails
        if (list == null || (list.loading && list.trails.isEmpty())) return TrailsState.LOADING
        if (shown(list).isNotEmpty()) return TrailsState.READY
        list.failure?.let { failure ->
            return when (failure.kind) {
                FailureKind.NOT_LOGGED_IN -> TrailsState.LOGGED_OUT
                FailureKind.NO_ACCESS -> TrailsState.NO_ACCESS
                else -> TrailsState.FAILED
            }
        }
        // No row passes the filter yet, but the CLI has more to list: the tab keeps its Load More row.
        return if (list.nextPageToken != null) TrailsState.READY else TrailsState.EMPTY
    }

    /**
     * Reads the first page of trails for each repository that has not been
     * asked about yet. The Trails tab asks for this when it is on screen.
     */
    fun ensure(repos: List<RepoContext>) {
        for (repo in repos) {
            if (repo.trails != null || !starting.add(repo.root)) continue
            scope.launch(state) {
                try {
                    load(repo)
                } finally {
                    starting.remove(repo.root)
                }
            }
        }
    }

    /**
     * Changes which trails the Trails tab shows: every trail, the open ones,
     * the closed ones, or the open ones in one phase.
     */
    suspend fun setFilter(next: TrailFilter, repos: List<RepoContext>) {
        if (trailFilterKey(next) == trailFilterKey(filter)) return
        filter = next
        changed()
        coroutineScope {
            repos.map { repo ->
                async {
                    val list = repo.trails
                    // A phase is matched here, so the trails already read for the status serve any phase of it.
                    if (list != null && list.status == next.status && !list.loading && list.failure == null) refilter(repo, list) else load(repo)
                }
            }.awaitAll()
        }
    }

    /** Starts the trail list of a repository over from its first page. */
    suspend fun load(repo: RepoContext) {
        val current = cli()
        val hosted = repo.entireRepo
        if (current == null || hosted == null || !enabled()) return
        val read = ++repo.trailReads
        val list = TrailList(Lists.trails(trailFilterKey(filter), read), filter.status).also { it.loading = true }
        // A refresh keeps the rows on screen until the fresh ones arrive. Rows of
        // another status go at once: they would sit under the wrong heading.
        val previous = repo.trails
        if (previous == null || previous.status != list.status || previous.trails.isEmpty()) {
            repo.trails = list
            changed()
        }
        val page = current.listTrails(repo.root, TrailScope(trailProject(hosted), trailRepo(hosted)), list.status, PAGE_SIZE)
        if (read != repo.trailReads) return
        list.loading = false
        when (page) {
            is CallResult.Ok -> addTrailPage(list, page.value)
            is CallResult.Failed -> {
                list.failure = page.failure
                log.warn("${repo.name}: ${page.failure.message} ${page.failure.detail.orEmpty()}".trim())
            }
        }
        if (previous != null && previous !== list) repo.forgetList(previous.list)
        repo.trails = list
        changed()
        // With a filter on a phase, the first page may hold few trails that pass it.
        readMore(repo, list)
        changed()
    }

    /** Shows the trails already read under a filter that has changed, from the first page of rows. */
    private suspend fun refilter(repo: RepoContext, list: TrailList) {
        repo.forgetList(list.list)
        list.list = Lists.trails(trailFilterKey(filter), ++repo.trailReads)
        changed()
        readMore(repo, list)
        changed()
    }

    /**
     * Asks the CLI for pages until the list shows as many trails as its rows
     * allow, the CLI has no more, or one read's worth of pages has been asked
     * for. Reads of one repository run one after another, because each
     * continues from the token the one before it returned.
     */
    suspend fun readMore(repo: RepoContext, list: TrailList): Boolean {
        if (list.nextPageToken == null || shown(list).size >= repo.limit(list.list)) return true
        return repo.trailPages.withLock {
            // The list was replaced, or its rows were restarted under another filter, while this waited.
            val name = list.list
            fun current() = repo.trails === list && list.list == name
            if (!current()) return@withLock true
            val reader = cli()
            val hosted = repo.entireRepo
            if (reader == null || hosted == null) return@withLock false
            var replaced = false
            val okay = readTrailPages(
                list,
                fetch = { pageToken ->
                    val page = reader.listTrails(repo.root, TrailScope(trailProject(hosted), trailRepo(hosted)), list.status, PAGE_SIZE, pageToken)
                    if (repo.trails !== list) {
                        replaced = true
                        null
                    } else {
                        when (page) {
                            is CallResult.Ok -> page.value
                            is CallResult.Failed -> {
                                log.warn("${repo.name}: ${page.failure.message} ${page.failure.detail.orEmpty()}".trim())
                                null
                            }
                        }
                    }
                },
                enough = { !current() || shown(list).size >= repo.limit(list.list) },
                // Rows appear as each page arrives, which matters when a read takes several.
                onPage = { changed() },
            )
            okay || replaced
        }
    }

    /**
     * Names a trail for a CLI call: by its ID when the caller, the Trails
     * tab, or an earlier read has it, and by its number otherwise.
     */
    fun trailRef(repo: RepoContext, trail: Long, id: String? = null): TrailRef {
        val known = id ?: repo.trails?.trails?.firstOrNull { it.number == trail }?.id ?: repo.trailMemory.idOf(trail)
        return TrailRef(trail, known)
    }

    /**
     * Reads one trail from entire.io: its changes, their merge gates and CI
     * runs, and its page on the web. The trail is kept for the views and for
     * the next panel that opens it. A read that is not shared stops when its
     * caller is cancelled.
     */
    suspend fun showTrail(repo: RepoContext, trail: TrailRef, shared: Boolean = true): CallResult<TrailDetail> {
        val current = cli() ?: return fail(FailureKind.CLI_MISSING, "The Entire CLI is not available.")
        if (!enabled()) return fail(FailureKind.NO_ACCESS, TRAILS_OFF)
        val hosted = repo.entireRepo ?: return fail(FailureKind.NO_ACCESS, TRAILS_NEED_ENTIRE_ORIGIN)
        val detail = current.showTrail(repo.root, trailProject(hosted), trail, shared)
        // An answer for an origin that has since changed belongs to another repository.
        if (repo.entireRepo != hosted) return detail
        when (detail) {
            is CallResult.Ok -> {
                detail.value.url?.let { repo.trailUrls[detail.value.number] = it }
                repo.trailMemory.keepDetail(detail.value)
            }
            is CallResult.Failed -> {
                if (detail.failure.kind == FailureKind.NOT_FOUND) repo.trailMemory.forget(trail.number)
                log.warn("Trail ${trail.number}: ${detail.failure.message} ${detail.failure.detail.orEmpty()}".trim())
            }
        }
        return detail
    }

    /**
     * The web page of a trail. It comes from the CLI: a trail's address is
     * not derived from its number here, because the CLI asks callers not to.
     */
    suspend fun trailUrl(repo: RepoContext, trail: Long): String? {
        repo.trailUrls[trail]?.let { return it }
        return (showTrail(repo, trailRef(repo, trail)) as? CallResult.Ok)?.value?.url
    }
}
