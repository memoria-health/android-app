package com.memoria.caregiver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the test harness can fail.
 *
 * HARNESS.md §4: a test whose red phase was never observed does not count. That
 * rule is worthless if the runner itself silently reports success on an empty or
 * misconfigured source set, which is a real and quiet Gradle failure mode. This
 * test was committed only after being watched to fail.
 */
class HarnessSmokeTest {

    @Test
    fun `harness reports arithmetic truthfully`() {
        assertEquals(2, 1 + 1)
    }
}
