package com.memoria.caregiver.ui.components

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Photo-resolution rules. Spec: DesignSystem/Components/PatientAvatar.swift.
 *
 * All Tier 1 — the whole of PatientAvatar's behavior is the refresh key and the
 * guarded, error-swallowing fetch, both of which are pure Kotlin.
 */
class PatientAvatarTest {

    private val patientA = "1FD3C0B4-2E5A-4C31-9C2B-000000000001"
    private val patientB = "1FD3C0B4-2E5A-4C31-9C2B-000000000002"

    // ---- refresh key ----

    /** "Same patient just uploaded a new photo" — the key must move. */
    @Test fun `a new storage key on the same patient re-resolves`() {
        assertNotEquals(
            patientAvatarRefreshKey(patientA, "old.jpg"),
            patientAvatarRefreshKey(patientA, "new.jpg"),
        )
    }

    /** "Switched to a different patient" — the key must move even with no photo. */
    @Test fun `different patients without photos still get different keys`() {
        assertNotEquals(
            patientAvatarRefreshKey(patientA, null),
            patientAvatarRefreshKey(patientB, null),
        )
    }

    /**
     * Deleting a photo has to be distinguishable from never having had one only
     * insofar as the key changes; null and the empty string collapse to the same
     * key, exactly as `photoStorageKey ?? ""` does on iOS. Pinned because a
     * "safer" `?: "none"` would silently refetch every photoless avatar the
     * first time a backend sent `""`.
     */
    @Test fun `null and empty storage keys produce the same key`() {
        assertEquals(
            patientAvatarRefreshKey(patientA, null),
            patientAvatarRefreshKey(patientA, ""),
        )
    }

    /** Recomposing with unchanged inputs must not restart the fetch. */
    @Test fun `identical inputs produce an identical key`() {
        assertEquals(
            patientAvatarRefreshKey(patientA, "a.jpg"),
            patientAvatarRefreshKey(patientA, "a.jpg"),
        )
    }

    // ---- photo resolution ----

    private class RecordingResolver(private val result: String? = "https://signed/photo.jpg") {
        val calls = mutableListOf<String>()
        val resolve: suspend (String) -> String? = { id ->
            calls += id
            result
        }
    }

    @Test fun `a patient with no photo never calls the API`() {
        val resolver = RecordingResolver()
        val url = runBlocking { resolvePatientPhotoUrl(patientA, null, resolver.resolve) }
        assertNull(url)
        assertEquals(emptyList<String>(), resolver.calls)
    }

    /**
     * iOS guards on `!= nil`, not on emptiness. An empty-but-present key is a
     * backend state we do not control, and it must behave like "has a photo".
     */
    @Test fun `an empty storage key still calls the API`() {
        val resolver = RecordingResolver()
        val url = runBlocking { resolvePatientPhotoUrl(patientA, "", resolver.resolve) }
        assertEquals("https://signed/photo.jpg", url)
        assertEquals(listOf(patientA), resolver.calls)
    }

    @Test fun `the patient id is what gets sent to the API`() {
        val resolver = RecordingResolver()
        runBlocking { resolvePatientPhotoUrl(patientB, "b.jpg", resolver.resolve) }
        assertEquals(listOf(patientB), resolver.calls)
    }

    /**
     * The backend returns 404 when no photo is stored, which the iOS client maps
     * to a nil URL rather than an error. A null result must pass through as
     * "no photo", not as a failure.
     */
    @Test fun `a null result from the API is no photo`() {
        val resolver = RecordingResolver(result = null)
        assertNull(runBlocking { resolvePatientPhotoUrl(patientA, "a.jpg", resolver.resolve) })
    }

    /** `try?` — a thrown error yields the initials placeholder, not a crash. */
    @Test fun `a failing fetch falls back to no photo`() {
        val url = runBlocking {
            resolvePatientPhotoUrl(patientA, "a.jpg") { error("network down") }
        }
        assertNull(url)
    }

    /**
     * The one deliberate divergence from `try?`: cancellation propagates, so a
     * LaunchedEffect torn down mid-fetch actually stops instead of running to
     * completion and writing into a dead composition.
     */
    @Test fun `cancellation propagates instead of being swallowed`() {
        runBlocking {
            try {
                resolvePatientPhotoUrl(patientA, "a.jpg") { throw CancellationException("gone") }
                fail("expected CancellationException to propagate")
            } catch (expected: CancellationException) {
                assertEquals("gone", expected.message)
            }
        }
    }
}
