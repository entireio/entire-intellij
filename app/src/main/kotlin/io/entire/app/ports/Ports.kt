package io.entire.app.ports

import io.entire.app.setup.StepId
import java.nio.file.Path

/*
 * What this module needs from the IDE. The plugin module implements these,
 * and the tests implement them with fakes.
 */

interface HostLog {
    fun info(message: String)

    fun warn(message: String)
}

interface Settings {
    /** A path to the Entire CLI that the user set, or null to search for it. */
    val cliPath: String?

    /** The base of links to the Entire web app, or null for the default. */
    val webBaseUrl: String?

    /** Whether trails may be read. They are read from Entire over the network, so the user can turn them off. */
    val trailsEnabled: Boolean
}

interface FolderSource {
    /** The folders of the open project in which to look for repositories. */
    fun folders(): List<String>
}

interface HostEnvironment {
    /** The environment for git and the Entire CLI. An IDE started from the desktop has to read it from the login shell. */
    fun variables(): Map<String, String>

    fun gitPath(): String

    val homeDir: String
    val windows: Boolean
}

/**
 * One file of a commit, for a diff. Each side is read when the diff is
 * shown. A side that is null did not exist: the file was added or deleted.
 */
class DiffFile(
    val path: String,
    /** First letter of the git status: A, M, D, R, C, or T. */
    val status: String,
    val title: String,
    val before: (suspend () -> ByteArray?)?,
    val after: (suspend () -> ByteArray?)?,
)

/** What the plugin shows and opens on behalf of this module. Each call may come from any thread. */
interface Ui {
    fun info(message: String)

    fun error(message: String)

    /** Says something, with one button that does what would help. */
    fun offer(message: String, button: String, action: () -> Unit)

    /** Opens the Setup tab at a step, or at the first step to do. */
    fun openSetup(step: StepId?)

    /** Shows a warning with one button, and returns true when the user chose it. */
    suspend fun confirm(message: String, button: String): Boolean

    fun openUrl(url: String)

    /** Puts text on the clipboard, and says so where the IDE puts short notes. */
    fun copy(value: String, note: String)

    /** Shows that something is being read while `work` runs. */
    suspend fun <T> withProgress(title: String, work: suspend () -> T): T

    fun showDiff(title: String, files: List<DiffFile>)

    /** Opens a file of the working tree. */
    fun openWorkingFile(path: Path)

    /** Opens text that is in no file, read-only. */
    fun openText(title: String, text: String)

    /** Opens a file from outside the project, read-only. */
    fun openFile(path: Path)
}
