package com.memoria.caregiver.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResetPasswordSession] — the submit gate, and the promise that the recovery
 * code cannot be printed.
 *
 * A plain JVM test: nothing here touches Android, and the gate is the one part
 * of the reset flow that can be checked exhaustively, including the case the
 * offline view-model harness cannot reach (a verified link), because building
 * the object needs no network.
 *
 * The gate is asserted here **and** through the screen's disabled button in
 * `ResetPasswordScreenTest`, on purpose: the two are separate copies of the same
 * rule — the screen may not be edited from this side — and only a test of each
 * catches them drifting apart into a button that is live and a call that refuses.
 */
class ResetPasswordSessionTest {

    private companion object {
        const val PASSWORD = "Password1!"
    }

    /** A verified link with a matching, long-enough password: everything the gate asks for. */
    private fun ready() = ResetPasswordSession(
        code = "recovery-code",
        state = ResetPasswordState(password = PASSWORD, isExchangingCode = false),
        confirm = PASSWORD,
        isLinkValid = true,
    )

    @Test
    fun `a verified link with a matching password can be submitted`() {
        assertTrue(ready().canSubmit)
    }

    /**
     * The exchange has to have succeeded. Without this the caregiver could type
     * a password into a form backed by a dead session and be told it worked.
     */
    @Test
    fun `an unverified link cannot be submitted`() {
        assertFalse(ready().copy(isLinkValid = false).canSubmit)
    }

    /** Nothing submits while the code is still being exchanged. */
    @Test
    fun `a link still being exchanged cannot be submitted`() {
        val exchanging = ready().let { it.copy(state = it.state.copy(isExchangingCode = true)) }

        assertFalse(exchanging.canSubmit)
    }

    /**
     * The confirmation is the only check on a password nobody can see. It is
     * silent when broken — the form still works, it just stops catching typos.
     */
    @Test
    fun `a confirmation that does not match cannot be submitted`() {
        assertFalse(ready().copy(confirm = "Password2!").canSubmit)
        assertFalse(ready().copy(confirm = "").canSubmit)
    }

    /** iOS: `password.count >= 8`. Asserted at the boundary, both sides. */
    @Test
    fun `the password must be at least eight characters`() {
        val seven = "Pass1!a"
        val eight = "Pass1!ab"
        assertEquals(7, seven.length)
        assertEquals(8, eight.length)

        assertFalse(ready().copy(state = ready().state.copy(password = seven), confirm = seven).canSubmit)
        assertTrue(ready().copy(state = ready().state.copy(password = eight), confirm = eight).canSubmit)
    }

    /** A double tap must not send two updates. */
    @Test
    fun `a submit already in flight cannot be submitted again`() {
        val busy = ready().let { it.copy(state = it.state.copy(form = it.state.form.copy(isBusy = true))) }

        assertFalse(busy.canSubmit)
    }

    /**
     * A failed submit leaves an error but the link is still good, so the form
     * stays usable — iOS keeps showing it rather than flipping to the
     * invalid-link body.
     */
    @Test
    fun `an error from a failed submit does not close the form`() {
        val failed = ready().let {
            it.copy(state = it.state.copy(form = it.state.form.copy(errorMessage = "Network error.")))
        }

        assertTrue(failed.canSubmit)
    }

    // -----------------------------------------------------------------------
    // The credential
    // -----------------------------------------------------------------------

    /**
     * A data class prints every field, and this one holds a recovery code and
     * two copies of a new password. Anything that logs the session — a crash
     * report, a failed assertion's message, a stray print while debugging a
     * StateFlow — would carry all three out of the process.
     */
    @Test
    fun `printing the session reveals neither the code nor the password`() {
        val printed = ready().toString()

        assertFalse("the recovery code was printed", printed.contains("recovery-code"))
        assertFalse("the new password was printed", printed.contains(PASSWORD))
        assertEquals("ResetPasswordSession(redacted)", printed)
    }

    /** Redacting the printout must not have cost the ability to compare sessions. */
    @Test
    fun `sessions still compare by value`() {
        assertEquals(ready(), ready())
        assertFalse(ready() == ready().copy(confirm = "something else"))
    }
}
