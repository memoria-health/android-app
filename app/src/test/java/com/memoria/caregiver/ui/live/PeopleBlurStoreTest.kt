package com.memoria.caregiver.ui.live

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The device-local "Blur people" preference.
 *
 * Small, but it decides whether a caregiver's live tile blurs people, and it has
 * to survive a relaunch to be worth anything — the toggle would otherwise reset
 * itself every time the app is killed, silently, in the direction of less
 * privacy.
 */
@RunWith(RobolectricTestRunner::class)
class PeopleBlurStoreTest {

    private lateinit var prefs: SharedPreferences

    private val patientA = "11111111-1111-1111-1111-111111111111"
    private val patientB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("people-blur-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test fun `the preferences key is the iOS key verbatim`() {
        assertEquals("peopleBlur.enabledPatientIDs", PeopleBlurStore.KEY)
    }

    @Test fun `blur is off for a patient nobody has enabled it for`() {
        val store = PeopleBlurStore(prefs)

        assertFalse(store.isEnabled(patientA))
        assertTrue(store.enabledIds.value.isEmpty())
    }

    @Test fun `enabling blur for one patient leaves the others alone`() {
        val store = PeopleBlurStore(prefs)

        store.setEnabled(true, patientA)

        assertTrue(store.isEnabled(patientA))
        assertFalse(store.isEnabled(patientB))
    }

    @Test fun `the preference survives a relaunch`() {
        PeopleBlurStore(prefs).setEnabled(true, patientA)

        val reopened = PeopleBlurStore(prefs)

        assertTrue(reopened.isEnabled(patientA))
    }

    @Test fun `turning it off removes the patient rather than storing a false`() {
        val store = PeopleBlurStore(prefs)
        store.setEnabled(true, patientA)

        store.setEnabled(false, patientA)

        assertFalse(store.isEnabled(patientA))
        assertEquals(emptySet<String>(), prefs.getStringSet(PeopleBlurStore.KEY, null))
        assertFalse(PeopleBlurStore(prefs).isEnabled(patientA))
    }

    @Test fun `the exposed set updates so a live tile can swap the instant it flips`() {
        val store = PeopleBlurStore(prefs)
        val seen = mutableListOf<Set<String>>()
        seen += store.enabledIds.value

        store.setEnabled(true, patientA)
        seen += store.enabledIds.value
        store.setEnabled(true, patientB)
        seen += store.enabledIds.value
        store.setEnabled(false, patientA)
        seen += store.enabledIds.value

        assertEquals(
            listOf(
                emptySet(),
                setOf(patientA),
                setOf(patientA, patientB),
                setOf(patientB),
            ),
            seen,
        )
    }

    /**
     * A live tile watches this flow. A no-op flip that still emits would rebuild
     * the video renderer for nothing.
     */
    @Test fun `setting the value it already has emits nothing`() {
        val store = PeopleBlurStore(prefs)
        store.setEnabled(true, patientA)
        val first = store.enabledIds.value

        store.setEnabled(true, patientA)

        assertTrue(first === store.enabledIds.value)
    }
}
