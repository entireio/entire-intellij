package io.entire.core.exec

import io.entire.core.CallResult
import io.entire.core.FailureKind
import io.entire.core.fail
import io.entire.core.ok
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A local server that this process started and has to stop. */
class RunningServer internal constructor(
    val url: String,
    private val process: Process,
    /** Completes once the process is gone, however it ended. */
    val exited: Deferred<Unit>,
) {
    private val stopping = AtomicBoolean(false)

    /** The processes seen under the server while it ran. A child can outlive a parent that exits first. */
    private val seen = LinkedHashSet<ProcessHandle>().also { it.addAll(ProcessTree.snapshot(process)) }

    /**
     * Asks the server and everything it started to stop, and kills what is
     * left after a grace period. Safe to call more than once.
     *
     * The request goes out before this returns, so it still counts when the
     * IDE is closing. The Entire CLI runs a plugin as a child of its own, and
     * the JVM cannot start a process in a group, so the tree is found and
     * each process in it is asked.
     */
    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        val tree = LinkedHashSet(seen)
        tree.addAll(ProcessTree.snapshot(process))
        tree.forEach { it.destroy() }
        thread(isDaemon = true, name = "entire-server-stop") {
            val deadline = System.nanoTime() + ProcessTree.GRACE_MS * 1_000_000
            while (tree.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(20)
            tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }
}

/** Most stderr kept for the reason a start failed. */
private const val STDERR_CAP = 16 * 1024

/** A line longer than this is not the one that carries the address. */
private const val LINE_CAP = 64 * 1024

private val NULL_DEVICE = File(if (System.getProperty("os.name").lowercase().contains("windows")) "NUL" else "/dev/null")

/**
 * Starts a program that serves until it is stopped, and returns once it has
 * printed its address. [Exec] runs a program to its end, which a server does
 * not have.
 *
 * `ready` reads one line of stdout, and returns the server's address once a
 * line carries it. After the address arrives the output is still read, and
 * thrown away: a pipe that nobody reads fills up and stalls the program that
 * writes to it.
 *
 * A caller that is cancelled while the program starts stops the program.
 */
suspend fun startServer(
    file: String,
    args: List<String>,
    cwd: String,
    env: Map<String, String>?,
    ready: (String) -> String?,
    readyTimeoutMs: Long,
): CallResult<RunningServer> = withContext(Dispatchers.IO) {
    val builder = ProcessBuilder(listOf(file) + args)
    builder.directory(File(cwd))
    builder.redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE))
    if (env != null) {
        builder.environment().clear()
        builder.environment().putAll(env)
    }
    val process = try {
        builder.start()
    } catch (e: IOException) {
        return@withContext fail(FailureKind.CLI_MISSING, "The program could not be started.", e.message)
    }

    val address = CompletableDeferred<String?>()
    val exited = CompletableDeferred<Unit>()
    val stderr = StringBuffer()
    thread(isDaemon = true, name = "entire-server-stderr") {
        try {
            process.errorStream.reader(Charsets.UTF_8).use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    if (!address.isCompleted && stderr.length < STDERR_CAP) stderr.append(chunk, 0, minOf(read, STDERR_CAP - stderr.length))
                }
            }
        } catch (e: IOException) {
            // The process closed its end.
        }
    }
    thread(isDaemon = true, name = "entire-server-stdout") {
        try {
            process.inputStream.reader(Charsets.UTF_8).use { reader ->
                val pending = StringBuilder()
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    if (address.isCompleted) continue
                    pending.append(chunk, 0, read)
                    var end = pending.indexOf("\n")
                    while (end >= 0 && !address.isCompleted) {
                        val url = ready(pending.substring(0, end).removeSuffix("\r"))
                        pending.delete(0, end + 1)
                        if (url != null) address.complete(url)
                        end = pending.indexOf("\n")
                    }
                    if (address.isCompleted || pending.length > LINE_CAP) pending.setLength(0)
                }
            }
        } catch (e: IOException) {
            // The process closed its end.
        }
        process.waitFor()
        // The program ended. When it had not printed its address, the start has failed.
        address.complete(null)
        exited.complete(Unit)
    }

    val server = RunningServer("", process, exited)
    try {
        val url = withTimeoutOrNull(readyTimeoutMs) { address.await() ?: "" }
        when {
            url == null -> {
                server.stop()
                fail(FailureKind.TIMEOUT, "The program took too long to start.", stderr.toString().trim())
            }
            url.isEmpty() -> {
                val code = if (process.isAlive) "none" else process.exitValue().toString()
                fail(FailureKind.EXIT, "The program stopped before it was ready.", stderr.toString().trim().ifEmpty { "exit code $code" })
            }
            else -> ok(RunningServer(url, process, exited))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        withContext(NonCancellable) { server.stop() }
        throw e
    }
}
