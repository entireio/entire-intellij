package io.entire.app.actions

import io.entire.app.repo.RepoManager
import io.entire.app.rows.RowKind
import io.entire.app.rows.RowNode
import io.entire.core.CallResult
import kotlinx.coroutines.withContext

/**
 * What a row's menu does. A row is plain text and IDs, so each action looks
 * the repository up again, and does nothing for a row whose repository has
 * left the project.
 */
class RowActions(private val manager: RepoManager, private val actions: Actions) {
    /**
     * Opens the row's page on entire.com. A session row and the row of one
     * session of a checkpoint open the session page. Commit and checkpoint
     * rows open the commit page, where entire.com shows the checkpoint.
     */
    suspend fun openOnWeb(row: RowNode) = withContext(manager.dispatcher) {
        val repo = manager.repoFor(row.repoRoot) ?: return@withContext
        row.trail?.let { return@withContext actions.openTrailOnWeb(repo, it.number) }
        val sessionId = row.menu.sessionId
        val commitSha = row.commitSha
        val preferSession = row.kind == RowKind.SESSION || row.kind == RowKind.SLOT
        when {
            sessionId != null && (preferSession || commitSha == null) -> actions.openSessionOnWeb(repo, sessionId)
            commitSha != null -> actions.openCommitOnWeb(repo, commitSha)
        }
    }

    suspend fun showCommitDiff(row: RowNode) = withContext(manager.dispatcher) {
        val repo = manager.repoFor(row.repoRoot) ?: return@withContext
        row.commitSha?.let { actions.showCommitDiff(repo, it) }
    }

    /**
     * Has the CLI write the raw transcript and opens it. A row that knows its
     * session's slot in a checkpoint asks for that slot. A session row asks
     * for the slot in its newest checkpoint, and when it has none, for the
     * session's own transcript.
     */
    suspend fun openRawTranscript(row: RowNode) = withContext(manager.dispatcher) {
        val repo = manager.repoFor(row.repoRoot) ?: return@withContext
        val checkpointId = row.checkpointId
        val sessionIndex = row.menu.sessionIndex
        if (row.kind != RowKind.SESSION) {
            if (checkpointId != null && sessionIndex != null) actions.openRawCheckpointTranscript(repo, checkpointId, sessionIndex)
            return@withContext
        }
        val sessionId = row.sessionId ?: return@withContext
        val newest = manager.sessions.entry(repo, sessionId)?.checkpoints?.firstOrNull()?.checkpointId
        if (newest != null) {
            val detail = manager.detail(repo, newest)
            val slot = (detail as? CallResult.Ok)?.value?.sessions?.firstOrNull { it.sessionId == sessionId }
            if (slot != null) return@withContext actions.openRawCheckpointTranscript(repo, newest, slot.index)
        }
        actions.openRawSessionTranscript(repo, sessionId)
    }

    suspend fun copyTrailLink(row: RowNode) = withContext(manager.dispatcher) {
        val repo = manager.repoFor(row.repoRoot) ?: return@withContext
        row.trail?.let { actions.copyTrailLink(repo, it.number) }
    }

    fun copyCheckpointId(row: RowNode) {
        row.checkpointId?.let { actions.copy(it, "checkpoint ID") }
    }

    fun copyCommitSha(row: RowNode) {
        row.commitSha?.let { actions.copy(it, "commit SHA") }
    }

    fun copySessionId(row: RowNode) {
        row.menu.sessionId?.let { actions.copy(it, "session ID") }
    }

    /** Opens a repository's page on entire.com. */
    suspend fun openRepoOnWeb(root: String) = withContext(manager.dispatcher) {
        manager.repoFor(root)?.let { actions.openRepoOnWeb(it) }
    }
}
