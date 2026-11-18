package io.entire.app.refresh

import io.entire.app.repo.RepoManager
import io.entire.core.io.DirectoryWatcher
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val GIT_DEBOUNCE_MS = 500L
private const val SESSION_DEBOUNCE_MS = 1000L

/** When the IDE comes to the front, data older than this is checked again. */
private const val FOCUS_MAX_AGE_MS = 60_000L

private class Pending {
    var git = false
    var status = false
    var timer: Job? = null
}

/**
 * Keeps the views current without polling.
 *
 * File watchers report when HEAD moves and when Entire's session state
 * changes. Work is deferred while no Entire view is on screen. Automatic
 * refreshes read git and `entire status --json` only: `entire session list`
 * prunes old session state as it loads, so it stays behind a user gesture.
 *
 * Its state lives on the manager's own thread. The functions here may be
 * called from any thread.
 */
class RefreshController(
    private val scope: CoroutineScope,
    private val manager: RepoManager,
    private val isVisible: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var watcher: DirectoryWatcher? = null
    private var watched = ""
    private val pending = HashMap<String, Pending>()

    @Volatile
    private var disposed = false

    init {
        scope.launch {
            manager.onChange { syncWatchers() }
        }
        scope.launch(manager.dispatcher) { syncWatchers() }
    }

    fun dispose() {
        disposed = true
        watcher?.close()
    }

    /** Runs the work that was deferred while no view was on screen. */
    fun flush() {
        scope.launch(manager.dispatcher) {
            for ((root, entry) in pending) {
                if (entry.git || entry.status) schedule(root, git = false, status = false, delayMs = 0)
            }
        }
    }

    /** The IDE came to the front. What has not been read for a while is read again. */
    fun onFocus() {
        scope.launch(manager.dispatcher) {
            for (repo in manager.enabled) {
                if (now() - repo.lastRefresh > FOCUS_MAX_AGE_MS) schedule(repo.root, git = true, status = true, delayMs = 0)
            }
        }
    }

    /** The IDE's own git support saw the repository change. It is a second way to hear what the watchers report. */
    fun gitChanged(root: String) {
        scope.launch(manager.dispatcher) {
            if (manager.repoFor(root)?.enabled == true) schedule(root, git = true, status = false, delayMs = GIT_DEBOUNCE_MS)
        }
    }

    /** Rebuilds the watchers when the set of enabled repositories changes. */
    private fun syncWatchers() {
        if (disposed) return
        val repos = manager.enabled
        val key = repos.joinToString("\n") { "${it.paths.gitDir}|${it.paths.commonDir}" }
        if (key == watched) return
        watched = key
        watcher?.close()
        val next = DirectoryWatcher()
        watcher = next
        for (repo in repos) {
            val root = repo.root
            val gitDir = Path.of(repo.paths.gitDir)
            // HEAD changes on a branch switch. The reflog changes on every commit,
            // reset, and rebase. Each watch covers one directory, and not the
            // whole git directory with its object store.
            next.watch(gitDir, { it == "HEAD" }) { later(root, git = true, status = false, delayMs = GIT_DEBOUNCE_MS) }
            next.watch(gitDir.resolve("logs"), { it == "HEAD" }) { later(root, git = true, status = false, delayMs = GIT_DEBOUNCE_MS) }
            next.watch(Path.of(repo.paths.commonDir).resolve("entire-sessions"), { it.endsWith(".json") }) {
                later(root, git = false, status = true, delayMs = SESSION_DEBOUNCE_MS)
            }
        }
    }

    /** From the watcher's thread onto the manager's. */
    private fun later(root: String, git: Boolean, status: Boolean, delayMs: Long) {
        if (disposed) return
        scope.launch(manager.dispatcher) { schedule(root, git, status, delayMs) }
    }

    private fun schedule(root: String, git: Boolean, status: Boolean, delayMs: Long) {
        val entry = pending.getOrPut(root) { Pending() }
        entry.git = entry.git || git
        entry.status = entry.status || status
        // A newer change waits its own time out. A read that has started is left to finish.
        entry.timer?.cancel()
        entry.timer = scope.launch(manager.dispatcher) {
            delay(delayMs)
            scope.launch(manager.dispatcher) { run(root) }
        }
    }

    private suspend fun run(root: String) {
        val entry = pending[root] ?: return
        if (!isVisible()) return
        val repo = manager.repoFor(root)
        if (repo == null) {
            pending.remove(root)
            return
        }
        val git = entry.git
        val status = entry.status
        entry.git = false
        entry.status = false
        if (git) manager.refreshGit(repo)
        if (status) manager.refreshStatus(repo)
    }
}
