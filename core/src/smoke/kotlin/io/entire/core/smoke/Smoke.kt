package io.entire.core.smoke

import io.entire.core.CallResult
import io.entire.core.checkpoint.CheckpointIndex
import io.entire.core.checkpoint.CheckpointStore
import io.entire.core.checkpoint.LIST_LIMIT
import io.entire.core.checkpoint.PAGE_SIZE
import io.entire.core.checkpoint.loadPage
import io.entire.core.brain.BrainUpdater
import io.entire.core.brain.brainDetails
import io.entire.core.brain.hasBrain
import io.entire.core.brain.isPluginMissing
import io.entire.core.brain.parseVizUrl
import io.entire.core.cli.EntireCli
import io.entire.core.cli.TrailChange
import io.entire.core.cli.TrailScope
import io.entire.core.cli.TrailSummary
import io.entire.core.cli.LocateDeps
import io.entire.core.cli.REMOTE_SESSIONS_PAGE
import io.entire.core.cli.isSupportedVersion
import io.entire.core.cli.locateCli
import io.entire.core.cli.readSessionRows
import io.entire.core.cli.totalTokens
import io.entire.core.exec.ProcessExec
import io.entire.core.format.formatPosition
import io.entire.core.format.relativeTime
import io.entire.core.git.Git
import io.entire.core.json.parseJson
import io.entire.core.json.stringify
import io.entire.core.protocol.toJson
import io.entire.core.sched.Lane
import io.entire.core.trails.TrailListStatus
import io.entire.core.trails.TrailRef
import io.entire.core.trails.changeRef
import io.entire.core.trails.checkDetail
import io.entire.core.trails.checkOutcome
import io.entire.core.trails.eventText
import io.entire.core.trails.gateLabel
import io.entire.core.trails.gateOutcome
import io.entire.core.trails.isCheckedOut
import io.entire.core.trails.isInRepo
import io.entire.core.trails.noTrailsText
import io.entire.core.trails.outcomeSummary
import io.entire.core.trails.primaryChange
import io.entire.core.trails.readTrailFilter
import io.entire.core.trails.trailFilterKey
import io.entire.core.trails.trailFilterLabel
import io.entire.core.trails.trailFilters
import io.entire.core.trails.trailProject
import io.entire.core.trails.trailRepo
import io.entire.core.transcript.FullBlock
import io.entire.core.transcript.parseCliText
import io.entire.core.transcript.parseCompactTranscript
import io.entire.core.transcript.toWireTurn
import io.entire.core.transcript.toolInputText
import io.entire.core.web.parseEntireRemote
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Prints what the plugin would know about a real repository, one fact to a
 * line and in a fixed order, so that the output can be compared with the same
 * facts read by the VS Code extension's own code.
 *
 * Usage: Smoke <repository> [now-in-milliseconds]
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: Smoke <repository> [now-in-milliseconds]")
        exitProcess(2)
    }
    val now = args.getOrNull(1)?.toLongOrNull() ?: System.currentTimeMillis()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val code = runBlocking { smoke(args[0], now, scope) }
    scope.cancel()
    exitProcess(code)
}

/** How many checkpoints are read in full: their detail, their stored transcript, and their author. */
private const val DETAILS = 12

private fun sha(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

private suspend fun smoke(folder: String, now: Long, scope: CoroutineScope): Int {
    val git = Git(ProcessExec, Lane(4, scope))
    val paths = when (val result = git.repoPaths(folder)) {
        is CallResult.Ok -> result.value
        is CallResult.Failed -> {
            println("notGit ${result.failure.message}")
            return 1
        }
    }
    println("root=${paths.root}")
    println("gitDir=${paths.gitDir}")
    println("commonDir=${paths.commonDir}")
    println("branch=${git.branch(paths.root).orEmpty()}")

    val located = locateCli(null, LocateDeps(System.getenv(), windows = false, homeDir = System.getProperty("user.home")))
    if (located == null) {
        println("cliMissing")
        return 1
    }
    val cli = EntireCli(located.path, ProcessExec, Lane(4, scope))
    val version = when (val result = cli.version(paths.root)) {
        is CallResult.Ok -> result.value
        is CallResult.Failed -> {
            println("cliMissing ${result.failure.message}")
            return 1
        }
    }
    println("cli=${version.raw} supported=${isSupportedVersion(version)}")

    when (val status = cli.status(paths.root)) {
        is CallResult.Ok -> println(
            "status enabled=${status.value.enabled} agents=${status.value.agents.joinToString(",")} active=${status.value.activeSessions.size}",
        )
        is CallResult.Failed -> println("status failed=${status.failure.kind} ${status.failure.message}")
    }

    val tip = git.head(paths.root)
    if (tip == null) {
        println("head=none")
        return 0
    }
    val index = CheckpointIndex(tip)
    val read = loadPage(index, PAGE_SIZE) { skip, count ->
        (git.logWindow(paths.root, tip, skip, count) as? CallResult.Ok)?.value
    }
    println("index read=$read scanned=${index.scanned} exhausted=${index.exhausted} linked=${index.commits.size}")

    when (val rows = cli.listCheckpoints(paths.root, LIST_LIMIT)) {
        is CallResult.Ok -> index.setRows(rows.value)
        is CallResult.Failed -> println("rows failed=${rows.failure.kind} ${rows.failure.message}")
    }
    println("rows=${index.allRows().size} unlinked=${index.unlinkedRows().size} sessions=${index.sessions().size}")

    for (commit in index.commits) {
        val sole = commit.checkpointIds.singleOrNull()
        val row = sole?.let { index.row(it) }
        val sessions = if (row == null) 0 else row.sessionCount ?: row.sessionIds.size
        val position = if (sole != null && sessions == 1) row?.sessionId?.let { index.position(it, sole) } else null
        println(
            listOf(
                "commit",
                commit.sha,
                commit.shortSha,
                commit.authorTime.toString(),
                commit.author,
                commit.subject,
                commit.checkpointIds.joinToString(","),
                row?.agent.orEmpty(),
                sessions.toString(),
                position?.let { formatPosition(it.ordinal, it.total, it.mayHaveEarlier) }.orEmpty(),
                relativeTime(commit.authorTime * 1000, now),
            ).joinToString("\t"),
        )
    }
    val store = CheckpointStore(git)
    var textViewRead = false
    for (commit in index.commits.filter { it.checkpointIds.size == 1 }.take(DETAILS)) {
        val id = commit.checkpointIds.single()
        when (val detail = cli.explainCheckpoint(paths.root, id)) {
            is CallResult.Failed -> println("detail\t$id\tfailed")
            is CallResult.Ok -> {
                val value = detail.value
                println(
                    listOf("detail", id, value.sessions.size, value.filesTouched.size, value.partial, value.promptCount ?: "", value.branch.orEmpty())
                        .joinToString("\t"),
                )
                for (session in value.sessions) {
                    println(
                        listOf(
                            "dsession", session.index, session.sessionId.orEmpty(), session.agent.orEmpty(), session.model.orEmpty(),
                            session.kind.orEmpty(), session.createdAt.orEmpty(), session.filesTouched.size,
                            session.tokenUsage?.let { totalTokens(it) } ?: "", sha(session.summary?.intent.orEmpty()), sha(session.summary?.outcome.orEmpty()),
                        ).joinToString("\t"),
                    )
                }
            }
        }
        val tree = store.locate(paths.root, id)
        println(listOf("store", id, tree?.treeish ?: "none", tree?.prefix.orEmpty()).joinToString("\t"))
        if (tree != null) {
            for (slot in store.sessions(paths.root, tree)) {
                println(listOf("slot", slot.index, slot.compactTranscriptPath.orEmpty(), slot.metadataPath.orEmpty()).joinToString("\t"))
                val meta = store.sessionMeta(paths.root, tree, slot)
                println(listOf("meta", slot.index, meta?.sessionId.orEmpty(), meta?.compactTranscriptStart ?: "").joinToString("\t"))
                val text = store.compactTranscript(paths.root, tree, slot)
                if (text != null) {
                    val parsed = parseCompactTranscript(text, meta?.compactTranscriptStart)
                    val wire = parsed.turns.mapIndexed { position, turn -> stringify(toWireTurn(turn, position).toJson()) }.joinToString("\n")
                    val bodies = StringBuilder()
                    for (turn in parsed.turns) {
                        for (block in turn.blocks) {
                            if (block is FullBlock.Tool) bodies.append(toolInputText(block.input)).append('\u0000').append(block.output.orEmpty()).append('\u0001')
                        }
                    }
                    println(listOf("transcript", slot.index, parsed.turns.size, parsed.startTurn, sha(wire), sha(bodies.toString())).joinToString("\t"))
                }
                val sessionId = meta?.sessionId
                if (sessionId != null) {
                    val opening = store.openingPrompt(paths.root, tree, sessionId)
                    println(listOf("opening", slot.index, opening?.fromSessionStart ?: "", sha(opening?.text.orEmpty())).joinToString("\t"))
                }
            }
        }
        when (val author = cli.checkpointAuthor(paths.root, id)) {
            is CallResult.Ok -> println(listOf("author", id, author.value.orEmpty()).joinToString("\t"))
            is CallResult.Failed -> println("author\t$id\tfailed")
        }
        if (!textViewRead) {
            textViewRead = true
            when (val full = cli.fullText(paths.root, id)) {
                is CallResult.Ok -> {
                    val parsed = parseCliText(full.value)
                    val wire = parsed.turns.mapIndexed { position, turn -> stringify(toWireTurn(turn, position).toJson()) }.joinToString("\n")
                    println(listOf("textview", id, parsed.turns.size, sha(wire)).joinToString("\t"))
                }
                is CallResult.Failed -> println("textview\t$id\tfailed")
            }
        }
    }

    for (session in index.sessions().take(200)) {
        println(
            listOf(
                "session",
                session.sessionId,
                session.agent.orEmpty(),
                session.checkpoints.size.toString(),
                session.lastDate.orEmpty(),
                session.mayHaveEarlier.toString(),
                session.checkpoints.firstOrNull()?.checkpointId.orEmpty(),
                relativeTime(session.lastDate, now),
            ).joinToString("\t"),
        )
    }

    // A file of made-up rows, read the way `entire session list --json` is. The real command prunes old session state, so it is not run here.
    System.getenv("PARITY_SESSION_ROWS")?.let { file ->
        val rows = readSessionRows(parseJson(File(file).readText()))
        println("sessionrows ${rows?.size ?: "unreadable"}")
        for (row in rows.orEmpty()) {
            println(
                listOf(
                    "sessionrow", row.sessionId, row.agent.orEmpty(), row.model.orEmpty(), row.status.orEmpty(), row.kind.orEmpty(),
                    row.startedAt.orEmpty(), row.endedAt.orEmpty(), row.lastActive.orEmpty(), row.turns ?: "", row.pendingTurns ?: "",
                    row.lastCheckpointId.orEmpty(), row.tokens?.let { "${it.total}/${it.input}/${it.cacheRead}/${it.cacheWrite}/${it.output}" }.orEmpty(),
                    sha(row.lastPrompt.orEmpty()),
                ).joinToString("\t"),
            )
        }
    }

    // What Entire lists for the repository, read with the saved login. Only a repository that Entire hosts has such a list.
    if (parseEntireRemote(git.originUrl(paths.root)) != null) {
        when (val page = cli.remoteSessions(paths.root, REMOTE_SESSIONS_PAGE)) {
            is CallResult.Failed -> println("remote failed=${page.failure.kind.name.lowercase().replace("_", "")} ${page.failure.message}")
            is CallResult.Ok -> {
                println("remote sessions=${page.value.sessions.size} more=${page.value.nextCursor != null}")
                for (session in page.value.sessions) {
                    println(
                        listOf(
                            "remotesession", session.sessionId, sha(session.name.orEmpty()), session.agent.orEmpty(), session.model.orEmpty(),
                            session.checkpointCount ?: "", session.startedAt.orEmpty(), session.lastActivityAt.orEmpty(), sha(session.prompt.orEmpty()),
                        ).joinToString("\t"),
                    )
                }
                val first = page.value.sessions.firstOrNull { (it.checkpointCount ?: 0) in 1..40 }
                if (first != null) {
                    when (val listed = cli.remoteSessionCheckpoints(paths.root, first.sessionId)) {
                        is CallResult.Failed -> println("remotecheckpoints failed=${listed.failure.kind.name.lowercase().replace("_", "")}")
                        is CallResult.Ok -> for (checkpoint in listed.value) {
                            println(
                                listOf("remotecheckpoint", checkpoint.checkpointId, checkpoint.commitSha.orEmpty(), sha(checkpoint.message.orEmpty()), checkpoint.date.orEmpty())
                                    .joinToString("\t"),
                            )
                        }
                    }
                }
            }
        }
    }
    // Trails are read from Entire with the saved login, for a repository that Entire hosts.
    val hosted = parseEntireRemote(git.originUrl(paths.root))
    if (hosted != null) {
        val branch = git.branch(paths.root)
        val scope = TrailScope(trailProject(hosted), trailRepo(hosted))
        fun changeLine(change: TrailChange): String =
            listOf(
                change.repository, change.number, change.branch.orEmpty(), change.base.orEmpty(), change.status.orEmpty(), change.hasCodeChanges,
                change.updatedAt.orEmpty(), sha(change.title.orEmpty()), change.url.orEmpty(),
            ).joinToString("/")
        val seen = ArrayList<String?>()
        var listed = emptyList<TrailSummary>()
        for (status in listOf(TrailListStatus.OPEN, TrailListStatus.CLOSED, TrailListStatus.ALL)) {
            when (val page = cli.listTrails(paths.root, scope, status, PAGE_SIZE)) {
                is CallResult.Failed -> println("trails ${status.key} failed=${kind(page)} ${page.failure.message}")
                is CallResult.Ok -> {
                    println("trails ${status.key} count=${page.value.trails.size} more=${page.value.nextPageToken != null} total=${page.value.total ?: ""}")
                    for (trail in page.value.trails) {
                        seen.add(trail.phase)
                        println(
                            listOf(
                                "trail", status.key, trail.number, trail.id.orEmpty(), sha(trail.title), sha(trail.body.orEmpty()), trail.status.orEmpty(),
                                trail.phase.orEmpty(), trail.type.orEmpty(), trail.priority.orEmpty(), trail.assignees.joinToString(","), trail.createdAt.orEmpty(),
                                trail.lastActivityAt.orEmpty(), trail.closedAt.orEmpty(), primaryChange(trail.changes, hosted, branch),
                                trail.changes.joinToString(" ") { changeLine(it) },
                            ).joinToString("\t"),
                        )
                    }
                    if (status == TrailListStatus.ALL) listed = page.value.trails
                }
            }
        }
        for (filter in trailFilters(seen)) {
            val back = readTrailFilter(trailFilterKey(filter))
            println(listOf("filter", trailFilterKey(filter), trailFilterLabel(filter), noTrailsText(filter), back?.let { trailFilterKey(it) } ?: "unreadable").joinToString("\t"))
        }
        for (key in listOf("open:", "closed:building", "open:Building", "open:a:b", "nope", "")) {
            println("filterkey\t$key\t${if (readTrailFilter(key) != null) "read" else "refused"}")
        }
        // Three trails are read in full: the most recent, one with several changes, and one that is closed.
        val chosen = listOfNotNull(listed.firstOrNull(), listed.firstOrNull { it.changes.size > 1 }, listed.firstOrNull { it.status == "closed" }).distinct()
        for (trail in chosen) {
            val read = when (val detail = cli.showTrail(paths.root, scope.project, TrailRef(trail.number, trail.id))) {
                is CallResult.Failed -> {
                    println("traildetail ${trail.number} failed=${kind(detail)}")
                    continue
                }
                is CallResult.Ok -> detail.value
            }
            println(listOf("traildetail", read.number, read.id.orEmpty(), sha(read.title), read.url.orEmpty(), read.projectName.orEmpty(), read.changes.size).joinToString("\t"))
            for (change in read.changes) {
                println(listOf("change", changeLine(change), isInRepo(change, hosted), isCheckedOut(change, hosted, branch)).joinToString("\t"))
                val merge = change.mergeability ?: continue
                println(
                    listOf(
                        "merge", merge.mergeable, merge.headSha.orEmpty(), merge.conflictStatus.orEmpty(), merge.checksAvailability.orEmpty(),
                        outcomeSummary(merge.checks.map { checkOutcome(it) }),
                    ).joinToString("\t"),
                )
                for (gate in merge.gates) {
                    println(
                        listOf("gate", gate.key, gateLabel(gate.key), gateOutcome(gate).key, gate.blocking, gate.status.orEmpty(), sha(gate.rationale.orEmpty()), gate.findingCount ?: "")
                            .joinToString("\t"),
                    )
                }
                for (run in merge.checks) {
                    println(listOf("check", sha(run.name), checkOutcome(run).key, checkDetail(run), run.appName.orEmpty(), run.detailsUrl != null).joinToString("\t"))
                }
            }
            val change = read.changes.getOrNull(primaryChange(read.changes, hosted, branch)) ?: continue
            val timeline = when (val answer = cli.trailTimeline(paths.root, scope.project, changeRef(change), 50)) {
                is CallResult.Failed -> {
                    println("timeline ${read.number} failed=${kind(answer)}")
                    continue
                }
                is CallResult.Ok -> answer.value
            }
            println(listOf("timeline", read.number, timeline.events.size, timeline.nextPageToken != null, timeline.commitCount ?: "", timeline.incomplete).joinToString("\t"))
            for (event in timeline.events) {
                println(
                    listOf(
                        "event", event.kind, sha(eventText(event)), event.actor.orEmpty(), event.occurredAt.orEmpty(), event.commitSha.orEmpty(), event.additions ?: "",
                        event.deletions ?: "", event.filesChanged ?: "", event.checkpointCount ?: "", event.agents.joinToString(","), event.findingCount ?: "",
                    ).joinToString("\t"),
                )
            }
        }
    }

    // What the entire-brain plugin reports about the repository's brain. It reads local state only.
    when (val brain = cli.brainStatus(paths.root)) {
        is CallResult.Failed -> println("brain failed=${kind(brain)}")
        is CallResult.Ok -> {
            val report = brain.value
            val updater = when (report.updater) {
                BrainUpdater.RUNNING -> "running"
                BrainUpdater.STOPPED -> "stopped"
                BrainUpdater.NOT_INSTALLED -> "notInstalled"
                null -> ""
            }
            println(
                listOf(
                    "brain", report.manifestState, hasBrain(report), report.generatedAt.orEmpty(), report.files ?: "", report.symbols ?: "", report.relations ?: "",
                    report.memories ?: "", report.exchanges ?: "", report.docsRecords ?: "", report.docsFiles ?: "", report.freshness.orEmpty(), updater,
                    report.hosted.orEmpty(), report.hostedSynced ?: "", sha(report.staleParts.joinToString("\n")), sha(report.warnings.joinToString("\n")),
                ).joinToString("\t"),
            )
            for (detail in brainDetails(report, relativeTime(report.generatedAt, now))) {
                println(listOf("braindetail", detail.key.name.lowercase(), detail.label, detail.value, sha(detail.tooltip.orEmpty()), detail.attention).joinToString("\t"))
            }
        }
    }
    val lines = listOf(
        "entire brain viz - http://127.0.0.1:49316", "http://127.0.0.1:0", "http://127.0.0.1:123456", "http://localhost:8080", "x http://127.0.0.1:65535 y",
        "http://127.0.0.1:00080",
    )
    for (line in lines) println("viz\t${parseVizUrl(line).orEmpty()}")
    val complaints = listOf(
        "Error: unknown command \"brain\" for \"entire\"", "The entire-brain plugin is not installed.", "run entire plugin install brain",
        "unknown command \"brainstorm\"", "something else",
    )
    for (text in complaints) println("pluginmissing\t${isPluginMissing(text)}")
    return 0
}

/** A failure's kind, spelled the way the reference program prints it. */
private fun kind(failed: CallResult.Failed): String = failed.failure.kind.name.lowercase().replace("_", "")
