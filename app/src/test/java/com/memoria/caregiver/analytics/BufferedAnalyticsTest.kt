@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.memoria.caregiver.analytics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The buffer, the flush, the persistence and the kill switch — the four things
 * `Services/Analytics.swift` does that Android had none of (FINDINGS.md F-28).
 *
 * Every test drives virtual time. The flush interval is an hour, so a real-time
 * test would either take an hour or prove nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BufferedAnalyticsTest {

    private val caregiverA = "11111111-1111-4111-8111-111111111111"
    private val caregiverB = "22222222-2222-4222-8222-222222222222"

    /** Records what was asked of it and can be told to fail. */
    private class FakeUploader : AnalyticsUploader {
        val batches = mutableListOf<List<UiEvent>>()
        var failuresRemaining = 0

        /** Every event this uploader has accepted, flattened. */
        val uploaded: List<UiEvent> get() = batches.flatten()

        override suspend fun upload(events: List<UiEvent>) {
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IllegalStateException("upload failed")
            }
            batches += events
        }
    }

    private fun device() = DeviceContext(
        appBuild = "0.1.0 (1)",
        osVersion = "15",
        deviceModel = "Pixel 8",
    )

    // ------------------------------------------------------------------
    // The configured numbers
    // ------------------------------------------------------------------

    @Test
    fun `the cadence and the sizes are the ones iOS uses`() {
        // Asserted as literals, deliberately. Every other test in this class
        // reads these constants, so changing one moves the test's own goalposts
        // and the change goes unnoticed — a mutation run proved exactly that.
        // These four numbers are a contract with `Services/Analytics.swift`:
        // the same cadence on both platforms is what makes a funnel comparable,
        // and the batch size is what the `ui_events` insert is tuned for.
        assertEquals("hourly, in milliseconds", 3_600_000L, AnalyticsConfig.FLUSH_INTERVAL_MS)
        assertEquals("a crash loses at most 24 events", 25, AnalyticsConfig.PERSIST_EVERY_N)
        assertEquals(5_000, AnalyticsConfig.MAX_BUFFERED_EVENTS)
        assertEquals(200, AnalyticsConfig.BATCH_SIZE)
        assertEquals("ui_events", AnalyticsConfig.TABLE)
        assertTrue("the kill switch ships on", AnalyticsConfig.ENABLED)
    }

    // ------------------------------------------------------------------
    // Buffering
    // ------------------------------------------------------------------

    @Test
    fun `log calls buffer and send nothing on their own`() = runTest {
        val uploader = FakeUploader()
        val analytics = BufferedAnalytics(
            scope = backgroundScope,
            store = InMemoryAnalyticsStore(),
            uploader = uploader,
            caregiverId = { caregiverA },
            device = device(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        analytics.start()
        runCurrent()

        analytics.logScreen("home")
        analytics.logButton("Add a patient")
        analytics.logToggle("people_blur.toggle", isOn = true)
        analytics.logLifecycle("app_background")
        runCurrent()

        assertEquals(
            "logging must not upload; only a flush does",
            emptyList<UiEvent>(),
            uploader.uploaded,
        )
        assertEquals(4, analytics.bufferedForTest().size)
    }

    @Test
    fun `a screen becomes the context stamped on later events`() = runTest {
        val analytics = newAnalytics(caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        analytics.logButton("Contact support")
        analytics.logScreen("patient.settings")
        analytics.logButton("Remove this patient")
        analytics.logLifecycle("app_background")

        val buffered = analytics.bufferedForTest()
        assertNull("no screen has been logged yet", buffered[0].screen)
        assertEquals("patient.settings", buffered[1].screen)
        assertEquals("patient.settings", buffered[2].screen)
        assertEquals(
            "lifecycle events carry the screen too, as iOS does",
            "patient.settings",
            buffered[3].screen,
        )
    }

    @Test
    fun `events carry the session and a monotonic sequence`() = runTest {
        val analytics = newAnalytics(caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        repeat(5) { analytics.logButton("tap") }

        val buffered = analytics.bufferedForTest()
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), buffered.map { it.seq })
        assertEquals(1, buffered.map { it.sessionId }.distinct().size)
        assertEquals(
            "each event needs its own id — it is the upsert conflict key",
            5,
            buffered.map { it.id }.distinct().size,
        )
    }

    @Test
    fun `the buffer is capped and drops the oldest`() = runTest {
        val analytics = newAnalytics(caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        repeat(AnalyticsConfig.MAX_BUFFERED_EVENTS + 10) { analytics.logButton("tap$it") }

        val buffered = analytics.bufferedForTest()
        assertEquals(AnalyticsConfig.MAX_BUFFERED_EVENTS, buffered.size)
        assertEquals("oldest dropped, newest kept", "tap10", buffered.first().name)
    }

    // ------------------------------------------------------------------
    // The flush trigger
    // ------------------------------------------------------------------

    @Test
    fun `the hourly worker drains the buffer`() = runTest {
        val uploader = FakeUploader()
        val analytics = newAnalytics(uploader = uploader, caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        analytics.logScreen("home")
        analytics.logButton("Add a patient")

        advanceTimeBy(AnalyticsConfig.FLUSH_INTERVAL_MS - 1)
        runCurrent()
        assertEquals("nothing before the interval elapses", 0, uploader.uploaded.size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("home", "Add a patient"), uploader.uploaded.map { it.name })
        assertEquals("sent events leave the buffer", 0, analytics.bufferedForTest().size)
    }

    @Test
    fun `a failed upload keeps the events for the next flush`() = runTest {
        val uploader = FakeUploader().apply { failuresRemaining = 1 }
        val analytics = newAnalytics(uploader = uploader, caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        analytics.logButton("Add a patient")

        advanceTimeBy(AnalyticsConfig.FLUSH_INTERVAL_MS + 1)
        runCurrent()
        assertEquals("the batch did not land", 0, uploader.uploaded.size)
        assertEquals("so it is still buffered", 1, analytics.bufferedForTest().size)

        advanceTimeBy(AnalyticsConfig.FLUSH_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("Add a patient"), uploader.uploaded.map { it.name })
    }

    @Test
    fun `only the signed-in caregiver's events are sent`() = runTest {
        var signedIn: String? = null
        val uploader = FakeUploader()
        val analytics = newAnalytics(uploader = uploader, caregiverId = { signedIn })
        analytics.start()
        runCurrent()

        // Before sign-in: no caregiver to attribute the event to.
        analytics.logScreen("auth.login")
        signedIn = caregiverA
        analytics.logScreen("home")
        // A second caregiver signs in on the same device.
        signedIn = caregiverB
        analytics.logScreen("home")

        signedIn = caregiverA
        advanceTimeBy(AnalyticsConfig.FLUSH_INTERVAL_MS + 1)
        runCurrent()

        assertEquals(
            "row-level security only accepts rows stamped with the caller",
            listOf(caregiverA),
            uploader.uploaded.map { it.caregiverId },
        )
        assertEquals(
            "the pre-sign-in event and the other caregiver's stay put",
            listOf(null, caregiverB),
            analytics.bufferedForTest().map { it.caregiverId },
        )
    }

    @Test
    fun `flushing sends in batches and does not lose events logged during the upload`() = runTest {
        // The read-modify-write shape FINDINGS.md F-12 is about: the buffer is
        // read, a suspending call happens, and the result is written back. If
        // the write-back were an assignment, everything logged in between would
        // be silently dropped.
        val analytics: BufferedAnalytics
        val uploader = object : AnalyticsUploader {
            val batches = mutableListOf<List<UiEvent>>()
            var onFirstBatch: (() -> Unit)? = null
            override suspend fun upload(events: List<UiEvent>) {
                batches += events
                onFirstBatch?.invoke()
                onFirstBatch = null
            }
        }
        analytics = newAnalytics(uploader = uploader, caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        repeat(AnalyticsConfig.BATCH_SIZE + 1) { analytics.logButton("tap$it") }
        uploader.onFirstBatch = { analytics.logButton("logged mid-flush") }

        advanceTimeBy(AnalyticsConfig.FLUSH_INTERVAL_MS + 1)
        runCurrent()

        assertEquals(
            "201 events at a batch size of 200 is two requests",
            listOf(AnalyticsConfig.BATCH_SIZE, 1),
            uploader.batches.map { it.size },
        )
        assertEquals(
            "the event logged while the upload was in flight survives",
            listOf("logged mid-flush"),
            analytics.bufferedForTest().map { it.name },
        )
    }

    // ------------------------------------------------------------------
    // Persistence across a process death
    // ------------------------------------------------------------------

    @Test
    fun `a buffer written to disk survives the process and flushes on next launch`() = runTest {
        val disk = InMemoryAnalyticsStore()
        val uploader = FakeUploader()

        // Run one: enough events to trip the persist threshold, then the process
        // dies. Nothing is uploaded and nothing calls a shutdown hook.
        val firstRun = newAnalytics(store = disk, caregiverId = { caregiverA })
        firstRun.start()
        runCurrent()
        repeat(AnalyticsConfig.PERSIST_EVERY_N) { firstRun.logButton("tap$it") }
        runCurrent()
        assertEquals(
            "the buffer reached disk without anyone asking",
            AnalyticsConfig.PERSIST_EVERY_N,
            disk.read().size,
        )

        // Run two: a new process, same disk.
        val secondRun = newAnalytics(store = disk, uploader = uploader, caregiverId = { caregiverA })
        secondRun.start()
        runCurrent()

        assertEquals(
            "the previous run's events go out at launch, not an hour later",
            AnalyticsConfig.PERSIST_EVERY_N,
            uploader.uploaded.size,
        )
        assertEquals("tap0", uploader.uploaded.first().name)
    }

    @Test
    fun `persistAndFlush writes everything, not just whole batches of 25`() = runTest {
        val disk = InMemoryAnalyticsStore()
        // Offline, which is exactly when this matters: the flush cannot get
        // through, so disk is the only thing standing between the buffer and a
        // process the system kills while the app is in the background.
        val analytics = newAnalytics(
            store = disk,
            uploader = FakeUploader().apply { failuresRemaining = Int.MAX_VALUE },
            caregiverId = { caregiverA },
        )
        analytics.start()
        runCurrent()

        analytics.logLifecycle("app_background")
        runCurrent()
        assertEquals("one event is well under the persist threshold", 0, disk.read().size)

        analytics.persistAndFlush()
        runCurrent()
        assertEquals(
            "backgrounding is the last moment before the process can be killed",
            listOf("app_background"),
            disk.read().map { it.name },
        )
    }

    @Test
    fun `loading from disk keeps events logged while the read was in flight`() = runTest {
        val disk = InMemoryAnalyticsStore(
            listOf(
                UiEvent(
                    id = "saved-1",
                    caregiverId = caregiverA,
                    sessionId = "previous-session",
                    seq = 0,
                    eventType = "button",
                    name = "from the previous run",
                    screen = null,
                    occurredAt = "2026-08-01T00:00:00Z",
                    appBuild = null,
                    osVersion = null,
                    deviceModel = null,
                    metadata = null,
                ),
            ),
        )
        // An uploader that never succeeds, so the drain that start() kicks off
        // cannot empty the buffer and hide what this test is about.
        val analytics = newAnalytics(
            store = disk,
            uploader = FakeUploader().apply { failuresRemaining = Int.MAX_VALUE },
            caregiverId = { caregiverA },
        )

        // Logged before start() has finished reading the file, which is the
        // whole reason loadPersisted prepends instead of assigning.
        analytics.start()
        analytics.logButton("from this run")
        runCurrent()

        assertEquals(
            listOf("from the previous run", "from this run"),
            analytics.bufferedForTest().map { it.name },
        )
    }

    // ------------------------------------------------------------------
    // The kill switch
    // ------------------------------------------------------------------

    @Test
    fun `disabled buffers nothing, persists nothing and uploads nothing`() = runTest {
        val disk = InMemoryAnalyticsStore()
        val uploader = FakeUploader()
        val analytics = newAnalytics(
            store = disk,
            uploader = uploader,
            caregiverId = { caregiverA },
            enabled = false,
        )
        analytics.start()
        runCurrent()

        analytics.logScreen("home")
        analytics.logButton("Add a patient")
        analytics.logToggle("people_blur.toggle", isOn = true)
        analytics.logLifecycle("app_launch")
        analytics.persistAndFlush()
        advanceTimeBy(AnalyticsConfig.FLUSH_INTERVAL_MS * 3)
        runCurrent()

        assertEquals(emptyList<UiEvent>(), analytics.bufferedForTest())
        assertEquals(emptyList<UiEvent>(), disk.read())
        assertEquals(emptyList<UiEvent>(), uploader.uploaded)
    }

    @Test
    fun `disabled does not read a buffer a previous enabled build left behind`() = runTest {
        val disk = InMemoryAnalyticsStore(
            listOf(
                UiEvent(
                    id = "saved-1",
                    caregiverId = caregiverA,
                    sessionId = "previous-session",
                    seq = 0,
                    eventType = "button",
                    name = "from before the switch was flipped",
                    screen = null,
                    occurredAt = "2026-08-01T00:00:00Z",
                    appBuild = null,
                    osVersion = null,
                    deviceModel = null,
                    metadata = null,
                ),
            ),
        )
        val uploader = FakeUploader()
        val analytics = newAnalytics(
            store = disk,
            uploader = uploader,
            caregiverId = { caregiverA },
            enabled = false,
        )
        analytics.start()
        advanceTimeBy(AnalyticsConfig.FLUSH_INTERVAL_MS * 3)
        runCurrent()

        assertEquals(
            "off means off: a leftover buffer is not uploaded either",
            emptyList<UiEvent>(),
            uploader.uploaded,
        )
    }

    // ------------------------------------------------------------------
    // Privacy
    // ------------------------------------------------------------------

    @Test
    fun `the only identifier on an event is the caregiver uuid`() = runTest {
        val analytics = newAnalytics(caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        analytics.logScreen("patient.settings")
        analytics.logButton("Remove this patient")
        analytics.logToggle("data_collection.toggle", isOn = false)
        analytics.logLifecycle("app_background")

        // Everything an event carries, as strings. If a future change smuggled a
        // patient name, an email, a photo URL or a narrative into any field, it
        // would have to appear here.
        val everyValue = analytics.bufferedForTest().flatMap { event ->
            listOf(
                event.caregiverId, event.sessionId, event.eventType, event.name,
                event.screen, event.occurredAt, event.appBuild, event.osVersion,
                event.deviceModel, event.id,
            ).filterNotNull() + (event.metadata?.flatMap { listOf(it.key, it.value) } ?: emptyList())
        }

        val allowedIdentifiers = setOf(caregiverA)
        val identifierShaped = everyValue.filter { value ->
            // A UUID is the only identifier this app is allowed to send. The
            // session id and the per-event id are client-minted and mean nothing
            // outside this app, so they are excluded by name below.
            UUID_PATTERN.matches(value)
        }
        val eventIds = analytics.bufferedForTest().map { it.id }.toSet()
        val sessionIds = analytics.bufferedForTest().map { it.sessionId }.toSet()
        val unexpected = identifierShaped.toSet() - eventIds - sessionIds - allowedIdentifiers
        assertEquals("no identifier beyond the caregiver id", emptySet<String>(), unexpected)

        // And nothing free-text got in through the toggle's metadata.
        val toggle = analytics.bufferedForTest().single { it.eventType == "toggle" }
        assertEquals(mapOf("value" to "off"), toggle.metadata)
    }

    @Test
    fun `toggle metadata is only ever the fixed strings on and off`() = runTest {
        val analytics = newAnalytics(caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        analytics.logToggle("audio_capture.toggle", isOn = true)
        analytics.logToggle("audio_capture.toggle", isOn = false)

        assertEquals(
            listOf(mapOf("value" to "on"), mapOf("value" to "off")),
            analytics.bufferedForTest().map { it.metadata },
        )
    }

    @Test
    fun `no event type other than the four the schema documents`() = runTest {
        val analytics = newAnalytics(caregiverId = { caregiverA })
        analytics.start()
        runCurrent()

        analytics.logButton("b")
        analytics.logToggle("t", isOn = true)
        analytics.logScreen("s")
        analytics.logLifecycle("l")

        assertEquals(
            listOf("button", "toggle", "screen", "lifecycle"),
            analytics.bufferedForTest().map { it.eventType },
        )
        assertTrue(
            "metadata is empty for everything but a toggle",
            analytics.bufferedForTest()
                .filter { it.eventType != "toggle" }
                .all { it.metadata == null },
        )
    }

    // ------------------------------------------------------------------

    private fun kotlinx.coroutines.test.TestScope.newAnalytics(
        store: AnalyticsStore = InMemoryAnalyticsStore(),
        uploader: AnalyticsUploader = FakeUploader(),
        caregiverId: () -> String?,
        enabled: Boolean = true,
        now: () -> Instant = { Clock.System.now() },
    ) = BufferedAnalytics(
        scope = backgroundScope,
        store = store,
        uploader = uploader,
        caregiverId = caregiverId,
        device = device(),
        enabled = enabled,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        now = now,
    )

    private companion object {
        val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
