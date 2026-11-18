package io.entire.core.protocol

import io.entire.core.git.isCheckpointId
import io.entire.core.git.isCommitSha
import io.entire.core.json.stringOrNull
import io.entire.core.transcript.WireTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Messages between the IDE and the detail panel's page.
 *
 * The page sends intents and indexes only. It never sends a path, a URL for
 * a header button, or a CLI argument: the IDE resolves those from the state
 * it built itself. The one exception is `openLink.href`, which the IDE checks
 * before it opens anything.
 */

const val PROTOCOL_VERSION = 1

enum class DetailMode(val wire: String) { CHECKPOINT("checkpoint"), SESSION("session") }

/** What a panel is open on. Plain data, so it can be saved and read back after the IDE restarts. */
data class DetailTarget(
    val repoRoot: String,
    val mode: DetailMode,
    val checkpointId: String? = null,
    val commitSha: String? = null,
    /** In session mode, the session to show. In checkpoint mode, the slot to preselect. */
    val sessionId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("repoRoot", repoRoot)
        put("mode", mode.wire)
        if (checkpointId != null) put("checkpointId", checkpointId)
        if (commitSha != null) put("commitSha", commitSha)
        if (sessionId != null) put("sessionId", sessionId)
    }
}

/** Validates a target that was saved earlier. A field that does not look like what it claims to be is dropped. */
fun readDetailTarget(value: JsonElement?): DetailTarget? {
    val raw = value as? JsonObject ?: return null
    val repoRoot = raw["repoRoot"].stringOrNull() ?: return null
    val mode = when (raw["mode"].stringOrNull()) {
        "checkpoint" -> DetailMode.CHECKPOINT
        "session" -> DetailMode.SESSION
        else -> return null
    }
    val target = DetailTarget(
        repoRoot = repoRoot,
        mode = mode,
        checkpointId = raw["checkpointId"].stringOrNull()?.takeIf { isCheckpointId(it) },
        commitSha = raw["commitSha"].stringOrNull()?.takeIf { isCommitSha(it) },
        sessionId = raw["sessionId"].stringOrNull()?.takeIf { it.length <= 128 },
    )
    if (target.mode == DetailMode.CHECKPOINT && target.checkpointId == null) return null
    if (target.mode == DetailMode.SESSION && target.sessionId.isNullOrEmpty()) return null
    return target
}

data class HeaderCommit(val sha: String, val shortSha: String, val subject: String, val author: String, val date: String)

data class HeaderSession(
    val index: Int,
    val sessionId: String? = null,
    val agent: String? = null,
    val model: String? = null,
    /** Tokens used since the previous checkpoint of this session. */
    val tokens: Long? = null,
    val createdAt: String? = null,
    val intent: String? = null,
    val outcome: String? = null,
)

data class HeaderFile(
    val path: String,
    /** Git status letter, when the file is part of the commit. */
    val status: String? = null,
)

/** What the CLI knows of a session that has local state: one that is running, or ran lately. */
data class HeaderLocalSession(
    val sessionId: String,
    val status: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val turns: Int? = null,
    val pendingTurns: Int? = null,
    val tokens: Long? = null,
    val startedAt: String? = null,
    val lastActive: String? = null,
)

data class HeaderPosition(val ordinal: Int, val total: Int, val mayHaveEarlier: Boolean, val hasPrevious: Boolean, val hasNext: Boolean)

data class PanelHeader(
    val mode: DetailMode,
    val title: String,
    val checkpointId: String? = null,
    val commit: HeaderCommit? = null,
    val otherCommits: Int = 0,
    val branch: String? = null,
    val sessions: List<HeaderSession> = emptyList(),
    val selected: Int = 0,
    val files: List<HeaderFile> = emptyList(),
    val position: HeaderPosition? = null,
    val localSession: HeaderLocalSession? = null,
    val promptAuthor: String? = null,
    val canOpenOnWeb: Boolean = false,
    val canShowCommitDiff: Boolean = false,
    val canOpenRaw: Boolean = false,
    val notice: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mode", mode.wire)
        put("title", title)
        if (checkpointId != null) put("checkpointId", checkpointId)
        put("otherCommits", otherCommits)
        put(
            "sessions",
            JsonArray(
                sessions.map { session ->
                    buildJsonObject {
                        put("index", session.index)
                        if (session.sessionId != null) put("sessionId", session.sessionId)
                        if (session.agent != null) put("agent", session.agent)
                        if (session.model != null) put("model", session.model)
                        if (session.tokens != null) put("tokens", session.tokens)
                        if (session.createdAt != null) put("createdAt", session.createdAt)
                        if (session.intent != null) put("intent", session.intent)
                        if (session.outcome != null) put("outcome", session.outcome)
                    }
                },
            ),
        )
        put("selected", selected)
        put(
            "files",
            JsonArray(
                files.map { file ->
                    buildJsonObject {
                        put("path", file.path)
                        if (file.status != null) put("status", file.status)
                    }
                },
            ),
        )
        put("canOpenOnWeb", canOpenOnWeb)
        put("canShowCommitDiff", canShowCommitDiff)
        put("canOpenRaw", canOpenRaw)
        if (commit != null) {
            put(
                "commit",
                buildJsonObject {
                    put("sha", commit.sha)
                    put("shortSha", commit.shortSha)
                    put("subject", commit.subject)
                    put("author", commit.author)
                    put("date", commit.date)
                },
            )
        }
        if (branch != null) put("branch", branch)
        if (localSession != null) {
            put(
                "localSession",
                buildJsonObject {
                    put("sessionId", localSession.sessionId)
                    if (localSession.status != null) put("status", localSession.status)
                    if (localSession.agent != null) put("agent", localSession.agent)
                    if (localSession.model != null) put("model", localSession.model)
                    if (localSession.turns != null) put("turns", localSession.turns)
                    if (localSession.pendingTurns != null) put("pendingTurns", localSession.pendingTurns)
                    if (localSession.tokens != null) put("tokens", localSession.tokens)
                    if (localSession.startedAt != null) put("startedAt", localSession.startedAt)
                    if (localSession.lastActive != null) put("lastActive", localSession.lastActive)
                },
            )
        }
        if (notice != null) put("notice", notice)
        if (promptAuthor != null) put("promptAuthor", promptAuthor)
        if (position != null) {
            put(
                "position",
                buildJsonObject {
                    put("ordinal", position.ordinal)
                    put("total", position.total)
                    put("mayHaveEarlier", position.mayHaveEarlier)
                    put("hasPrevious", position.hasPrevious)
                    put("hasNext", position.hasNext)
                },
            )
        }
    }
}

enum class TranscriptSource(val wire: String) { COMPACT("compact"), CLI_TEXT("cliText"), NONE("none") }

data class TranscriptInfo(val source: TranscriptSource, val total: Int, val start: Int, val notice: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("source", source.wire)
        put("total", total)
        put("start", start)
        if (notice != null) put("notice", notice)
    }
}

/** What the IDE sends to the detail panel's page. */
sealed interface HostMessage {
    fun toJson(): JsonObject

    data class Loading(val title: String) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "loading")
            put("title", title)
        }
    }

    data class Init(val header: PanelHeader, val transcript: TranscriptInfo, val restore: DetailTarget) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "init")
            put("protocol", PROTOCOL_VERSION)
            put("header", header.toJson())
            put("transcript", transcript.toJson())
            put("restore", restore.toJson())
        }
    }

    /** Turns are sent in batches. `done` marks the last one. */
    data class Turns(val turns: List<WireTurn>, val done: Boolean) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "turns")
            put("turns", JsonArray(turns.map { it.toJson() }))
            put("done", done)
        }
    }

    data class PromptAuthor(val name: String) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "promptAuthor")
            put("name", name)
        }
    }

    data class ToolBody(val requestId: Int, val input: String, val output: String, val truncated: Boolean) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "toolBody")
            put("requestId", requestId)
            put("input", input)
            put("output", output)
            put("truncated", truncated)
        }
    }

    data class Image(val requestId: Int, val mediaType: String, val base64: String) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "image")
            put("requestId", requestId)
            put("mediaType", mediaType)
            put("base64", base64)
        }
    }

    data class ImageUnavailable(val requestId: Int) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "imageUnavailable")
            put("requestId", requestId)
        }
    }

    data class Error(val message: String, val canRetry: Boolean) : HostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "error")
            put("message", message)
            put("canRetry", canRetry)
        }
    }
}

enum class CopyTarget { CHECKPOINT_ID, COMMIT_SHA, SESSION_ID }

/** What the detail panel's page sends to the IDE. */
sealed interface WebviewMessage {
    data class Ready(val protocol: Int) : WebviewMessage

    data class SelectSession(val position: Int) : WebviewMessage

    data class StepSession(val next: Boolean) : WebviewMessage

    data class RequestToolBody(val requestId: Int, val turn: Int, val block: Int) : WebviewMessage

    data class RequestImage(val requestId: Int, val turn: Int, val block: Int) : WebviewMessage

    data class OpenToolBody(val turn: Int, val block: Int) : WebviewMessage

    data object OpenOnWeb : WebviewMessage

    data object ShowCommitDiff : WebviewMessage

    data class OpenFile(val index: Int) : WebviewMessage

    data object OpenRawTranscript : WebviewMessage

    data class OpenLink(val href: String) : WebviewMessage

    data class Copy(val target: CopyTarget) : WebviewMessage

    data object Retry : WebviewMessage
}

/** A whole number that is not negative, which is what the page sends for every index and ID. */
internal fun index(message: JsonObject, key: String): Int? {
    val number = (message[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return null
    if (number.isNaN() || number % 1.0 != 0.0 || number < 0 || number > Int.MAX_VALUE) return null
    return number.toInt()
}

/** Reads a message from the page, or returns null for anything that is not one. Extra fields are ignored. */
fun readWebviewMessage(value: JsonElement?): WebviewMessage? {
    val message = value as? JsonObject ?: return null
    return when (message["type"].stringOrNull()) {
        "ready" -> index(message, "protocol")?.let { WebviewMessage.Ready(it) }
        "selectSession" -> index(message, "position")?.let { WebviewMessage.SelectSession(it) }
        "stepSession" -> when (message["direction"].stringOrNull()) {
            "previous" -> WebviewMessage.StepSession(next = false)
            "next" -> WebviewMessage.StepSession(next = true)
            else -> null
        }
        "requestToolBody" -> {
            val requestId = index(message, "requestId") ?: return null
            val turn = index(message, "turn") ?: return null
            val block = index(message, "block") ?: return null
            WebviewMessage.RequestToolBody(requestId, turn, block)
        }
        "requestImage" -> {
            val requestId = index(message, "requestId") ?: return null
            val turn = index(message, "turn") ?: return null
            val block = index(message, "block") ?: return null
            WebviewMessage.RequestImage(requestId, turn, block)
        }
        "openToolBody" -> {
            val turn = index(message, "turn") ?: return null
            val block = index(message, "block") ?: return null
            WebviewMessage.OpenToolBody(turn, block)
        }
        "openFile" -> index(message, "index")?.let { WebviewMessage.OpenFile(it) }
        "openLink" -> message["href"].stringOrNull()?.let { WebviewMessage.OpenLink(it) }
        "copy" -> when (message["target"].stringOrNull()) {
            "checkpointId" -> WebviewMessage.Copy(CopyTarget.CHECKPOINT_ID)
            "commitSha" -> WebviewMessage.Copy(CopyTarget.COMMIT_SHA)
            "sessionId" -> WebviewMessage.Copy(CopyTarget.SESSION_ID)
            else -> null
        }
        "openOnWeb" -> WebviewMessage.OpenOnWeb
        "showCommitDiff" -> WebviewMessage.ShowCommitDiff
        "openRawTranscript" -> WebviewMessage.OpenRawTranscript
        "retry" -> WebviewMessage.Retry
        else -> null
    }
}
