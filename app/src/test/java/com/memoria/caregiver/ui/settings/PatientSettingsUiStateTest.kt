package com.memoria.caregiver.ui.settings

import com.memoria.caregiver.data.model.FaceEnrollmentSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure logic behind the patient Settings tab. Spec:
 * `PatientSettingsView.swift` + `PatientEmailAlertsCard.swift`, PARITY.md §24.
 *
 * Everything here is a Tier-1 test: no composition, no Robolectric. Each case is
 * one that a plausible "tidy-up" would silently break and that a screenshot pass
 * would not catch.
 */
class PatientSettingsUiStateTest {

    private fun summary(
        selfies: Int = 0,
        harvested: Int = 0,
        processing: Int = 0,
        failed: Int = 0,
    ) = FaceEnrollmentSummary(
        selfieCount = selfies,
        harvestedCount = harvested,
        processingCount = processing,
        failedCount = failed,
    )

    // ---- identity card ----

    @Test
    fun `no summary yet is the loading state`() {
        assertEquals(IdentityCardState.Loading, identityCardState(null))
    }

    /**
     * The ordering guard. A patient with ready selfies AND a batch still
     * processing must read "in progress", not "complete" — otherwise the card
     * flips to done the moment the first embedding lands and stays there while
     * the rest of the batch runs.
     */
    @Test
    fun `processing wins over enrolled`() {
        assertEquals(
            IdentityCardState.Processing,
            identityCardState(summary(selfies = 4, processing = 2)),
        )
    }

    @Test
    fun `selfies with nothing processing is enrolled`() {
        assertEquals(IdentityCardState.Enrolled, identityCardState(summary(selfies = 1)))
    }

    /**
     * Harvested faces are not enrollment. A patient with harvested crops but no
     * selfie is NOT enrolled, and the card must stay in its urgent state.
     */
    @Test
    fun `harvested faces alone are not enrollment`() {
        assertEquals(IdentityCardState.NotEnrolled, identityCardState(summary(harvested = 12)))
    }

    /** Copy is matched byte-for-byte with the web dashboard. Pinned. */
    @Test
    fun `identity titles are the dashboard's copy`() {
        assertEquals("Patient identity", identityCardTitle(IdentityCardState.Loading, "Peggy"))
        assertEquals(
            "Peggy's face enrollment is complete",
            identityCardTitle(IdentityCardState.Enrolled, "Peggy"),
        )
        assertEquals(
            "Peggy's face enrollment is in progress",
            identityCardTitle(IdentityCardState.Processing, "Peggy"),
        )
        assertEquals(
            "Peggy's face enrollment is incomplete",
            identityCardTitle(IdentityCardState.NotEnrolled, "Peggy"),
        )
    }

    @Test
    fun `display name falls back to the generic phrase with no patient`() {
        assertEquals("this patient", patientDisplayName(null))
        assertEquals("Peggy", patientDisplayName(patient(preferredName = "Peggy")))
    }

    // ---- recipient validation ----

    /**
     * The Swift-split trap. `"a@@b.com".split(separator: "@")` omits empty
     * subsequences in Swift and yields two parts, so iOS ACCEPTS it. Kotlin's
     * `split("@")` yields three and would reject it, so the two platforms would
     * disagree about the same typed string.
     */
    @Test
    fun `doubled at-sign is accepted, matching Swift's split`() {
        assertTrue(isValidRecipientEmail("a@@b.com"))
    }

    @Test
    fun `a missing local part or missing dot is rejected`() {
        assertFalse(isValidRecipientEmail("@home.com"))
        assertFalse(isValidRecipientEmail("jane@"))
        assertFalse(isValidRecipientEmail("jane@home"))
        assertFalse(isValidRecipientEmail("jane home@x.com"))
    }

    @Test
    fun `an ordinary address is accepted`() {
        assertTrue(isValidRecipientEmail("jane@home.com"))
    }

    // ---- add / remove ----

    @Test
    fun `a blank draft is ignored rather than rejected`() {
        assertEquals(
            AddRecipientResult.Ignored,
            addRecipient("   ", "me@home.com", emptyList()),
        )
    }

    @Test
    fun `a malformed address is rejected with the product sentence`() {
        val result = addRecipient("jane", "me@home.com", emptyList())
        assertEquals(
            AddRecipientResult.Rejected("That doesn't look like a valid email address."),
            result,
        )
    }

    /**
     * The account email is already a recipient implicitly, so adding it again
     * would send two copies of every alert. Case-insensitive, because a
     * caregiver typing their own address will not match the server's casing.
     */
    @Test
    fun `the account email cannot be re-added, whatever the case`() {
        val result = addRecipient(" ME@Home.com ", "me@home.com", emptyList())
        assertEquals(
            AddRecipientResult.Rejected("That address is already on the list."),
            result,
        )
    }

    @Test
    fun `an address already on the list is rejected`() {
        val result = addRecipient("Jane@Home.com", "me@home.com", listOf("jane@home.com"))
        assertEquals(
            AddRecipientResult.Rejected("That address is already on the list."),
            result,
        )
    }

    /** The server replaces the whole array, so the result is the full next list. */
    @Test
    fun `accepting appends the normalized address to the whole list`() {
        val result = addRecipient("  Bob@Home.COM ", "me@home.com", listOf("jane@home.com"))
        assertEquals(
            AddRecipientResult.Accepted(listOf("jane@home.com", "bob@home.com")),
            result,
        )
    }

    /**
     * Removal is exact-match, not case-insensitive: the list is the server's,
     * and a looser match could drop a second address differing only in case.
     */
    @Test
    fun `removal takes only the exact string`() {
        val list = listOf("jane@home.com", "JANE@home.com")
        assertEquals(listOf("JANE@home.com"), removeRecipient("jane@home.com", list))
    }
}
