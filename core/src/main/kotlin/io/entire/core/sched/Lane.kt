package io.entire.core.sched

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

enum class Priority { USER, BACKGROUND }

/**
 * Limits how many processes of one kind run at once.
 *
 * User actions run before queued background work, at most one background job
 * runs at a time, and callers that pass the same key share one run.
 *
 * A run with a key belongs to the lane and not to its first caller: a caller
 * that is cancelled stops waiting, and the run goes on for the others. A run
 * without a key belongs to its caller, and stops when the caller is cancelled.
 */
class Lane(private val limit: Int, scope: CoroutineScope) {
    private class Waiter(val priority: Priority) {
        val gate = CompletableDeferred<Unit>()
    }

    private val shared = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()
    private var running = 0
    private var runningBackground = 0
    private val queue = ArrayList<Waiter>()
    private val inFlight = HashMap<String, Deferred<*>>()

    suspend fun <T> run(priority: Priority = Priority.USER, key: String? = null, task: suspend () -> T): T {
        if (key == null) return withSlot(priority, task)
        val run = synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val existing = inFlight[key] as Deferred<T>?
            existing ?: shared.async(start = CoroutineStart.LAZY) { withSlot(priority, task) }.also { created ->
                inFlight[key] = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight[key] === created) inFlight.remove(key)
                    }
                }
            }
        }
        run.start()
        return run.await()
    }

    private suspend fun <T> withSlot(priority: Priority, task: suspend () -> T): T {
        val waiter = Waiter(priority)
        synchronized(lock) {
            queue.add(waiter)
            drain()
        }
        try {
            waiter.gate.await()
        } catch (e: CancellationException) {
            // The slot may have been handed over just before the cancellation was seen.
            val hadSlot = synchronized(lock) { !queue.remove(waiter) }
            if (hadSlot) release(priority)
            throw e
        }
        try {
            return task()
        } finally {
            release(priority)
        }
    }

    private fun release(priority: Priority) {
        synchronized(lock) {
            running -= 1
            if (priority == Priority.BACKGROUND) runningBackground -= 1
            drain()
        }
    }

    private fun drain() {
        while (running < limit) {
            val index = nextIndex()
            if (index < 0) return
            val waiter = queue.removeAt(index)
            running += 1
            if (waiter.priority == Priority.BACKGROUND) runningBackground += 1
            waiter.gate.complete(Unit)
        }
    }

    private fun nextIndex(): Int {
        val user = queue.indexOfFirst { it.priority == Priority.USER }
        if (user >= 0) return user
        if (runningBackground > 0) return -1
        return queue.indexOfFirst { it.priority == Priority.BACKGROUND }
    }
}
