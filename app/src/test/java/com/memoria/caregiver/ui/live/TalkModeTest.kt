@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.memoria.caregiver.ui.live

import com.memoria.caregiver.data.model.Camera
import com.memoria.caregiver.data.remote.ApiError
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Talk mode: the call lifecycle, the loudspeaker guarantee, the copy, and the
 * pure display rules.
 *
 * Everything here is plain JVM. The controller only touches its four ports, so
 * the fakes below record an ORDERED event log across all of them — several of
 * the behaviours being pinned are about sequence (mute before mic-off, mic
 * publish before the loudspeaker override), and a per-fake counter cannot see
 * that.
 */
class TalkModeTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private fun camera(
        id: String = "cam-1",
        livekitIdentity: String? = "cam_1a2b3c4d",
        privacyEnabled: Boolean = false,
        sourceType: Camera.SourceType = Camera.SourceType.PHONE,
    ): Camera = Camera(
        disabled = false,
        aiPaused = false,
        sourceType = sourceType,
        roomClass = null,
        privacyEnabled = privacyEnabled,
        desiredState = Camera.DesiredState.CONNECTED,
        id = id,
        patientId = PATIENT,
        displayId = 1,
        room = "Living room",
        iconName = "tv",
        online = true,
        livekitIdentity = livekitIdentity,
        createdAt = Instant.fromEpochSeconds(1_760_000_000),
        paired = true,
        appPresent = null,
    )

    private class Harness {
        val events = mutableListOf<String>()
        var errorMessage: String? = null
        var errorWrites = 0

        /** Invoked with the 0-based call index, so one test can fail only the first. */
        var onStartTalk: suspend (Int) -> Unit = {}
        var onEndTalk: suspend () -> Unit = {}
        var onPublishMicrophone: suspend (Boolean) -> Unit = {}

        private var startTalkCount = 0

        val api = object : TalkApi {
            override suspend fun startTalk(patientId: String, cameraId: String) {
                events += "startTalk($patientId,$cameraId)"
                onStartTalk(startTalkCount++)
            }

            override suspend fun endTalk(patientId: String) {
                events += "endTalk($patientId)"
                onEndTalk()
            }
        }

        val media = object : TalkMedia {
            override suspend fun publishMicrophone(enable: Boolean) {
                events += "publishMicrophone($enable)"
                onPublishMicrophone(enable)
            }

            override fun setRemoteAudioPlayback(identity: String?) {
                events += "setRemoteAudioPlayback($identity)"
            }
        }

        val errors = TalkErrorSink {
            errorMessage = it
            errorWrites++
            events += "error(${it ?: "null"})"
        }

        var nowMillis: Long = 1_000_000L

        fun controller() = TalkController(
            patientId = PATIENT,
            api = api,
            media = media,
            errors = errors,
            log = TalkLog { events += "log($it)" },
            now = { nowMillis },
        )
    }

    // -----------------------------------------------------------------------
    // startCall
    // -----------------------------------------------------------------------

    @Test
    fun `startCall publishes the mic forces the loudspeaker unmutes the target then starts talk`() =
        runTest {
            val h = Harness()
            val c = h.controller()

            c.startCall(camera())

            assertEquals(
                listOf(
                    "publishMicrophone(true)",
                    "setRemoteAudioPlayback(cam_1a2b3c4d)",
                    "startTalk($PATIENT,cam-1)",
                    "error(null)",
                ),
                h.events,
            )
            assertEquals(TalkCallState.ACTIVE, c.state.value.callState)
            assertEquals(h.nowMillis, c.state.value.callStartedAtMillis!!)
        }

    // The loudspeaker override used to be sequenced here, against a fake route
    // this controller called itself. It now fires inside LiveKitClient on mic
    // publish, so ordering against `publishMicrophone` is no longer something
    // this unit can observe — it is guaranteed by construction instead, which
    // is the point of the move. The rule is pinned by AudioRoutingTest.

    @Test
    fun `startCall moves to CONNECTING before any of the work completes`() = runTest {
        val h = Harness()
        val c = h.controller()
        val gate = CompletableDeferred<Unit>()
        h.onPublishMicrophone = { gate.await() }

        val job = launch { c.startCall(camera()) }
        runCurrent()

        assertEquals(TalkCallState.CONNECTING, c.state.value.callState)
        assertNull(c.state.value.callStartedAtMillis)

        gate.complete(Unit)
        job.join()
        assertEquals(TalkCallState.ACTIVE, c.state.value.callState)
    }

    @Test
    fun `startCall refuses a privacy-enabled camera`() = runTest {
        val h = Harness()
        val c = h.controller()

        c.startCall(camera(privacyEnabled = true))

        assertEquals(emptyList<String>(), h.events)
        assertEquals(TalkCallState.IDLE, c.state.value.callState)
    }

    @Test
    fun `startCall does nothing without a target`() = runTest {
        val h = Harness()

        h.controller().startCall(null)

        assertEquals(emptyList<String>(), h.events)
    }

    @Test
    fun `startCall is ignored when a call is already up`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.startCall(camera(id = "cam-2"))

        assertEquals(emptyList<String>(), h.events)
    }

    @Test
    fun `a failed startCall unpublishes the mic mutes everything and reports the copy`() =
        runTest {
            val h = Harness()
            val c = h.controller()
            h.onStartTalk = { throw ApiError.Offline }

            c.startCall(camera())

            assertEquals(
                listOf(
                    "publishMicrophone(true)",
                    "setRemoteAudioPlayback(cam_1a2b3c4d)",
                    "startTalk($PATIENT,cam-1)",
                    "publishMicrophone(false)",
                    "setRemoteAudioPlayback(null)",
                    "error(Call failed: ${ApiError.Offline.errorDescription})",
                ),
                h.events,
            )
            assertEquals(TalkCallState.IDLE, c.state.value.callState)
            assertNull(c.state.value.callStartedAtMillis)
        }

    @Test
    fun `startCall on an unpaired row unmutes nothing rather than crashing`() = runTest {
        val h = Harness()

        h.controller().startCall(camera(livekitIdentity = null))

        assertTrue(h.events.contains("setRemoteAudioPlayback(null)"))
    }

    // -----------------------------------------------------------------------
    // endCall
    // -----------------------------------------------------------------------

    @Test
    fun `endCall mutes remote audio before dropping the mic and always posts talk end`() =
        runTest {
            val h = Harness()
            val c = h.controller()
            c.startCall(camera())
            h.events.clear()

            c.endCall()

            assertEquals(
                listOf(
                    "setRemoteAudioPlayback(null)",
                    "publishMicrophone(false)",
                    "endTalk($PATIENT)",
                ),
                h.events,
            )
        }

    @Test
    fun `endCall stamps the UI idle before touching the network`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        val gate = CompletableDeferred<Unit>()
        h.onEndTalk = { gate.await() }

        val job = launch { c.endCall() }
        runCurrent()

        assertEquals(TalkCallState.IDLE, c.state.value.callState)
        assertNull(c.state.value.callStartedAtMillis)

        gate.complete(Unit)
        job.join()
    }

    @Test
    fun `endCall does nothing at all when there is no call`() = runTest {
        val h = Harness()

        h.controller().endCall()

        assertEquals(emptyList<String>(), h.events)
    }

    @Test
    fun `endCall tears down from CONNECTING too`() = runTest {
        val h = Harness()
        val c = h.controller()
        val gate = CompletableDeferred<Unit>()
        h.onPublishMicrophone = { enable -> if (enable) gate.await() }
        val job = launch { c.startCall(camera()) }
        runCurrent()
        assertEquals(TalkCallState.CONNECTING, c.state.value.callState)
        h.events.clear()

        c.endCall()

        assertTrue(h.events.contains("endTalk($PATIENT)"))
        assertEquals(TalkCallState.IDLE, c.state.value.callState)

        gate.complete(Unit)
        job.join()
    }

    @Test
    fun `a failed talk end reports the copy and still leaves the call ended`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.onEndTalk = { throw ApiError.Offline }

        c.endCall()

        assertEquals(
            "End call failed: ${ApiError.Offline.errorDescription}",
            h.errorMessage,
        )
        assertEquals(TalkCallState.IDLE, c.state.value.callState)
    }

    @Test
    fun `a mic that will not unpublish is logged and does not stop the talk end`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.onPublishMicrophone = { enable -> if (!enable) error("audio engine is wedged") }
        h.events.clear()

        c.endCall()

        assertTrue(h.events.any { it.startsWith("log([endCall] publishMicrophone(false) failed") })
        assertTrue(h.events.contains("endTalk($PATIENT)"))
    }

    /**
     * iOS runs the hang-up in an unstructured `Task`, which the disappearing
     * view never cancels. Compose scopes do cancel, so the teardown runs under
     * `NonCancellable` — without it a caregiver could tap "end", navigate away,
     * and leave the wall phone broadcasting their kitchen.
     */
    @Test
    fun `a cancelled endCall still completes the mic drop and the talk end`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        val gate = CompletableDeferred<Unit>()
        h.onEndTalk = { gate.await() }
        h.events.clear()

        val job = launch { c.endCall() }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(
            listOf(
                "setRemoteAudioPlayback(null)",
                "publishMicrophone(false)",
                "endTalk($PATIENT)",
            ),
            h.events,
        )
    }

    // -----------------------------------------------------------------------
    // transferCall
    // -----------------------------------------------------------------------

    @Test
    fun `transferCall re-starts talk on the new camera and moves the audio gate`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.transferCall(camera(id = "cam-2", livekitIdentity = "cam_beef"))

        assertEquals(
            listOf(
                "startTalk($PATIENT,cam-2)",
                "setRemoteAudioPlayback(cam_beef)",
                "error(null)",
            ),
            h.events,
        )
    }

    @Test
    fun `transferCall never issues a talk end between targets`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.transferCall(camera(id = "cam-2"))

        assertFalse(h.events.any { it.startsWith("endTalk") })
    }

    @Test
    fun `transferCall refuses a privacy-enabled camera`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.transferCall(camera(id = "cam-2", privacyEnabled = true))

        assertEquals(emptyList<String>(), h.events)
    }

    @Test
    fun `a stale transfer failure cannot overwrite a newer transfer's result`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        val gate = CompletableDeferred<Unit>()
        // First transfer hangs, then fails. Second overtakes it and succeeds.
        h.onStartTalk = { index ->
            if (index == 1) {
                gate.await()
                throw ApiError.Offline
            }
        }

        val stale = launch { c.transferCall(camera(id = "cam-2")) }
        runCurrent()
        c.transferCall(camera(id = "cam-3"))
        gate.complete(Unit)
        stale.join()

        assertNull("the newer transfer's cleared error must survive", h.errorMessage)
    }

    @Test
    fun `the newest transfer's failure is reported`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.onStartTalk = { throw ApiError.Offline }

        c.transferCall(camera(id = "cam-2"))

        assertEquals(
            "Transfer failed: ${ApiError.Offline.errorDescription}",
            h.errorMessage,
        )
    }

    // -----------------------------------------------------------------------
    // Carousel swipes
    // -----------------------------------------------------------------------

    @Test
    fun `swiping to another phone camera transfers`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        val next = camera(id = "cam-2", livekitIdentity = "cam_beef")
        c.onSelectedCameraChanged("cam-2", next)

        assertTrue(h.events.contains("startTalk($PATIENT,cam-2)"))
        assertFalse(h.events.any { it.startsWith("endTalk") })
        assertEquals(TalkCallState.ACTIVE, c.state.value.callState)
    }

    @Test
    fun `swiping to a gateway camera hangs up instead of transferring`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        val gateway = camera(id = "cam-2", sourceType = Camera.SourceType.SENSOR)
        c.onSelectedCameraChanged("cam-2", gateway)

        assertTrue(h.events.contains("endTalk($PATIENT)"))
        assertFalse(h.events.any { it.startsWith("startTalk") })
        assertEquals(TalkCallState.IDLE, c.state.value.callState)
    }

    @Test
    fun `swiping to a privacy-enabled camera hangs up`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.onSelectedCameraChanged("cam-2", camera(id = "cam-2", privacyEnabled = true))

        assertTrue(h.events.contains("endTalk($PATIENT)"))
        assertEquals(TalkCallState.IDLE, c.state.value.callState)
    }

    @Test
    fun `swiping to a camera that no longer exists hangs up`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.onSelectedCameraChanged("cam-2", null)

        assertTrue(h.events.contains("endTalk($PATIENT)"))
    }

    @Test
    fun `an emptied carousel is not a hang-up instruction`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.onSelectedCameraChanged(null, null)

        assertEquals(emptyList<String>(), h.events)
        assertEquals(TalkCallState.ACTIVE, c.state.value.callState)
    }

    @Test
    fun `swiping with no call up does nothing`() = runTest {
        val h = Harness()

        h.controller().onSelectedCameraChanged("cam-2", camera(id = "cam-2"))

        assertEquals(emptyList<String>(), h.events)
    }

    // -----------------------------------------------------------------------
    // Privacy interlock
    // -----------------------------------------------------------------------

    @Test
    fun `privacy flipping on the call target tears the call down`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.onCallTargetPrivacyChanged(wasPrivate = false, isPrivate = true)

        assertTrue(h.events.contains("endTalk($PATIENT)"))
        assertEquals(TalkCallState.IDLE, c.state.value.callState)
    }

    @Test
    fun `privacy flipping mid-connect tears the in-flight call down too`() = runTest {
        val h = Harness()
        val c = h.controller()
        val gate = CompletableDeferred<Unit>()
        h.onPublishMicrophone = { enable -> if (enable) gate.await() }
        val job = launch { c.startCall(camera()) }
        runCurrent()
        h.events.clear()

        c.onCallTargetPrivacyChanged(wasPrivate = false, isPrivate = true)

        assertTrue(h.events.contains("endTalk($PATIENT)"))

        gate.complete(Unit)
        job.join()
    }

    @Test
    fun `privacy clearing is not a call event`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.onCallTargetPrivacyChanged(wasPrivate = true, isPrivate = false)

        assertEquals(emptyList<String>(), h.events)
        assertEquals(TalkCallState.ACTIVE, c.state.value.callState)
    }

    @Test
    fun `a privacy flip with no call up does nothing`() = runTest {
        val h = Harness()

        h.controller().onCallTargetPrivacyChanged(wasPrivate = false, isPrivate = true)

        assertEquals(emptyList<String>(), h.events)
    }

    // -----------------------------------------------------------------------
    // Leaving the tab
    // -----------------------------------------------------------------------

    @Test
    fun `leaving the tab during a call hangs up`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.events.clear()

        c.tabAwayCleanup()

        assertEquals(
            listOf("publishMicrophone(false)", "endTalk($PATIENT)"),
            h.events,
        )
        assertEquals(TalkCallState.IDLE, c.state.value.callState)
    }

    @Test
    fun `leaving the tab with no call up touches nothing but still resets the state`() = runTest {
        val h = Harness()
        val c = h.controller()

        c.tabAwayCleanup()

        assertEquals(emptyList<String>(), h.events)
        assertEquals(TalkCallState.IDLE, c.state.value.callState)
        assertNull(c.state.value.callStartedAtMillis)
    }

    @Test
    fun `tab-away failures are silent to the caregiver`() = runTest {
        val h = Harness()
        val c = h.controller()
        c.startCall(camera())
        h.onEndTalk = { throw ApiError.Offline }
        h.errorMessage = null
        h.errorWrites = 0

        c.tabAwayCleanup()

        assertEquals("the screen is being dismissed; there is nowhere to show it", 0, h.errorWrites)
        assertNull(h.errorMessage)
    }

    // -----------------------------------------------------------------------
    // The call button
    // -----------------------------------------------------------------------

    @Test
    fun `the button maps idle to start active to end and connecting to nothing`() {
        assertEquals(TalkButtonAction.START, talkButtonAction(TalkCallState.IDLE))
        assertEquals(TalkButtonAction.END, talkButtonAction(TalkCallState.ACTIVE))
        assertEquals(TalkButtonAction.NONE, talkButtonAction(TalkCallState.CONNECTING))
    }

    @Test
    fun `tapping mid-connect does nothing`() = runTest {
        val h = Harness()
        val c = h.controller()
        val gate = CompletableDeferred<Unit>()
        h.onPublishMicrophone = { enable -> if (enable) gate.await() }
        val job = launch { c.startCall(camera()) }
        runCurrent()
        h.events.clear()

        c.onCallButtonTap(camera())

        assertEquals(emptyList<String>(), h.events)

        gate.complete(Unit)
        job.join()
    }

    @Test
    fun `the call button shows only on the selected phone camera`() {
        assertTrue(shouldShowCallButton(camera(), isThisCallTarget = true))
        assertFalse(shouldShowCallButton(camera(), isThisCallTarget = false))
    }

    @Test
    fun `the call button is hidden on a private room`() {
        assertFalse(
            shouldShowCallButton(camera(privacyEnabled = true), isThisCallTarget = true),
        )
    }

    @Test
    fun `the call button is hidden on a camera with no speaker`() {
        assertFalse(
            shouldShowCallButton(
                camera(sourceType = Camera.SourceType.SENSOR),
                isThisCallTarget = true,
            ),
        )
    }

    @Test
    fun `the kill switch hides the call button without touching anything else`() {
        assertFalse(
            shouldShowCallButton(camera(), isThisCallTarget = true, talkEnabled = false),
        )
        assertTrue("the feature ships on", TALK_ENABLED)
    }

    // -----------------------------------------------------------------------
    // Waveform wiring
    // -----------------------------------------------------------------------

    @Test
    fun `the waveform shows only on the active call's own tile`() {
        assertTrue(shouldShowTalkWaveform(TalkCallState.ACTIVE, "cam-1", "cam-1"))
        assertFalse(shouldShowTalkWaveform(TalkCallState.ACTIVE, "cam-2", "cam-1"))
        assertFalse(shouldShowTalkWaveform(TalkCallState.IDLE, "cam-1", "cam-1"))
        assertFalse(shouldShowTalkWaveform(TalkCallState.CONNECTING, "cam-1", "cam-1"))
        assertFalse(shouldShowTalkWaveform(TalkCallState.ACTIVE, "cam-1", null))
    }

    @Test
    fun `waveform levels come from the camera's own identity`() {
        val levels = mapOf("cam_a" to listOf(0.1f, 0.9f), "cam_b" to listOf(0.5f))

        assertEquals(listOf(0.1f, 0.9f), talkWaveformLevels(levels, "cam_a"))
        assertEquals(emptyList<Float>(), talkWaveformLevels(levels, "cam_c"))
        assertEquals(emptyList<Float>(), talkWaveformLevels(levels, null))
    }

    @Test
    fun `the waveform keeps the iOS frame`() {
        assertEquals(100f, TALK_WAVEFORM_WIDTH.value, 0f)
        assertEquals(22f, TALK_WAVEFORM_HEIGHT.value, 0f)
    }

    @Test
    fun `the on-air timer ticks once a second`() {
        assertEquals(1_000L, TALK_TIMER_TICK_MILLIS)
    }

    // -----------------------------------------------------------------------
    // The status line
    // -----------------------------------------------------------------------

    @Test
    fun `the status line counts up in minutes and seconds`() {
        val start = 1_000_000L
        assertEquals(
            "ON AIR · 00:00",
            talkStatusLine(TalkCallState.ACTIVE, start, start),
        )
        assertEquals(
            "ON AIR · 00:09",
            talkStatusLine(TalkCallState.ACTIVE, start, start + 9_999L),
        )
        assertEquals(
            "ON AIR · 01:01",
            talkStatusLine(TalkCallState.ACTIVE, start, start + 61_000L),
        )
    }

    /** iOS does not wrap the minutes field, and neither do we. */
    @Test
    fun `an hour-long call reads sixty minutes not one hour`() {
        val start = 0L
        assertEquals("ON AIR · 60:00", talkStatusLine(TalkCallState.ACTIVE, start, 3_600_000L))
    }

    @Test
    fun `the status line drops the timer when there is no start stamp`() {
        assertEquals("ON AIR", talkStatusLine(TalkCallState.ACTIVE, null, 1L))
    }

    @Test
    fun `the status line yields to the sensor label when no call is up`() {
        assertNull(talkStatusLine(TalkCallState.IDLE, 0L, 1_000L))
        assertNull(talkStatusLine(TalkCallState.CONNECTING, 0L, 1_000L))
    }

    @Test
    fun `the separator is a middle dot`() {
        val line = talkStatusLine(TalkCallState.ACTIVE, 0L, 0L)!!
        assertTrue("expected U+00B7, got: $line", line.contains('·'))
    }

    // -----------------------------------------------------------------------
    // Copy
    // -----------------------------------------------------------------------

    @Test
    fun `the three failure sentences are verbatim`() {
        assertEquals("Call failed: no wifi", callFailedMessage("no wifi"))
        assertEquals("End call failed: no wifi", endCallFailedMessage("no wifi"))
        assertEquals("Transfer failed: no wifi", transferFailedMessage("no wifi"))
    }

    @Test
    fun `an ApiError contributes the product's own sentence`() {
        assertEquals(
            ApiError.Offline.errorDescription,
            talkErrorDescription(ApiError.Offline),
        )
        assertEquals("boom", talkErrorDescription(IllegalStateException("boom")))
    }

    // -----------------------------------------------------------------------
    // The token
    // -----------------------------------------------------------------------

    // The identity, room and role strings are pinned by LiveKitIdentityTest,
    // which is where the single definition of them now lives. Three tests here
    // asserted the same values against a second copy; both the copy and these
    // went away together.

    // -----------------------------------------------------------------------
    // Audio route
    // -----------------------------------------------------------------------

    // The loudspeaker rule moved to data/livekit/AudioRouting.kt, where it is
    // applied on mic publish and pinned by AudioRoutingTest. The test that
    // stood here only asserted that a null handler did not crash; it never
    // covered the headset rule, which is the part that can be wrong in a way a
    // caregiver notices.

    private companion object {
        const val PATIENT = "pat-1"
    }
}
