package io.entire.core.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Readers for the JSON that the Entire CLI prints.
 *
 * Each reader takes whatever was parsed and returns a value or null. They are
 * tolerant on purpose: a field of the wrong type is treated as absent, an
 * entry without its ID is dropped, and the rest of the payload is kept.
 */

data class ActiveSession(
    val sessionId: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val status: String? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
)

data class StatusInfo(
    val enabled: Boolean,
    /** Present when the CLI could not evaluate the repo, e.g. "not a git repository". */
    val error: String? = null,
    val agents: List<String> = emptyList(),
    val activeSessions: List<ActiveSession> = emptyList(),
)

/** One row of `entire checkpoint explain --json` with no target. */
data class CheckpointRow(
    val checkpointId: String,
    /** The session the row's `agent` and `message` describe. */
    val sessionId: String? = null,
    val agent: String? = null,
    val date: String? = null,
    /**
     * The commit subject when a commit on the branch carries the checkpoint,
     * and otherwise the latest prompt of its session.
     */
    val message: String? = null,
    val sessionIds: List<String> = emptyList(),
    val sessionCount: Int? = null,
)

data class TokenUsage(
    val input: Long = 0,
    val cacheCreation: Long = 0,
    val cacheRead: Long = 0,
    val output: Long = 0,
    val apiCalls: Long = 0,
)

/** Total tokens for a checkpoint session, counting cache reads and writes. */
fun totalTokens(usage: TokenUsage): Long = usage.input + usage.cacheCreation + usage.cacheRead + usage.output

data class SessionSummaryText(val intent: String? = null, val outcome: String? = null)

/** One session slot inside a checkpoint. */
data class CheckpointSession(
    val index: Int,
    val sessionId: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val kind: String? = null,
    val createdAt: String? = null,
    val filesTouched: List<String> = emptyList(),
    /** Usage since the previous checkpoint of the same session. */
    val tokenUsage: TokenUsage? = null,
    val summary: SessionSummaryText? = null,
)

data class CheckpointTask(
    val toolUseId: String,
    val agentId: String? = null,
    val subagentType: String? = null,
    val description: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
)

/** `entire checkpoint explain --checkpoint <id> --json`. */
data class CheckpointDetail(
    val checkpointId: String,
    val branch: String? = null,
    /** Named `checkpoints_count` by the CLI, but it counts prompts. */
    val promptCount: Int? = null,
    val filesTouched: List<String> = emptyList(),
    val sessions: List<CheckpointSession> = emptyList(),
    val tasks: List<CheckpointTask> = emptyList(),
    /** The CLI could read only part of the checkpoint. */
    val partial: Boolean = false,
)

data class SessionTokens(val total: Long = 0, val input: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val output: Long = 0)

/** One row of `entire session list --json`. */
data class SessionRow(
    val sessionId: String,
    val agent: String? = null,
    val model: String? = null,
    /** `active`, `idle`, or `ended`. */
    val status: String? = null,
    val kind: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val lastActive: String? = null,
    val turns: Int? = null,
    /** Turns not yet condensed into a checkpoint. */
    val pendingTurns: Int? = null,
    val lastCheckpointId: String? = null,
    val tokens: SessionTokens? = null,
    val lastPrompt: String? = null,
)

/** A string that is not empty. It is not trimmed. */
internal fun str(value: JsonElement?): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifEmpty { null }

/** A finite number. `true`, `null`, and a number in quotes are not numbers. */
internal fun num(value: JsonElement?): Double? =
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** `true` or `false`, and nothing that only looks like one. */
internal fun bool(value: JsonElement?): Boolean? =
    when ((value as? JsonPrimitive)?.takeIf { !it.isString }?.content) {
        "true" -> true
        "false" -> false
        else -> null
    }

internal fun strings(value: JsonElement?): List<String> = (value as? JsonArray)?.mapNotNull { str(it) } ?: emptyList()

internal fun objects(value: JsonElement?): List<JsonObject> = (value as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

private fun wholeNumber(value: JsonElement?): Int? = num(value)?.takeIf { it % 1.0 == 0.0 }?.toInt()

fun readStatus(value: JsonElement?): StatusInfo? {
    val json = value as? JsonObject ?: return null
    val enabled = bool(json["enabled"]) ?: return null
    return StatusInfo(
        enabled = enabled,
        error = str(json["error"]),
        agents = strings(json["agents"]),
        activeSessions = objects(json["active_sessions"]).map { session ->
            ActiveSession(
                sessionId = str(session["session_id"]),
                agent = str(session["agent"]),
                model = str(session["model"]),
                status = str(session["status"]),
                branch = str(session["branch"]),
                worktreePath = str(session["worktree_path"]),
            )
        },
    )
}

fun readCheckpointRows(value: JsonElement?): List<CheckpointRow>? {
    if (value !is JsonArray) return null
    val rows = ArrayList<CheckpointRow>()
    for (item in objects(value)) {
        val checkpointId = str(item["checkpoint_id"]) ?: continue
        val sessionId = str(item["session_id"])
        val listed = strings(item["session_ids"])
        // The row's own session comes first when the list leaves it out.
        val sessionIds = if (sessionId != null && sessionId !in listed) listOf(sessionId) + listed else listed
        rows.add(
            CheckpointRow(
                checkpointId = checkpointId,
                sessionId = sessionId,
                agent = str(item["agent"]),
                date = str(item["date"]),
                message = str(item["message"]),
                sessionIds = sessionIds,
                sessionCount = wholeNumber(item["session_count"]),
            ),
        )
    }
    return rows
}

private fun readTokenUsage(value: JsonElement?): TokenUsage? {
    val json = value as? JsonObject ?: return null
    fun count(key: String): Long = num(json[key])?.toLong() ?: 0
    return TokenUsage(
        input = count("input_tokens"),
        cacheCreation = count("cache_creation_tokens"),
        cacheRead = count("cache_read_tokens"),
        output = count("output_tokens"),
        apiCalls = count("api_call_count"),
    )
}

private fun readSummary(value: JsonElement?): SessionSummaryText? {
    val json = value as? JsonObject ?: return null
    val summary = SessionSummaryText(intent = str(json["intent"]), outcome = str(json["outcome"]))
    return if (summary.intent != null || summary.outcome != null) summary else null
}

fun readCheckpointDetail(value: JsonElement?): CheckpointDetail? {
    val json = value as? JsonObject ?: return null
    val checkpointId = str(json["checkpoint_id"]) ?: return null
    val sessions = objects(json["sessions"]).mapIndexed { position, session ->
        CheckpointSession(
            index = wholeNumber(session["index"]) ?: position,
            sessionId = str(session["session_id"]),
            agent = str(session["agent"]),
            model = str(session["model"]),
            kind = str(session["kind"]),
            createdAt = str(session["created_at"]),
            filesTouched = strings(session["files_touched"]),
            tokenUsage = readTokenUsage(session["token_usage"]),
            summary = readSummary(session["summary"]),
        )
    }
    val tasks = objects(json["tasks"]).mapNotNull { task ->
        val toolUseId = str(task["tool_use_id"]) ?: return@mapNotNull null
        CheckpointTask(
            toolUseId = toolUseId,
            agentId = str(task["agent_id"]),
            subagentType = str(task["subagent_type"]),
            description = str(task["task_description"]),
            startedAt = str(task["started_at"]),
            completedAt = str(task["completed_at"]),
        )
    }
    return CheckpointDetail(
        checkpointId = checkpointId,
        branch = str(json["branch"]),
        promptCount = wholeNumber(json["checkpoints_count"]),
        filesTouched = strings(json["files_touched"]),
        sessions = sessions,
        tasks = tasks,
        partial = bool(json["partial"]) == true,
    )
}

private fun readSessionTokens(value: JsonElement?): SessionTokens? {
    val json = value as? JsonObject ?: return null
    fun count(key: String): Long = num(json[key])?.toLong() ?: 0
    return SessionTokens(total = count("total"), input = count("input"), cacheRead = count("cache_read"), cacheWrite = count("cache_write"), output = count("output"))
}

fun readSessionRows(value: JsonElement?): List<SessionRow>? {
    if (value !is JsonArray) return null
    return objects(value).mapNotNull { item ->
        val sessionId = str(item["session_id"]) ?: return@mapNotNull null
        SessionRow(
            sessionId = sessionId,
            agent = str(item["agent"]),
            model = str(item["model"]),
            status = str(item["status"]),
            kind = str(item["kind"]),
            startedAt = str(item["started_at"]),
            endedAt = str(item["ended_at"]),
            lastActive = str(item["last_active"]),
            turns = wholeNumber(item["turns"]),
            pendingTurns = wholeNumber(item["pending_turns"]),
            lastCheckpointId = str(item["last_checkpoint_id"]),
            tokens = readSessionTokens(item["tokens"]),
            lastPrompt = str(item["last_prompt"]),
        )
    }
}
