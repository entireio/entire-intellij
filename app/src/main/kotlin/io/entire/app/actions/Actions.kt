package io.entire.app.actions

import io.entire.app.ports.DiffFile
import io.entire.app.ports.Ui
import io.entire.app.repo.RepoContext
import io.entire.app.repo.RepoManager
import io.entire.app.setup.StepId
import io.entire.core.CallResult
import io.entire.core.cli.EntireCli
import io.entire.core.git.FileChange
import io.entire.core.web.commitUrl
import io.entire.core.web.repoUrl
import io.entire.core.web.sessionUrl
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val NO_WEB = "Links to entire.com are available when the origin remote uses the entire:// scheme."

private val WEB_ADDRESS = Regex("https?://[^/\\s?#]+.*", RegexOption.IGNORE_CASE)

/** User-facing operations shared by the tree views and the detail panel. */
class Actions(private val manager: RepoManager, private val ui: Ui, private val transcripts: TranscriptFiles) {
    fun openRepoOnWeb(repo: RepoContext) {
        val hosted = repo.entireRepo ?: return ui.info(NO_WEB)
        openExternal(repoUrl(manager.webBase(), hosted))
    }

    /** Opens the commit page, which is where entire.com shows a commit's checkpoint. */
    suspend fun openCommitOnWeb(repo: RepoContext, sha: String) {
        val hosted = repo.entireRepo ?: return ui.info(NO_WEB)
        if (!manager.git.isOnRemote(repo.root, sha)) {
            val open = ui.confirm("This commit is not on a remote yet, so entire.com may not have it.", "Open Anyway")
            if (!open) return
        }
        openExternal(commitUrl(manager.webBase(), hosted, sha))
    }

    fun openSessionOnWeb(repo: RepoContext, sessionId: String) {
        val hosted = repo.entireRepo ?: return ui.info(NO_WEB)
        openExternal(sessionUrl(manager.webBase(), hosted, sessionId))
    }

    /** Opens a trail's page. The address comes from the CLI, which may have to look it up first. */
    suspend fun openTrailOnWeb(repo: RepoContext, trail: Long) {
        trailUrl(repo, trail)?.let { openExternal(it) }
    }

    suspend fun copyTrailLink(repo: RepoContext, trail: Long) {
        trailUrl(repo, trail)?.let { copy(it, "trail link") }
    }

    private suspend fun trailUrl(repo: RepoContext, trail: Long): String? {
        if (repo.entireRepo == null) {
            ui.info(NO_WEB)
            return null
        }
        val url = ui.withProgress("Entire: looking up the trail") { manager.trailUrl(repo, trail) }
        if (url == null) ui.error("Entire could not look up the web page of this trail.")
        return url
    }

    /** Opens http and https links only. Transcript text can contain any URL. */
    fun openExternal(href: String) {
        val address = href.trim()
        if (address.any { it.isWhitespace() || it.isISOControl() } || !WEB_ADDRESS.matches(address)) return
        val url = try {
            URI(address).toString()
        } catch (e: URISyntaxException) {
            return
        }
        ui.openUrl(url)
    }

    suspend fun showCommitDiff(repo: RepoContext, sha: String) {
        val files = manager.git.commitFiles(repo.root, sha)
        if (files.isEmpty()) return ui.info("This commit changed no files.")
        val parent = manager.git.parent(repo.root, sha)
        ui.showDiff("Commit ${sha.take(8)}", files.map { diffFile(repo, sha, parent, it) })
    }

    /** Shows what a commit did to one file, or opens the file when the commit did not change it. */
    suspend fun openFile(repo: RepoContext, sha: String?, filePath: String) {
        if (sha != null) {
            val change = manager.git.commitFiles(repo.root, sha).firstOrNull { it.path == filePath }
            if (change != null) {
                val file = diffFile(repo, sha, manager.git.parent(repo.root, sha), change)
                ui.showDiff(file.title, listOf(file))
                return
            }
        }
        // Stay inside the repository: a transcript can name any path.
        val absolute = try {
            val root = Path.of(repo.root)
            root.resolve(filePath).normalize().takeIf { it.startsWith(root) && it != root }
        } catch (e: InvalidPathException) {
            null
        }
        if (absolute == null || !Files.exists(absolute)) return ui.info("$filePath is not in the working tree.")
        ui.openWorkingFile(absolute)
    }

    suspend fun openRawCheckpointTranscript(repo: RepoContext, checkpointId: String, sessionIndex: Int) {
        openRawTranscript("$checkpointId-session-$sessionIndex.jsonl") { cli, file ->
            cli.rawTranscript(repo.root, checkpointId, sessionIndex, file)
        }
    }

    suspend fun openRawSessionTranscript(repo: RepoContext, sessionId: String) {
        openRawTranscript("session-$sessionId.jsonl") { cli, file -> cli.sessionRawTranscript(repo.root, sessionId, file) }
    }

    /**
     * Has the CLI write a raw transcript to a temporary file, then opens the
     * file. A raw transcript can be far too large to pass around in memory.
     */
    private suspend fun openRawTranscript(name: String, write: suspend (EntireCli, Path) -> CallResult<Long>) {
        val cli = manager.cli ?: return ui.offer("The Entire CLI is not installed, or it is not on the PATH.", "Set up Entire") { ui.openSetup(StepId.CLI) }
        val file = transcripts.prepare(name)
        when (val result = ui.withProgress("Entire: reading the raw transcript") { write(cli, file) }) {
            is CallResult.Failed -> {
                transcripts.discard(file)
                ui.error(result.failure.message)
            }
            is CallResult.Ok -> {
                transcripts.seal(file)
                ui.openFile(file)
            }
        }
    }

    fun openText(title: String, text: String) = ui.openText(title, text)

    fun copy(value: String, label: String) = ui.copy(value, "Copied $label")

    private fun diffFile(repo: RepoContext, sha: String, parent: String?, file: FileChange): DiffFile {
        val git = manager.git
        // An added file has no earlier side, and a deleted one has no later side.
        val before = if (file.status == "A" || parent == null) null else parent
        val after = if (file.status == "D") null else sha
        val oldPath = file.oldPath ?: file.path
        return DiffFile(
            path = file.path,
            status = file.status,
            title = "${file.path.substringAfterLast('/')} (${sha.take(8)})",
            before = before?.let { ref -> suspend { git.blob(repo.root, ref, oldPath) } },
            after = after?.let { ref -> suspend { git.blob(repo.root, ref, file.path) } },
        )
    }
}
