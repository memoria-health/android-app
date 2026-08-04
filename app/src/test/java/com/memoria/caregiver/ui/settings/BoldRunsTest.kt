package com.memoria.caregiver.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one markdown construct the privacy notice uses.
 *
 * Small, but it runs over a legal document: a parser that drops characters
 * changes what the notice says. Every case below is either a shape the policy
 * actually contains or a malformed one it must survive.
 */
class BoldRunsTest {

    private fun bolded(source: String): List<String> {
        val runs = boldRuns(source)
        return runs.bold.map { runs.text.substring(it.first, it.last + 1) }
    }

    @Test
    fun `plain text is returned unchanged with no runs`() {
        val runs = boldRuns("Personal Information is retained only as reasonably necessary:")
        assertEquals("Personal Information is retained only as reasonably necessary:", runs.text)
        assertTrue(runs.bold.isEmpty())
    }

    @Test
    fun `a leading bold term is stripped of markers and marked`() {
        // The shape of every bullet in section 2 and section 6.
        val source = "**Account Holder.** The natural person creating a Memoria account."
        assertEquals(
            "Account Holder. The natural person creating a Memoria account.",
            boldRuns(source).text,
        )
        assertEquals(listOf("Account Holder."), bolded(source))
    }

    @Test
    fun `a bold span mid sentence keeps the surrounding text`() {
        // Section 16, which is the one clause in the notice that most needs to
        // render correctly.
        val source = "The Service is a general wellness service. **The Service is not a medical device** and is not intended to diagnose."
        val runs = boldRuns(source)
        assertEquals(
            "The Service is a general wellness service. The Service is not a medical device and is not intended to diagnose.",
            runs.text,
        )
        assertEquals(listOf("The Service is not a medical device"), bolded(source))
    }

    @Test
    fun `two spans in one string are both found`() {
        assertEquals(listOf("one", "two"), bolded("a **one** b **two** c"))
    }

    @Test
    fun `an unmatched opener is rendered literally rather than swallowing the rest`() {
        // A typo in the policy must cost two asterisks, never the paragraph
        // after them.
        val runs = boldRuns("Retention is **thirty (30) days for closed accounts.")
        assertEquals("Retention is **thirty (30) days for closed accounts.", runs.text)
        assertTrue(runs.bold.isEmpty())
    }

    @Test
    fun `an unmatched closer is rendered literally`() {
        val runs = boldRuns("Retention is thirty (30) days.** No exceptions.")
        assertEquals("Retention is thirty (30) days.** No exceptions.", runs.text)
        assertTrue(runs.bold.isEmpty())
    }

    @Test
    fun `an empty span produces no range`() {
        val runs = boldRuns("nothing **** here")
        assertTrue(runs.bold.isEmpty())
        assertEquals("nothing  here", runs.text)
    }

    @Test
    fun `no character of the source is ever lost`() {
        // The property that matters most for a legal notice: whatever the
        // parser decides about styling, the words survive.
        val everyString = PrivacyPolicyContent.sections.flatMap { section ->
            section.body.flatMap { block ->
                when (block) {
                    is PrivacyContent.Paragraph -> listOf(block.text)
                    is PrivacyContent.Subheading -> listOf(block.title)
                    is PrivacyContent.Bullets -> block.items
                }
            }
        }
        for (source in everyString) {
            val runs = boldRuns(source)
            assertEquals(
                "markers stripped changed the text of: $source",
                source.replace("**", ""),
                runs.text,
            )
        }
    }

    @Test
    fun `every bold range lies inside its text`() {
        val everyBulleted = PrivacyPolicyContent.sections
            .flatMap { it.body }
            .filterIsInstance<PrivacyContent.Bullets>()
            .flatMap { it.items }
        for (source in everyBulleted) {
            val runs = boldRuns(source)
            for (range in runs.bold) {
                assertTrue("range $range escapes '${runs.text}'", range.first >= 0)
                assertTrue("range $range escapes '${runs.text}'", range.last < runs.text.length)
            }
        }
    }
}
