package com.memoria.caregiver.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The on-disk half of "the buffer survives a process death". */
class FileAnalyticsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun event(id: String, name: String) = UiEvent(
        id = id,
        caregiverId = "11111111-1111-4111-8111-111111111111",
        sessionId = "22222222-2222-4222-8222-222222222222",
        seq = 0,
        eventType = "button",
        name = name,
        screen = "home",
        occurredAt = "2026-08-03T10:11:12.345Z",
        appBuild = "0.1.0 (1)",
        osVersion = "15",
        deviceModel = "Pixel 8",
        metadata = null,
    )

    @Test
    fun `nothing written yet reads as nothing, not as a failure`() {
        val store = FileAnalyticsStore(File(folder.root, "ui_events_buffer.json"))
        assertEquals(emptyList<UiEvent>(), store.read())
    }

    @Test
    fun `what is written is what comes back`() {
        val store = FileAnalyticsStore(File(folder.root, "ui_events_buffer.json"))
        val events = listOf(event("a", "one"), event("b", "two"))

        store.write(events)

        assertEquals(events, FileAnalyticsStore(File(folder.root, "ui_events_buffer.json")).read())
    }

    @Test
    fun `a later write replaces an earlier one`() {
        val file = File(folder.root, "ui_events_buffer.json")
        val store = FileAnalyticsStore(file)

        store.write(listOf(event("a", "one"), event("b", "two")))
        store.write(listOf(event("b", "two")))

        assertEquals(listOf(event("b", "two")), store.read())
    }

    @Test
    fun `a corrupt file is dropped rather than re-read forever`() {
        val file = File(folder.root, "ui_events_buffer.json")
        // What a kill mid-write used to be able to leave behind, before the
        // write went through a temporary file.
        file.writeText("[{\"id\":\"a\",\"caregiv")

        val store = FileAnalyticsStore(file)

        assertEquals(emptyList<UiEvent>(), store.read())
        assertFalse("otherwise it is re-read and re-fails on every launch", file.exists())
    }

    @Test
    fun `no temporary file is left behind`() {
        val file = File(folder.root, "ui_events_buffer.json")
        FileAnalyticsStore(file).write(listOf(event("a", "one")))

        assertEquals(
            listOf("ui_events_buffer.json"),
            folder.root.listFiles().orEmpty().map { it.name }.sorted(),
        )
    }
}
