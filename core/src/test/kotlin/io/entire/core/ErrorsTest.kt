package io.entire.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ErrorsTest {
    @Test
    fun `ok wraps a value`() {
        assertEquals(CallResult.Ok(42), ok(42))
    }

    @Test
    fun `ok wraps empty values without losing them`() {
        assertEquals(CallResult.Ok(null), ok<String?>(null))
        assertEquals(CallResult.Ok(""), ok(""))
        assertEquals(CallResult.Ok(emptyList<String>()), ok(emptyList<String>()))
    }

    @Test
    fun `fail carries the kind and a message`() {
        assertEquals(
            CallResult.Failed(Failure(FailureKind.TIMEOUT, "The Entire CLI took too long.")),
            fail(FailureKind.TIMEOUT, "The Entire CLI took too long."),
        )
    }

    @Test
    fun `fail carries the detail when there is one, including an empty one`() {
        assertEquals(
            CallResult.Failed(Failure(FailureKind.EXIT, "Git could not read the commit history.", "fatal: bad object")),
            fail(FailureKind.EXIT, "Git could not read the commit history.", "fatal: bad object"),
        )
        assertEquals(
            CallResult.Failed(Failure(FailureKind.EXIT, "Failed.", "")),
            fail(FailureKind.EXIT, "Failed.", ""),
        )
    }

    @Test
    fun `fail leaves the detail absent when there is none`() {
        val result = fail(FailureKind.NOT_GIT, "This folder is not a Git repository.")

        assertNull((result as CallResult.Failed).failure.detail)
    }
}
