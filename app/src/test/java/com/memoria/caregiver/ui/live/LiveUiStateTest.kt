package com.memoria.caregiver.ui.live

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.ui.theme.Danger
import com.memoria.caregiver.ui.theme.Ink3
import com.memoria.caregiver.ui.theme.Success
import com.memoria.caregiver.ui.theme.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live tab's decisions. Spec: `PatientLiveView.swift`, PARITY.md §18.
 *
 * Pure JVM — none of this needs a composition, which is the whole reason it was
 * lifted out of the screen. Layout is judged by screenshot per DECISIONS.md
 * D-004; what is pinned here is every rule the Swift comments defend at length,
 * because each one is a bug someone already paid for.
 */
class LiveUiStateTest {

    // -----------------------------------------------------------------------
    // Which cameras reach the carousel
    // -----------------------------------------------------------------------

    /**
     * A held track is ground truth and ALWAYS shows. This is what keeps a
     * streaming camera visible when its `desired_state` has not caught up.
     */
    @Test
    fun `a camera we hold a track for shows even against a disconnected intent`() {
        val camera = liveCamera(
            livekitIdentity = "cam_aaaa",
            online = false,
            desiredState = Camera.DesiredState.DISCONNECTED,
        )

        assertEquals(
            listOf(camera),
            onlineCameras(listOf(camera), trackIdentities = setOf("cam_aaaa")),
        )
    }

    /** Central state says streaming and the intent still agrees: the bridge tile. */
    @Test
    fun `a camera the backend calls online shows before its track lands`() {
        val camera = liveCamera(online = true, desiredState = Camera.DesiredState.CONNECTED)

        assertEquals(listOf(camera), onlineCameras(listOf(camera), trackIdentities = emptySet()))
    }

    /**
     * The intent check on the `online` branch is what makes a just-disconnected
     * camera drop out immediately. `online` lags stale-true for seconds after a
     * disconnect-all, so without it the tile would linger as a placeholder
     * instead of falling back to the offline card.
     */
    @Test
    fun `a just-disconnected camera drops out at once despite a stale online flag`() {
        val camera = liveCamera(online = true, desiredState = Camera.DesiredState.DISCONNECTED)

        assertEquals(emptyList<Camera>(), onlineCameras(listOf(camera), emptySet()))
    }

    @Test
    fun `a camera with no identity never reaches the carousel`() {
        val camera = liveCamera(livekitIdentity = null)

        assertEquals(emptyList<Camera>(), onlineCameras(listOf(camera), emptySet()))
    }

    @Test
    fun `a user-hidden camera never reaches the carousel, track or not`() {
        val camera = liveCamera(livekitIdentity = "cam_aaaa", disabled = true)

        assertEquals(emptyList<Camera>(), onlineCameras(listOf(camera), setOf("cam_aaaa")))
    }

    /**
     * There is no `cameras_paused` gate. The bulk lever cascades per-camera
     * intent; it does not decide what the caregiver can see.
     */
    @Test
    fun `the carousel does not consult the patient-level pause flag`() {
        val camera = liveCamera()
        val paused = LiveVideoState(cameras = listOf(camera), camerasPaused = true)

        assertEquals(listOf(camera), paused.onlineCameras)
    }

    // -----------------------------------------------------------------------
    // Privacy
    // -----------------------------------------------------------------------

    @Test
    fun `the explainer covers a fail-closed classification, not just a known room`() {
        val unknownRoom = liveCamera(id = "cam-u", privacyEnabled = true, roomClass = null)

        assertEquals(
            listOf(unknownRoom),
            unacknowledgedSensitiveCameras(listOf(unknownRoom), acknowledgedIds = emptySet()),
        )
    }

    @Test
    fun `a dismissed explainer stays dismissed and reveals the next camera`() {
        val first = liveCamera(id = "cam-a", privacyEnabled = true)
        val second = liveCamera(id = "cam-b", privacyEnabled = true)

        val remaining = unacknowledgedSensitiveCameras(listOf(first, second), setOf("cam-a"))

        assertEquals(listOf(second), remaining)
    }

    @Test
    fun `a hidden camera never surfaces an explainer`() {
        val hidden = liveCamera(privacyEnabled = true, disabled = true)

        assertEquals(
            emptyList<Camera>(),
            unacknowledgedSensitiveCameras(listOf(hidden), emptySet()),
        )
    }

    /**
     * The copy names a guess as a guess. A classifier that put the camera in a
     * known private room says "looks like"; a fail-closed one states it flatly.
     */
    @Test
    fun `the explainer copy distinguishes a classified room from a fail-closed one`() {
        val bedroom = liveCamera(room = "Bedroom", privacyEnabled = true, roomClass = Camera.RoomClass.BEDROOM)
        val unknown = liveCamera(room = "Back room", privacyEnabled = true, roomClass = null)

        assertEquals("Bedroom is a private room", sensitiveRoomCopy(bedroom).title)
        assertEquals(
            "This camera looks like it's in a private room. Privacy is on by default: " +
                "no live view, and sensitive moments in clips are always censored.",
            sensitiveRoomCopy(bedroom).body,
        )
        assertEquals(
            "This camera is in a private room. Privacy is on: " +
                "no live view, and sensitive moments in clips are always censored.",
            sensitiveRoomCopy(unknown).body,
        )
    }

    /**
     * The host watches this and revokes audio for each. Sorted so an unrelated
     * roster reorder is not seen as a change.
     */
    @Test
    fun `private camera identities are sorted and exclude everything else`() {
        val cameras = listOf(
            liveCamera(id = "1", livekitIdentity = "cam_zzzz", privacyEnabled = true),
            liveCamera(id = "2", livekitIdentity = "cam_mmmm", privacyEnabled = false),
            liveCamera(id = "3", livekitIdentity = "cam_aaaa", privacyEnabled = true),
        )

        assertEquals(listOf("cam_aaaa", "cam_zzzz"), privateCameraIdentities(cameras))
    }

    @Test
    fun `the call target's privacy is read off the current selection`() {
        val cameras = listOf(
            liveCamera(id = "cam-a", privacyEnabled = false),
            liveCamera(id = "cam-b", privacyEnabled = true),
        )

        assertFalse(callTargetPrivacyOn(cameras, "cam-a"))
        assertTrue(callTargetPrivacyOn(cameras, "cam-b"))
        assertFalse(callTargetPrivacyOn(cameras, null))
    }

    // -----------------------------------------------------------------------
    // Selection
    // -----------------------------------------------------------------------

    /** Do not yank the caregiver off their tile mid-swipe or mid-call. */
    @Test
    fun `a still-valid selection survives a change to the live set`() {
        assertEquals("cam-b", resolveSelection("cam-b", listOf("cam-a", "cam-b", "cam-c")))
    }

    /**
     * The first camera coming online while we are already on the Live tab must
     * resolve a selection, or no tile is the call target and the Call button
     * stays hidden.
     */
    @Test
    fun `a stale or absent selection falls to the first online camera`() {
        assertEquals("cam-a", resolveSelection("gone", listOf("cam-a", "cam-b")))
        assertEquals("cam-a", resolveSelection(null, listOf("cam-a", "cam-b")))
        assertEquals(null, resolveSelection("gone", emptyList()))
    }

    @Test
    fun `the server's active-camera guess wins only when it is actually online`() {
        assertEquals("cam-b", resolveSelectionFromServer("cam-b", listOf("cam-a", "cam-b")))
        assertEquals("cam-a", resolveSelectionFromServer("cam-offline", listOf("cam-a")))
        assertEquals("cam-a", resolveSelectionFromServer(null, listOf("cam-a")))
    }

    // -----------------------------------------------------------------------
    // Tile chrome
    // -----------------------------------------------------------------------

    @Test
    fun `connectivity is unpaired without an identity, and room-down without a room`() {
        assertEquals(
            LiveConnectivity.Unpaired,
            liveConnectivity(liveCamera(livekitIdentity = null), emptySet(), roomConnected = true),
        )
        assertEquals(
            LiveConnectivity.RoomDown,
            liveConnectivity(liveCamera(), setOf("cam_1a2b3c4d"), roomConnected = false),
        )
    }

    /**
     * Connectivity deliberately does NOT consult `online`: the track map is the
     * direct "video is flowing right now" signal and the backend column can lag
     * it by about ten seconds.
     */
    @Test
    fun `connectivity follows the track map, not the backend online flag`() {
        val offlineButStreaming = liveCamera(livekitIdentity = "cam_aaaa", online = false)
        val onlineButSilent = liveCamera(livekitIdentity = "cam_bbbb", online = true)

        assertEquals(
            LiveConnectivity.Live,
            liveConnectivity(offlineButStreaming, setOf("cam_aaaa"), roomConnected = true),
        )
        assertEquals(
            LiveConnectivity.Offline,
            liveConnectivity(onlineButSilent, emptySet(), roomConnected = true),
        )
    }

    /**
     * Every live tile reads ON AIR during a call, not only the call target — the
     * Swift reads the global call state here. Carried verbatim.
     */
    @Test
    fun `the live pill reads ON AIR for every tile while a call is up`() {
        val pill = statePill(liveCamera(), LiveConnectivity.Live, callActive = true)

        assertEquals(LiveStatePill.Live(onAir = true), pill)
    }

    @Test
    fun `the live pill reads LIVE when no call is up`() {
        assertEquals(
            LiveStatePill.Live(onAir = false),
            statePill(liveCamera(), LiveConnectivity.Live, callActive = false),
        )
    }

    /**
     * The WAITING and CONNECTING pills were removed: there is deliberately no
     * loading indication in the pill. App-closed still surfaces, because it is
     * actionable — the phone has to be reopened.
     */
    @Test
    fun `only the app-closed state earns a pill when a camera is not live`() {
        assertEquals(
            LiveStatePill.None,
            statePill(liveCamera(), LiveConnectivity.RoomDown, callActive = false),
        )
        assertEquals(
            LiveStatePill.None,
            statePill(liveCamera(), LiveConnectivity.Joining, callActive = false),
        )
        assertEquals(
            LiveStatePill.None,
            statePill(liveCamera(online = false), LiveConnectivity.Offline, callActive = false),
        )
        assertEquals(
            LiveStatePill.AppClosed,
            statePill(appClosedCamera(), LiveConnectivity.Offline, callActive = false),
        )
    }

    // -----------------------------------------------------------------------
    // Status line
    // -----------------------------------------------------------------------

    @Test
    fun `a tile that is not the call target reads its sensor number, zero-padded`() {
        val line = tileStatusLine(
            camera = liveCamera(displayId = 7),
            talk = TalkUiState(TalkCallState.ACTIVE, callStartedAtMillis = 0L),
            selectedCameraId = "someone-else",
            nowMillis = 60_000L,
        )

        assertEquals("SENSOR 07", line)
    }

    @Test
    fun `the call target counts up in minutes and seconds`() {
        fun at(elapsedMillis: Long) = tileStatusLine(
            camera = liveCamera(id = "cam-1"),
            talk = TalkUiState(TalkCallState.ACTIVE, callStartedAtMillis = 1_000_000L),
            selectedCameraId = "cam-1",
            nowMillis = 1_000_000L + elapsedMillis,
        )

        assertEquals("ON AIR · 00:00", at(0))
        assertEquals("ON AIR · 00:09", at(9_400))
        assertEquals("ON AIR · 01:05", at(65_000))
        assertEquals("ON AIR · 12:34", at(754_000))
    }

    /** No start stamp yet: the label says ON AIR rather than inventing a time. */
    @Test
    fun `the call target with no start stamp reads plain ON AIR`() {
        val line = tileStatusLine(
            camera = liveCamera(id = "cam-1"),
            talk = TalkUiState(TalkCallState.ACTIVE, callStartedAtMillis = null),
            selectedCameraId = "cam-1",
            nowMillis = 5_000L,
        )

        assertEquals("ON AIR", line)
    }

    @Test
    fun `an idle call leaves every tile on its sensor number`() {
        val line = tileStatusLine(
            camera = liveCamera(id = "cam-1", displayId = 3),
            talk = TalkUiState(TalkCallState.IDLE, callStartedAtMillis = 1_000L),
            selectedCameraId = "cam-1",
            nowMillis = 61_000L,
        )

        assertEquals("SENSOR 03", line)
    }

    // -----------------------------------------------------------------------
    // The status dot
    // -----------------------------------------------------------------------

    /**
     * Intent beats a lagging `online`. The stale-online sweeper takes seconds to
     * write false, and checking `online` first kept the dot green right after a
     * disconnect while the header already read "0 ONLINE".
     */
    @Test
    fun `a deliberate disconnect wins over a stale online flag`() {
        val camera = liveCamera(online = true, desiredState = Camera.DesiredState.DISCONNECTED)

        assertEquals(Danger, cameraStatusDotColor(camera))
    }

    @Test
    fun `a streaming sensor is green`() =
        assertEquals(Success, cameraStatusDotColor(liveCamera(online = true)))

    /**
     * AI pause never changes the color: the feed keeps streaming, so an online
     * sensor is green either way — which is why resuming AI never has to turn it
     * back green, it never left.
     */
    @Test
    fun `an ai-paused sensor is still green`() =
        assertEquals(Success, cameraStatusDotColor(liveCamera(online = true, aiPaused = true)))

    @Test
    fun `a closed sensor app is dark, the one state reconnect cannot fix`() =
        assertEquals(Ink3, cameraStatusDotColor(appClosedCamera()))

    @Test
    fun `connected intent with no stream yet is amber`() =
        assertEquals(Warning, cameraStatusDotColor(liveCamera(online = false)))

    // -----------------------------------------------------------------------
    // The sensor list
    // -----------------------------------------------------------------------

    @Test
    fun `the sensor row spells the closed-app state out rather than leaving it to the dot`() {
        assertEquals("APP CLOSED", cameraRowTrailingLabel(appClosedCamera()))
        assertEquals("Sensor 04", cameraRowTrailingLabel(liveCamera(displayId = 4)))
    }

    // -----------------------------------------------------------------------
    // The offline card
    // -----------------------------------------------------------------------

    @Test
    fun `all sensors offline points at the sensors, not at reconnect`() {
        val copy = offlineCardCopy(listOf(liveCamera(online = false)), camerasPaused = false)

        assertEquals("antenna.radiowaves.left.and.right.slash", copy.icon)
        assertEquals("All sensors are offline", copy.title)
        assertEquals(
            "All available Memoria Sensors are offline, not open, or disconnected.",
            copy.detailMarkdown,
        )
    }

    @Test
    fun `a closed sensor app gets its own title`() {
        val copy = offlineCardCopy(listOf(appClosedCamera()), camerasPaused = false)

        assertEquals("iphone.slash", copy.icon)
        assertEquals("Memoria Sensor is closed", copy.title)
    }

    @Test
    fun `paused monitoring points at reconnect-all, in bold`() {
        val copy = offlineCardCopy(listOf(liveCamera(online = false)), camerasPaused = true)

        assertEquals("pause.circle", copy.icon)
        assertEquals("Monitoring paused", copy.title)
        assertEquals(
            "Tap **Reconnect all sensors** below to bring your sensors back online.",
            copy.detailMarkdown,
        )
    }

    /**
     * Reconnect-all cannot revive a phone whose app is closed — nothing is
     * running there to receive the intent — so the paused copy has to say so, and
     * has to get the singular and plural right.
     */
    @Test
    fun `paused monitoring warns that closed apps will not come back`() {
        val one = offlineCardCopy(
            listOf(appClosedCamera(id = "a"), liveCamera(id = "b", online = false)),
            camerasPaused = true,
        )
        val many = offlineCardCopy(
            listOf(appClosedCamera(id = "a"), appClosedCamera(id = "b", displayId = 3)),
            camerasPaused = true,
        )

        assertEquals(
            "Tap **Reconnect all sensors** below to bring your sensors back online. " +
                "1 sensor has its app closed, so Reconnect won't bring it back " +
                "— reopen Memoria Sensor on that phone.",
            one.detailMarkdown,
        )
        assertEquals(
            "Tap **Reconnect all sensors** below to bring your sensors back online. " +
                "2 sensors have their app closed, so Reconnect won't bring those back " +
                "— reopen Memoria Sensor on those phones.",
            many.detailMarkdown,
        )
    }

    /** A hidden camera is not something the caregiver has to go and reopen. */
    @Test
    fun `a hidden closed sensor is not counted in the warning`() {
        val copy = offlineCardCopy(
            listOf(appClosedCamera(disabled = true)),
            camerasPaused = true,
        )

        assertEquals(
            "Tap **Reconnect all sensors** below to bring your sensors back online.",
            copy.detailMarkdown,
        )
    }

    // -----------------------------------------------------------------------
    // The bulk lever
    // -----------------------------------------------------------------------

    /**
     * Disconnect-all only makes sense when there is a live sensor to turn off, so
     * it never sits nonsensically under an all-offline card.
     */
    @Test
    fun `the bulk lever offers reconnect whenever nothing is live`() {
        assertEquals(
            BulkMonitoringButton("Reconnect all sensors", "video.fill", isPrimary = true),
            bulkMonitoringButton(onlineCount = 0),
        )
        assertEquals(
            BulkMonitoringButton("Disconnect all sensors", "video.slash", isPrimary = false),
            bulkMonitoringButton(onlineCount = 1),
        )
    }

    // -----------------------------------------------------------------------
    // Geometry and markdown
    // -----------------------------------------------------------------------

    @Test
    fun `the carousel is sixteen-by-nine of the content width plus dot headroom`() {
        assertEquals(36.dp, PageDotHeadroom)
        assertEquals(32.dp, PageDotPadding)
        assertEquals((343f * 9f / 16f).dp + 36.dp, liveTileHeight(343.dp))
    }

    @Test
    fun `bold runs survive the markdown, and the rest is left alone`() {
        val rendered = liveMarkdownBold("Tap **Reconnect all sensors** below.")

        assertEquals("Tap Reconnect all sensors below.", rendered.text)
        val bold = rendered.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals("Reconnect all sensors", rendered.text.substring(bold.start, bold.end))
    }

    @Test
    fun `plain copy carries no spans`() {
        val rendered = liveMarkdownBold("All available Memoria Sensors are offline.")

        assertEquals("All available Memoria Sensors are offline.", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty())
    }

    /** Malformed markdown is emitted literally rather than swallowing the tail. */
    @Test
    fun `an unterminated bold marker is left as written`() {
        assertEquals("Tap **Reconnect", liveMarkdownBold("Tap **Reconnect").text)
    }
}
