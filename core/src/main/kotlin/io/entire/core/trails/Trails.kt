package io.entire.core.trails

import io.entire.core.cli.TrailChange
import io.entire.core.cli.TrailCheckRun
import io.entire.core.cli.TrailEvent
import io.entire.core.cli.TrailGate
import io.entire.core.cli.TrailPage
import io.entire.core.cli.TrailSummary
import io.entire.core.json.jsTrim
import io.entire.core.web.EntireRepo
import java.util.Locale

/** What the CLI can list: the trails of one status, or the trails of both. A trail is open until someone closes it. */
enum class TrailListStatus(val key: String) { OPEN("open"), CLOSED("closed"), ALL("all") }

/**
 * Which trails the Trails tab shows.
 *
 * A trail has a status, and an open trail is also in a phase that follows its
 * work: planning, building, or complete. The CLI lists by status and cannot
 * list by phase, so a phase is matched here, against the trails read so far.
 */
data class TrailFilter(
    val status: TrailListStatus,
    /** Only open trails in this phase. */
    val phase: String? = null,
)

val DEFAULT_TRAIL_FILTER = TrailFilter(TrailListStatus.OPEN)

/** The phases of an open trail that the filter offers by name. */
val OPEN_PHASES: List<String> = listOf("planning", "building", "complete")

private val PHASE_NAME = Regex("[a-z][a-z0-9_-]{0,39}")

/** A filter as one string: `all`, `open`, `closed`, or `open:<phase>`. */
fun trailFilterKey(filter: TrailFilter): String = if (filter.phase.isNullOrEmpty()) filter.status.key else "${filter.status.key}:${filter.phase}"

/** Reads a filter back from its key, or returns null for anything else. */
fun readTrailFilter(key: String?): TrailFilter? {
    if (key == null) return null
    TrailListStatus.entries.firstOrNull { it.key == key }?.let { return TrailFilter(it) }
    val parts = key.split(':')
    // Only an open trail has a phase to tell apart. A closed one is in the closed phase.
    if (parts.size != 2 || parts[0] != "open" || !PHASE_NAME.matches(parts[1])) return null
    return TrailFilter(TrailListStatus.OPEN, parts[1])
}

/** The filter's name: "All", "Open", "Closed", or the phase, such as "Building". */
fun trailFilterLabel(filter: TrailFilter): String {
    if (!filter.phase.isNullOrEmpty()) return sentenceCase(filter.phase)
    return when (filter.status) {
        TrailListStatus.ALL -> "All"
        TrailListStatus.CLOSED -> "Closed"
        TrailListStatus.OPEN -> "Open"
    }
}

/** What to say when no trail matches the filter. */
fun noTrailsText(filter: TrailFilter): String {
    if (!filter.phase.isNullOrEmpty()) return "No open trails in the ${words(filter.phase)} phase touch this repository."
    if (filter.status == TrailListStatus.ALL) return "No trails touch this repository."
    return "No ${filter.status.key} trails touch this repository."
}

/**
 * The filters to offer: every status the CLI lists by, then each phase of an
 * open trail. `seen` adds the phases that trails read so far are in, so a
 * phase that a later CLI introduces can still be picked.
 */
fun trailFilters(seen: Iterable<String?> = emptyList()): List<TrailFilter> {
    val extra = seen.filterNotNull().distinct()
        .filter { PHASE_NAME.matches(it) }
        .filter { it != "closed" && it !in OPEN_PHASES }
        .sorted()
    return listOf(TrailFilter(TrailListStatus.ALL), TrailFilter(TrailListStatus.OPEN), TrailFilter(TrailListStatus.CLOSED)) +
        (OPEN_PHASES + extra).map { TrailFilter(TrailListStatus.OPEN, it) }
}

/** Whether a trail the CLI listed for the filter's status also passes the rest of the filter. */
fun inTrailFilter(trail: TrailSummary, filter: TrailFilter): Boolean = filter.phase.isNullOrEmpty() || trail.phase == filter.phase

/** The trails read so far for one status, and where the CLI's list continues. */
open class TrailPages {
    /** Most recent activity first, as the CLI lists them. */
    val trails: MutableList<TrailSummary> = ArrayList()

    /** Present while the CLI has more trails to list. */
    var nextPageToken: String? = null

    /** How many trails the CLI has for the status in all, when it says. */
    var total: Long? = null
}

/** Adds a page to the trails read so far, and returns how many of its trails were new. */
fun addTrailPage(list: TrailPages, page: TrailPage): Int {
    // A trail moves to the front when it sees activity, so it can turn up again on a later page.
    val known = list.trails.mapTo(HashSet()) { it.number }
    val fresh = page.trails.filter { it.number !in known }
    list.trails.addAll(fresh)
    list.nextPageToken = page.nextPageToken?.ifEmpty { null }
    if (page.total != null) list.total = page.total
    return fresh.size
}

/**
 * Pages one read may ask for. A phase can be rare among the open trails, and
 * without a limit a filter on it would read every page there is in one go.
 */
const val MAX_TRAIL_PAGES_PER_READ = 4

/**
 * Reads further pages until `enough()` says the list holds what is wanted,
 * the CLI has no more, or `maxPages` have been read. `fetch` returns null
 * when a page could not be read, and the result is then false.
 */
suspend fun readTrailPages(
    list: TrailPages,
    fetch: suspend (pageToken: String) -> TrailPage?,
    enough: () -> Boolean,
    maxPages: Int = MAX_TRAIL_PAGES_PER_READ,
    onPage: () -> Unit = {},
): Boolean {
    var read = 0
    while (read < maxPages && !enough()) {
        val token = list.nextPageToken ?: break
        val page = fetch(token) ?: return false
        val added = addTrailPage(list, page)
        // A page that brings nothing new and points back at itself would repeat without end.
        if (added == 0 && list.nextPageToken == token) list.nextPageToken = null
        onPage()
        read += 1
    }
    return true
}

/** The CLI's `--project` argument for a repository: `gh/<owner>` or `et/<project>`. */
fun trailProject(repo: EntireRepo): String = "${repo.forge}/${repo.owner}"

/** The CLI's `--repo` argument for a repository. */
fun trailRepo(repo: EntireRepo): String = "${repo.forge}/${repo.owner}/${repo.repo}"

/** A trail's ID on Entire is a ULID: 26 characters of Crockford base 32. */
private val TRAIL_ID = Regex("[0-9A-HJKMNP-TV-Z]{26}")

fun isTrailId(value: String?): Boolean = value != null && TRAIL_ID.matches(value)

/**
 * How a CLI call names a trail. Given a number, the CLI first looks up the
 * trail's ID, which costs a request to entire.io. A call that already has
 * the ID passes it and skips that lookup.
 */
data class TrailRef(
    /** The trail's number in its project. */
    val number: Long,
    val id: String? = null,
)

/**
 * What a timeline call reads: one change, or a trail, for which the CLI
 * picks the change. Its pick is certain only for a trail with one change:
 * with several open ones it exits with an error, and its reply does not say
 * which change it read.
 */
sealed interface TimelineOf {
    /** A change, named the way the CLI takes it: `<repository>/<number>`. */
    data class Change(val repository: String, val number: Long) : TimelineOf

    data class Trail(val trail: TrailRef) : TimelineOf
}

fun changeRef(change: TrailChange): TimelineOf.Change = TimelineOf.Change(change.repository, change.number)

fun sameChange(a: TimelineOf.Change, b: TrailChange): Boolean = a.repository == b.repository && a.number == b.number

fun sameChange(a: TrailChange, b: TrailChange): Boolean = a.repository == b.repository && a.number == b.number

/** Whether a change is a branch of this repository. A trail can span several. */
fun isInRepo(change: TrailChange, repo: EntireRepo): Boolean = change.repository.lowercase() == repo.repo.lowercase()

/** Whether a change is the branch that is checked out in this repository. */
fun isCheckedOut(change: TrailChange, repo: EntireRepo, branch: String?): Boolean =
    branch != null && change.branch == branch && isInRepo(change, repo)

/**
 * Position of the change that stands for a trail in one repository: the one
 * on the checked-out branch, then the first one in the repository, then the
 * first one. -1 when the trail has no changes.
 */
fun primaryChange(changes: List<TrailChange>, repo: EntireRepo, branch: String?): Int {
    val here = changes.indices.filter { isInRepo(changes[it], repo) }
    val current = if (branch.isNullOrEmpty()) null else here.firstOrNull { changes[it].branch == branch }
    if (current != null) return current
    here.firstOrNull()?.let { return it }
    return if (changes.isNotEmpty()) 0 else -1
}

/** How a gate or a CI run stands, reduced to what the views draw. */
enum class Outcome(val key: String) { PASSED("passed"), FAILED("failed"), PENDING("pending"), SKIPPED("skipped") }

/** A status the CLI does not report as settled counts as pending. */
fun gateOutcome(gate: TrailGate): Outcome =
    when (gate.status) {
        "passed" -> Outcome.PASSED
        "failed" -> Outcome.FAILED
        "skipped" -> Outcome.SKIPPED
        else -> Outcome.PENDING
    }

fun checkOutcome(run: TrailCheckRun): Outcome {
    if (run.status != "completed") return Outcome.PENDING
    return when (run.conclusion) {
        "success" -> Outcome.PASSED
        "skipped", "neutral" -> Outcome.SKIPPED
        else -> Outcome.FAILED
    }
}

private val SEPARATORS = Regex("[_-]+")

/** `in_progress` becomes "in progress". */
fun words(value: String): String = value.replace(SEPARATORS, " ").jsTrim()

internal fun sentenceCase(value: String): String {
    val text = words(value)
    return text.take(1).uppercase(Locale.ROOT) + text.drop(1)
}

private val GATE_LABELS = mapOf(
    "approvals" to "Approvals",
    "checks" to "Checks",
    "findings" to "Findings",
    "up_to_date" to "Up to date with base",
)

/** A gate's name as the panel shows it. A gate this plugin does not know keeps its key, spelled out. */
fun gateLabel(key: String): String = GATE_LABELS[key] ?: sentenceCase(key)

/** What a CI run is doing or how it ended, in words. */
fun checkDetail(run: TrailCheckRun): String =
    if (run.status == "completed") words(run.conclusion ?: "completed") else words(run.status ?: "pending")

private val OUTCOME_ORDER = listOf(Outcome.FAILED, Outcome.PENDING, Outcome.PASSED, Outcome.SKIPPED)

/** "1 failed, 2 pending, 20 passed", leaving out the outcomes that did not occur. */
fun outcomeSummary(outcomes: List<Outcome>): String =
    OUTCOME_ORDER.mapNotNull { outcome ->
        val count = outcomes.count { it == outcome }
        if (count > 0) "$count ${outcome.key}" else null
    }.joinToString(", ")

/** One line that says what a timeline entry is. */
fun eventText(event: TrailEvent): String =
    when (event.kind) {
        "commit" -> event.title ?: "Commit"
        "trail_created" -> "Created the trail"
        "review_posted" -> when (val findings = event.findingCount) {
            null -> "Posted a review"
            0L -> "Posted a review with no findings"
            1L -> "Posted a review with 1 finding"
            else -> "Posted a review with ${String.format(Locale.US, "%,d", findings)} findings"
        }
        "finding_resolved" -> "Resolved a finding"
        // The CLI adds kinds between releases. An unknown one is shown by name.
        else -> event.title ?: sentenceCase(event.kind)
    }
