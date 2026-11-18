package io.entire.core.cli

import io.entire.core.CallResult
import io.entire.core.Failure
import io.entire.core.FailureKind
import io.entire.core.brain.BRAIN_VIZ_ARGS
import io.entire.core.brain.BrainStatus
import io.entire.core.brain.isPluginMissing
import io.entire.core.brain.parseVizUrl
import io.entire.core.brain.readBrainStatus
import io.entire.core.exec.Exec
import io.entire.core.exec.ExecOptions
import io.entire.core.exec.ExecResult
import io.entire.core.exec.RunningServer
import io.entire.core.exec.startServer
import io.entire.core.fail
import io.entire.core.git.isCheckpointId
import io.entire.core.ok
import io.entire.core.sched.Lane
import io.entire.core.sched.Priority
import io.entire.core.trails.TimelineOf
import io.entire.core.trails.TrailListStatus
import io.entire.core.trails.TrailRef
import io.entire.core.trails.isTrailId
import io.entire.core.transcript.parseCheckpointAuthor
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Typed, read-only calls of the Entire CLI.
 *
 * Nothing here creates, edits, or deletes anything in the repository or on
 * Entire. [isReadCommand] names every command this class may start, and a
 * command outside that list is refused before a process exists.
 */
class EntireCli(
    val binPath: String,
    private val exec: Exec,
    private val lane: Lane,
    private val environment: () -> Map<String, String> = { System.getenv() },
    private val log: (String) -> Unit = {},
) {
    private suspend fun run(
        cwd: String,
        args: List<String>,
        timeoutMs: Long,
        priority: Priority = Priority.USER,
        dedupe: Boolean = false,
        maxBytes: Long? = null,
        stdoutFile: Path? = null,
    ): ExecResult {
        check(isReadCommand(args)) { "The plugin does not run: entire ${args.joinToString(" ")}" }
        return lane.run(priority = priority, key = if (dedupe) cwd + "\u0000" + args.joinToString("\u0000") else null) {
            val started = System.nanoTime()
            val result = exec.run(
                binPath,
                args,
                ExecOptions(
                    cwd = cwd,
                    timeoutMs = timeoutMs,
                    env = environment() + mapOf("NO_COLOR" to "1", "GIT_TERMINAL_PROMPT" to "0"),
                    maxBytes = maxBytes,
                    stdoutFile = stdoutFile,
                ),
            )
            val outcome = if (result.timedOut) "timed out" else "exit ${result.code}"
            log("entire ${args.joinToString(" ")} ($outcome, ${(System.nanoTime() - started) / 1_000_000} ms)")
            val stderr = result.stderr.trim()
            if (stderr.isNotEmpty()) log("  stderr: " + stderr.split('\n').take(6).joinToString("\n          "))
            result
        }
    }

    suspend fun version(cwd: String): CallResult<CliVersion> {
        val result = run(cwd, listOf("version"), timeoutMs = 5000)
        processFailure(result, "check the Entire CLI version")?.let { return CallResult.Failed(it) }
        val version = parseCliVersion(result.text())
        return if (version != null) ok(version) else fail(FailureKind.UNEXPECTED_OUTPUT, "The Entire CLI did not report a version.", result.text())
    }

    /** Local only, with no side effects. Safe to call on every refresh. */
    suspend fun status(cwd: String): CallResult<StatusInfo> {
        val result = run(cwd, listOf("status", "--json"), timeoutMs = 10_000, dedupe = true)
        return jsonResult(result, "read the Entire status", ::readStatus)
    }

    /**
     * Rows for the current branch. Slow when the CLI has remote checkpoints to
     * fetch, so it runs at background priority and nothing should wait for it
     * before drawing.
     */
    suspend fun listCheckpoints(cwd: String, limit: Int): CallResult<List<CheckpointRow>> {
        val result = run(
            cwd,
            listOf("checkpoint", "explain", "--json", "--limit", limit.toString(), "--no-pager"),
            timeoutMs = 90_000,
            priority = Priority.BACKGROUND,
            dedupe = true,
        )
        return jsonResult(result, "list checkpoints", ::readCheckpointRows)
    }

    suspend fun explainCheckpoint(cwd: String, checkpointId: String): CallResult<CheckpointDetail> {
        if (!isCheckpointId(checkpointId)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a checkpoint ID.")
        // --checkpoint forces the argument to be read as an ID. A 12-hex ID is
        // otherwise indistinguishable from a commit SHA prefix.
        val result = run(cwd, listOf("checkpoint", "explain", "--checkpoint", checkpointId, "--json", "--no-pager"), timeoutMs = 45_000)
        return jsonResult(result, "read the checkpoint", ::readCheckpointDetail)
    }

    /** The CLI's own text rendering. It covers only the latest session of the checkpoint. */
    suspend fun fullText(cwd: String, checkpointId: String): CallResult<String> {
        if (!isCheckpointId(checkpointId)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a checkpoint ID.")
        val result = run(
            cwd,
            listOf("checkpoint", "explain", "--checkpoint", checkpointId, "--full", "--no-pager"),
            timeoutMs = 60_000,
            maxBytes = TRANSCRIPT_MAX_BYTES,
        )
        processFailure(result, "read the transcript")?.let { return CallResult.Failed(it) }
        return ok(result.text())
    }

    /**
     * Who the CLI names as the author of a checkpoint: the author of the commit
     * that carries it, or else of the commit that stored its data. The value is
     * null when the CLI names no one. The CLI's JSON leaves the author out, so
     * this reads the header of its short text view.
     */
    suspend fun checkpointAuthor(cwd: String, checkpointId: String): CallResult<String?> {
        if (!isCheckpointId(checkpointId)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a checkpoint ID.")
        val result = run(cwd, listOf("checkpoint", "explain", "--checkpoint", checkpointId, "--short", "--no-pager"), timeoutMs = 45_000)
        processFailure(result, "read the checkpoint")?.let { return CallResult.Failed(it) }
        return ok(parseCheckpointAuthor(result.text()))
    }

    /**
     * Writes the stored transcript, in the agent's own format, to `file` and
     * returns its size in bytes. It goes to a file because the transcript of one
     * long session can run to hundreds of megabytes.
     */
    suspend fun rawTranscript(cwd: String, checkpointId: String, sessionIndex: Int, file: Path): CallResult<Long> {
        if (!isCheckpointId(checkpointId)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a checkpoint ID.")
        if (sessionIndex < 0) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a session index.")
        val result = run(
            cwd,
            listOf("checkpoint", "explain", "--checkpoint", checkpointId, "--transcript", "--session-index", sessionIndex.toString(), "--no-pager"),
            timeoutMs = RAW_TRANSCRIPT_TIMEOUT_MS,
            stdoutFile = file,
        )
        processFailure(result, "read the raw transcript")?.let { return CallResult.Failed(it) }
        return ok(result.bytes)
    }

    /** Has a side effect in the CLI: it prunes session state idle for more than seven days. */
    suspend fun sessionList(cwd: String): CallResult<List<SessionRow>> {
        val result = run(cwd, listOf("session", "list", "--json"), timeoutMs = 15_000, dedupe = true)
        return jsonResult(result, "list sessions", ::readSessionRows)
    }

    /**
     * Writes the live transcript of a local session, in the agent's own format,
     * to `file` and returns its size in bytes.
     */
    suspend fun sessionRawTranscript(cwd: String, sessionId: String, file: Path): CallResult<Long> {
        if (!SESSION_ID.matches(sessionId)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a session ID.")
        val result = run(cwd, listOf("session", "info", sessionId, "--transcript"), timeoutMs = RAW_TRANSCRIPT_TIMEOUT_MS, stdoutFile = file)
        processFailure(result, "read the session transcript")?.let { return CallResult.Failed(it) }
        return ok(result.bytes)
    }

    /**
     * One page of the repository's sessions as Entire lists them, on every
     * branch, most recent activity first. This is the list on the Sessions
     * page of entire.com, with the name Entire gave each session.
     *
     * The CLI works out which repository from the `origin` remote, and reads
     * the cell that hosts it.
     */
    suspend fun remoteSessions(cwd: String, limit: Int, cursor: String? = null): CallResult<RemoteSessionPage> {
        if (limit !in 1..200) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a page size.")
        if (cursor != null && !PAGE_TOKEN.matches(cursor)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a page token.")
        val result = run(cwd, listOf("api", "--to", "cell", remoteSessionsPath(limit, cursor)), timeoutMs = API_TIMEOUT_MS, dedupe = true)
        return apiResult(result, "list the sessions on Entire", ::readRemoteSessionPage, notFound = "Entire does not list sessions for this repository.")
    }

    /**
     * The checkpoints of one session as its page on entire.com lists them,
     * newest first. Entire sends them with the session's transcript, so this
     * is a slow call for a long session.
     */
    suspend fun remoteSessionCheckpoints(cwd: String, sessionId: String): CallResult<List<RemoteCheckpoint>> {
        if (!SESSION_ID.matches(sessionId)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a session ID.")
        val result = run(
            cwd,
            listOf("api", "--to", "cell", remoteSessionCheckpointsPath(sessionId)),
            timeoutMs = API_TRANSCRIPT_TIMEOUT_MS,
            maxBytes = API_TRANSCRIPT_MAX_BYTES,
            dedupe = true,
        )
        return apiResult(
            result,
            "list the checkpoints of the session on Entire",
            ::readRemoteCheckpoints,
            notFound = "Entire could not find this session.",
        )
    }

    /**
     * One page of what Entire's search service finds for a query in this
     * repository: commits, checkpoints, and sessions, best match first. It
     * reads Entire with the login the CLI has saved, and the CLI works out
     * the repository from the origin remote. The query goes after `--`, so
     * nothing in it is read as a flag. Filters such as `author:alice` work
     * inside it.
     */
    suspend fun search(cwd: String, query: String, limit: Int, page: Int): CallResult<SearchPage> {
        val text = query.trim()
        if (text.isEmpty() || text.length > 500 || text.any { it.isISOControl() }) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a search.")
        if (limit !in 1..100 || page !in 1..1000) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a page of results.")
        val result = run(cwd, searchArgs(text, limit, page), timeoutMs = SEARCH_TIMEOUT_MS)
        // Asked in a clone whose origin Entire cannot place, the CLI says so and prints nothing.
        if (result.text().isBlank() && NO_SEARCH_REPO.containsMatchIn(result.stderr)) {
            return fail(FailureKind.NO_ACCESS, "Entire cannot search this repository, because its origin is not a repository on Entire.", lastLines(result.stderr))
        }
        return jsonResult(result, "search", ::readSearchPage, SEARCH_FAILURES)
    }

    /** Whether a login is saved, and whose. Reads the CLI's own state. */
    suspend fun authStatus(cwd: String): CallResult<AuthStatus> {
        val result = run(cwd, AUTH_STATUS_ARGS, timeoutMs = 15_000, dedupe = true)
        return jsonResult(result, "read the login", ::readAuthStatus)
    }

    /** The graph plugin's version, which says that the plugin is installed. */
    suspend fun graphVersion(cwd: String): CallResult<String> {
        val result = run(cwd, GRAPH_VERSION_ARGS, timeoutMs = 15_000, dedupe = true)
        if (result.code != null && result.code != 0 && isPluginMissing(result.stderr, "graph")) {
            return fail(FailureKind.PLUGIN_MISSING, "The entire-graph plugin is not installed.", lastLines(result.stderr))
        }
        return jsonResult(result, "read the graph plugin's version", { str((it as? kotlinx.serialization.json.JsonObject)?.get("version")) })
    }

    /** The agents Entire can install hooks for, and which this repository has. */
    suspend fun agentList(cwd: String): CallResult<List<AgentChoice>> {
        val result = run(cwd, AGENT_LIST_ARGS, timeoutMs = 15_000, dedupe = true)
        processFailure(result, "list the agents")?.let { return CallResult.Failed(it) }
        return readAgentList(result.text())?.let { ok(it) }
            ?: fail(FailureKind.UNEXPECTED_OUTPUT, "The Entire CLI listed no agents.", result.text().take(400))
    }

    /**
     * Whether the repository has a brain, as the entire-brain plugin reports
     * it. Reads local state only. A call that is not shared stops when its
     * caller is cancelled.
     */
    suspend fun brainStatus(cwd: String, shared: Boolean = true): CallResult<BrainStatus> {
        val result = run(cwd, BRAIN_STATUS_ARGS, timeoutMs = 30_000, dedupe = shared)
        // Without the plugin the CLI does not know the command, and says so with its usage text.
        if (result.code != null && result.code != 0 && isPluginMissing(result.stderr)) {
            return fail(FailureKind.PLUGIN_MISSING, NO_BRAIN_PLUGIN, lastLines(result.stderr))
        }
        return jsonResult(result, "read the brain status", ::readBrainStatus)
    }

    /**
     * Starts `entire brain viz`, the plugin's graph page for a repository,
     * and returns its address once it is listening. The page is served on
     * the loopback address, from the brain on disk, until the caller stops it.
     *
     * It takes no lane: the lanes bound how many short calls run at once, and
     * this one stays up for as long as the page is in use.
     */
    suspend fun startBrainViz(cwd: String): CallResult<RunningServer> {
        check(isReadCommand(BRAIN_VIZ_ARGS)) { "The plugin does not run: entire ${BRAIN_VIZ_ARGS.joinToString(" ")}" }
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val command = "entire ${BRAIN_VIZ_ARGS.joinToString(" ")}"
        val server = startServer(
            binPath,
            BRAIN_VIZ_ARGS,
            cwd = cwd,
            env = environment() + mapOf("NO_COLOR" to "1", "GIT_TERMINAL_PROMPT" to "0"),
            ready = ::parseVizUrl,
            readyTimeoutMs = BRAIN_VIZ_READY_MS,
        )
        when (server) {
            is CallResult.Ok -> {
                log("$command (listening on ${server.value.url} after ${elapsed()} ms)")
                server.value.exited.invokeOnCompletion { log("$command (stopped)") }
                return server
            }
            is CallResult.Failed -> {
                val failure = server.failure
                log("$command (${failure.kind.name.lowercase()}, ${elapsed()} ms)")
                val detail = failure.detail.orEmpty()
                if (detail.isNotEmpty()) log("  stderr: " + lastLines(detail, 6).split('\n').joinToString("\n          "))
                return when (failure.kind) {
                    FailureKind.EXIT ->
                        if (isPluginMissing(detail)) {
                            fail(FailureKind.PLUGIN_MISSING, NO_BRAIN_PLUGIN, lastLines(detail))
                        } else {
                            fail(FailureKind.EXIT, "The Entire CLI could not start the brain graph.", lastLines(detail))
                        }
                    FailureKind.CLI_MISSING -> fail(FailureKind.CLI_MISSING, "The Entire CLI could not be started.", failure.detail)
                    FailureKind.TIMEOUT -> fail(FailureKind.TIMEOUT, "The Entire CLI took too long to start the brain graph.", failure.detail)
                    else -> server
                }
            }
        }
    }

    /**
     * One page of the trails that touch a repository, most recent activity
     * first: the open ones, the closed ones, or both. Those are the only ways
     * the CLI lists them. Trail calls read entire.io with the login the CLI
     * has saved.
     */
    suspend fun listTrails(cwd: String, scope: TrailScope, status: TrailListStatus, limit: Int, pageToken: String? = null): CallResult<TrailPage> {
        if (!TRAIL_PROJECT.matches(scope.project) || !TRAIL_REPO.matches(scope.repo)) {
            return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a repository on Entire.")
        }
        if (limit !in 1..100) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a page size.")
        if (pageToken != null && !PAGE_TOKEN.matches(pageToken)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a page token.")
        val args = ArrayList(listOf("trail", "list", "--project", scope.project, "--repo", scope.repo))
        // With no status the CLI lists every trail, open and closed.
        if (status != TrailListStatus.ALL) args.addAll(listOf("--status", if (status == TrailListStatus.CLOSED) "closed" else "open"))
        args.addAll(listOf("--limit", limit.toString(), "--json"))
        if (!pageToken.isNullOrEmpty()) args.add("--page-token=$pageToken")
        val result = run(cwd, args, timeoutMs = TRAIL_TIMEOUT_MS, dedupe = true)
        return jsonResult(result, "list trails", ::readTrailPage, TRAIL_FAILURES)
    }

    /**
     * One trail with its changes, their merge gates and CI runs, and its page
     * on the web. With `shared`, callers that ask for the same trail at once
     * wait for one call. A call that is not shared stops when its caller is
     * cancelled.
     */
    suspend fun showTrail(cwd: String, project: String, trail: TrailRef, shared: Boolean = true): CallResult<TrailDetail> {
        if (!TRAIL_PROJECT.matches(project)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a project on Entire.")
        val name = trailArg(trail) ?: return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a trail number.")
        val result = run(cwd, listOf("trail", "show", name, "--project", project, "--json"), timeoutMs = TRAIL_TIMEOUT_MS, dedupe = shared)
        return jsonResult(result, "read the trail", ::readTrailDetail, TRAIL_FAILURES)
    }

    /**
     * One page of the activity on a change, newest first.
     *
     * A change is named by its repository and its own number, which is not the
     * trail's number. Given a trail instead, the CLI picks the change, which a
     * caller can rely on only for a trail with one change. A later page is
     * asked for the way the first one was.
     */
    suspend fun trailTimeline(cwd: String, project: String, of: TimelineOf, limit: Int, pageToken: String? = null): CallResult<TrailTimeline> {
        if (!TRAIL_PROJECT.matches(project)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a project on Entire.")
        val name = when (of) {
            is TimelineOf.Trail -> trailArg(of.trail) ?: return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a trail number.")
            is TimelineOf.Change -> {
                if (!REPO_NAME.matches(of.repository)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a repository on Entire.")
                if (of.number !in 1..MAX_SAFE_INTEGER) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a change number.")
                "${of.repository}/${of.number}"
            }
        }
        if (limit !in 1..100) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a page size.")
        if (pageToken != null && !PAGE_TOKEN.matches(pageToken)) return fail(FailureKind.UNEXPECTED_OUTPUT, "That is not a page token.")
        val args = ArrayList(listOf("trail", "timeline", name, "--project", project, "--limit", limit.toString(), "--json"))
        if (!pageToken.isNullOrEmpty()) args.add("--page-token=$pageToken")
        val result = run(cwd, args, timeoutMs = TRAIL_TIMEOUT_MS)
        return jsonResult(result, "read the trail timeline", ::readTrailTimeline, TRAIL_FAILURES)
    }
}

/** The project and repository whose trails a call reads, as the CLI's `--project` and `--repo` arguments. */
data class TrailScope(val project: String, val repo: String)

private val BRAIN_STATUS_ARGS = listOf("brain", "status", "--json")
private val AUTH_STATUS_ARGS = listOf("auth", "status", "--json")
private val GRAPH_VERSION_ARGS = listOf("graph", "version", "--json")
private val AGENT_LIST_ARGS = listOf("agent", "list")

/** Search waits on the network and on the search service ranking its answer. */
private const val SEARCH_TIMEOUT_MS = 30_000L
private val NO_SEARCH_REPO = Regex("could not determine current repository", RegexOption.IGNORE_CASE)
private val SEARCH_FAILURES = FailureWording(remote = true, notFound = "Entire found nothing to search for this repository.")

/** `entire search` for one page, with the query after `--`. */
fun searchArgs(query: String, limit: Int, page: Int): List<String> =
    listOf("search", "--json", "--limit", limit.toString(), "--page", page.toString(), "--", query)

/** `entire brain viz` listens in well under a second. This leaves room for a slow disk. */
private const val BRAIN_VIZ_READY_MS = 20_000L
private const val NO_BRAIN_PLUGIN = "The entire-brain plugin is not installed."

/** Trail calls go to entire.io, so they wait on the network. */
private const val TRAIL_TIMEOUT_MS = 30_000L

/** An owner, project, or repository name. It cannot start with a dash, so it is never read as a flag. */
private const val NAME = "[A-Za-z0-9_.][A-Za-z0-9_.-]{0,99}"
private val TRAIL_PROJECT = Regex("(gh|et)/$NAME")
private val TRAIL_REPO = Regex("(gh|et)/$NAME/$NAME")
private val REPO_NAME = Regex(NAME)

/** The largest whole number the CLI's own language holds exactly. */
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val TRAIL_FAILURES = FailureWording(remote = true, notFound = "Entire could not find this trail.")

/** The argument that names a trail: its ID when the caller has it, which saves the CLI a lookup, or its number. */
private fun trailArg(trail: TrailRef): String? {
    if (trail.number !in 1..MAX_SAFE_INTEGER) return null
    return if (isTrailId(trail.id)) trail.id else trail.number.toString()
}

/** What the CLI takes as a session ID: a UUID, a ULID, or a name such as `ses_...`. */
private val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")

/** A page token is printable ASCII, and is passed as one argument. */
private val PAGE_TOKEN = Regex("[\\x21-\\x7e]{1,4096}")
private const val API_TIMEOUT_MS = 30_000L

/** A session's checkpoints come with its transcript, which can be long. */
private const val API_TRANSCRIPT_TIMEOUT_MS = 60_000L
private const val API_TRANSCRIPT_MAX_BYTES = 48L * 1024 * 1024

/** The text view of a long session, which the CLI prints whole. */
private const val TRANSCRIPT_MAX_BYTES = 64L * 1024 * 1024
private const val RAW_TRANSCRIPT_TIMEOUT_MS = 180_000L

/**
 * Whether these arguments are one of the commands that only read. The
 * commands that write, such as `enable`, `login`, `trail approve`, and
 * `brain setup`, are not here, so the plugin cannot start them.
 */
fun isReadCommand(args: List<String>): Boolean {
    val first = args.getOrNull(0)
    val second = args.getOrNull(1)
    return when (first) {
        "version" -> args.size == 1
        "status" -> args == listOf("status", "--json")
        "checkpoint" -> second == "explain"
        "session" -> args == listOf("session", "list", "--json") ||
            (second == "info" && args.size == 4 && args[3] == "--transcript")
        // A path and nothing else, which makes the CLI send a GET.
        "api" -> args.size == 4 && second == "--to" && args[2] == "cell" && args[3].startsWith("/")
        "brain" -> args == listOf("brain", "status", "--json") || args == listOf("brain", "viz", "--no-open", "--port", "0")
        "graph" -> args == GRAPH_VERSION_ARGS
        "auth" -> args == AUTH_STATUS_ARGS
        "agent" -> args == AGENT_LIST_ARGS
        // Search reads Entire and changes nothing. Its query is the one argument after `--`.
        "search" -> args.size == 8 && args[1] == "--json" && args[2] == "--limit" && args[4] == "--page" && args[6] == "--" &&
            args[3].toIntOrNull() != null && args[5].toIntOrNull() != null
        "trail" -> second == "list" || second == "show" || second == "timeline"
        else -> false
    }
}

/** How a failed call is worded, for calls that are not about a checkpoint. */
internal class FailureWording(
    /** The call reads entire.io, so it can fail for want of a login or of access. */
    val remote: Boolean = false,
    /** What to say when the CLI reports that the thing asked for does not exist. */
    val notFound: String? = null,
    /** What to say when entire.io does not have the thing, or the saved login cannot see it. */
    val noAccess: String? = null,
)

private val NOT_LOGGED_IN = Regex("not logged in|entire login|re-authenticate", RegexOption.IGNORE_CASE)
private val NO_ACCESS = Regex("does not exist or is not visible|no active mirror|needs a GitHub repo", RegexOption.IGNORE_CASE)
private val MISSING = Regex("not found", RegexOption.IGNORE_CASE)
private val MISSING_REMOTE = Regex("not found|\\bno (change|trail)\\b[^\\n]*\\bfound\\b", RegexOption.IGNORE_CASE)
private val NOT_ENABLED = Regex("entire is (disabled|not enabled)|entire enable", RegexOption.IGNORE_CASE)

private fun lastLines(text: String, count: Int = 3): String = text.trim().split('\n').takeLast(count).joinToString("\n")

/** Maps a finished process to a failure, or null when it exited cleanly. */
internal fun processFailure(result: ExecResult, action: String, wording: FailureWording = FailureWording()): Failure? {
    result.spawnError?.let {
        return Failure(FailureKind.CLI_MISSING, "The Entire CLI could not be started.", it.message)
    }
    if (result.timedOut) return Failure(FailureKind.TIMEOUT, "The Entire CLI took too long to $action.")
    if (result.truncated) return Failure(FailureKind.UNEXPECTED_OUTPUT, "The Entire CLI returned more data than expected.")
    result.writeError?.let {
        return Failure(FailureKind.EXIT, "Entire could not save what the CLI returned.", it.message)
    }
    if (result.code != 0) {
        val stderr = lastLines(result.stderr)
        if (wording.remote) {
            if (NOT_LOGGED_IN.containsMatchIn(stderr)) {
                return Failure(FailureKind.NOT_LOGGED_IN, "The Entire CLI is not logged in.", stderr)
            }
            // The second and third are what `entire api` says of an origin it cannot place on Entire.
            if (NO_ACCESS.containsMatchIn(stderr)) {
                return Failure(
                    FailureKind.NO_ACCESS,
                    wording.noAccess ?: "This project is not on Entire, or the saved login cannot see it.",
                    stderr,
                )
            }
        }
        // For a change, the CLI says "no change <repo>/<n> found".
        val missing = if (wording.remote) MISSING_REMOTE else MISSING
        if (missing.containsMatchIn(stderr)) {
            return Failure(
                FailureKind.NOT_FOUND,
                wording.notFound ?: "Entire has no session data for this checkpoint, on this machine or on the remote.",
                stderr,
            )
        }
        return Failure(FailureKind.EXIT, "The Entire CLI could not $action.", stderr)
    }
    return null
}

private fun parseJson(text: String): JsonElement? =
    try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        null
    }

private val NOT_AUTHORIZED = Regex("\\b(unauthori[sz]ed|unauthenticated|invalid token|token (has )?expired)\\b", RegexOption.IGNORE_CASE)

/**
 * Reads the reply to an `entire api` call. A call that Entire refuses exits
 * non-zero with the refusal as JSON on stdout, so here the exit code is
 * looked at first, and the refusal is read for what went wrong.
 */
internal fun <T : Any> apiResult(result: ExecResult, action: String, read: (JsonElement?) -> T?, notFound: String): CallResult<T> {
    val text = result.text().trim()
    val refusal = if (result.code == 0) null else remoteRefusal(text)
    val seen = if (refusal == null) {
        result
    } else {
        ExecResult(
            code = result.code,
            stdout = result.stdout,
            stderr = (result.stderr.trim() + "\n" + refusal).trim(),
            timedOut = result.timedOut,
            truncated = result.truncated,
            bytes = result.bytes,
            spawnError = result.spawnError,
            writeError = result.writeError,
        )
    }
    val wording = FailureWording(remote = true, notFound = notFound, noAccess = "This repository is not on Entire, or the saved login cannot see it.")
    processFailure(seen, action, wording)?.let { failure ->
        // The API answers an expired or missing token in its own words, not the CLI's.
        if (failure.kind == FailureKind.EXIT && NOT_AUTHORIZED.containsMatchIn(refusal.orEmpty())) {
            return fail(FailureKind.NOT_LOGGED_IN, "The Entire CLI is not logged in.", failure.detail)
        }
        return CallResult.Failed(failure)
    }
    val parsed = parseJson(text) ?: return fail(FailureKind.UNEXPECTED_OUTPUT, "Entire returned no data when asked to $action.", text.take(400))
    val value = read(parsed)
    return if (value == null) fail(FailureKind.UNEXPECTED_OUTPUT, "Entire returned data this plugin does not understand.", text.take(400)) else ok(value)
}

/**
 * Parses stdout as JSON before looking at the exit code: the CLI exits
 * non-zero for a partially readable checkpoint while still printing a usable
 * envelope. In a repo where Entire is disabled it prints prose and exits 0.
 */
internal fun <T : Any> jsonResult(
    result: ExecResult,
    action: String,
    read: (JsonElement?) -> T?,
    wording: FailureWording = FailureWording(),
): CallResult<T> {
    val text = result.text().trim()
    if (text.isNotEmpty() && !result.timedOut && !result.truncated) {
        val parsed = parseJson(text)
        if (parsed != null) {
            val value = read(parsed)
            if (value != null) return ok(value)
            return fail(FailureKind.UNEXPECTED_OUTPUT, "The Entire CLI returned data this plugin does not understand.", text.take(400))
        }
        if (NOT_ENABLED.containsMatchIn(text)) {
            return fail(FailureKind.NOT_ENABLED, "Entire is not enabled in this repository.", text.take(400))
        }
    }
    processFailure(result, action, wording)?.let { return CallResult.Failed(it) }
    return fail(FailureKind.UNEXPECTED_OUTPUT, "The Entire CLI returned no data when asked to $action.", text.take(400))
}
