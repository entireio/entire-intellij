package io.entire.core.brain

import io.entire.core.json.jsEntries
import io.entire.core.json.jsTrim
import io.entire.core.json.stringOrNull
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The repository brain, which the entire-brain plugin builds and serves.
 *
 * The plugin is read for two things, both through the Entire CLI as `entire
 * brain …`: whether a repository has a brain, and where the plugin's local
 * graph page is once `entire brain viz` has started. The plugin is run
 * through the CLI and never by its own name, because the CLI finds the copy
 * it manages, which can be newer than one on `PATH`.
 */

/** `entire brain viz`, told not to open a browser itself and to pick a free port. */
val BRAIN_VIZ_ARGS: List<String> = listOf("brain", "viz", "--no-open", "--port", "0")

/** The plugin's background updater for this machine. */
enum class BrainUpdater { RUNNING, STOPPED, NOT_INSTALLED }

/** `entire brain status --json`, as far as the plugin uses it. */
data class BrainStatus(
    /**
     * `current` for a brain that is built, and `absent` for a repository that
     * has none. The plugin also reports `corrupt`, `unsafe`, and `unsupported`.
     */
    val manifestState: String,
    /** When the brain was last written, as an ISO time. */
    val generatedAt: String? = null,
    /** The size of the code graph, which is what `entire brain viz` draws. */
    val files: Long? = null,
    val symbols: Long? = null,
    val relations: Long? = null,
    /** Durable memories in the brain. The plugin's report calls them facts. */
    val memories: Long? = null,
    /** Exchanges from retained sessions that the brain has indexed. */
    val exchanges: Long? = null,
    val docsRecords: Long? = null,
    val docsFiles: Long? = null,
    /** The worst the plugin says of any part of the brain: `ok`, `degraded`, or `unsafe`. */
    val freshness: String? = null,
    /** The parts that are not fresh, each with the plugin's reason. */
    val staleParts: List<String> = emptyList(),
    val updater: BrainUpdater? = null,
    /** `connected` when the repository is bound to a hosted brain. */
    val hosted: String? = null,
    val hostedSynced: Boolean? = null,
    val warnings: List<String> = emptyList(),
)

private fun part(value: JsonElement?): JsonObject = value as? JsonObject ?: JsonObject(emptyMap())

/** A whole number that is not negative. */
private fun count(value: JsonElement?): Long? {
    val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return null
    if (!number.isFinite() || number < 0 || number % 1.0 != 0.0 || number > 9_007_199_254_740_991.0) return null
    return number.toLong()
}

/** A string with something in it besides space, trimmed. */
private fun text(value: JsonElement?): String? = value.stringOrNull()?.jsTrim()?.ifEmpty { null }

/** How bad a freshness word is. A word the plugin adds later counts as worse than `ok`. */
private fun badness(severity: String): Int = if (severity == "ok") 0 else if (severity == "unsafe") 2 else 1

private const val MAX_NOTES = 10
private const val NOTE_CHARS = 400

/** Reads the plugin's status report. Null when it does not say what state the brain is in. */
fun readBrainStatus(value: JsonElement?): BrainStatus? {
    val json = value as? JsonObject ?: return null
    val brain = json["brain"] as? JsonObject ?: return null
    val state = brain["manifest_state"].stringOrNull()?.ifEmpty { null } ?: return null

    val semantic = part(json["semantic"])
    val coverage = part(semantic["coverage"])
    val retrieval = part(json["retrieval"])

    // The code graph and the indexes each report their own freshness. The view shows the worst.
    var worst: String? = null
    val staleParts = ArrayList<String>()
    for (source in listOf(semantic, retrieval)) {
        val freshness = part(source["freshness"])
        val severity = text(freshness["severity"])
        if (severity != null && (worst == null || badness(severity) > badness(worst))) worst = severity
        for ((name, axis) in part(freshness["axes"]).jsEntries()) {
            val axisState = text(part(axis)["state"])
            if (axisState == null || axisState == "ok" || staleParts.size >= MAX_NOTES) continue
            val detail = text(part(axis)["detail"])
            staleParts.add("$name: ${detail ?: axisState}".take(NOTE_CHARS))
        }
    }

    val daemon = part(json["onboarding"])["daemon"] as? JsonObject
    val updater = when {
        daemon == null -> null
        isTrue(daemon["running"]) -> BrainUpdater.RUNNING
        isTrue(daemon["installed"]) -> BrainUpdater.STOPPED
        else -> BrainUpdater.NOT_INSTALLED
    }
    val hosted = part(json["hosted"])
    val warnings = ArrayList<String>()
    for (warning in json["warnings"] as? JsonArray ?: JsonArray(emptyList())) {
        val note = text(warning)
        if (note != null && warnings.size < MAX_NOTES) warnings.add(note.take(NOTE_CHARS))
    }

    return BrainStatus(
        manifestState = state,
        generatedAt = text(brain["generated_at"]),
        files = count(coverage["files"]),
        symbols = count(coverage["symbols"]),
        relations = count(coverage["relations"]),
        memories = count(part(json["facts"])["facts"]),
        exchanges = count(part(retrieval["conversation"])["exchanges"]),
        docsRecords = count(retrieval["docs_records"]),
        docsFiles = count(retrieval["docs_files"]),
        freshness = worst,
        staleParts = staleParts,
        updater = updater,
        hosted = text(hosted["state"]),
        hostedSynced = truth(hosted["synced"]),
        warnings = warnings,
    )
}

private fun truth(value: JsonElement?): Boolean? =
    when ((value as? JsonPrimitive)?.takeIf { !it.isString }?.content) {
        "true" -> true
        "false" -> false
        else -> null
    }

private fun isTrue(value: JsonElement?): Boolean = truth(value) == true

/** What a line of the Brain tab is about. */
enum class BrainDetailKey { STATE, GRAPH, MEMORIES, CONVERSATIONS, DOCS, FRESHNESS, UPDATES, HOSTED, WARNING }

/** One line of the Brain tab: a fact about the brain of a repository. */
data class BrainDetail(
    val key: BrainDetailKey,
    val label: String,
    val value: String,
    /** More than fits on the line. */
    val tooltip: String? = null,
    /** The line reports something that needs a look. */
    val attention: Boolean = false,
)

private fun number(value: Long): String = String.format(Locale.US, "%,d", value)

private fun plural(value: Long, one: String, many: String): String = "${number(value)} ${if (value == 1L) one else many}"

private val SEPARATORS = Regex("[_-]+")

private fun sentence(value: String): String {
    val words = value.replace(SEPARATORS, " ").jsTrim()
    return words.take(1).uppercase(Locale.ROOT) + words.drop(1)
}

/**
 * What the Brain tab lists for a repository that has a brain, in the order
 * it lists it. `updated` is the age of the brain in words, such as "2h ago",
 * which the caller works out.
 */
fun brainDetails(status: BrainStatus, updated: String = ""): List<BrainDetail> {
    val details = ArrayList<BrainDetail>()
    val built = status.manifestState == "current"
    details.add(
        BrainDetail(
            key = BrainDetailKey.STATE,
            label = "Brain",
            value = listOf(if (built) "Built" else sentence(status.manifestState), if (updated.isNotEmpty()) "updated $updated" else "")
                .filter { it.isNotEmpty() }.joinToString(" · "),
            tooltip = if (built) null else "Run 'entire brain status' in a terminal for the details.",
            attention = !built,
        ),
    )

    val graph = listOfNotNull(
        status.files?.let { plural(it, "file", "files") },
        status.symbols?.let { plural(it, "symbol", "symbols") },
        status.relations?.let { plural(it, "relation", "relations") },
    )
    if (graph.isNotEmpty()) details.add(BrainDetail(BrainDetailKey.GRAPH, "Code graph", graph.joinToString(" · ")))
    status.memories?.let { details.add(BrainDetail(BrainDetailKey.MEMORIES, "Memories", number(it))) }
    status.exchanges?.let { details.add(BrainDetail(BrainDetailKey.CONVERSATIONS, "Conversations", plural(it, "exchange", "exchanges"))) }
    status.docsRecords?.let { records ->
        val from = status.docsFiles?.let { " from ${plural(it, "file", "files")}" }.orEmpty()
        details.add(BrainDetail(BrainDetailKey.DOCS, "Docs", "${plural(records, "record", "records")}$from"))
    }

    status.freshness?.let { freshness ->
        val fresh = freshness == "ok"
        details.add(
            BrainDetail(
                key = BrainDetailKey.FRESHNESS,
                label = "Freshness",
                value = if (fresh) "Up to date" else sentence(freshness),
                tooltip = if (status.staleParts.isNotEmpty()) status.staleParts.joinToString("\n") else null,
                attention = !fresh,
            ),
        )
    }
    status.updater?.let { updater ->
        details.add(
            BrainDetail(
                key = BrainDetailKey.UPDATES,
                label = "Updates",
                value = when (updater) {
                    BrainUpdater.RUNNING -> "Running in the background"
                    BrainUpdater.STOPPED -> "Not running"
                    BrainUpdater.NOT_INSTALLED -> "Not installed"
                },
                attention = updater == BrainUpdater.STOPPED,
            ),
        )
    }
    status.hosted?.let { hosted ->
        val connected = hosted == "connected"
        val synced = if (!connected || status.hostedSynced == null) "" else if (status.hostedSynced) " · synced" else " · not synced"
        val state = if (connected) "Connected" else if (hosted == "disconnected") "Not connected" else sentence(hosted)
        details.add(BrainDetail(BrainDetailKey.HOSTED, "Hosted brain", "$state$synced"))
    }
    for (warning in status.warnings) {
        details.add(BrainDetail(BrainDetailKey.WARNING, "Warning", warning, tooltip = warning, attention = true))
    }
    return details
}

/** Whether the repository has a brain to show. Any state but `absent` has something on disk. */
fun hasBrain(status: BrainStatus): Boolean = status.manifestState != "absent"

private val VIZ_ADDRESS = Regex("http://127\\.0\\.0\\.1:(\\d{1,5})(?!\\d)")

/**
 * The address of the plugin's graph page, from the line it prints once it is
 * listening, which ends in `http://127.0.0.1:49316`.
 *
 * The plugin binds to the loopback address only. The address returned here is
 * rebuilt from the port number, so whatever else the line holds, the plugin
 * opens a page on this machine and nothing else.
 */
fun parseVizUrl(line: String): String? {
    val port = VIZ_ADDRESS.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return if (port in 1..65535) "http://127.0.0.1:$port" else null
}

/**
 * Whether the CLI's complaint means a plugin, entire-brain unless `plugin`
 * names another, is not installed. Without the plugin, its name is not a
 * command the CLI knows: it prints `unknown command "brain …"`, and newer
 * versions name the plugin and how to install it.
 */
fun isPluginMissing(output: String, plugin: String = "brain"): Boolean {
    val name = Regex.escape(plugin)
    return Regex("unknown command \"$name\\b|entire-$name plugin is not installed|entire plugin install $name", RegexOption.IGNORE_CASE)
        .containsMatchIn(output)
}
