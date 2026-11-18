package io.entire.app.actions

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Temporary files that hold raw transcripts while they are open in an editor.
 *
 * A raw transcript is in the agent's own format and can run to hundreds of
 * megabytes, which is too much to hold in memory and hand to the editor as one
 * string. The Entire CLI's output goes straight to a file here, and the IDE
 * opens that file with its own handling for large files.
 *
 * The files sit in a directory that only the current user can read, are made
 * read-only once written, and are deleted when the project closes.
 */
class TranscriptFiles {
    private var directory: Path? = null

    /** A path for `name` with nothing at it yet. A transcript opened earlier under that name is replaced. */
    @Synchronized
    fun prepare(name: String): Path {
        // On POSIX a temporary directory is created with access for its owner only.
        val home = directory ?: Files.createTempDirectory("entire-transcripts-").also { directory = it }
        val file = home.resolve(name.replace(UNSAFE, "_"))
        discard(file)
        return file
    }

    /** Marks a finished file read-only, so the editor opens it read-only too. */
    fun seal(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"))
        } catch (e: UnsupportedOperationException) {
            file.toFile().setReadOnly()
        } catch (e: IOException) {
            file.toFile().setReadOnly()
        }
    }

    fun discard(file: Path) {
        try {
            // A sealed file on Windows has to be writable before it can be deleted.
            file.toFile().setWritable(true)
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            // Nothing to do: the directory is removed with the project.
        }
    }

    @Synchronized
    fun dispose() {
        val home = directory ?: return
        directory = null
        home.toFile().listFiles()?.forEach { discard(it.toPath()) }
        try {
            Files.deleteIfExists(home)
        } catch (e: IOException) {
            // A file that is still open on Windows keeps the directory. The system's own cleanup takes it.
        }
    }

    private companion object {
        val UNSAFE = Regex("[^\\w.-]")
    }
}
