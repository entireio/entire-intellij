package io.entire.core.git

import io.entire.core.CallResult
import io.entire.core.FailureKind
import io.entire.core.exec.Exec
import io.entire.core.exec.ExecOptions
import io.entire.core.exec.ExecResult
import io.entire.core.fail
import io.entire.core.ok
import io.entire.core.sched.Lane
import java.nio.file.Path

data class RepoPaths(
    /** Working tree root. */
    val root: String,
    /** This worktree's git directory, which holds HEAD. */
    val gitDir: String,
    /** The git directory shared by every worktree, which holds refs and session state. */
    val commonDir: String,
)

data class FileChange(
    /** First letter of the git status: A, M, D, R, C, or T. */
    val status: String,
    val path: String,
    /** Previous path for a rename or copy. */
    val oldPath: String? = null,
)

/** The start of a file in a tree, and whether that is all of it. */
class BlobHead(val data: ByteArray, val whole: Boolean)

private const val DEFAULT_TIMEOUT_MS = 10_000L

/** Read-only git queries. Nothing here writes to the repository. */
class Git(
    private val exec: Exec,
    private val lane: Lane,
    private val gitPath: String = "git",
    private val environment: () -> Map<String, String> = { System.getenv() },
) {
    private suspend fun run(cwd: String, args: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS, maxBytes: Long? = null): ExecResult =
        lane.run {
            exec.run(
                gitPath,
                args,
                ExecOptions(
                    cwd = cwd,
                    timeoutMs = timeoutMs,
                    // The second keeps git from taking the index lock, which the user's own git may want.
                    env = environment() + mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_OPTIONAL_LOCKS" to "0"),
                    maxBytes = maxBytes,
                ),
            )
        }

    private suspend fun line(cwd: String, args: List<String>): String? {
        val result = run(cwd, args)
        if (result.code != 0) return null
        return result.text().trim().ifEmpty { null }
    }

    suspend fun repoPaths(cwd: String): CallResult<RepoPaths> {
        val result = run(cwd, listOf("rev-parse", "--show-toplevel", "--git-dir", "--git-common-dir"))
        result.spawnError?.let {
            return fail(FailureKind.SPAWN, "Git is not installed, or it is not on the PATH.", it.message)
        }
        if (result.code != 0) return fail(FailureKind.NOT_GIT, "This folder is not a Git repository.", result.stderr)
        val lines = result.text().split('\n')
        val root = lines.getOrNull(0).orEmpty()
        val gitDir = lines.getOrNull(1).orEmpty()
        val commonDir = lines.getOrNull(2).orEmpty()
        if (root.isEmpty() || gitDir.isEmpty() || commonDir.isEmpty()) {
            return fail(FailureKind.NOT_GIT, "This folder is not a Git repository with a working tree.", result.stderr)
        }
        // Git prints these two relative to the folder it was asked in.
        val base = Path.of(cwd)
        return ok(RepoPaths(root, base.resolve(gitDir).normalize().toString(), base.resolve(commonDir).normalize().toString()))
    }

    /** The commit HEAD points at, or null on a branch with no commits. */
    suspend fun head(cwd: String): String? = line(cwd, listOf("rev-parse", "--verify", "-q", "HEAD^{commit}"))

    suspend fun branch(cwd: String): String? = line(cwd, listOf("symbolic-ref", "--short", "-q", "HEAD"))

    suspend fun originUrl(cwd: String): String? = line(cwd, listOf("config", "--get", "remote.origin.url"))

    /** One window of history starting at `tip`, including commits with no checkpoint. */
    suspend fun logWindow(cwd: String, tip: String, skip: Int, count: Int): CallResult<List<CommitRecord>> {
        if (!isCommitSha(tip)) return fail(FailureKind.UNEXPECTED_OUTPUT, "The branch tip is not a commit.")
        val result = run(
            cwd,
            listOf("log", tip, "--skip=$skip", "--max-count=$count", "-z", "--no-show-signature", "--format=$LOG_FORMAT"),
            timeoutMs = 30_000,
            maxBytes = 64L * 1024 * 1024,
        )
        if (result.timedOut) return fail(FailureKind.TIMEOUT, "Reading the commit history took too long.")
        if (result.code != 0) return fail(FailureKind.EXIT, "Git could not read the commit history.", result.stderr)
        return ok(parseLogRecords(result.text()))
    }

    /** A single commit, for commits outside the windows already loaded. */
    suspend fun commit(cwd: String, sha: String): CommitRecord? {
        if (!isCommitSha(sha)) return null
        val result = run(cwd, listOf("log", sha, "--max-count=1", "-z", "--no-show-signature", "--format=$LOG_FORMAT"))
        if (result.code != 0) return null
        return parseLogRecords(result.text()).firstOrNull()
    }

    /** SHA of the commit's first parent, or null for a root commit. */
    suspend fun parent(cwd: String, sha: String): String? {
        if (!isCommitSha(sha)) return null
        val parent = line(cwd, listOf("rev-parse", "--verify", "-q", "$sha^"))
        return parent?.takeIf { isCommitSha(it) }
    }

    /** Whether a remote-tracking ref contains the commit, so a web page for it can exist. */
    suspend fun isOnRemote(cwd: String, sha: String): Boolean {
        if (!isCommitSha(sha)) return false
        val ref = line(cwd, listOf("for-each-ref", "--contains", sha, "--count=1", "--format=%(refname)", "refs/remotes/"))
        return ref != null
    }

    /** Files a commit changed relative to its first parent, or all its files for a root commit. */
    suspend fun commitFiles(cwd: String, sha: String): List<FileChange> {
        if (!isCommitSha(sha)) return emptyList()
        val parent = parent(cwd, sha)
        // Comparing two trees also works for a merge commit, where the
        // one-commit form of diff-tree prints nothing.
        val range = if (parent != null) listOf(parent, sha) else listOf("--root", sha)
        val result = run(cwd, listOf("diff-tree", "-r", "--no-commit-id", "--name-status", "-z", "-M") + range)
        if (result.code != 0) return emptyList()
        return parseNameStatus(result.text())
    }

    /** Contents of `<treeish>:<path>`, or null when it does not exist. */
    suspend fun blob(cwd: String, treeish: String, filePath: String): ByteArray? {
        val result = run(cwd, listOf("cat-file", "blob", "$treeish:$filePath"), timeoutMs = 30_000, maxBytes = 128L * 1024 * 1024)
        if (result.code != 0 || result.truncated) return null
        return result.stdout
    }

    /**
     * The start of `<treeish>:<path>`: at most `maxBytes` of it, or all of it
     * when it is shorter. Null when the file does not exist. This is for a
     * large file of which only the first lines are wanted: git is stopped
     * once it has written that much.
     */
    suspend fun blobHead(cwd: String, treeish: String, filePath: String, maxBytes: Long): BlobHead? {
        val result = run(cwd, listOf("cat-file", "blob", "$treeish:$filePath"), timeoutMs = 30_000, maxBytes = maxBytes)
        if (result.truncated) return BlobHead(result.stdout, whole = false)
        return if (result.code == 0) BlobHead(result.stdout, whole = true) else null
    }

    suspend fun exists(cwd: String, treeish: String, filePath: String): Boolean =
        run(cwd, listOf("cat-file", "-e", "$treeish:$filePath")).code == 0

    /** Full names of the refs that match the given `for-each-ref` patterns. */
    suspend fun refs(cwd: String, patterns: List<String>): List<String> {
        val result = run(cwd, listOf("for-each-ref", "--format=%(refname)") + patterns)
        if (result.code != 0) return emptyList()
        return result.text().split('\n').filter { it.isNotEmpty() }
    }
}

fun parseNameStatus(output: String): List<FileChange> {
    val tokens = output.split('\u0000')
    val changes = ArrayList<FileChange>()
    var i = 0
    while (i < tokens.size) {
        val status = tokens[i]
        if (status.isEmpty()) break
        val letter = status.substring(0, 1)
        if (letter == "R" || letter == "C") {
            val oldPath = tokens.getOrNull(i + 1).orEmpty()
            val newPath = tokens.getOrNull(i + 2).orEmpty()
            if (oldPath.isNotEmpty() && newPath.isNotEmpty()) changes.add(FileChange(letter, newPath, oldPath))
            i += 3
        } else {
            val filePath = tokens.getOrNull(i + 1).orEmpty()
            if (filePath.isNotEmpty()) changes.add(FileChange(letter, filePath))
            i += 2
        }
    }
    return changes
}
