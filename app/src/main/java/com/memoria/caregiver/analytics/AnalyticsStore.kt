package com.memoria.caregiver.analytics

import com.memoria.caregiver.data.remote.NemaraJson
import java.io.File

/**
 * Where the buffer is kept between runs. iOS writes one JSON file into
 * Application Support; this is the same idea behind an interface so the buffer
 * logic can be exercised on the JVM without a filesystem.
 *
 * Both methods are blocking, and every caller runs them off the main thread.
 */
interface AnalyticsStore {

    /** Everything a previous run left behind. Empty when there is nothing, or on any error. */
    fun read(): List<UiEvent>

    /** Replace the saved buffer with [events]. Failures are swallowed by the caller. */
    fun write(events: List<UiEvent>)
}

/**
 * The real store: one JSON file in the app's private files directory.
 *
 * ## Why the write goes through a temporary file
 *
 * Android can kill the process at any moment, including in the middle of a
 * write. Writing straight into the live file would then leave a half-written
 * JSON array that fails to parse on next launch, so a crash would cost the whole
 * buffer instead of the few events it should. Writing to a sibling and renaming
 * means the live file only ever changes from one complete version to another —
 * `File.renameTo` within one directory is atomic on every filesystem Android
 * uses. It is the same guarantee iOS gets from `Data.write(options: .atomic)`.
 */
class FileAnalyticsStore(private val file: File) : AnalyticsStore {

    override fun read(): List<UiEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            NemaraJson.json.decodeFromString<List<UiEvent>>(file.readText())
        }.getOrElse {
            // A file we cannot parse is a file we can never drain, so it would
            // sit there being re-read on every launch forever. Drop it and move
            // on: losing buffered analytics is not worth one line of noise.
            file.delete()
            emptyList()
        }
    }

    override fun write(events: List<UiEvent>) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(NemaraJson.json.encodeToString(events))
        if (!temporary.renameTo(file)) {
            // Rename can only fail here if something outside the app moved the
            // directory. Fall back to a direct write rather than losing the
            // buffer, and clean up the stray temporary file either way.
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}

/**
 * Store that keeps the buffer only in memory.
 *
 * Used when the app is built without a Context (tests, previews) so nothing has
 * to special-case a missing store, and used by tests that want persistence
 * behaviour without touching disk.
 */
class InMemoryAnalyticsStore(initial: List<UiEvent> = emptyList()) : AnalyticsStore {

    @Volatile
    private var saved: List<UiEvent> = initial

    override fun read(): List<UiEvent> = saved

    override fun write(events: List<UiEvent>) {
        saved = events
    }
}
