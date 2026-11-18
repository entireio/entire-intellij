package io.entire.core.io

import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Reports changes to named files in a few directories.
 *
 * Each watch covers one directory and not what is under it, so watching a
 * git directory does not watch its object store. A directory that does not
 * exist yet is watched for from its parent, and reported when it appears.
 *
 * The callbacks run on the watcher's own thread, and should hand the work on.
 */
class DirectoryWatcher : AutoCloseable {
    private class Watch(val directory: Path, val matches: (String) -> Boolean, val onEvent: () -> Unit)

    private val service: WatchService? = try {
        FileSystems.getDefault().newWatchService()
    } catch (e: IOException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }
    private val watches = ConcurrentHashMap<WatchKey, CopyOnWriteArrayList<Watch>>()

    /** Watches whose directory was not there to register. */
    private val waiting = CopyOnWriteArrayList<Watch>()

    init {
        if (service != null) thread(isDaemon = true, name = "entire-watch") { loop(service) }
    }

    /** Calls `onEvent` when a file of `directory` whose name passes `matches` is created, written, or deleted. */
    fun watch(directory: Path, matches: (String) -> Boolean, onEvent: () -> Unit) {
        val watch = Watch(directory, matches, onEvent)
        if (!register(watch)) {
            waiting.add(watch)
            // The directory's own creation is an event in its parent.
            val name = directory.fileName?.toString() ?: return
            val parent = directory.parent ?: return
            register(Watch(parent, { it == name }) { retryWaiting() })
        }
    }

    private fun register(watch: Watch): Boolean {
        val watcher = service ?: return false
        if (!Files.isDirectory(watch.directory)) return false
        return try {
            val key = watch.directory.register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            watches.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(watch)
            true
        } catch (e: IOException) {
            false
        } catch (e: ClosedWatchServiceException) {
            false
        }
    }

    private fun retryWaiting() {
        for (watch in waiting) {
            if (!register(watch)) continue
            waiting.remove(watch)
            // Whatever was written while the directory was being made went unseen.
            watch.onEvent()
        }
    }

    private fun loop(watcher: WatchService) {
        while (true) {
            val key = try {
                watcher.take()
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }
            val listening = watches[key].orEmpty()
            for (event in key.pollEvents()) {
                // After an overflow the names are lost, so everything in the directory may have changed.
                val name = if (event.kind() == OVERFLOW) null else (event.context() as? Path)?.toString()
                for (watch in listening) {
                    if (name == null || watch.matches(name)) watch.onEvent()
                }
            }
            if (!key.reset()) {
                // The directory is gone. It is watched for again from its parent.
                watches.remove(key)?.forEach { watch(it.directory, it.matches, it.onEvent) }
            }
        }
    }

    override fun close() {
        try {
            service?.close()
        } catch (e: IOException) {
            // Nothing is left to stop.
        }
        watches.clear()
        waiting.clear()
    }
}
