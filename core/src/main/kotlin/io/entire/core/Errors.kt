package io.entire.core

/** Why a git or Entire CLI call did not produce usable data. */
enum class FailureKind {
    CLI_MISSING,
    CLI_TOO_OLD,
    NOT_GIT,
    NOT_ENABLED,
    NOT_FOUND,

    /** entire.io rejected the call because no login is saved, or the saved one expired. */
    NOT_LOGGED_IN,

    /** The project is not on entire.io, or the saved login cannot see it. */
    NO_ACCESS,

    /** The command belongs to a plugin of the Entire CLI that is not installed. */
    PLUGIN_MISSING,
    TIMEOUT,
    EXIT,
    SPAWN,
    UNEXPECTED_OUTPUT,
}

data class Failure(
    val kind: FailureKind,
    /** One sentence that is safe to show in the UI. */
    val message: String,
    /** Extra context for the log, such as stderr. */
    val detail: String? = null,
)

/**
 * What a git or Entire CLI call returns. A call that fails returns [Failed]
 * and does not throw. A call that is cancelled does throw, as coroutines do.
 */
sealed interface CallResult<out T> {
    data class Ok<out T>(val value: T) : CallResult<T>

    data class Failed(val failure: Failure) : CallResult<Nothing>
}

fun <T> ok(value: T): CallResult<T> = CallResult.Ok(value)

fun fail(kind: FailureKind, message: String, detail: String? = null): CallResult<Nothing> =
    CallResult.Failed(Failure(kind, message, detail))
