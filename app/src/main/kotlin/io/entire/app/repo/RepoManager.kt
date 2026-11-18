package io.entire.app.repo

import io.entire.app.ports.FolderSource
import io.entire.app.ports.HostEnvironment
import io.entire.app.ports.HostLog
import io.entire.app.ports.Settings
import io.entire.app.rows.RowsView
import io.entire.app.rows.checkpointRowId
import io.entire.app.rows.RowKind
import io.entire.app.rows.SEARCH_LIST
import io.entire.app.rows.SearchKind
import io.entire.app.rows.SearchRef
import io.entire.app.rows.checkpointsView
import io.entire.app.rows.searchGroup
import io.entire.app.rows.commitRowId
import io.entire.app.rows.otherGroupRowId
import io.entire.app.rows.repoRowId
import io.entire.app.rows.sessionCount
import io.entire.app.rows.sessionGroupRowId
import io.entire.app.rows.sessionRowId
import io.entire.app.rows.sessionsView
import io.entire.app.rows.slotRowId
import io.entire.app.rows.trailsView
import io.entire.core.CallResult
import io.entire.core.FailureKind
import io.entire.core.checkpoint.CheckpointIndex
import io.entire.core.checkpoint.CheckpointStore
import io.entire.core.checkpoint.LIST_LIMIT
import io.entire.core.checkpoint.PAGE_SIZE
import io.entire.core.checkpoint.loadPage
import io.entire.core.cli.CheckpointDetail
import io.entire.core.cli.CliVersion
import io.entire.core.cli.EntireCli
import io.entire.core.cli.LocateDeps
import io.entire.core.cli.MIN_CLI_VERSION_TEXT
import io.entire.core.cli.SessionRow
import io.entire.core.cli.StatusInfo
import io.entire.core.cli.isSupportedVersion
import io.entire.core.cli.locateCli
import io.entire.core.cli.loginShellProbe
import io.entire.core.exec.Exec
import io.entire.core.fail
import io.entire.core.git.CommitRecord
import io.entire.core.git.Git
import io.entire.core.git.RepoPaths
import io.entire.core.protocol.DetailMode
import io.entire.core.protocol.DetailTarget
import io.entire.core.sched.Lane
import io.entire.core.trails.TrailFilter
import io.entire.core.trails.TrailMemory
import io.entire.core.trails.trailFilterLabel
import io.entire.app.search.SearchDirectory
import io.entire.app.search.SearchResults
import io.entire.app.setup.CliState
import io.entire.app.setup.SetupFacts
import io.entire.core.web.EntireRepo
import io.entire.core.web.commitUrl
import io.entire.core.web.sessionUrl
import java.nio.file.Files
import io.entire.core.web.parseEntireRemote
import io.entire.core.web.resolveWebBase
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the views can show: why there is nothing yet, or that there is something. */
enum class ViewState { LOADING, NO_FOLDER, NOT_GIT, CLI_MISSING, CLI_TOO_OLD, NOT_ENABLED, READY }

/** The names of the lists that show one page of rows at a time. */
object Lists {
    const val COMMITS = "commits"
    const val OTHER_CHECKPOINTS = "other"
    const val ACTIVE_SESSIONS = "sessions:active"
    const val RECENT_SESSIONS = "sessions:recent"

    fun sessionCheckpoints(sessionId: String): String = "session:$sessionId"

    /**
     * Each fresh read of the trails, and each change of the filter, gets a
     * list of its own, so it starts again at one page.
     */
    fun trails(filter: String, read: Int): String = "trails:$filter:$read"
}

/**
 * What the tool window shows besides its rows: which tabs it has, and what
 * the Trails tab is filtered to.
 */
data class Chrome(
    /** A repository that Entire hosts is open and trails are turned on, so the Trails tab exists. */
    val hasTrails: Boolean = false,
    /** The filter of the Trails tab by name, such as "Open" or "Building". */
    val trailFilter: String = "",
    /** The repositories have been read and Entire is enabled in one, so the Brain tab exists. */
    val ready: Boolean = false,
    /** The repositories that Entire hosts, each of which has a page on entire.com: root and name. */
    val webRepos: List<Pair<String, String>> = emptyList(),
    /**
     * What is missing when the CLI is missing or too old, or Entire is not
     * enabled in any repository. Null when nothing is. A change of what is
     * missing, such as a CLI path set right while Entire is still not
     * enabled, is a change of the chrome, so the Setup tab reads again.
     */
    val setup: ViewState? = null,
    /** The query Entire's search last ran, so the tabs can tell whether their text has moved on. */
    val searched: String? = null,
) {
    /** The Setup tab comes first. */
    val setupNeeded: Boolean get() = setup != null
}

/** What a row of Entire's search results opens. */
sealed interface HitTarget {
    data class Detail(val target: DetailTarget) : HitTarget

    data class Web(val url: String) : HitTarget
}

/** How many more pages a list may grow by, to bring the row of an open tab onto it. */
private const val MAX_PAGES_TO_REACH_A_ROW = 20
private const val DETAIL_CACHE_SIZE = 200
private const val DETAIL_TTL_MS = 5 * 60_000L

/** Automatic enrichment runs at most this often for each repository. */
private const val ENRICHMENT_MIN_INTERVAL_MS = 60_000L
private const val ENRICHMENT_DEBOUNCE_MS = 3000L

/** An answer about one checkpoint, and when it was asked for. */
class CachedDetail(val at: Long, val result: Deferred<CallResult<CheckpointDetail>>) {
    /** The answer, once it has arrived. The rows read it, because they cannot wait. */
    var outcome: CallResult<CheckpointDetail>? = null
}

/** What is known about one repository. Only the manager's own thread reads and writes it. */
class RepoContext(val paths: RepoPaths) {
    val root: String get() = paths.root
    val name: String = Path.of(paths.root).fileName?.toString() ?: paths.root
    var branch: String? = null
    var status: StatusInfo? = null
    var index: CheckpointIndex? = null
    var gitError: String? = null
    var originUrl: String? = null

    /** The repository on Entire that the origin remote names, when it uses the entire:// scheme. */
    var entireRepo: EntireRepo? = null

    /** What the CLI said about checkpoints lately, least recently used first. */
    val details = LinkedHashMap<String, CachedDetail>()

    /** Checkpoints a commit names whose session data the CLI could not find. */
    val withoutData = HashSet<String>()

    /** Why the sessions of a checkpoint could not be listed, for each checkpoint whose row was opened and whose read failed. */
    val slotFailures = HashMap<String, String>()

    /** Who the CLI named as the author of a checkpoint that no scanned commit carries. */
    val checkpointAuthors = HashMap<String, String>()

    /** The rows of `entire session list`. Null until the Sessions tab has asked for them. */
    var sessionRows: List<SessionRow>? = null

    /** The session list is being read for the first time, so an empty list does not yet mean there are no sessions. */
    var sessionRowsLoading = false

    /** Entire's list of the repository's sessions. Null until the Sessions tab has asked for it. */
    var remoteSessions: RemoteSessionList? = null

    /** Raised each time Entire's list is read from its first page, so that an older read can tell it was overtaken. */
    var remoteSessionReads = 0

    /** Entire's list of the checkpoints of each session that was asked about. */
    val remoteCheckpoints = LinkedHashMap<String, RemoteCheckpointRead>()

    /** The opening prompt read for each session, which names a session that Entire has not named. */
    val sessionNames = HashMap<String, SessionName>()
    internal val remotePages = Mutex()

    /** What Entire's search found for the query of the Checkpoints and Sessions tabs. Null when nothing was searched. */
    var search: SearchResults? = null

    /** Counts the searches, so that a late answer to an earlier one can be dropped. */
    var searchReads = 0

    /** Null until the Trails tab first asks, so nothing reads entire.io before then. */
    var trails: TrailList? = null

    /** Counts the reads of the trail list, so a late answer to an earlier one can be dropped. */
    var trailReads = 0

    /** Web pages of trails, by trail number, as `entire trail show` reported them. */
    val trailUrls = HashMap<Long, String>()

    /** Trails read in this project, so a trail panel can draw one at once while it reads it again. */
    val trailMemory = TrailMemory()
    internal val trailPages = Mutex()

    /** Drops everything read about the repository's trails. An answer still on its way is ignored. */
    fun forgetTrails() {
        trails?.let { forgetList(it.list) }
        trails = null
        trailReads += 1
        trailUrls.clear()
        trailMemory.clear()
    }

    /** Drops what is kept about a list that no longer exists. */
    fun forgetList(list: String) {
        limits.remove(list)
        autoLoadPaused.remove(list)
    }

    /** Drops what Entire listed. An answer still on its way is ignored. */
    fun forgetRemoteSessions() {
        remoteSessions = null
        remoteSessionReads += 1
        remoteCheckpoints.clear()
    }

    /** Raised each time the index is rebuilt, so that a read that finishes late can tell. */
    var generation = 0
    var lastRefresh = 0L
    var lastEnrichment = 0L

    /** The wait before the CLI is next asked for checkpoint rows. A newer change of the history starts the wait over. */
    internal var enrichmentTimer: Job? = null

    /** Lists whose next page is not loaded on scroll, because the last such load found no new row. */
    val autoLoadPaused = HashSet<String>()
    internal val scans = Mutex()
    private val limits = HashMap<String, Int>()

    val enabled: Boolean get() = status?.enabled == true

    /** How many rows of a list the views show. */
    fun limit(list: String): Int = limits[list] ?: PAGE_SIZE

    fun showMore(list: String) {
        limits[list] = limit(list) + PAGE_SIZE
    }
}

/**
 * Finds the project's repositories and the Entire CLI, and keeps what the
 * views show about them.
 *
 * All of its state lives on one thread. A function here runs from one
 * suspension point to the next without another one running in between, so a
 * read that finishes late compares a counter it took before it started with
 * the counter now, and drops its answer when they differ.
 */
class RepoManager(
    private val scope: CoroutineScope,
    private val exec: Exec,
    private val settings: Settings,
    private val folders: FolderSource,
    private val environment: HostEnvironment,
    private val log: HostLog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val state = Dispatchers.Default.limitedParallelism(1)
    private val gitLane = Lane(4, scope)
    private val cliLane = Lane(4, scope)

    /** The one thread on which this manager's state, and that of the panels that read it, is touched. */
    val dispatcher: CoroutineDispatcher get() = state

    var git = Git(exec, gitLane, "git", environment::variables)
        private set
    var cli: EntireCli? = null
        private set

    /** Where the CLI was found, including one too old to use. Null when none was found. */
    var cliPath: String? = null
        private set

    /** The version of the CLI that was found, including one too old to use. */
    val cliVersionText: String? get() = cliVersion?.raw

    /** What the views can show. Call it on [dispatcher]. */
    val currentState: ViewState get() = viewState

    /** Reads what checkpoints store in git. It is the only reader of that layout. */
    val store: CheckpointStore get() = CheckpointStore(git)
    private var cliVersion: CliVersion? = null
    private var loadSequence = 0
    private var repos: List<RepoContext> = emptyList()
    private var viewState = ViewState.LOADING

    private val published = MutableStateFlow(RowsView(ViewState.LOADING, emptyList(), null))

    /** What the Checkpoints tab shows. A new value replaces the old one whole. */
    val checkpoints: StateFlow<RowsView> = published.asStateFlow()

    private val publishedSessions = MutableStateFlow(RowsView(ViewState.LOADING, emptyList(), null))

    /** What the Sessions tab shows. It has no rows until the tab has been on screen. */
    val sessionRows: StateFlow<RowsView> = publishedSessions.asStateFlow()

    /** The Sessions tab has been on screen, so its two reads may run: the session list, and Entire's list. */
    private var sessionsShown = false

    private val publishedTrails = MutableStateFlow(RowsView(ViewState.LOADING, emptyList(), null))

    /** What the Trails tab shows. It has no rows until the tab has been on screen. */
    val trailRows: StateFlow<RowsView> = publishedTrails.asStateFlow()

    /** The Trails tab has been on screen, so trails may be read from Entire. */
    private var trailsShown = false

    private val publishedChrome = MutableStateFlow(Chrome())

    /** Which tabs the tool window has, and what the Trails tab is filtered to. */
    val chrome: StateFlow<Chrome> = publishedChrome.asStateFlow()

    val trails = TrailDirectory(scope, state, { cli }, { settings.trailsEnabled }, log, { publish() })

    /** Whether the settings allow trails to be read. */
    val trailsEnabled: Boolean get() = settings.trailsEnabled

    /** True until the first look at the project has finished. */
    val loading: Boolean get() = viewState == ViewState.LOADING

    /** Repositories that Entire hosts, which are the ones the Trails tab lists. None while trails are turned off. */
    val trailRepos: List<RepoContext>
        get() {
            if (!settings.trailsEnabled) return emptyList()
            // Trails live on entire.io, so a repository can have them before Entire is enabled in its clone.
            if (viewState != ViewState.READY && viewState != ViewState.NOT_ENABLED) return emptyList()
            return repos.filter { it.entireRepo != null }
        }

    /** The repositories in which Entire is enabled. Call it on [dispatcher]. */
    val enabled: List<RepoContext> get() = enabledRepos

    /** The repositories the project has open. Call it on [dispatcher]. */
    val open: List<RepoContext> get() = repos

    val sessions = SessionDirectory(scope, state, { cli }, { store }, log, now, { publish() }, ::repoFor)

    private val enabledRepos: List<RepoContext> get() = repos.filter { it.enabled }

    /** The repository with this root, when the project has it open. Call it on [dispatcher]. */
    fun repoFor(root: String): RepoContext? = repos.firstOrNull { it.root == root }

    /** Returns once the first look at the project has finished, whatever it found. */
    suspend fun whenLoaded() {
        published.first { it.state != ViewState.LOADING }
    }

    /** The base of links to the Entire web app. */
    fun webBase(): String = resolveWebBase(settings.webBaseUrl, environment.variables())

    /**
     * Checkpoint detail from the CLI, cached. A failure is not cached, so a
     * retry runs again, except "not found": the CLI spends two seconds asking
     * the remote before it says so, and the answer rarely changes within minutes.
     */
    suspend fun detail(repo: RepoContext, checkpointId: String): CallResult<CheckpointDetail> = withContext(state) {
        val current = cli ?: return@withContext fail(FailureKind.CLI_MISSING, "The Entire CLI is not available.")
        val cached = repo.details[checkpointId]
        if (cached != null && now() - cached.at < DETAIL_TTL_MS) {
            // Move the entry to the end, so the least recently used one is dropped first.
            repo.details.remove(checkpointId)
            repo.details[checkpointId] = cached
            return@withContext cached.result.await()
        }
        var entry: CachedDetail? = null
        // The read belongs to the manager. A panel that stops waiting does not stop it for the others.
        val result = scope.async(state, start = CoroutineStart.LAZY) {
            val outcome = current.explainCheckpoint(repo.root, checkpointId)
            val missing = outcome is CallResult.Failed && outcome.failure.kind == FailureKind.NOT_FOUND
            if (outcome is CallResult.Failed) {
                if (!missing && repo.details[checkpointId] === entry) repo.details.remove(checkpointId)
                log.warn("$checkpointId: ${outcome.failure.message} ${outcome.failure.detail.orEmpty()}".trim())
            }
            entry?.outcome = outcome
            // Rows for the checkpoint say whether it has data, so they are redrawn when that is learned.
            if (missing != (checkpointId in repo.withoutData) && (missing || outcome is CallResult.Ok)) {
                if (missing) repo.withoutData.add(checkpointId) else repo.withoutData.remove(checkpointId)
                publish()
            }
            outcome
        }
        entry = CachedDetail(now(), result)
        // The rows under the checkpoint keep the sessions they list while the answer is read again.
        entry.outcome = cached?.outcome?.takeIf { it is CallResult.Ok }
        repo.details.remove(checkpointId)
        repo.details[checkpointId] = entry
        if (repo.details.size > DETAIL_CACHE_SIZE) repo.details.remove(repo.details.keys.first())
        result.start()
        result.await()
    }

    private fun publish() {
        published.value = withSearch(checkpointsView(viewState, enabledRepos, now()), SearchKind.CHECKPOINTS)
        publishedSessions.value = if (sessionsShown) {
            ensureSessions()
            withSearch(sessionsView(viewState, enabledRepos, sessions, now()), SearchKind.SESSIONS)
        } else {
            // Nothing about sessions has been read, so a repository that is ready does not yet say it has none.
            RowsView(if (viewState == ViewState.READY) ViewState.LOADING else viewState, emptyList(), null)
        }
        val hosted = trailRepos
        publishedTrails.value = if (trailsShown && hosted.isNotEmpty()) {
            trails.ensure(hosted)
            trailsView(hosted, trails, now())
        } else {
            RowsView(ViewState.LOADING, emptyList(), null)
        }
        publishedChrome.value = Chrome(
            hasTrails = hosted.isNotEmpty(),
            trailFilter = trailFilterLabel(trails.filter),
            ready = viewState == ViewState.READY,
            webRepos = repos.filter { it.entireRepo != null }.map { it.root to it.name },
            setup = viewState.takeIf { it == ViewState.CLI_MISSING || it == ViewState.CLI_TOO_OLD || it == ViewState.NOT_ENABLED },
            searched = search.query,
        )
        for (listener in listeners) listener()
    }

    /**
     * Puts each repository's "On Entire" group at the end of what a tab
     * shows: at the end of the list with one repository, and under each
     * repository's row with several.
     */
    private fun withSearch(view: RowsView, kind: SearchKind): RowsView {
        if (enabledRepos.none { it.search != null }) return view
        val single = enabledRepos.singleOrNull()
        val rows = if (single != null) {
            view.rows + listOfNotNull(searchGroup(single, kind, now()))
        } else {
            view.rows.map { row ->
                val repo = enabledRepos.firstOrNull { row.kind == RowKind.REPO && it.root == row.repoRoot } ?: return@map row
                searchGroup(repo, kind, now())?.let { row.copy(children = row.children + it) } ?: row
            }
        }
        return view.copy(rows = rows)
    }

    val search = SearchDirectory({ cli }, log, { publish() })

    /** Searches Entire for a query in each repository where Entire is enabled, and shows the first page. */
    suspend fun searchEntire(query: String) = withContext(state) {
        search.search(query.trim(), enabledRepos)
    }

    /** Forgets what Entire's search found, as when the search text is cleared or changed. */
    suspend fun clearSearch() = withContext(state) {
        search.clear(repos)
    }

    /**
     * What a row of Entire's search results opens: a tab in the detail panel
     * when the hit can be shown in one, and otherwise its page on the web.
     * A session that the clone and Entire's session list do not know opens
     * the checkpoint that matched, with the session chosen. A commit opens
     * its checkpoint when the clone has the commit and it names one.
     */
    suspend fun resolveSearchHit(root: String, hit: SearchRef): HitTarget? = withContext(state) {
        val repo = repoFor(root) ?: return@withContext null
        val hosted = repo.entireRepo
        when (hit.type) {
            "session" -> {
                val sessionId = hit.sessionId ?: return@withContext null
                when {
                    sessions.entry(repo, sessionId) != null -> HitTarget.Detail(DetailTarget(root, DetailMode.SESSION, sessionId = sessionId))
                    // The session is on Entire and not in the lists read so far. Its matched checkpoint is where the panel starts.
                    hit.checkpointId != null -> HitTarget.Detail(DetailTarget(root, DetailMode.SESSION, checkpointId = hit.checkpointId, sessionId = sessionId))
                    hosted != null -> HitTarget.Web(sessionUrl(webBase(), hosted, sessionId))
                    else -> null
                }
            }
            "checkpoint" -> HitTarget.Detail(DetailTarget(root, DetailMode.CHECKPOINT, checkpointId = hit.id, commitSha = hit.commitSha, sessionId = hit.sessionId))
            "commit" -> {
                val sha = hit.commitSha ?: hit.id
                val commit = git.commit(root, sha)
                val checkpointId = commit?.checkpointIds?.firstOrNull()
                when {
                    commit != null && checkpointId != null -> HitTarget.Detail(DetailTarget(root, DetailMode.CHECKPOINT, checkpointId = checkpointId, commitSha = commit.sha))
                    hosted != null -> HitTarget.Web(commitUrl(webBase(), hosted, sha))
                    hit.url != null -> HitTarget.Web(hit.url)
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * What the Setup tab shows for a repository: whether the CLI is there,
     * the login, and how far Entire, the graph, and the brain are set up in
     * the repository. Each read only reads.
     */
    suspend fun setupFacts(root: String?): SetupFacts = withContext(state) {
        val cliState = when (viewState) {
            ViewState.CLI_MISSING -> CliState.MISSING
            ViewState.CLI_TOO_OLD -> CliState.TOO_OLD
            else -> if (cli != null) CliState.FOUND else CliState.MISSING
        }
        val repo = root?.let { repoFor(it) } ?: repos.firstOrNull { !it.enabled } ?: repos.firstOrNull()
        val base = SetupFacts(
            cliState = cliState,
            cliPath = cliPath,
            cliVersion = cliVersionText,
            configuredPath = settings.cliPath,
            root = repo?.root,
            repoName = repo?.name,
            hosted = repo?.entireRepo != null,
        )
        val current = cli ?: return@withContext base
        if (repo == null) return@withContext base.copy(login = current.authStatus(folders.folders().firstOrNull() ?: environment.homeDir))
        coroutineScope {
            val login = async { current.authStatus(repo.root) }
            val status = async { current.status(repo.root) }
            val agents = async { current.agentList(repo.root) }
            val graph = async { current.graphVersion(repo.root) }
            val brain = async { current.brainStatus(repo.root, shared = false) }
            val enabled = (status.await() as? CallResult.Ok)?.value?.enabled ?: repo.enabled
            base.copy(
                login = login.await(),
                enabled = enabled,
                agents = agents.await(),
                graph = graph.await(),
                brain = brain.await(),
                agentGuide = Files.isRegularFile(Path.of(repo.root, ".entire", "agent-guide.md")),
            )
        }
    }

    /** The name Entire's search gave a session, for a session that the lists read so far do not have. */
    fun searchedSessionName(repo: RepoContext, sessionId: String): String? =
        repo.search?.hits?.filterIsInstance<io.entire.core.cli.SearchHit.Session>()?.firstOrNull { it.sessionId == sessionId }?.let { it.name ?: it.prompt }

    /** The repositories the Setup tab can set up: root and name. */
    suspend fun setupRepos(): List<Pair<String, String>> = withContext(state) { repos.map { it.root to it.name } }

    private val listeners = ArrayList<() -> Unit>()

    /** Calls `listener` on [dispatcher] each time what the views show may have changed. */
    suspend fun onChange(listener: () -> Unit) = withContext(state) {
        listeners.add(listener)
    }

    /**
     * The Trails tab is on screen, so the trails of each repository that
     * Entire hosts may be read.
     */
    suspend fun showTrails() = withContext(state) {
        trailsShown = true
        publish()
    }

    /** Changes which trails the Trails tab shows. */
    suspend fun setTrailFilter(filter: TrailFilter) = withContext(state) {
        trails.setFilter(filter, trailRepos)
        publish()
    }

    /**
     * Applies a change of the setting that allows trails. Turning trails off
     * hides the Trails tab and drops what was read, so turning them on again
     * starts from a fresh read.
     */
    suspend fun applyTrailsSetting() = withContext(state) {
        if (!settings.trailsEnabled) repos.forEach { it.forgetTrails() }
        publish()
    }

    /** The web page of a trail, which the CLI reports. Null when it could not be looked up. */
    suspend fun trailUrl(repo: RepoContext, trail: Long): String? = withContext(state) { trails.trailUrl(repo, trail) }

    /** The phases of the trails read so far, for the filter to offer. */
    suspend fun trailPhases(): List<String?> = withContext(state) {
        trailRepos.flatMap { repo -> repo.trails?.trails?.map { it.phase } ?: emptyList() }
    }

    /** The filter of the Trails tab. */
    suspend fun trailFilter(): TrailFilter = withContext(state) { trails.filter }

    /**
     * The Sessions tab is on screen. The full session list loads now, and
     * again only on a manual refresh, because listing prunes old session
     * state. Entire's own list of the sessions, which names them, is read at
     * the same two moments.
     */
    suspend fun showSessions() = withContext(state) {
        sessionsShown = true
        publish()
    }

    private fun ensureSessions() {
        if (viewState != ViewState.READY) return
        for (repo in enabledRepos) {
            if (repo.sessionRows == null) {
                repo.sessionRows = emptyList()
                repo.sessionRowsLoading = true
                scope.launch(state) { sessions.loadSessionRows(repo) }
            }
            if (repo.entireRepo != null && repo.remoteSessions == null) {
                // The list is set at once, so that the next redraw does not start a second read.
                repo.remoteSessions = RemoteSessionList().also { it.loading = true }
                scope.launch(state) {
                    repo.remoteSessions = null
                    sessions.loadRemoteSessions(repo)
                }
            }
        }
    }

    /** A session row was opened. Entire is asked for the session's checkpoints when it counts more than the clone knows. */
    suspend fun sessionExpanded(root: String, sessionId: String) = withContext(state) {
        val repo = repoFor(root) ?: return@withContext
        sessions.checkpoints(repo, sessionId)
        publish()
    }

    /** The row of a checkpoint that holds several sessions was opened. The CLI lists them, and the row then shows one row for each. */
    suspend fun checkpointOpened(root: String, checkpointId: String) = withContext(state) {
        val repo = repoFor(root) ?: return@withContext
        when (val answer = detail(repo, checkpointId)) {
            is CallResult.Ok -> repo.slotFailures.remove(checkpointId)
            is CallResult.Failed -> repo.slotFailures[checkpointId] = answer.failure.message
        }
        // The repository may have been closed while the CLI answered.
        if (repoFor(root) === repo) publish()
    }

    /**
     * The ID of the row of one session under the row of a checkpoint that
     * holds several, or null when the checkpoint's row has no such row under
     * it. The CLI is asked for the checkpoint's sessions when they are not
     * known, and the rows are then redrawn with them.
     */
    private suspend fun slotRow(repo: RepoContext, checkpointId: String, sessionId: String?, parentId: String): String? {
        if (sessionId == null || sessionCount(repo.index?.row(checkpointId)) <= 1) return null
        val known = repo.details[checkpointId]?.outcome is CallResult.Ok
        val answer = detail(repo, checkpointId) as? CallResult.Ok<CheckpointDetail> ?: return null
        val session = answer.value.sessions.firstOrNull { it.sessionId == sessionId } ?: return null
        if (!known) publish()
        return slotRowId(parentId, session.index)
    }

    private fun setState(next: ViewState) {
        viewState = next
        publish()
    }

    /**
     * Looks at everything again: the folders, the CLI, each repository's
     * status, and its history. `manual` is true for the Refresh button.
     */
    suspend fun reload(manual: Boolean) = withContext(state) {
        val sequence = ++loadSequence
        fun stale() = sequence != loadSequence
        val previous = repos.associateBy { it.root }
        setState(if (repos.isEmpty()) ViewState.LOADING else viewState)

        val projectFolders = folders.folders()
        if (projectFolders.isEmpty()) {
            repos = emptyList()
            setState(ViewState.NO_FOLDER)
            return@withContext
        }

        git = Git(exec, gitLane, environment.gitPath(), environment::variables)
        val found = ArrayList<RepoContext>()
        for (folder in projectFolders) {
            val paths = git.repoPaths(folder)
            if (stale()) return@withContext
            when (paths) {
                is CallResult.Failed -> log.info("$folder: ${paths.failure.message} ${paths.failure.detail.orEmpty()}".trim())
                is CallResult.Ok -> if (found.none { it.root == paths.value.root }) {
                    found.add(previous[paths.value.root] ?: RepoContext(paths.value))
                }
            }
        }
        repos = found
        if (found.isEmpty()) {
            setState(ViewState.NOT_GIT)
            return@withContext
        }

        val cwd = found.first().root
        val configured = settings.cliPath
        val variables = environment.variables()
        val located = locateCli(
            configured,
            LocateDeps(
                env = variables,
                windows = environment.windows,
                homeDir = environment.homeDir,
                probeLoginShell = loginShellProbe(exec, variables, cwd),
            ),
        )
        if (stale()) return@withContext
        cliPath = located?.path
        if (located == null) {
            cli = null
            log.warn(
                if (!configured.isNullOrBlank()) {
                    "The Entire CLI path is set to \"$configured\", but no executable exists there."
                } else {
                    "The Entire CLI was not found on PATH, in common install locations, or through the login shell."
                },
            )
            setState(ViewState.CLI_MISSING)
            return@withContext
        }

        val candidate = EntireCli(located.path, exec, cliLane, environment::variables) { log.info(it) }
        val version = candidate.version(cwd)
        if (stale()) return@withContext
        if (version is CallResult.Failed) {
            cli = null
            log.warn("${located.path}: ${version.failure.message} ${version.failure.detail.orEmpty()}".trim())
            setState(ViewState.CLI_MISSING)
            return@withContext
        }
        val reported = (version as CallResult.Ok).value
        cliVersion = reported
        log.info("Using Entire CLI ${reported.raw} at ${located.path} (found through ${located.source.name.lowercase()}).")
        if (!isSupportedVersion(reported)) {
            cli = null
            log.warn("Entire CLI ${reported.raw} is older than the required $MIN_CLI_VERSION_TEXT.")
            setState(ViewState.CLI_TOO_OLD)
            return@withContext
        }
        cli = candidate

        coroutineScope { found.map { repo -> async { loadStatus(repo) } }.awaitAll() }
        if (stale()) return@withContext
        if (manual) {
            for (repo in found) {
                repo.trailUrls.clear()
                repo.trailMemory.clear()
                // Only lists the Trails tab has already asked for. Nothing waits for them.
                if (repo.entireRepo != null && repo.trails != null) scope.launch(state) { trails.load(repo) }
            }
        }
        if (enabledRepos.isEmpty()) {
            setState(ViewState.NOT_ENABLED)
            return@withContext
        }

        coroutineScope {
            enabledRepos.map { repo ->
                async {
                    repo.details.clear()
                    repo.withoutData.clear()
                    repo.slotFailures.clear()
                    loadGit(repo, force = true)
                    // The CLI can take tens of seconds over this, so nothing waits for it.
                    scheduleEnrichment(repo, immediate = true)
                    if (manual && repo.sessionRows != null) sessions.loadSessionRows(repo)
                    if (manual && repo.remoteSessions != null) {
                        // Only a list the Sessions tab has already asked for. Nothing waits for it.
                        repo.remoteCheckpoints.clear()
                        scope.launch(state) { sessions.loadRemoteSessions(repo) }
                    }
                }
            }.awaitAll()
        }
        if (stale()) return@withContext
        setState(ViewState.READY)
    }

    /** Reads HEAD again, and rebuilds the commit index when the tip of the branch has moved. */
    suspend fun refreshGit(repo: RepoContext) = withContext(state) {
        if (!repo.enabled || cli == null) return@withContext
        if (loadGit(repo, force = false)) {
            scheduleEnrichment(repo, immediate = false)
            publish()
        }
    }

    /** Reads `entire status --json` again, the only source of sessions with no side effects. */
    suspend fun refreshStatus(repo: RepoContext) = withContext(state) {
        if (cli == null) return@withContext
        val wasEnabled = repo.enabled
        loadStatus(repo)
        if (repo.enabled != wasEnabled) {
            reload(manual = false)
            return@withContext
        }
        publish()
    }

    /**
     * Asks the CLI for checkpoint rows soon. After a change that the plugin
     * noticed by itself it waits, and asks at most once a minute for each
     * repository: a rebase moves HEAD many times in a row.
     */
    private fun scheduleEnrichment(repo: RepoContext, immediate: Boolean) {
        repo.enrichmentTimer?.cancel()
        val wait = if (immediate) 0L else maxOf(ENRICHMENT_DEBOUNCE_MS, ENRICHMENT_MIN_INTERVAL_MS - (now() - repo.lastEnrichment))
        repo.enrichmentTimer = scope.launch(state) {
            delay(wait)
            // The read is not the timer's to cancel once it has started.
            scope.launch(state) { enrich(repo) }
        }
    }

    /**
     * Shows one more page of a list. The commit list scans further back in
     * history when the commits already scanned run short. Returns false when
     * that scan could not read the history.
     */
    suspend fun showMore(root: String, list: String, automatic: Boolean = false): Boolean = withContext(state) {
        val repo = repos.firstOrNull { it.root == root } ?: return@withContext true
        // Entire's search results are paged by Entire, and not by how many rows a list shows.
        if (list == SEARCH_LIST) return@withContext search.more(repo)
        val trailList = repo.trails?.takeIf { it.list == list }
        val remote = list == Lists.RECENT_SESSIONS && repo.remoteSessions != null
        fun visible(): Int = when {
            trailList != null -> minOf(repo.limit(list), trails.shown(trailList).size)
            remote -> minOf(repo.limit(list), sessions.sessions(repo).recent.size)
            else -> minOf(repo.limit(Lists.COMMITS), repo.index?.commits?.size ?: 0)
        }
        val before = visible()
        repo.showMore(list)
        if (!automatic) repo.autoLoadPaused.remove(list)
        var read = true
        if (list == Lists.COMMITS || remote || trailList != null) {
            read = when {
                trailList != null -> trails.readMore(repo, trailList)
                remote -> sessions.readMoreRemoteSessions(repo)
                else -> scanForCommits(repo)
            }
            // A scan stops after a fixed stretch of history. When loading on scroll
            // finds no new row in that stretch, the Load More row is still in view
            // and would start the next scan at once, and so on to the first commit.
            // Stop there and leave the rest to a click.
            if (automatic && (!read || visible() <= before)) repo.autoLoadPaused.add(list)
        }
        publish()
        read
    }

    /**
     * The row to select while a detail tab shows `target`, as the IDs of the
     * rows from the top of the Checkpoints tree down to it: the commit that
     * carries the checkpoint, the checkpoint's own row under a commit that
     * carries several, or its row under Other Checkpoints. With `slot`, it is
     * the row of the target's session under that, for a checkpoint that holds
     * several sessions.
     *
     * A row beyond the end of its list is brought onto it: the list grows by
     * as many pages as it takes. Null when the view has no row for the
     * checkpoint.
     */
    suspend fun rowPath(target: DetailTarget, slot: Boolean = false): List<String>? = withContext(state) {
        val repo = repoFor(target.repoRoot)
        val checkpointId = target.checkpointId
        val index = repo?.index
        if (repo == null || !repo.enabled || index == null || checkpointId == null || viewState != ViewState.READY) return@withContext null
        // With several repositories, each one's rows are under a row of its own.
        val top = if (enabledRepos.size == 1) emptyList() else listOf(repoRowId(repo.root))

        val carriers = index.commitsFor(checkpointId)
        val commit = carriers.firstOrNull { it.sha == target.commitSha } ?: carriers.firstOrNull()
        if (commit != null) {
            // The commit has been scanned, so only the number of rows on show stands between it and the list.
            var pages = 0
            while (true) {
                val at = repo.index?.commits?.indexOf(commit) ?: -1
                if (at < 0) return@withContext null
                if (at < repo.limit(Lists.COMMITS)) break
                if (pages++ >= MAX_PAGES_TO_REACH_A_ROW) return@withContext null
                showMore(repo.root, Lists.COMMITS)
            }
            val commitId = commitRowId(repo.root, commit.sha)
            val path = if (commit.checkpointIds.size > 1) top + commitId + checkpointRowId(commitId, checkpointId) else top + commitId
            val session = if (slot) slotRow(repo, checkpointId, target.sessionId, path.last()) else null
            if (session != null) path + session else path
        } else {
            val at = index.unlinkedRows().indexOfFirst { it.checkpointId == checkpointId }
            if (at < 0) return@withContext null
            var pages = 0
            while (at >= repo.limit(Lists.OTHER_CHECKPOINTS)) {
                if (pages++ >= MAX_PAGES_TO_REACH_A_ROW) return@withContext null
                showMore(repo.root, Lists.OTHER_CHECKPOINTS)
            }
            val group = otherGroupRowId(repo.root)
            val path = top + group + checkpointRowId(group, checkpointId)
            val session = if (slot) slotRow(repo, checkpointId, target.sessionId, path.last()) else null
            if (session != null) path + session else path
        }
    }

    /**
     * The row to select in the Sessions tab while a detail tab shows `target`:
     * the session's row for a tab opened on the session, and otherwise the
     * checkpoint's row under the session. With `slot`, it is the row of the
     * session under that, for a checkpoint that holds several sessions. When
     * the session lists no such checkpoint, the session's row stands in. Null
     * when the tab does not list the session.
     */
    suspend fun sessionRowPath(target: DetailTarget, slot: Boolean = false): List<String>? = withContext(state) {
        val repo = repoFor(target.repoRoot)
        val sessionId = target.sessionId
        if (repo == null || !repo.enabled || sessionId == null || viewState != ViewState.READY) return@withContext null

        fun place(): Pair<String, Int>? {
            val lists = sessions.sessions(repo)
            val active = lists.active.indexOfFirst { it.sessionId == sessionId }
            if (active >= 0) return Lists.ACTIVE_SESSIONS to active
            val recent = lists.recent.indexOfFirst { it.sessionId == sessionId }
            return if (recent >= 0) Lists.RECENT_SESSIONS to recent else null
        }
        var pages = 0
        var found = place() ?: return@withContext null
        while (found.second >= repo.limit(found.first)) {
            if (pages++ >= MAX_PAGES_TO_REACH_A_ROW) return@withContext null
            showMore(repo.root, found.first)
            found = place() ?: return@withContext null
        }
        val top = if (enabledRepos.size == 1) emptyList() else listOf(repoRowId(repo.root))
        // The rows are under Active and Recent only while something is running.
        val grouped = sessions.sessions(repo).active.isNotEmpty()
        val group = if (grouped) listOf(sessionGroupRowId(repo.root, found.first == Lists.ACTIVE_SESSIONS)) else emptyList()
        val sessionRow = sessionRowId(repo.root, sessionId)
        val toSession = top + group + sessionRow
        val checkpointId = target.checkpointId
        if (target.mode == DetailMode.SESSION || checkpointId == null) return@withContext toSession

        val list = Lists.sessionCheckpoints(sessionId)
        fun at(): Int = sessions.entry(repo, sessionId)?.checkpoints?.indexOfFirst { it.checkpointId == checkpointId } ?: -1
        pages = 0
        while (at() >= repo.limit(list) && pages++ < MAX_PAGES_TO_REACH_A_ROW) showMore(repo.root, list)
        val position = at()
        if (position < 0 || position >= repo.limit(list)) return@withContext toSession
        val path = toSession + checkpointRowId(sessionRow, checkpointId)
        val session = if (slot) slotRow(repo, checkpointId, sessionId, path.last()) else null
        if (session != null) path + session else path
    }

    private suspend fun loadStatus(repo: RepoContext) {
        val current = cli
        val (status, origin) = coroutineScope {
            val status = async { current?.status(repo.root) }
            val origin = async { git.originUrl(repo.root) }
            status.await() to origin.await()
        }
        val hosted = parseEntireRemote(origin)
        // The origin now names another repository, or none. Entire's sessions are not this one's.
        if (hosted != repo.entireRepo) {
            repo.forgetTrails()
            repo.forgetRemoteSessions()
        }
        repo.originUrl = origin
        repo.entireRepo = hosted
        repo.lastRefresh = now()
        when (status) {
            is CallResult.Ok -> repo.status = status.value
            is CallResult.Failed -> {
                repo.status = StatusInfo(enabled = false, error = status.failure.message)
                log.warn("${repo.name}: ${status.failure.message} ${status.failure.detail.orEmpty()}".trim())
            }
            null -> {}
        }
    }

    /** Returns true when the index was rebuilt. */
    private suspend fun loadGit(repo: RepoContext, force: Boolean): Boolean {
        val (tip, branch) = coroutineScope {
            val tip = async { git.head(repo.root) }
            val branch = async { git.branch(repo.root) }
            tip.await() to branch.await()
        }
        repo.lastRefresh = now()
        val branchChanged = branch != repo.branch
        repo.branch = branch
        if (tip == null) {
            val had = repo.index != null
            repo.index = null
            repo.gitError = null
            return had || branchChanged
        }
        if (!force && repo.index?.tipSha == tip) return branchChanged

        val generation = ++repo.generation
        val index = CheckpointIndex(tip)
        // Scan for as many rows as the commit list shows, so a rebuilt index does
        // not collapse a list the user has already extended.
        val okay = loadPage(index, repo.limit(Lists.COMMITS)) { skip, count -> fetchWindow(repo, tip, skip, count) }
        if (generation != repo.generation) return false
        // Keep the previous rows until fresh ones arrive, so rows do not lose
        // their agent for a moment on every commit.
        index.setRows(repo.index?.allRows() ?: emptyList())
        repo.index = index
        repo.autoLoadPaused.remove(Lists.COMMITS)
        repo.gitError = if (okay) null else "Entire could not read the commit history."
        return true
    }

    private suspend fun fetchWindow(repo: RepoContext, tip: String, skip: Int, count: Int): List<CommitRecord>? =
        when (val window = git.logWindow(repo.root, tip, skip, count)) {
            is CallResult.Ok -> window.value
            is CallResult.Failed -> {
                log.warn("${repo.name}: ${window.failure.message} ${window.failure.detail.orEmpty()}".trim())
                null
            }
        }

    /**
     * Scans history until the index holds as many linked commits as the commit
     * list shows. Scans of one repository run one after another: each starts
     * where the index stops, so two at once would read the same stretch twice
     * and list its commits twice.
     */
    private suspend fun scanForCommits(repo: RepoContext): Boolean = repo.scans.withLock {
        val index = repo.index
        val missing = if (index != null) repo.limit(Lists.COMMITS) - index.commits.size else 0
        if (index == null || index.exhausted || missing <= 0) return@withLock true
        loadPage(index, missing) { skip, count -> fetchWindow(repo, index.tipSha, skip, count) }
    }

    /**
     * Asks the CLI for checkpoint rows in the background. The CLI may fetch
     * checkpoint refs from the remote while it lists, which can take tens of
     * seconds, so the views never wait for this.
     */
    private suspend fun enrich(repo: RepoContext) {
        val current = cli
        val generation = repo.generation
        if (current == null || repo.index == null) return
        repo.lastEnrichment = now()
        val rows = current.listCheckpoints(repo.root, LIST_LIMIT)
        val index = repo.index
        if (generation != repo.generation || index == null) return
        when (rows) {
            is CallResult.Failed -> log.warn("${repo.name}: ${rows.failure.message} ${rows.failure.detail.orEmpty()}".trim())
            is CallResult.Ok -> {
                index.setRows(rows.value)
                publish()
            }
        }
    }
}
