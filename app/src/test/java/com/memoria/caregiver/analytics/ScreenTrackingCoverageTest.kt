package com.memoria.caregiver.analytics

import com.memoria.caregiver.ui.nav.MainTab
import com.memoria.caregiver.ui.patient.PatientTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screen-name contract with iOS, read out of the source.
 *
 * A screen name is not a label, it is a join key: it is what a funnel query on
 * `ui_events` groups by, and iOS has been sending these names for months. A
 * renamed or missing one does not break anything visibly — it produces a funnel
 * with a step that looks unused, which is worse than an error because someone
 * acts on it.
 *
 * Six screens were untracked on Android when this was written — Roster, New
 * patient, Pairing code, Add detector, Detector library and the data-collection
 * consent sheet (FINDINGS.md F-28) — and nothing failed. This is what would have
 * failed.
 */
class ScreenTrackingCoverageTest {

    @Test
    fun `the app emits exactly the screen names iOS does, plus the ones listed here`() {
        assertEquals(
            "add a screen and you must say so here, so the iOS/Android funnels " +
                "stay comparable",
            expected,
            trackedScreenNames(),
        )
    }

    @Test
    fun `the tab bars carry the names, so a new tab cannot be untracked`() {
        // Both shells log `tab.analyticsName` from where the tab's content is
        // drawn, which is the port of iOS's onAppear. The name lives on the enum
        // constructor, so a tab added without one does not compile.
        assertEquals(listOf("home", "settings"), MainTab.entries.map { it.analyticsName })
        assertEquals(
            listOf(
                "patient.live",
                "patient.events",
                "patient.sensors",
                "patient.detectors",
                "patient.settings",
            ),
            PatientTab.entries.map { it.analyticsName },
        )
    }

    @Test
    fun `every full-surface overlay names a screen`() {
        // `screen` is a required parameter, so this cannot fail by omission —
        // it fails if someone adds an overlay presenter that is not ModalOverlay.
        val overlays = sourceFiles.sumOf { file ->
            OVERLAY_WITH_SCREEN.findAll(file.readText()).count()
        }
        val bareOverlays = sourceFiles.sumOf { file ->
            Regex("(?<!fun )ModalOverlay\\((?!screen)").findAll(file.readText()).count()
        }
        assertTrue("expected the overlay presenter to be in use", overlays >= 13)
        assertEquals("every ModalOverlay names its screen", 0, bareOverlays)
    }

    @Test
    fun `both tab bars log on appearance, not on tap`() {
        // iOS's `.trackScreen()` is an onAppear. Logging from a tab's onClick
        // instead loses two things silently: the tab a session STARTS on — Home,
        // and Live for every patient — and any tab the app selects for itself,
        // such as the Events tab a notification tap lands on. Both shells
        // therefore log from where the tab's content is drawn.
        //
        // Asserted against the source rather than by rendering: neither shell
        // can be composed without a live API client and a patient row, and a
        // one-line regression here is invisible in production — it produces a
        // funnel that merely looks like nobody uses the app's busiest screens.
        for (shell in listOf("MainShell.kt", "PatientShell.kt")) {
            val text = sourceFiles.single { it.name == shell }.readText()
            assertTrue(
                "$shell must log its tab from a LaunchedEffect on the tab",
                "LaunchedEffect(tab) { analytics.logScreen(tab.analyticsName) }" in text,
            )
            assertTrue(
                "$shell must not log a screen from the tab bar's onClick",
                "analytics.logScreen(entry.analyticsName)" !in text,
            )
        }
    }

    // ------------------------------------------------------------------

    /**
     * Every screen name the app can emit: the two tab bars, every full-surface
     * overlay, and the remaining hand-written `logScreen` calls.
     */
    private fun trackedScreenNames(): Set<String> {
        val fromSource = sourceFiles.flatMap { file ->
            val text = file.readText()
            OVERLAY_WITH_SCREEN.findAll(text).map { it.groupValues[1] } +
                LOG_SCREEN_LITERAL.findAll(text).map { it.groupValues[1] } +
                SCREEN_NAME_CONSTANT.findAll(text).map { it.groupValues[1] }
        }
        return (
            fromSource +
                MainTab.entries.map { it.analyticsName } +
                PatientTab.entries.map { it.analyticsName }
            ).toSet()
    }

    private companion object {

        val OVERLAY_WITH_SCREEN = Regex("ModalOverlay\\(screen = \"([^\"]+)\"\\)")
        val LOG_SCREEN_LITERAL = Regex("logScreen\\(\"([^\"]+)\"\\)")

        /**
         * One screen keeps its name in a file-private constant rather than
         * inline. Still a fixed string; it just needs a second pattern.
         */
        val SCREEN_NAME_CONSTANT = Regex("const val ScreenName = \"([^\"]+)\"")

        /**
         * The 27 names iOS's `.trackScreen()` calls use, plus one Android-only
         * addition.
         *
         * `detector_detail` has no iOS counterpart: iOS never tracked its
         * detector editor. Android's overlay presenter requires a name, so the
         * choice was to name it or to build a way to opt out of tracking — and
         * an opt-out is the hole this whole mechanism exists to close. Recorded
         * in DIFFERENCES.md.
         */
        val expected = setOf(
            // signed out
            "auth.welcome",
            "auth.login",
            "auth.signup",
            "forgot_password",
            "reset_password",
            // signed in, top level
            "home",
            "settings",
            "new_patient",
            "edit_profile",
            "privacy_policy",
            "support_contact",
            "feedback",
            // the patient tabs
            "patient.live",
            "patient.events",
            "patient.sensors",
            "patient.detectors",
            "patient.settings",
            // presented over a patient
            "camera_detail",
            "pairing_code",
            "add_camera",
            "sensor_setup",
            "add_detector",
            "detector_library",
            "detector_detail",
            "face_enrollment",
            "data_collection_consent",
            "event_detail",
            "event_review",
        )

        val sourceFiles: List<File> by lazy {
            val candidates = listOf(
                "src/main/java/com/memoria/caregiver",
                "app/src/main/java/com/memoria/caregiver",
                "../app/src/main/java/com/memoria/caregiver",
            )
            val root = candidates.map { File(it).absoluteFile }.firstOrNull { it.isDirectory }
                ?: error("could not locate the main source tree from ${File("").absolutePath}")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }
}
