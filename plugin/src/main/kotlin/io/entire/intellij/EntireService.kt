package io.entire.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.EnvironmentUtil
import git4idea.config.GitExecutableManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import io.entire.app.actions.Actions
import io.entire.app.actions.RowActions
import io.entire.app.actions.TranscriptFiles
import io.entire.app.brain.BrainSection
import io.entire.app.brain.BrainService
import io.entire.app.panel.DetailPanelController
import io.entire.app.panel.PanelSink
import io.entire.app.panel.TrailPanelController
import io.entire.app.panel.TrailPanelSink
import io.entire.app.ports.FolderSource
import io.entire.app.ports.HostEnvironment
import io.entire.app.ports.HostLog
import io.entire.app.ports.Settings
import io.entire.app.refresh.RefreshController
import io.entire.app.repo.Chrome
import io.entire.app.repo.HitTarget
import io.entire.app.repo.Lists
import io.entire.app.rows.RowNode
import io.entire.app.setup.SetupFacts
import io.entire.app.setup.StepId
import io.entire.app.repo.RepoManager
import io.entire.app.rows.RowsView
import io.entire.app.rows.FollowedView
import io.entire.app.rows.SelectionFollower
import io.entire.core.exec.ProcessExec
import io.entire.core.protocol.DetailTarget
import io.entire.core.protocol.TrailTarget
import io.entire.core.trails.TrailFilter
import io.entire.core.trails.trailFilters
import io.entire.intellij.editor.PanelFiles
import io.entire.intellij.settings.EntireConfigurable
import io.entire.intellij.settings.EntireSettings
import io.entire.intellij.toolwindow.EntireToolWindowFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Entire tool window of one project shows, and the way to ask for
 * more of it. Nothing here starts a process until a view is first on screen.
 */
@Service(Service.Level.PROJECT)
class EntireService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val logger = logger<EntireService>()
    private val started = AtomicBoolean(false)

    private val hostLog = object : HostLog {
        override fun info(message: String) = logger.info(message)

        override fun warn(message: String) = logger.warn(message)
    }

    private val settings = object : Settings {
        // Read each time, so that a change on the settings page counts from the next read on.
        override val cliPath: String? get() = EntireSettings.getInstance().cliPath
        override val webBaseUrl: String? get() = EntireSettings.getInstance().webBaseUrl
        override val trailsEnabled: Boolean get() = EntireSettings.getInstance().trailsEnabled
    }

    private val folders = object : FolderSource {
        override fun folders(): List<String> {
            val found = LinkedHashSet<String>()
            project.basePath?.let { found.add(it) }
            GitRepositoryManager.getInstance(project).repositories.forEach { found.add(it.root.path) }
            return found.toList()
        }
    }

    private val environment = object : HostEnvironment {
        // An IDE started from the desktop does not have the shell's PATH. The IDE reads it from the login shell.
        override fun variables(): Map<String, String> = EnvironmentUtil.getEnvironmentMap()

        override fun gitPath(): String = GitExecutableManager.getInstance().getPathToGit(project)

        override val homeDir: String = System.getProperty("user.home")
        override val windows: Boolean = SystemInfo.isWindows
    }

    private val manager = RepoManager(scope, ProcessExec, settings, folders, environment, hostLog)
    private val transcripts = TranscriptFiles()
    /** Opens a page in the browser. A check replaces this, so that no browser opens. */
    @Volatile
    var browse: (String) -> Unit = { BrowserUtil.browse(it) }

    private val ui = PluginUi(project) { browse(it) }
    private val actions = Actions(manager, ui, transcripts)
    private val rowActions = RowActions(manager, actions)

    /** Runs what an item of a row's menu does. */
    fun rowAction(action: suspend RowActions.() -> Unit) {
        scope.launch { rowActions.action() }
    }

    /** Opens a repository's page on entire.com. */
    fun openRepoOnWeb(root: String) {
        scope.launch { rowActions.openRepoOnWeb(root) }
    }

    val checkpoints: StateFlow<RowsView> get() = manager.checkpoints

    val sessionRows: StateFlow<RowsView> get() = manager.sessionRows

    val trailRows: StateFlow<RowsView> get() = manager.trailRows

    /** Which tabs the tool window has, and what the Trails tab is filtered to. */
    val chrome: StateFlow<Chrome> get() = manager.chrome

    private val brain = BrainService(scope, manager, ui, hostLog)

    /** Whether the Entire tool window is on screen. Work that keeps the views current waits while it is not. */
    private val viewsVisible = AtomicBoolean(false)
    private val refresher = RefreshController(scope, manager, { viewsVisible.get() })

    init {
        // Two more ways to hear of a change, for a folder whose watchers stay silent, such as one on a network drive.
        project.messageBus.connect(this).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository -> refresher.gitChanged(repository.root.path) },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = refresher.onFocus()
            },
        )
    }

    /** The tool window came on screen or left it. What was put off while it was away is done now. */
    fun viewsShown(visible: Boolean) {
        if (viewsVisible.getAndSet(visible) != visible && visible) refresher.flush()
    }

    /** What the Brain tab shows: one section for each repository that has Entire enabled. */
    val brainSections: StateFlow<List<BrainSection>> get() = brain.sections

    /** The Brain tab is on screen, so the entire-brain plugin may be asked about each repository's brain. */
    fun showBrain() {
        scope.launch { brain.show() }
    }

    /** Opens the graph of a repository's brain in the browser, starting its server first when none runs. */
    fun viewBrain(root: String) {
        scope.launch { brain.view(root) }
    }

    /** Stops the graph server of one repository, or every server. */
    fun stopBrain(root: String? = null) {
        scope.launch { brain.stop(root) }
    }

    /** Whether a brain graph server is running. */
    fun brainRunning(): Boolean = brain.running()

    /** Each tab's follower, by the tab's name. It moves the tab's selection when a detail tab opened from it moves on by itself. */
    private val followers = ConcurrentHashMap<String, SelectionFollower>()

    /** A tab whose selection follows the detail tabs that were opened from it. */
    fun follow(parent: Disposable, origin: String, view: FollowedView) {
        val created = SelectionFollower(view) { target, slot ->
            if (origin == ORIGIN_SESSIONS) manager.sessionRowPath(target, slot) else manager.rowPath(target, slot)
        }
        followers[origin] = created
        Disposer.register(parent) { followers.remove(origin, created) }
    }

    /** The Sessions tab is on screen, so the session list and Entire's list of sessions may be read. */
    fun showSessions() {
        scope.launch { manager.showSessions() }
    }

    /** The Trails tab is on screen, so trails may be read from Entire. */
    fun showTrails() {
        scope.launch { manager.showTrails() }
    }

    fun setTrailFilter(filter: TrailFilter) {
        scope.launch { manager.setTrailFilter(filter) }
    }

    /** Hands the filters to offer, and the one in force, to `show` on the UI thread. */
    fun trailFilterChoices(show: (List<TrailFilter>, TrailFilter) -> Unit) {
        scope.launch {
            val choices = trailFilters(manager.trailPhases())
            val current = manager.trailFilter()
            withContext(Dispatchers.EDT) { show(choices, current) }
        }
    }

    /** Opens a trail in the trail panel: in the tab that each click on a trail reuses, or in a tab of its own. */
    fun openTrail(target: TrailTarget, newTab: Boolean, focus: Boolean) {
        PanelFiles.getInstance(project).openTrail(target, newTab, focus)
    }

    /** The controller behind one trail tab. A tab that the IDE brought back is a view on screen, so it starts the first read too. */
    fun newTrailController(sink: TrailPanelSink, target: TrailTarget): TrailPanelController {
        ensureLoaded()
        // A checkpoint from the timeline opens in the tab that each click on a checkpoint reuses.
        return TrailPanelController(scope, manager, actions, sink, target) { checkpoint ->
            openDetail(checkpoint, newTab = false, focus = true, origin = ORIGIN_CHECKPOINTS)
        }
    }

    /**
     * The setting that allows trails was changed. The Trails tab comes or
     * goes, and an open trail says that trails are off, or reads its trail
     * now that they are on.
     */
    fun trailsSettingChanged() {
        scope.launch { manager.applyTrailsSetting() }
        PanelFiles.getInstance(project).openTrails().forEach { it.refresh() }
    }

    /** Opens the IDE's log, where the plugin writes each command it runs and each failure. */
    fun showLog() = ui.showLog()

    fun copy(value: String, note: String) = ui.copy(value, note)

    /** Searches Entire for the words of the Checkpoints or Sessions tab. One search feeds both tabs. */
    fun searchEntire(query: String) {
        scope.launch { manager.searchEntire(query) }
    }

    /** Drops what Entire's search found, as when the search text changes. */
    fun clearSearch() {
        scope.launch { manager.clearSearch() }
    }

    /** Opens a row of Entire's search results: in the detail panel when it can be shown there, and otherwise on the web. */
    fun openSearchHit(row: RowNode, newTab: Boolean, focus: Boolean, origin: String) {
        val hit = row.search ?: return
        scope.launch {
            when (val target = manager.resolveSearchHit(row.repoRoot, hit)) {
                is HitTarget.Detail -> withContext(Dispatchers.EDT) { openDetail(target.target, newTab, focus, origin) }
                is HitTarget.Web -> actions.openExternal(target.url)
                null -> ui.info(EntireBundle.message("search.cannotOpen"))
            }
        }
    }

    /** Asks for the Setup tab, at a step or at the first one to do. Each ask counts, so asking twice shows the tab twice. */
    private val setupAsks = MutableStateFlow(SetupAsk(0, null))

    /** The latest ask for the Setup tab. */
    val setupRequests: StateFlow<SetupAsk> get() = setupAsks.asStateFlow()

    fun openSetup(step: StepId?) {
        setupAsks.value = SetupAsk(setupAsks.value.count + 1, step)
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) ToolWindowManager.getInstance(project).getToolWindow(EntireToolWindowFactory.ID)?.activate(null)
        }
    }

    /** What the Setup tab shows for a repository, read again. Only reads. */
    suspend fun setupFacts(root: String?): SetupFacts = manager.setupFacts(root)

    /** The project's repositories, for the Setup tab to choose from. */
    suspend fun setupRepos(): List<Pair<String, String>> = manager.setupRepos()

    /** Where the CLI is, for the Setup tab's allowlist. */
    val cliPath: String? get() = manager.cliPath

    /**
     * A setup step finished. The project is read again, the way a click on
     * Refresh does, so that what the step changed shows in every tab. Then
     * `then` runs on the UI thread.
     */
    fun setupStepEnded(then: () -> Unit) {
        started.set(true)
        scope.launch {
            brain.refresh()
            manager.reload(manual = true)
            withContext(Dispatchers.EDT) { then() }
        }
    }

    fun sessionExpanded(root: String, sessionId: String) {
        scope.launch { manager.sessionExpanded(root, sessionId) }
    }

    /** The row of a checkpoint that holds several sessions was opened, so the CLI is asked which. */
    fun checkpointOpened(root: String, checkpointId: String) {
        scope.launch { manager.checkpointOpened(root, checkpointId) }
    }

    /** Reads everything the first time a view is shown. Later calls do nothing. */
    fun ensureLoaded() {
        if (started.compareAndSet(false, true)) refresh(manual = false)
    }

    fun refresh(manual: Boolean = true) {
        started.set(true)
        // The brain's report is read on request, like the trails. A refresh reads again what has been read.
        if (manual) scope.launch { brain.refresh() }
        scope.launch { manager.reload(manual) }
    }

    /**
     * The path to the Entire CLI was changed on the settings page, so the
     * project looks for the CLI again. A project that has shown nothing of
     * Entire yet reads the new value when it first does.
     */
    fun settingsChanged() {
        if (started.get()) refresh(manual = false)
    }

    /** Opens the plugin's page in the IDE's settings. */
    fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, EntireConfigurable::class.java)
    }

    /** Shows the next page of a list. `automatic` is for a Load More row that scrolled into view, which is no reason for a warning. */
    fun showMore(root: String, list: String, automatic: Boolean = false) {
        scope.launch {
            val read = manager.showMore(root, list, automatic)
            if (read || automatic) return@launch
            val key = when {
                list == Lists.COMMITS -> "notification.history.unreadable"
                list == Lists.RECENT_SESSIONS -> "notification.sessions.unreadable"
                list.startsWith("trails:") -> "notification.trails.unreadable"
                else -> return@launch
            }
            ui.warn(EntireBundle.message(key)) { showMore(root, list) }
        }
    }

    /** Opens a checkpoint in the detail panel: in the tab that each click reuses, or in a tab of its own. */
    fun openDetail(target: DetailTarget, newTab: Boolean, focus: Boolean, origin: String) {
        PanelFiles.getInstance(project).open(target, newTab, focus, origin)
    }

    /**
     * The controller behind one panel tab. A tab that the IDE brought back is
     * a view on screen, so it starts the first read too. `origin` names the
     * tool window tab the panel was opened from, which is the one that follows it.
     */
    fun newDetailController(sink: PanelSink, target: DetailTarget, origin: () -> String): DetailPanelController {
        ensureLoaded()
        return DetailPanelController(scope, manager, actions, sink, target) { shown ->
            // This runs on the manager's own thread, where the search for the row belongs.
            scope.launch(manager.dispatcher) { followers[origin()]?.follow(shown) }
        }
    }

    /** The raw transcripts that were opened are removed with the project, and the brain graph servers it started are stopped. */
    override fun dispose() {
        refresher.dispose()
        brain.dispose()
        transcripts.dispose()
    }

    /** Calls `show` on the UI thread with the current value and each later one, until `parent` is disposed. */
    fun <T> observe(parent: Disposable, flow: StateFlow<T>, show: (T) -> Unit) {
        val job = scope.launch(Dispatchers.EDT) { flow.collect { show(it) } }
        Disposer.register(parent) { job.cancel() }
    }

    /** An ask for the Setup tab: the how-manieth, and the step to show. */
    data class SetupAsk(val count: Int, val step: StepId?)

    companion object {
        const val ORIGIN_CHECKPOINTS = "checkpoints"
        const val ORIGIN_SESSIONS = "sessions"

        fun getInstance(project: Project): EntireService = project.service()
    }
}
