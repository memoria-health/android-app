package com.memoria.caregiver.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The privacy notice is a legal document, and this is the gate that makes
 * changing it deliberate.
 *
 * A port that quietly reworded a clause would be indistinguishable from one that
 * fixed a typo, and nothing else in the pipeline would notice: there is no
 * compiler error in an edited string, and no screenshot diff can be trusted to
 * catch one word in four thousand.
 */
class PrivacyPolicyContentTest {

    /**
     * Every section title, in order, exactly as `PrivacyPolicyView.swift` has
     * them. A section deleted, added or renamed fails here.
     */
    private val expectedTitles = listOf(
        "Summary",
        "1. Introduction and Scope",
        "2. Defined Terms",
        "3. Categories of Personal Information We Collect",
        "4. How We Use Personal Information",
        "5. How We Handle Sensor Video and Audio",
        "6. Disclosure to Service Providers",
        "7. Data Retention",
        "8. International Data Transfers",
        "9. Live Look-In and Access Controls",
        "10. Information Security",
        "11. Your Rights",
        "12. Children's Privacy",
        "13. Consent of Monitored Persons",
        "14. Limitations on Use",
        "15. Cookies and Similar Technologies",
        "16. Wellness Service, Not a Medical Device",
        "17. Changes to this Notice",
        "18. Contact",
    )

    @Test
    fun `every section from the Swift is present, in order`() {
        assertEquals(expectedTitles, PrivacyPolicyContent.sections.map { it.title })
    }

    @Test
    fun `no section is empty`() {
        for (section in PrivacyPolicyContent.sections) {
            assertTrue("'${section.title}' has no body", section.body.isNotEmpty())
        }
    }

    /**
     * A digest of the whole notice.
     *
     * **A failure here is not a bug — it is a question.** It means the text
     * changed. If that was deliberate (a re-export from
     * `dashboard.nemara-health.com/privacy`), update the constant below in the
     * same commit as the text, and update iOS to match in the same change. If it
     * was not deliberate, the diff will show what moved.
     *
     * The point is that a legal notice cannot be edited without someone saying
     * out loud that they meant to.
     */
    @Test
    fun `the notice matches the digest it was ported with`() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalText().toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "The privacy notice text changed. If that was intended, re-export from " +
                "the dashboard, update iOS in the same change, and set this digest.",
            PORTED_DIGEST,
            digest,
        )
    }

    @Test
    fun `the effective date is stated`() {
        // iOS renders this in the header, and a notice with no date cannot be
        // reasoned about at all — section 17 makes "Last Revised" load-bearing.
        assertTrue(PrivacyPolicyContent.EFFECTIVE.contains("EFFECTIVE"))
        assertTrue(PrivacyPolicyContent.EFFECTIVE.contains("LAST REVISED"))
    }

    @Test
    fun `the contact section has somebody to contact`() {
        // Sections 11, 12 and 18 all direct the reader here. A blank footer
        // would make four rights unexercisable.
        assertTrue(PrivacyPolicyContent.FOOTER_NAME.isNotBlank())
        assertTrue(PrivacyPolicyContent.FOOTER_CONTACT.contains("@"))
    }

    @Test
    fun `no bullet is left holding an unmatched marker`() {
        // A stray `**` renders literally by design (see BoldRunsTest), which is
        // right for robustness and wrong to ship. This catches it in the source.
        for (section in PrivacyPolicyContent.sections) {
            for (block in section.body) {
                val strings = when (block) {
                    is PrivacyContent.Paragraph -> listOf(block.text)
                    is PrivacyContent.Subheading -> listOf(block.title)
                    is PrivacyContent.Bullets -> block.items
                }
                for (s in strings) {
                    assertEquals(
                        "odd number of ** markers in: $s",
                        0,
                        s.split("**").size.minus(1) % 2,
                    )
                }
            }
        }
    }

    @Test
    fun `the notice does not name the Android app, and that is a known gap`() {
        // Deliberately asserted the "wrong" way round. The notice is iOS-only in
        // its wording — Apple Push Notification tokens, iOS Keychain, "iOS
        // caregiver app" — and this port carries it verbatim rather than
        // rewriting a legal document (FINDINGS.md F-42).
        //
        // The day the policy is re-exported to cover Android, THIS TEST FAILS,
        // which is exactly when someone should be told that the finding can be
        // closed and the digest above updated.
        val everything = canonicalText()
        assertTrue("expected the iOS-only wording this port carried", everything.contains("iOS caregiver app"))
        assertFalse(
            "the notice now mentions Android — see FINDINGS.md F-42, this gap may be closed",
            everything.contains("Android"),
        )
    }

    /** The whole notice as one string, in render order. */
    private fun canonicalText(): String = buildString {
        appendLine(PrivacyPolicyContent.TITLE)
        appendLine(PrivacyPolicyContent.EFFECTIVE)
        for (section in PrivacyPolicyContent.sections) {
            appendLine(section.title)
            for (block in section.body) {
                when (block) {
                    is PrivacyContent.Paragraph -> appendLine(block.text)
                    is PrivacyContent.Subheading -> appendLine(block.title)
                    is PrivacyContent.Bullets -> block.items.forEach { appendLine(it) }
                }
            }
        }
        appendLine(PrivacyPolicyContent.FOOTER_LEAD)
        appendLine(PrivacyPolicyContent.FOOTER_NAME)
        appendLine(PrivacyPolicyContent.FOOTER_CONTACT)
    }

    private companion object {
        /**
         * SHA-256 of the notice as ported on 2026-08-03, verified string-by-string
         * against `PrivacyPolicyView.swift` at that time: 98 literals on each
         * side, zero differences. See the test above for what a failure means.
         */
        const val PORTED_DIGEST =
            "845b4a55464e117f0f5b9cba566d2cbc0bf0638eaf2f28b47fe1bf0732a27485"
    }
}
