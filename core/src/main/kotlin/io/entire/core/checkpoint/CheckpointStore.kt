package io.entire.core.checkpoint

import io.entire.core.git.Git
import io.entire.core.git.isCheckpointId
import io.entire.core.json.parseJson
import io.entire.core.json.stringOrNull
import io.entire.core.transcript.openingPrompt
import io.entire.core.transcript.parseCompactTranscript
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where a checkpoint's files live in git.
 *
 * This is the only file that knows the on-disk checkpoint layout. The plugin
 * reads it for one reason: the normalized transcript (`transcript.jsonl`) is
 * stored there and no CLI command outputs it. The transcript is read whole to
 * show it, and its first lines are read to name a session by its opening
 * prompt. Every reader returns null on any surprise, and callers fall back to
 * the CLI.
 */
data class CheckpointTree(
    /** A ref whose tree contains the checkpoint. */
    val treeish: String,
    /** Directory of the checkpoint inside that tree, with a trailing slash, or empty. */
    val prefix: String,
)

data class StoredSession(
    val index: Int,
    /** Path of `transcript.jsonl` from the tree root. Null when none was stored. */
    val compactTranscriptPath: String? = null,
    val metadataPath: String? = null,
)

/** The first prompt of a stored transcript. */
data class OpeningPrompt(
    val text: String,
    /** The transcript holds the session from its start, so this prompt is the one that opened the session. */
    val fromSessionStart: Boolean,
)

data class StoredSessionMeta(
    /** The session the slot belongs to. A checkpoint can hold several. */
    val sessionId: String? = null,
    /**
     * 0-based line in `transcript.jsonl` where this checkpoint's portion begins.
     * Null means the file holds only this checkpoint's portion.
     */
    val compactTranscriptStart: Int? = null,
)

private val LEGACY_BRANCH_PATTERNS = listOf("refs/heads/entire/checkpoints/v1", "refs/remotes/*/entire/checkpoints/v1")

/**
 * How much of a transcript is read to find the prompt that opens it. In 219
 * sessions of two real repositories that prompt ended within 3 KB of the
 * start. This leaves room for a long one, and for a pasted document.
 */
private const val OPENING_BYTES = 256L * 1024

private val TREE_PATH = Regex("[\\w./-]+")

/** Turns a stored path such as `/0/transcript.jsonl` into a safe tree-relative path. */
fun safeTreePath(stored: String?): String? {
    if (stored == null) return null
    val relative = stored.trimStart('/')
    if (relative.isEmpty() || !TREE_PATH.matches(relative)) return null
    if (relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
    return relative
}

private fun parseObject(bytes: ByteArray?): JsonObject? = bytes?.let { parseJson(it.toString(Charsets.UTF_8)) } as? JsonObject

class CheckpointStore(private val git: Git) {
    /**
     * Finds the checkpoint in the local clone.
     *
     * The git-refs backend keeps one ref per checkpoint at
     * `refs/entire/checkpoints/<shard>/<id>`, and that ref's tree is the
     * checkpoint. The legacy backend keeps every checkpoint in the
     * `entire/checkpoints/v1` branch under `<id[:2]>/<id[2:]>/`; a clone often
     * has only the remote-tracking copy of that branch.
     */
    suspend fun locate(cwd: String, checkpointId: String): CheckpointTree? {
        if (!isCheckpointId(checkpointId)) return null

        git.refs(cwd, listOf("refs/entire/checkpoints/*/$checkpointId")).firstOrNull()?.let { return CheckpointTree(it, "") }

        val prefix = "${checkpointId.take(2)}/${checkpointId.drop(2)}/"
        for (branch in git.refs(cwd, LEGACY_BRANCH_PATTERNS)) {
            if (git.exists(cwd, branch, "${prefix}metadata.json")) return CheckpointTree(branch, prefix)
        }
        return null
    }

    /** Session slots listed in the checkpoint's root `metadata.json`. */
    suspend fun sessions(cwd: String, tree: CheckpointTree): List<StoredSession> {
        val root = parseObject(git.blob(cwd, tree.treeish, "${tree.prefix}metadata.json"))
        val sessions = root?.get("sessions") as? JsonArray ?: return emptyList()
        return sessions.mapIndexed { index, entry ->
            val paths = entry as? JsonObject ?: return@mapIndexed StoredSession(index)
            // Stored paths are relative to the tree root and already include the prefix.
            StoredSession(
                index = index,
                compactTranscriptPath = safeTreePath(paths["compact_transcript"].stringOrNull()),
                metadataPath = safeTreePath(paths["metadata"].stringOrNull()),
            )
        }
    }

    suspend fun sessionMeta(cwd: String, tree: CheckpointTree, session: StoredSession): StoredSessionMeta? {
        val path = session.metadataPath ?: return null
        val meta = parseObject(git.blob(cwd, tree.treeish, path)) ?: return null
        return StoredSessionMeta(
            sessionId = meta["session_id"].stringOrNull()?.ifEmpty { null },
            compactTranscriptStart = wholeNumberFromZero(meta["compact_transcript_start"]),
        )
    }

    /**
     * The first prompt in the transcript that a checkpoint stores for a
     * session, read from the start of the file. Null when the checkpoint
     * stores no normalized transcript for the session.
     *
     * Most transcripts hold the whole conversation up to the checkpoint, and
     * say where the checkpoint's own part begins. Their first prompt opens the
     * session, so every checkpoint of the session gives the same one, and
     * `fromSessionStart` is true. An older transcript holds only its
     * checkpoint's part. Its first prompt opens the session only if the
     * checkpoint is the session's first, which the caller has to know.
     */
    suspend fun openingPrompt(cwd: String, tree: CheckpointTree, sessionId: String): OpeningPrompt? {
        // Each slot's metadata names its session. A checkpoint can hold several.
        var slot: StoredSession? = null
        var meta: StoredSessionMeta? = null
        for (candidate in sessions(cwd, tree)) {
            val found = sessionMeta(cwd, tree, candidate)
            if (found?.sessionId == sessionId) {
                slot = candidate
                meta = found
                break
            }
        }
        val path = slot?.compactTranscriptPath ?: return null
        val head = git.blobHead(cwd, tree.treeish, path, OPENING_BYTES) ?: return null
        val text = head.data.toString(Charsets.UTF_8)
        // A cut goes through a line. Everything up to the last line break is whole.
        val lines = if (head.whole) text else text.substring(0, text.lastIndexOf('\n') + 1)
        val prompt = openingPrompt(parseCompactTranscript(lines, null).turns) ?: return null
        return OpeningPrompt(prompt, fromSessionStart = meta?.compactTranscriptStart != null)
    }

    /** The normalized transcript of one session, or null when none was stored. */
    suspend fun compactTranscript(cwd: String, tree: CheckpointTree, session: StoredSession): String? {
        val path = session.compactTranscriptPath ?: return null
        return git.blob(cwd, tree.treeish, path)?.toString(Charsets.UTF_8)
    }

    /**
     * An image that the CLI moved out of the transcript, addressed by a
     * placeholder such as `entire-asset:assets/img-ab12.png`. Assets sit beside
     * the session's transcript.
     */
    suspend fun asset(cwd: String, tree: CheckpointTree, session: StoredSession, assetPath: String): ByteArray? {
        val relative = safeTreePath(assetPath)
        val anchor = session.compactTranscriptPath ?: session.metadataPath
        if (relative == null || !relative.startsWith("assets/") || anchor == null) return null
        val sessionDir = anchor.substring(0, anchor.lastIndexOf('/') + 1)
        return git.blob(cwd, tree.treeish, sessionDir + relative)
    }
}

/** A whole number that is not negative, or null for anything else, a JSON null included. */
private fun wholeNumberFromZero(value: JsonElement?): Int? {
    if (value == null || value is JsonNull) return null
    val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return null
    if (number.isNaN() || number.isInfinite() || number % 1.0 != 0.0 || number < 0 || number > Int.MAX_VALUE) return null
    return number.toInt()
}
