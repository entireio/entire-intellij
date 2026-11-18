package io.entire.core.cli

import io.entire.core.format.parseTime
import io.entire.core.git.isCheckpointId
import io.entire.core.json.jsTrim
import io.entire.core.json.parseJson
import io.entire.core.json.stringOrNull
import io.entire.core.web.encodeUriComponent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A repository's sessions as Entire lists them, which is what the Sessions
 * page of entire.com shows: each session with the name Entire gave it, its
 * agent and model, and how many checkpoints it has.
 *
 * They are read through `entire api`, the CLI's authenticated GET. The CLI
 * calls these endpoints internal and says their shape can change without
 * notice, so the readers here take what they recognize, and every caller
 * falls back to what the clone knows when a read fails.
 */

/** Sessions Entire returns for one call. Its own default, and what one page of the Sessions view shows. */
const val REMOTE_SESSIONS_PAGE = 50

data class RemoteSession(
    val sessionId: String,
    /** The name entire.com shows: one a person set, or else the title Entire wrote for the session. */
    val name: String? = null,
    val agent: String? = null,
    val model: String? = null,
    /** How many checkpoints Entire counts for the session. */
    val checkpointCount: Int? = null,
    val startedAt: String? = null,
    val lastActivityAt: String? = null,
    /** The prompt that opened the session. */
    val prompt: String? = null,
)

/** One page of a repository's sessions, most recent activity first. */
data class RemoteSessionPage(
    val sessions: List<RemoteSession>,
    /** Asks for the page after this one. Null on the last page. */
    val nextCursor: String? = null,
)

/** A checkpoint of a session, as the session's page on entire.com lists it. */
data class RemoteCheckpoint(
    val checkpointId: String,
    val commitSha: String? = null,
    /** The subject of the commit that carries the checkpoint. */
    val message: String? = null,
    val date: String? = null,
)

/** A string with something in it besides space, trimmed. */
private fun text(value: JsonElement?): String? = value.stringOrNull()?.jsTrim()?.ifEmpty { null }

/** A whole number that is not negative. */
private fun count(value: JsonElement?): Int? {
    val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return null
    if (number.isNaN() || number % 1.0 != 0.0 || number < 0 || number > Int.MAX_VALUE) return null
    return number.toInt()
}

/** The path of one page of the repository's sessions, on every branch. `{repo_id}` is the CLI's to fill in. */
fun remoteSessionsPath(limit: Int, cursor: String? = null): String {
    val after = if (cursor == null) "" else "&cursor=${encodeUriComponent(cursor)}"
    return "/api/v1/repos/{repo_id}/sessions?scope=all&limit=$limit$after"
}

/**
 * The path that lists a session's checkpoints. Entire has no lighter one: the
 * list comes with the session's transcript, which the caller throws away.
 */
fun remoteSessionCheckpointsPath(sessionId: String): String = "/api/v1/repos/{repo_id}/sessions/${encodeUriComponent(sessionId)}/transcript"

fun readRemoteSessionPage(value: JsonElement?): RemoteSessionPage? {
    val json = value as? JsonObject ?: return null
    val items = json["sessions"] as? JsonArray ?: return null
    val sessions = ArrayList<RemoteSession>()
    val seen = HashSet<String>()
    for (item in items) {
        if (item !is JsonObject) continue
        val sessionId = text(item["sessionId"]) ?: continue
        if (!seen.add(sessionId)) continue
        sessions.add(
            RemoteSession(
                sessionId = sessionId,
                // A name a person set comes back as the display name too. Read it first in case it one day does not.
                name = text(item["customName"]) ?: text(item["displayName"]),
                agent = text(item["agent"]),
                model = text(item["model"]),
                checkpointCount = count(item["checkpointCount"]),
                startedAt = text(item["startedAt"]),
                lastActivityAt = text(item["lastActivityAt"]),
                prompt = text(item["prompt"]),
            ),
        )
    }
    return RemoteSessionPage(sessions, text(json["next_cursor"]))
}

private val SHA_PREFIX = Regex("[0-9a-f]{7,64}", RegexOption.IGNORE_CASE)

/** The checkpoints in a session's transcript reply, newest first. */
fun readRemoteCheckpoints(value: JsonElement?): List<RemoteCheckpoint>? {
    val json = value as? JsonObject ?: return null
    val items = json["checkpoints"] as? JsonArray ?: return null
    val checkpoints = ArrayList<RemoteCheckpoint>()
    val seen = HashSet<String>()
    for (item in items) {
        if (item !is JsonObject) continue
        val checkpointId = text(item["checkpointId"]) ?: continue
        if (!isCheckpointId(checkpointId) || !seen.add(checkpointId)) continue
        checkpoints.add(
            RemoteCheckpoint(
                checkpointId = checkpointId,
                commitSha = text(item["commitSha"])?.takeIf { SHA_PREFIX.matches(it) },
                message = text(item["message"])?.split('\n')?.firstOrNull()?.jsTrim()?.ifEmpty { null },
                date = text(item["timestamp"]),
            ),
        )
    }
    return checkpoints.sortedByDescending { it.date?.let { date -> parseTime(date) } ?: 0 }
}

/** What the API said when it refused a call. It answers a refusal with a small JSON object. */
fun remoteRefusal(output: String): String? {
    val json = parseJson(output) as? JsonObject ?: return null
    val said = listOf("error", "detail", "title", "message").mapNotNull { text(json[it]) }
    return if (said.isEmpty()) null else said.joinToString(": ").take(400)
}
