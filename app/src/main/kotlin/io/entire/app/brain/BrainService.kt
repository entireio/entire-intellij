package io.entire.app.brain

import io.entire.app.ports.HostLog
import io.entire.app.ports.Ui
import io.entire.app.repo.RepoContext
import io.entire.app.repo.RepoManager
import io.entire.app.setup.StepId
import io.entire.core.CallResult
import io.entire.core.Failure
import io.entire.core.FailureKind
import io.entire.core.brain.BrainDetail
import io.entire.core.brain.BrainStatus
import io.entire.core.brain.brainDetails
import io.entire.core.brain.hasBrain
import io.entire.core.exec.RunningServer
import io.entire.core.format.relativeTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a request to open the brain graph ended. */
sealed interface BrainOutcome {
    /** The page is open. `started` says whether a server had to be started for it. */
    data class Opened(val url: String, val started: Boolean) : BrainOutcome

    /** The entire-brain plugin is not installed. */
    data object PluginMissing : BrainOutcome

    /** The repository has no brain yet. */
    data object NoBrain : BrainOutcome

    data class Failed(val message: String) : BrainOutcome
}

/** What the Brain tab shows for a repository: the brain's details, or the reason it has none. */
enum class BrainViewState { LOADING, PLUGIN_MISSING, NO_BRAIN, FAILED, READY }

/** What is known about the brain of one repository, as far as it has been read. */
class BrainInfo(
    val loading: Boolean,
    /** The plugin's last report. Kept while a fresh one is read. */
    val status: BrainStatus? = null,
    /** Why the last read gave no report. */
    val failure: Failure? = null,
)

/** What the Brain tab shows for one repository. */
data class BrainSection(
    val root: String,
    /** The repository's name, shown as a heading when the tab holds several. */
    val name: String,
    /** `READY` lists the brain. The others say why there is nothing to list. */
    val state: BrainViewState,
    val details: List<BrainDetail>,
    /** Where the graph's server listens, such as `127.0.0.1:50828`, while it runs. */
    val running: String?,
)

private class Running(
    val server: RunningServer,
    /** The branch that was checked out when the server started. It reads the branch once. */
    val branch: String?,
)

/**
 * The brain of each open repository: what the entire-brain plugin reports
 * about it, for the Brain tab, and the plugin's graph page.
 *
 * The report is read when the Brain tab is first shown and on Refresh, and
 * not before: a repository may have no brain.
 *
 * The graph page is the one `entire brain viz` serves on the loopback
 * address. It opens in the browser. It cannot be shown in an editor tab,
 * because the plugin's server forbids every other page from framing it
 * (`frame-ancestors 'none'`). The server runs until it is stopped, so this
 * owns one per repository and stops them all when the project closes.
 *
 * Nothing here installs the plugin or builds a brain: both write, and the
 * IDE plugin only reads.
 *
 * Everything runs on the manager's own thread, except [dispose].
 */
class BrainService(
    private val scope: CoroutineScope,
    private val manager: RepoManager,
    private val ui: Ui,
    private val log: HostLog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val info = HashMap<String, BrainInfo>()

    /** Counts the reads of each repository's report, so a late answer to an earlier one is dropped. */
    private val reads = HashMap<String, Int>()

    /** The servers that run. A closing project stops them from its own thread, so the map takes any thread. */
    private val servers = ConcurrentHashMap<String, Running>()

    /** Starts under way, by repository, so that two requests share one. */
    private val starting = HashMap<String, Deferred<BrainOutcome>>()

    /** The Brain tab has been on screen, so the reports may be read. */
    private var shown = false

    @Volatile
    private var disposed = false

    private val published = MutableStateFlow<List<BrainSection>>(emptyList())

    /** What the Brain tab shows: one section for each repository that has Entire enabled. */
    val sections: StateFlow<List<BrainSection>> = published.asStateFlow()

    init {
        // A repository that leaves the project takes its report and its server with it.
        scope.launch {
            manager.onChange {
                for (root in (servers.keys + info.keys).toSet()) {
                    if (manager.repoFor(root) != null) continue
                    servers.remove(root)?.server?.stop()
                    info.remove(root)
                }
                // The Brain tab reads a repository when Entire becomes enabled in it.
                if (shown) ensureStatus()
                announce()
            }
        }
    }

    private fun stateOf(root: String): BrainViewState {
        val known = info[root]
        if (known == null || (known.loading && known.status == null && known.failure == null)) return BrainViewState.LOADING
        known.status?.let { return if (hasBrain(it)) BrainViewState.READY else BrainViewState.NO_BRAIN }
        return if (known.failure?.kind == FailureKind.PLUGIN_MISSING) BrainViewState.PLUGIN_MISSING else BrainViewState.FAILED
    }

    private fun announce() {
        if (disposed) return
        published.value = if (!manager.chrome.value.ready) {
            emptyList()
        } else {
            manager.enabled.map { repo ->
                val state = stateOf(repo.root)
                val status = info[repo.root]?.status
                BrainSection(
                    root = repo.root,
                    name = repo.name,
                    state = state,
                    details = if (state == BrainViewState.READY && status != null) brainDetails(status, relativeTime(status.generatedAt, now())) else emptyList(),
                    running = servers[repo.root]?.server?.url?.removePrefix("http://"),
                )
            }
        }
    }

    /** The Brain tab is on screen. The report of each repository that has not been asked about is read. */
    suspend fun show() = withContext(manager.dispatcher) {
        shown = true
        ensureStatus()
        announce()
    }

    private fun ensureStatus() {
        if (!manager.chrome.value.ready) return
        for (repo in manager.enabled) {
            if (repo.root !in info) scope.launch(manager.dispatcher) { loadStatus(repo) }
        }
    }

    /** Reads again every report that has been read. Refresh calls this. */
    suspend fun refresh() = withContext(manager.dispatcher) {
        for (root in info.keys.toList()) {
            val repo = manager.repoFor(root) ?: continue
            scope.launch(manager.dispatcher) { loadStatus(repo) }
        }
    }

    /**
     * Reads the plugin's report on one repository. The one already shown
     * stays until the answer arrives. A read that is not shared stops when
     * its caller is cancelled, and then says nothing new.
     */
    private suspend fun loadStatus(repo: RepoContext, shared: Boolean = true): BrainInfo {
        val cli = manager.cli
        val read = (reads[repo.root] ?: 0) + 1
        reads[repo.root] = read
        val before = info[repo.root]
        val pending = BrainInfo(loading = true, status = before?.status, failure = before?.failure)
        info[repo.root] = pending
        announce()

        val result = try {
            cli?.brainStatus(repo.root, shared) ?: CallResult.Failed(Failure(FailureKind.CLI_MISSING, "The Entire CLI is not available."))
        } catch (e: CancellationException) {
            // A cancelled read says nothing new. What was known before still stands.
            if (reads[repo.root] == read && info[repo.root] === pending) {
                if (before != null) info[repo.root] = BrainInfo(loading = false, status = before.status, failure = before.failure) else info.remove(repo.root)
                announce()
            }
            throw e
        }
        // A newer read, or the repository leaving the project, makes this answer stale.
        if (reads[repo.root] != read || info[repo.root] !== pending) return info[repo.root] ?: BrainInfo(loading = false)
        val next = when (result) {
            is CallResult.Ok -> BrainInfo(loading = false, status = result.value)
            is CallResult.Failed -> {
                if (result.failure.kind != FailureKind.PLUGIN_MISSING) {
                    log.warn("${repo.name}: ${result.failure.message} ${result.failure.detail.orEmpty()}".trim())
                }
                BrainInfo(loading = false, failure = result.failure)
            }
        }
        info[repo.root] = next
        announce()
        return next
    }

    /**
     * Opens the graph of a repository's brain in the browser, starting its
     * server when none runs, and says why when it could not. Starting takes a
     * moment and can be cancelled.
     */
    suspend fun view(root: String) {
        val repo = withContext(manager.dispatcher) { manager.repoFor(root)?.takeIf { it.enabled } }
        if (repo == null) {
            ui.info("Entire is not enabled in an open repository.")
            return
        }
        // A server that is already up opens at once. Starting one takes a moment, and can be cancelled.
        val slow = withContext(manager.dispatcher) { needsStart(repo) }
        val outcome = if (slow) {
            ui.withProgress("Starting the brain graph…") { withContext(manager.dispatcher) { open(repo) } }
        } else {
            withContext(manager.dispatcher) { open(repo) }
        }
        when (outcome) {
            is BrainOutcome.Opened -> {}
            // Installing the plugin and building a brain are the Brain step of the Setup tab, which runs them on a click.
            BrainOutcome.PluginMissing -> ui.offer(
                "The brain graph needs the entire-brain plugin, which is not installed.",
                "Set up the brain",
            ) { ui.openSetup(StepId.BRAIN) }
            BrainOutcome.NoBrain -> ui.offer(
                "This repository has no brain yet.",
                "Set up the brain",
            ) { ui.openSetup(StepId.BRAIN) }
            is BrainOutcome.Failed -> ui.error(outcome.message)
        }
    }

    /** Whether opening the graph has to start a server first, which takes a moment. */
    private fun needsStart(repo: RepoContext): Boolean {
        val running = servers[repo.root]
        return running == null || running.branch != repo.branch
    }

    private suspend fun open(repo: RepoContext): BrainOutcome {
        val running = servers[repo.root]
        if (running != null && running.branch == repo.branch) {
            ui.openUrl(running.server.url)
            return BrainOutcome.Opened(running.server.url, started = false)
        }
        // The server read the branch when it started. Another branch needs another server.
        if (running != null) stopServer(repo.root)

        val start = starting[repo.root] ?: scope.async(manager.dispatcher) {
            try {
                start(repo)
            } finally {
                starting.remove(repo.root)
            }
        }.also { starting[repo.root] = it }
        try {
            return start.await()
        } catch (e: CancellationException) {
            // The request was given up, so the start it waited for is given up too.
            start.cancel()
            throw e
        }
    }

    private suspend fun start(repo: RepoContext): BrainOutcome {
        val cli = manager.cli ?: return BrainOutcome.Failed("The Entire CLI is not available.")

        // The server would start without a brain and show an empty page, so the report is read first.
        val known = loadStatus(repo, shared = false)
        val status = known.status
        if (status != null) {
            if (!hasBrain(status)) return BrainOutcome.NoBrain
        } else if (known.failure?.kind == FailureKind.PLUGIN_MISSING) {
            return BrainOutcome.PluginMissing
        }
        // Any other failure to read the report is no reason not to try: the page says what the brain holds.

        val server = when (val started = cli.startBrainViz(repo.root)) {
            is CallResult.Failed -> {
                if (started.failure.kind == FailureKind.PLUGIN_MISSING) return BrainOutcome.PluginMissing
                log.warn("${repo.name}: ${started.failure.message} ${started.failure.detail.orEmpty()}".trim())
                return BrainOutcome.Failed(started.failure.message)
            }
            is CallResult.Ok -> started.value
        }
        if (disposed || manager.repoFor(repo.root) == null) {
            // The project closed, or the repository left it, while the server started.
            server.stop()
            throw CancellationException("The repository is no longer open.")
        }
        servers[repo.root] = Running(server, repo.branch)
        server.exited.invokeOnCompletion {
            scope.launch(manager.dispatcher) {
                if (servers[repo.root]?.server === server) {
                    servers.remove(repo.root)
                    announce()
                }
            }
        }
        announce()
        ui.openUrl(server.url)
        return BrainOutcome.Opened(server.url, started = true)
    }

    private fun stopServer(root: String) {
        servers.remove(root)?.server?.stop()
    }

    /** Stops the graph server of one repository, or every server. */
    suspend fun stop(root: String? = null) = withContext(manager.dispatcher) {
        for (key in if (root == null) servers.keys.toList() else listOf(root)) stopServer(key)
        announce()
    }

    /** Whether a graph server is up, for any repository. Any thread may ask. */
    fun running(): Boolean = servers.isNotEmpty()

    /** Stops every graph server. The closing project calls this from its own thread, and the request goes out before it returns. */
    fun dispose() {
        disposed = true
        for (key in servers.keys.toList()) stopServer(key)
    }
}
