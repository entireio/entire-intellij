package io.entire.core.cli

import io.entire.core.trails.isTrailId
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * What the Entire CLI prints about trails: `entire trail list`, `entire
 * trail show`, and `entire trail timeline`, each with `--json`. The readers
 * are as tolerant as the others in this package.
 */

/** One CI run on the head commit of a change. */
data class TrailCheckRun(
    val name: String,
    /** `queued`, `in_progress`, or `completed`. */
    val status: String? = null,
    /** Set once the run completes: `success`, `failure`, `skipped`, and so on. */
    val conclusion: String? = null,
    val detailsUrl: String? = null,
    val appName: String? = null,
)

/** One condition a change has to meet before it can merge. */
data class TrailGate(
    val key: String,
    val blocking: Boolean,
    /** `passed`, `failed`, `skipped`, or a status that means it is still being evaluated. */
    val status: String? = null,
    val rationale: String? = null,
    val findingCount: Long? = null,
)

data class TrailMergeability(
    val mergeable: Boolean,
    val headSha: String? = null,
    /** `clean` when the branch merges without conflicts. */
    val conflictStatus: String? = null,
    /** `available` when the CLI could read the CI runs. */
    val checksAvailability: String? = null,
    val checks: List<TrailCheckRun> = emptyList(),
    val gates: List<TrailGate> = emptyList(),
)

/**
 * One branch of a trail, in one repository. The CLI calls it a change, and
 * numbers it inside its repository, apart from the trail's own number.
 */
data class TrailChange(
    /** Short repository name, such as `widget`. */
    val repository: String,
    val number: Long,
    val title: String? = null,
    val status: String? = null,
    val branch: String? = null,
    val base: String? = null,
    val hasCodeChanges: Boolean = false,
    val updatedAt: String? = null,
    /** The change's page on the web. Only `entire trail show` reports it. */
    val url: String? = null,
    /** Only `entire trail show` reports it. */
    val mergeability: TrailMergeability? = null,
)

/** A trail as `entire trail list --json` reports it. */
data class TrailSummary(
    val number: Long,
    /** The trail's ID on Entire. A CLI call that names the trail by it is quicker than one by number. */
    val id: String? = null,
    val title: String,
    val body: String? = null,
    /** `open` or `closed`. */
    val status: String? = null,
    /** `planning`, `building`, `complete`, or `closed`. */
    val phase: String? = null,
    /** `bug`, `feature`, or `task`. */
    val type: String? = null,
    val priority: String? = null,
    val assignees: List<String> = emptyList(),
    val createdAt: String? = null,
    val lastActivityAt: String? = null,
    val closedAt: String? = null,
    val changes: List<TrailChange> = emptyList(),
)

data class TrailPage(
    val trails: List<TrailSummary>,
    /** Present when the CLI has more trails to list. */
    val nextPageToken: String? = null,
    /** How many trails match the filter in all, when the CLI says. */
    val total: Long? = null,
)

/** `entire trail show <number> --json`: the trail, and what only this command reports. */
data class TrailDetail(
    val summary: TrailSummary,
    /** The trail's page on the web. The CLI asks callers to use this and not to build one. */
    val url: String? = null,
    val projectName: String? = null,
) {
    val number: Long get() = summary.number
    val id: String? get() = summary.id
    val title: String get() = summary.title
    val changes: List<TrailChange> get() = summary.changes
}

/** One entry of a change's timeline: a commit, a review, a resolved finding, and so on. */
data class TrailEvent(
    val kind: String,
    /** Who did it: a person's name or login, or the name of an agent. */
    val actor: String? = null,
    val occurredAt: String? = null,
    /** For a commit, its subject. */
    val title: String? = null,
    val commitSha: String? = null,
    val additions: Long? = null,
    val deletions: Long? = null,
    val filesChanged: Long? = null,
    /** Checkpoints entire.io has for the commit. */
    val checkpointCount: Long? = null,
    val agents: List<String> = emptyList(),
    val findingCount: Long? = null,
)

/** `entire trail timeline --json`, for one change of a trail. Newest first. */
data class TrailTimeline(
    val events: List<TrailEvent>,
    val nextPageToken: String? = null,
    val commitCount: Long? = null,
    /** The CLI could not read the whole history of the branch. */
    val incomplete: Boolean = false,
)

/** The largest whole number JavaScript holds exactly, which is as far as the CLI's numbers go. */
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

/** A whole number. The CLI prints a count or a trail number as one, and anything else is treated as absent. */
private fun whole(value: JsonElement?): Long? = num(value)?.takeIf { it % 1.0 == 0.0 && abs(it) <= MAX_SAFE_INTEGER }?.toLong()

/** Names from a list whose entries are strings or objects with a `name`. */
private fun names(value: JsonElement?): List<String> {
    val items = value as? JsonArray ?: return emptyList()
    return items.mapNotNull { item -> if (item is JsonObject) str(item["name"]) ?: str(item["label"]) else str(item) }
}

private fun readMergeability(value: JsonElement?): TrailMergeability? {
    val json = value as? JsonObject ?: return null
    val checks = json["checks"] as? JsonObject
    val runs = objects(checks?.get("runs")).mapNotNull { run ->
        val name = str(run["name"]) ?: return@mapNotNull null
        TrailCheckRun(
            name = name,
            status = str(run["status"]),
            conclusion = str(run["conclusion"]),
            detailsUrl = str(run["details_url"]),
            appName = str(run["app_name"]),
        )
    }
    val gates = objects(json["gates"]).mapNotNull { gate ->
        val key = str(gate["gate_key"]) ?: str(gate["gate_type"]) ?: return@mapNotNull null
        TrailGate(
            key = key,
            blocking = bool(gate["blocking"]) == true,
            status = str(gate["status"]),
            rationale = str(gate["rationale"]),
            findingCount = whole(gate["finding_count"]),
        )
    }
    return TrailMergeability(
        mergeable = bool(json["mergeable"]) == true,
        headSha = str(json["head_sha"]),
        conflictStatus = str(json["conflict_status"]),
        checksAvailability = str(checks?.get("availability")),
        checks = runs,
        gates = gates,
    )
}

private fun readTrailChanges(value: JsonElement?): List<TrailChange> =
    objects(value).mapNotNull { change ->
        val repository = str(change["repository"]) ?: return@mapNotNull null
        val number = whole(change["number"]) ?: return@mapNotNull null
        TrailChange(
            repository = repository,
            number = number,
            title = str(change["title"]),
            status = str(change["status"]),
            branch = str(change["branch"]),
            base = str(change["base"]),
            hasCodeChanges = bool(change["hasCodeChanges"]) == true,
            updatedAt = str(change["updatedAt"]),
            url = str(change["url"]),
            mergeability = readMergeability(change["mergeability"]),
        )
    }

private fun readTrailSummary(item: JsonObject): TrailSummary? {
    val number = whole(item["number"]) ?: return null
    return TrailSummary(
        number = number,
        // Kept only in the form the CLI is known to accept. Without it, calls name the trail by number.
        id = str(item["id"])?.takeIf { isTrailId(it) },
        title = str(item["title"]) ?: "Trail $number",
        body = str(item["body"]),
        status = str(item["status"]),
        phase = str(item["phase"]),
        type = str(item["type"]),
        priority = str(item["priority"]),
        assignees = strings(item["assignees"]),
        createdAt = str(item["createdAt"]),
        lastActivityAt = str(item["lastActivityAt"]) ?: str(item["updatedAt"]),
        closedAt = str(item["closedAt"]),
        changes = readTrailChanges(item["changes"]),
    )
}

fun readTrailPage(value: JsonElement?): TrailPage? {
    val json = value as? JsonObject ?: return null
    if (json["items"] !is JsonArray) return null
    val trails = objects(json["items"]).mapNotNull(::readTrailSummary)
    // The CLI reports one total per group, and one group when the list is not grouped.
    val groups = objects(json["groupTotals"]).ifEmpty { objects(json["groups"]) }
    val totals = groups.mapNotNull { whole(it["totalCount"]) }
    return TrailPage(
        trails = trails,
        nextPageToken = str(json["nextPageToken"]),
        total = if (totals.isEmpty()) null else totals.sum(),
    )
}

fun readTrailDetail(value: JsonElement?): TrailDetail? {
    val json = value as? JsonObject ?: return null
    val summary = readTrailSummary(json) ?: return null
    return TrailDetail(summary = summary, url = str(json["url"]), projectName = str((json["project"] as? JsonObject)?.get("name")))
}

private fun readActor(value: JsonElement?): String? {
    val json = value as? JsonObject ?: return null
    return str((json["identity"] as? JsonObject)?.get("display_name")) ?: str(json["label"]) ?: str(json["login"])
}

fun readTrailTimeline(value: JsonElement?): TrailTimeline? {
    val json = value as? JsonObject ?: return null
    if (json["items"] !is JsonArray) return null
    val events = objects(json["items"]).mapNotNull { item ->
        val kind = str(item["kind"]) ?: return@mapNotNull null
        TrailEvent(
            kind = kind,
            actor = readActor(item["actor"]),
            occurredAt = str(item["occurredAt"]),
            title = str(item["title"]),
            commitSha = str(item["commitSha"]),
            additions = whole(item["additions"]),
            deletions = whole(item["deletions"]),
            filesChanged = whole(item["filesChanged"]),
            checkpointCount = whole(item["checkpointCount"]),
            agents = names(item["agents"]),
            findingCount = whole(item["findingCount"]),
        )
    }
    return TrailTimeline(
        events = events,
        nextPageToken = str(json["nextPageToken"]),
        commitCount = whole(json["commitCount"]),
        incomplete = bool(json["incomplete"]) == true,
    )
}
