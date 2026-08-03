package com.memoria.caregiver.legal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LegalConstants] against `Features/Legal/LegalConstants.swift`.
 *
 * These are not stylistic assertions. The version string is the **consent
 * record**: it is stored against the caregiver row and is the only thing that
 * says which document they agreed to. It has to be byte-identical to the one iOS
 * and the web app send, and the backend rejects anything outside its
 * `KNOWN_TOS_VERSIONS` allowlist — so a typo here does not degrade, it makes
 * every Android sign-up fail. PARITY.md §16, QUESTIONS.md Q4.
 *
 * The document list is pinned for the same reason at one remove: `resource` is
 * the asset filename the gate rasterises, so a change to it is a gate that
 * cannot be satisfied.
 */
class LegalConstantsTest {

    @Test
    fun `the terms version is the one the backend allows`() {
        assertEquals("2026-07-26-placeholder", LegalConstants.currentTosVersion)
    }

    @Test
    fun `both documents are listed, in the order iOS shows their tabs`() {
        assertEquals(
            listOf(
                LegalConstants.Doc(id = "terms", title = "Terms of Service", resource = "terms"),
                LegalConstants.Doc(id = "privacy", title = "Privacy Notice", resource = "privacy"),
            ),
            LegalConstants.documents,
        )
    }
}
