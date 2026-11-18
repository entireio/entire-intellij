package io.entire.core.cli

import io.entire.core.exec.Exec
import io.entire.core.exec.ExecOptions
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class CliVersion(
    val raw: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Built from source, so there is no release number to compare. */
    val development: Boolean = false,
)

/** The oldest CLI whose JSON output this plugin was written against. */
val MIN_CLI_VERSION = CliVersion(raw = "0.12.0", major = 0, minor = 12, patch = 0)

val MIN_CLI_VERSION_TEXT: String = "${MIN_CLI_VERSION.major}.${MIN_CLI_VERSION.minor}.${MIN_CLI_VERSION.patch}"

private val RELEASE = Regex("^Entire CLI v?(\\d+)\\.(\\d+)\\.(\\d+)(\\S*)", RegexOption.MULTILINE)
private val DEVELOPMENT = Regex("^Entire CLI dev\\s*$", RegexOption.MULTILINE)

/** Reads the first line of `entire version`, for example `Entire CLI 0.13.3`. */
fun parseCliVersion(output: String): CliVersion? {
    val match = RELEASE.find(output)
    if (match == null) {
        // A build from source reports "dev" where a release reports a number.
        if (DEVELOPMENT.containsMatchIn(output)) return CliVersion(raw = "dev", major = 0, minor = 0, patch = 0, development = true)
        return null
    }
    val (major, minor, patch, suffix) = match.destructured
    val numbers = listOf(major, minor, patch).map { it.toIntOrNull() ?: return null }
    return CliVersion(raw = "$major.$minor.$patch$suffix", major = numbers[0], minor = numbers[1], patch = numbers[2])
}

/** Pre-release builds of the minimum version count as supported. */
fun isSupportedVersion(version: CliVersion): Boolean {
    if (version.development) return true
    if (version.major != MIN_CLI_VERSION.major) return version.major > MIN_CLI_VERSION.major
    if (version.minor != MIN_CLI_VERSION.minor) return version.minor > MIN_CLI_VERSION.minor
    return version.patch >= MIN_CLI_VERSION.patch
}

enum class CliSource { SETTING, PATH, KNOWN, SHELL }

data class LocatedCli(val path: String, val source: CliSource)

class LocateDeps(
    val env: Map<String, String>,
    val windows: Boolean,
    val homeDir: String,
    val isExecutable: (String) -> Boolean = ::isExecutableFile,
    /** Asks the user's login shell where `entire` is. Skipped when absent. */
    val probeLoginShell: (suspend () -> String?)? = null,
)

fun isExecutableFile(file: String): Boolean =
    try {
        val path = Path.of(file)
        Files.isRegularFile(path) && Files.isExecutable(path)
    } catch (e: InvalidPathException) {
        false
    }

// Only the executable: a .cmd or .bat file needs a shell to start it.
private fun binaryNames(windows: Boolean): List<String> = if (windows) listOf("entire.exe") else listOf("entire")

private fun knownDirs(deps: LocateDeps): List<String> {
    if (deps.windows) return emptyList()
    return listOf(
        Path.of(deps.homeDir, ".local", "bin").toString(),
        "/opt/homebrew/bin",
        "/usr/local/bin",
        Path.of(deps.homeDir, "go", "bin").toString(),
        Path.of(deps.homeDir, ".local", "share", "mise", "shims").toString(),
    )
}

private fun join(dir: String, name: String): String? =
    try {
        Path.of(dir, name).toString()
    } catch (e: InvalidPathException) {
        null
    }

private fun isAbsolute(path: String): Boolean =
    try {
        Path.of(path).isAbsolute
    } catch (e: InvalidPathException) {
        false
    }

/**
 * The file that a configured CLI path names. A leading `~` stands for the
 * home folder. Null when nothing is configured, or when the text is not a path.
 */
fun configuredCliFile(configured: String?, homeDir: String): String? {
    val setting = configured?.trim().orEmpty()
    if (setting.isEmpty()) return null
    return if (setting.startsWith("~")) join(homeDir, setting.substring(1)) else setting
}

/**
 * Finds the Entire CLI. A configured path wins and is never second-guessed:
 * when it points nowhere, the result is null so the UI can say so.
 */
suspend fun locateCli(configured: String?, deps: LocateDeps): LocatedCli? {
    if (!configured.isNullOrBlank()) {
        val expanded = configuredCliFile(configured, deps.homeDir) ?: return null
        return if (deps.isExecutable(expanded)) LocatedCli(expanded, CliSource.SETTING) else null
    }

    val names = binaryNames(deps.windows)
    val pathVar = deps.env["PATH"] ?: deps.env["Path"] ?: ""
    val separator = if (deps.windows) ';' else ':'
    fun search(dirs: List<String>, source: CliSource): LocatedCli? {
        for (dir in dirs) {
            // An empty entry means the current folder, which is never searched.
            if (dir.isEmpty()) continue
            for (name in names) {
                val candidate = join(dir, name) ?: continue
                if (deps.isExecutable(candidate)) return LocatedCli(candidate, source)
            }
        }
        return null
    }

    search(pathVar.split(separator), CliSource.PATH)?.let { return it }
    search(knownDirs(deps), CliSource.KNOWN)?.let { return it }

    val probed = deps.probeLoginShell?.invoke()
    if (!probed.isNullOrEmpty() && isAbsolute(probed) && deps.isExecutable(probed)) {
        return LocatedCli(probed, CliSource.SHELL)
    }
    return null
}

private const val START = "__ENTIRE_PATH_START__"
private const val END = "__ENTIRE_PATH_END__"
private val PROBE_ANSWER = Regex("$START(.*?)$END", RegexOption.DOT_MATCHES_ALL)

/**
 * An IDE started from the desktop can miss PATH entries that the user's shell
 * profile adds. This asks an interactive login shell, and marks the answer so
 * that banner text printed by the profile cannot be mistaken for it.
 */
fun loginShellProbe(exec: Exec, env: Map<String, String>, cwd: String): suspend () -> String? = probe@{
    val shell = env["SHELL"]
    if (shell.isNullOrEmpty() || !isAbsolute(shell)) return@probe null
    val script = "printf '$START%s$END' \"\$(command -v entire)\""
    val result = exec.run(shell, listOf("-ilc", script), ExecOptions(cwd = cwd, timeoutMs = 5000, env = env))
    PROBE_ANSWER.find(result.text())?.groupValues?.get(1)?.trim()?.ifEmpty { null }
}
