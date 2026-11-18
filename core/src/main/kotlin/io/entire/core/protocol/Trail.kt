package io.entire.core.protocol

import io.entire.core.json.stringOrNull
import io.entire.core.trails.Outcome
import io.entire.core.trails.isTrailId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Messages between the IDE and the trail panel's page. As with the detail
 * panel, the page names things by position: the IDE looks up the commit, the
 * checkpoint, or the address in its own state.
 */

/** What a trail panel is open on. Plain data, so it can be saved and read back after the IDE restarts. */
data class TrailTarget(
    val repoRoot: String,
    /** The trail's number in its project. */
    val trail: Long,
    /** The trail's ID on Entire, when known. The CLI finds a trail by its ID sooner than by its number. */
    val id: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("repoRoot", repoRoot)
        put("mode", "trail")
        put("trail", trail)
        if (id != null) put("id", id)
    }
}

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

/** Validates a target that was saved earlier. */
fun readTrailTarget(value: JsonElement?): TrailTarget? {
    val raw = value as? JsonObject ?: return null
    val repoRoot = raw["repoRoot"].stringOrNull() ?: return null
    if (raw["mode"].stringOrNull() != "trail") return null
    val number = (raw["trail"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return null
    if (number.isNaN() || number % 1.0 != 0.0 || number < 1 || number > MAX_SAFE_INTEGER) return null
    // An ID that does not look like one is dropped, and the trail is then read by its number.
    return TrailTarget(repoRoot, number.toLong(), raw["id"].stringOrNull()?.takeIf { isTrailId(it) })
}

/** One condition a change has to meet before it can merge. */
data class TrailGateView(
    val label: String,
    val outcome: Outcome,
    /** A gate that does not block is reported and does not stop a merge. */
    val blocking: Boolean,
    /** The reason entire.io gives for the outcome. */
    val rationale: String? = null,
    val findings: Long? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("label", label)
        put("outcome", outcome.key)
        put("blocking", blocking)
        if (rationale != null) put("rationale", rationale)
        if (findings != null) put("findings", findings)
    }
}

/** One CI run on the head commit of a change. */
data class TrailCheckView(
    val name: String,
    val outcome: Outcome,
    /** What the run is doing or how it ended, in words. */
    val detail: String,
    val app: String? = null,
    /** The run has a page of its own, which the IDE can open. */
    val hasLink: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("outcome", outcome.key)
        put("detail", detail)
        if (app != null) put("app", app)
        put("hasLink", hasLink)
    }
}

data class TrailMergeView(
    val mergeable: Boolean,
    /** Set when the branch does not merge cleanly: the CLI's word for why. */
    val conflict: String? = null,
    val gates: List<TrailGateView>,
    val checks: List<TrailCheckView>,
    /** "1 failed, 20 passed". Empty when there are no runs. */
    val checkSummary: String,
    /** Why no CI runs are listed, when the CLI could not read them. */
    val checksNote: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("mergeable", mergeable)
        if (conflict != null) put("conflict", conflict)
        put("gates", JsonArray(gates.map { it.toJson() }))
        put("checks", JsonArray(checks.map { it.toJson() }))
        put("checkSummary", checkSummary)
        if (checksNote != null) put("checksNote", checksNote)
    }
}

/** One branch of the trail, in one repository. */
data class TrailChangeView(
    val repository: String,
    /** The change's number in its repository. Not the trail's number. */
    val number: Long,
    val title: String? = null,
    val status: String? = null,
    val branch: String? = null,
    val base: String? = null,
    /** The branch is the one checked out in the repository this panel belongs to. */
    val checkedOut: Boolean,
    val hasCodeChanges: Boolean,
    val merge: TrailMergeView? = null,
    val canOpenOnWeb: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("repository", repository)
        put("number", number)
        if (title != null) put("title", title)
        if (status != null) put("status", status)
        if (branch != null) put("branch", branch)
        if (base != null) put("base", base)
        put("checkedOut", checkedOut)
        put("hasCodeChanges", hasCodeChanges)
        if (merge != null) put("merge", merge.toJson())
        put("canOpenOnWeb", canOpenOnWeb)
    }
}

data class TrailCommitView(
    val shortSha: String,
    val additions: Long? = null,
    val deletions: Long? = null,
    val files: Long? = null,
    /** The commit is in this clone, so its diff can be shown. */
    val local: Boolean,
    /** Checkpoints the commit carries, as read from this clone. Each one can be opened. */
    val checkpoints: Int,
    /** Checkpoints entire.io has for the commit. Shown when the commit is not in this clone. */
    val remoteCheckpoints: Long,
    val agents: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("shortSha", shortSha)
        put("local", local)
        put("checkpoints", checkpoints)
        put("remoteCheckpoints", remoteCheckpoints)
        put("agents", JsonArray(agents.map { JsonPrimitive(it) }))
        if (additions != null) put("additions", additions)
        if (deletions != null) put("deletions", deletions)
        if (files != null) put("files", files)
    }
}

/** One entry of a change's timeline. */
data class TrailEventView(
    val kind: String,
    /** One line that says what happened. For a commit, its subject. */
    val text: String,
    val actor: String? = null,
    /** ISO timestamp. */
    val at: String? = null,
    val commit: TrailCommitView? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("text", text)
        if (actor != null) put("actor", actor)
        if (at != null) put("at", at)
        if (commit != null) put("commit", commit.toJson())
    }
}

class TrailTimelineView(
    /** Newest first. */
    val events: MutableList<TrailEventView>,
    var hasMore: Boolean,
    /** A caveat, for example that the timeline could not be read. */
    val notice: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("events", JsonArray(events.map { it.toJson() }))
        put("hasMore", hasMore)
        if (notice != null) put("notice", notice)
    }
}

/** What the trail page draws. The fields that follow a read under way change while the panel is open. */
class TrailView(
    val number: Long,
    val title: String,
    val status: String? = null,
    val phase: String? = null,
    val type: String? = null,
    val priority: String? = null,
    val project: String? = null,
    val assignees: List<String> = emptyList(),
    val createdAt: String? = null,
    val lastActivityAt: String? = null,
    val closedAt: String? = null,
    /** The trail's description, as Markdown. */
    val body: String = "",
    val canOpenOnWeb: Boolean = false,
    val changes: List<TrailChangeView> = emptyList(),
    /** Position in `changes` of the one whose merge state and timeline are shown. */
    val selected: Int = 0,
    /** Activity on the selected change. Null when the trail has no changes, and while it is being read. */
    var timeline: TrailTimelineView? = null,
    /**
     * The view holds only what the Trails tab listed about the trail: its
     * title and the facts under it. The changes are still being read.
     */
    val partial: Boolean = false,
    /** The timeline of the selected change is still being read. */
    var timelinePending: Boolean = false,
    /** The view is the trail as it was read earlier, and a fresh read is under way. */
    var refreshing: Boolean = false,
    /** A caveat about the whole view, for example that the fresh read failed. */
    var notice: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("number", number)
        put("title", title)
        if (status != null) put("status", status)
        if (phase != null) put("phase", phase)
        if (type != null) put("type", type)
        if (priority != null) put("priority", priority)
        if (project != null) put("project", project)
        put("assignees", JsonArray(assignees.map { JsonPrimitive(it) }))
        if (createdAt != null) put("createdAt", createdAt)
        if (lastActivityAt != null) put("lastActivityAt", lastActivityAt)
        if (closedAt != null) put("closedAt", closedAt)
        put("body", body)
        put("canOpenOnWeb", canOpenOnWeb)
        put("changes", JsonArray(changes.map { it.toJson() }))
        put("selected", selected)
        timeline?.let { put("timeline", it.toJson()) }
        if (partial) put("partial", true)
        if (timelinePending) put("timelinePending", true)
        if (refreshing) put("refreshing", true)
        notice?.let { put("notice", it) }
    }
}

/**
 * What the IDE sends the trail panel's page. A trail arrives in stages,
 * because each read from Entire takes seconds: `trail` once with what is
 * known at once, `trail` again when the trail has been read, and `timeline`
 * when its timeline has.
 */
sealed interface TrailHostMessage {
    fun toJson(): JsonObject

    data class Loading(val title: String) : TrailHostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "loading")
            put("title", title)
        }
    }

    class Trail(val view: TrailView, val restore: TrailTarget) : TrailHostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "trail")
            put("protocol", PROTOCOL_VERSION)
            put("view", view.toJson())
            put("restore", restore.toJson())
        }
    }

    /** The first page of the selected change's timeline. It replaces the one on the page. */
    class Timeline(val timeline: TrailTimelineView) : TrailHostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "timeline")
            put("timeline", timeline.toJson())
        }
    }

    class TimelinePage(val events: List<TrailEventView>, val hasMore: Boolean, val notice: String? = null) : TrailHostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "timelinePage")
            put("events", JsonArray(events.map { it.toJson() }))
            put("hasMore", hasMore)
            if (notice != null) put("notice", notice)
        }
    }

    /** The fresh read of a view sent with `refreshing` has ended. `notice` says so when it failed. */
    data class Refreshed(val notice: String? = null) : TrailHostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "refreshed")
            if (notice != null) put("notice", notice)
        }
    }

    data class Error(val message: String, val canRetry: Boolean) : TrailHostMessage {
        override fun toJson() = buildJsonObject {
            put("type", "error")
            put("message", message)
            put("canRetry", canRetry)
        }
    }
}

/** What the trail panel's page can ask for. */
sealed interface TrailWebviewMessage {
    data class Ready(val protocol: Int) : TrailWebviewMessage

    data object Retry : TrailWebviewMessage

    data class OpenLink(val href: String) : TrailWebviewMessage

    data class SelectChange(val position: Int) : TrailWebviewMessage

    data object OpenOnWeb : TrailWebviewMessage

    data object OpenChangeOnWeb : TrailWebviewMessage

    data class OpenCheck(val run: Int) : TrailWebviewMessage

    data class OpenCheckpoint(val event: Int, val checkpoint: Int) : TrailWebviewMessage

    data class ShowCommitDiff(val event: Int) : TrailWebviewMessage

    data class CopyCommitSha(val event: Int) : TrailWebviewMessage

    data object CopyBranch : TrailWebviewMessage

    data object CopyLink : TrailWebviewMessage

    data object LoadMoreTimeline : TrailWebviewMessage
}

/** Reads a message that arrived from the trail panel's page, or returns null for anything else. */
fun readTrailWebviewMessage(value: JsonElement?): TrailWebviewMessage? {
    val message = value as? JsonObject ?: return null
    return when (message["type"].stringOrNull()) {
        "ready" -> index(message, "protocol")?.let { TrailWebviewMessage.Ready(it) }
        "selectChange" -> index(message, "position")?.let { TrailWebviewMessage.SelectChange(it) }
        "openCheck" -> index(message, "run")?.let { TrailWebviewMessage.OpenCheck(it) }
        "openCheckpoint" -> {
            val event = index(message, "event") ?: return null
            val checkpoint = index(message, "checkpoint") ?: return null
            TrailWebviewMessage.OpenCheckpoint(event, checkpoint)
        }
        "showCommitDiff" -> index(message, "event")?.let { TrailWebviewMessage.ShowCommitDiff(it) }
        "copyCommitSha" -> index(message, "event")?.let { TrailWebviewMessage.CopyCommitSha(it) }
        "openLink" -> message["href"].stringOrNull()?.let { TrailWebviewMessage.OpenLink(it) }
        "retry" -> TrailWebviewMessage.Retry
        "openOnWeb" -> TrailWebviewMessage.OpenOnWeb
        "openChangeOnWeb" -> TrailWebviewMessage.OpenChangeOnWeb
        "copyBranch" -> TrailWebviewMessage.CopyBranch
        "copyLink" -> TrailWebviewMessage.CopyLink
        "loadMoreTimeline" -> TrailWebviewMessage.LoadMoreTimeline
        else -> null
    }
}
