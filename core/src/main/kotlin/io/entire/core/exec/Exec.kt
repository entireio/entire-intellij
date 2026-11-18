package io.entire.core.exec

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class ExecOptions(
    val cwd: String,
    val timeoutMs: Long,
    /** The whole environment of the process. The caller's own environment when absent. */
    val env: Map<String, String>? = null,
    /** Stop collecting stdout past this many bytes and stop the process. */
    val maxBytes: Long? = null,
    /**
     * Write stdout to this file instead of collecting it, for output that can be
     * too large to hold in memory. `stdout` is then empty, `bytes` says how much
     * was written, and `maxBytes` does not apply. The file is created with
     * access for its owner only.
     */
    val stdoutFile: Path? = null,
)

class ExecResult(
    /** Exit code, or null when the process was stopped or never started. */
    val code: Int?,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean = false,
    /** stdout hit `maxBytes`. */
    val truncated: Boolean = false,
    /** How many bytes of stdout the process produced before it stopped or was cut off. */
    val bytes: Long = 0,
    /** Set when the process could not be started, for example because the file does not exist. */
    val spawnError: IOException? = null,
    /** Set when stdout could not be written to `stdoutFile`, for example on a full disk. */
    val writeError: IOException? = null,
) {
    fun text(): String = stdout.toString(Charsets.UTF_8)
}

/**
 * Runs a program and returns what it printed. A run that fails returns a
 * result that says how, and does not throw. A run whose caller is cancelled
 * stops the process and throws, as coroutines do.
 */
fun interface Exec {
    suspend fun run(file: String, args: List<String>, options: ExecOptions): ExecResult
}

/**
 * Runs a program without a shell and collects its output, or streams it to a
 * file. The program gets no stdin to wait on.
 *
 * A timeout stops the process and everything it started, so the git
 * transports that the Entire CLI starts stop with it.
 */
object ProcessExec : Exec {
    private const val DEFAULT_MAX_BYTES = 16L * 1024 * 1024
    private const val STDERR_CAP = 64 * 1024
    private val NO_BYTES = ByteArray(0)
    private val WINDOWS = System.getProperty("os.name").lowercase().contains("windows")
    private val NULL_DEVICE = File(if (WINDOWS) "NUL" else "/dev/null")

    private class Collected(val data: ByteArray, val bytes: Long, val truncated: Boolean, val writeError: IOException?)

    override suspend fun run(file: String, args: List<String>, options: ExecOptions): ExecResult =
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(listOf(file) + args)
            builder.directory(File(options.cwd))
            builder.redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE))
            options.env?.let { env ->
                builder.environment().clear()
                builder.environment().putAll(env)
            }
            val process = try {
                builder.start()
            } catch (e: IOException) {
                return@withContext ExecResult(code = null, stdout = NO_BYTES, stderr = "", spawnError = e)
            }

            val stopped = AtomicBoolean(false)
            val timedOut = AtomicBoolean(false)
            coroutineScope {
                val stop: () -> Unit = {
                    if (stopped.compareAndSet(false, true)) launch { ProcessTree.terminate(process) }
                }
                val timer = launch {
                    delay(options.timeoutMs)
                    timedOut.set(true)
                    stop()
                }
                val stdout = async {
                    val target = options.stdoutFile
                    if (target == null) {
                        collect(process.inputStream, options.maxBytes ?: DEFAULT_MAX_BYTES, stop)
                    } else {
                        copyToFile(process.inputStream, target, stop)
                    }
                }
                // Collected as bytes and decoded once, so a character split across reads survives.
                val stderr = async { collect(process.errorStream, STDERR_CAP.toLong()) {} }
                try {
                    val out = stdout.await()
                    val err = stderr.await()
                    val exit = runInterruptible { process.waitFor() }
                    timer.cancel()
                    ExecResult(
                        code = if (stopped.get()) null else exit,
                        stdout = out.data,
                        stderr = err.data.toString(Charsets.UTF_8),
                        timedOut = timedOut.get(),
                        truncated = out.truncated,
                        bytes = out.bytes,
                        writeError = out.writeError,
                    )
                } catch (e: CancellationException) {
                    // The readers end when the process does, and this scope waits for them.
                    withContext(NonCancellable) { ProcessTree.terminate(process) }
                    throw e
                }
            }
        }

    /** Reads to the end, and keeps the first `maxBytes`. The rest is read and dropped, so the process is not held up. */
    private fun collect(input: InputStream, maxBytes: Long, onOverflow: () -> Unit): Collected {
        val kept = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        var truncated = false
        input.use {
            while (true) {
                val read = try {
                    it.read(buffer)
                } catch (e: IOException) {
                    -1
                }
                if (read < 0) break
                if (truncated) continue
                bytes += read
                if (bytes > maxBytes) {
                    truncated = true
                    val fits = (read - (bytes - maxBytes)).toInt()
                    if (fits > 0) kept.write(buffer, 0, fits)
                    onOverflow()
                } else {
                    kept.write(buffer, 0, read)
                }
            }
        }
        return Collected(kept.toByteArray(), bytes, truncated, null)
    }

    private fun copyToFile(input: InputStream, file: Path, onError: () -> Unit): Collected {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        var writeError: IOException? = null
        val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        val output = try {
            val channel = if (WINDOWS) {
                Files.newByteChannel(file, options)
            } else {
                Files.newByteChannel(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            }
            Channels.newOutputStream(channel)
        } catch (e: IOException) {
            writeError = e
            onError()
            null
        }
        input.use {
            while (true) {
                val read = try {
                    it.read(buffer)
                } catch (e: IOException) {
                    -1
                }
                if (read < 0) break
                bytes += read
                if (output != null && writeError == null) {
                    try {
                        // A write waits for the disk, which holds the process back when it prints faster than the disk takes it.
                        output.write(buffer, 0, read)
                    } catch (e: IOException) {
                        writeError = e
                        onError()
                    }
                }
            }
        }
        try {
            output?.close()
        } catch (e: IOException) {
            if (writeError == null) writeError = e
        }
        return Collected(NO_BYTES, bytes, false, writeError)
    }
}

/**
 * Stops a process and everything under it.
 *
 * The JVM cannot start a process in a group of its own, so there is no group
 * to signal. This finds the tree instead: it asks each process in it to stop,
 * waits, and kills what is left.
 */
object ProcessTree {
    const val GRACE_MS = 2000L

    /** The process and its descendants. A child whose parent has exited is no longer among them. */
    fun snapshot(process: Process): Set<ProcessHandle> {
        val tree = LinkedHashSet<ProcessHandle>()
        tree.add(process.toHandle())
        process.toHandle().descendants().forEach { tree.add(it) }
        return tree
    }

    /**
     * `remembered` holds processes seen earlier, for a server whose children
     * may outlive it. A handle compares by PID and start time, so a
     * remembered one does not match a later process that reuses the PID.
     */
    suspend fun terminate(process: Process, remembered: Set<ProcessHandle> = emptySet(), graceMs: Long = GRACE_MS) {
        val tree = LinkedHashSet(remembered)
        tree.addAll(snapshot(process))
        tree.forEach { it.destroy() }
        if (awaitDead(tree, graceMs)) return
        tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
        awaitDead(tree, graceMs)
    }

    private suspend fun awaitDead(tree: Set<ProcessHandle>, limitMs: Long): Boolean {
        val deadline = System.nanoTime() + limitMs * 1_000_000
        while (tree.any { it.isAlive }) {
            if (System.nanoTime() >= deadline) return false
            delay(20)
        }
        return true
    }
}
