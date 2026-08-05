@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.remote

import java.time.DateTimeException
import java.time.ZoneOffset
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for the nemara-backend wire.
 * Port of iOS `Networking/JSONCoders.swift` (`enum NemaraJSON`).
 *
 * The backend is FastAPI + pydantic v2. It writes `datetime` columns as
 * ISO-8601 text with six digits after the decimal point
 * (`"2026-05-21T22:18:00.182734+00:00"`) and `date` columns as `"YYYY-MM-DD"`.
 *
 * iOS cannot use `ISO8601DateFormatter` for this because it only understands
 * three digits after the decimal point, so it tries four `DateFormatter`
 * patterns in order. See [parseInstant] for the ported chain and
 * `PARITY.md §6` for why this file is the highest-risk one in the phase: if
 * the accepted formats are wrong, roughly half the models silently fail to
 * decode.
 *
 * Every rule below was measured against the real Swift code on macOS rather
 * than inferred from the format strings, because Foundation's date parser is
 * considerably more forgiving than the patterns suggest. The measured
 * behaviour is recorded next to each rule.
 */
object NemaraJson {

    /**
     * The one configured [Json] for the whole app.
     *
     * iOS needs two objects (`JSONDecoder` and `JSONEncoder`) because Swift
     * splits reading and writing into separate types. kotlinx.serialization
     * uses a single [Json] for both, and the settings below do not overlap:
     * `ignoreUnknownKeys` only affects reading, `encodeDefaults` only affects
     * writing. So one instance is the faithful port, not a shortcut.
     *
     * - `ignoreUnknownKeys = true` — responses must decode leniently. The
     *   same Kotlin class has to parse both the REST shape and the raw
     *   database row that Supabase Realtime ships, and those are different
     *   (`PARITY.md §7`). A new backend column must never break decoding.
     *
     * - `explicitNulls = false` — matches Swift exactly in both directions.
     *   Writing: Swift's generated encoder calls `encodeIfPresent` for
     *   optional fields, so a nil is left out of the request body entirely
     *   rather than sent as `null`. That is what makes `PATCH` bodies mean
     *   "only change the fields I sent" (see iOS `UpdatePatientRequest`).
     *   Reading: a missing key becomes null for a nullable field, which is
     *   what Swift's `decodeIfPresent` does.
     *
     * - `encodeDefaults = true` — also matches Swift. A Swift field that is
     *   not optional is always written, even when it happens to hold the same
     *   value the Kotlin declaration uses as its default. kotlinx's default
     *   of `false` would leave such a field out, and the backend uses
     *   `extra="forbid"` with required fields, so a missing required field is
     *   a 422 rather than a warning (`PARITY.md §9 R5`).
     *
     * Deliberately NOT set, each for a reason:
     *
     * - `coerceInputValues` — it would turn an explicit `null` into the
     *   declared default, which does match Swift's `decodeIfPresent ?? default`
     *   pattern. But it also silently turns any unrecognised enum value into
     *   the default, and Swift does not do that: iOS throws unless the model
     *   defines a specific fallback. `PARITY.md §7` lists exactly which enums
     *   are lenient and what each one falls back to (`Camera.SourceType` to
     *   `.sensor`, `Camera.RoomClass` to `.other`), and those need their own
     *   serializers, not a blanket rule. A model that must tolerate an
     *   explicit `null` should declare the field nullable with a default.
     *
     * - `isLenient` — Swift's `JSONDecoder` requires strictly valid JSON.
     *
     * - `namingStrategy` — the port spells every wire name out with
     *   `@SerialName`. An automatic snake_case rule would hide the fields
     *   where the Kotlin name and the wire name genuinely differ.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        // An enum value this build does not know falls back to the property's
        // default instead of throwing.
        //
        // `Detector.Category` documents itself as having an UNKNOWN case that
        // "absorbs a value from a newer server" — and until this flag was set
        // that was simply not true: kotlinx-serialization threw
        // `does not contain element with name`, and `category` is on both
        // Detector and DetectionEvent, both of which load inside `bootstrap()`,
        // which runs inside SIGN-IN. A fifth category added server-side would
        // have locked every older Android client out of the app. Same shape as
        // F-43. See FINDINGS.md F-44.
        //
        // This also coerces an explicit `null` into the default for a
        // non-nullable property that has one, which is a real widening: it
        // hides a server sending null where it promised a value. Accepted for
        // the same reason iOS decodes tolerantly everywhere — on this app's
        // critical path, degrading one field beats failing the whole response.
        coerceInputValues = true
    }

    // ---------------------------------------------------------------------
    // Reading dates
    // ---------------------------------------------------------------------

    /**
     * The ported fallback chain. Returns null when nothing matches, which is
     * what makes the caller throw with the same message iOS uses.
     *
     * iOS tries four patterns in this order:
     *
     * 1. `yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXXXX` — six digits after the point
     * 2. `yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX` — three digits after the point
     * 3. `yyyy-MM-dd'T'HH:mm:ssXXXXX` — no fractional part
     * 4. `yyyy-MM-dd` — a plain calendar date
     *
     * Patterns 1 and 2 accept **exactly the same strings**. Measured: both
     * parse `.182734`, `.182`, `.1827`, `.18`, `.1` and `.182734567`
     * identically, because Foundation reads the digits after the point as a
     * decimal fraction and ignores how many the pattern asked for. They are
     * collapsed into [parseFractional] here rather than written twice; the
     * tests still cover the six-digit and three-digit cases separately so the
     * chain stays traceable to the spec.
     *
     * The three remaining cases accept strictly separate sets of strings — a
     * fractional part is required by the first, rejected by the second and
     * impossible in the third — so trying them in order is what a single
     * combined match would do anyway. Order is preserved regardless, because
     * `PARITY.md §6` states it as part of the contract.
     */
    internal fun parseInstant(raw: String): Instant? =
        parseFractional(raw)
            ?: parsePlain(raw)
            ?: parseDateOnlyAsInstant(raw)

    /** Patterns 1 and 2: a date, a time, digits after the point, an offset. */
    private fun parseFractional(raw: String): Instant? {
        val parts = FRACTIONAL_ISO.matchEntire(raw)?.groupValues ?: return null
        return buildInstant(
            year = parts[1], month = parts[2], day = parts[3],
            hour = parts[4], minute = parts[5], second = parts[6],
            millis = millisOf(parts[7]), offset = parts[8],
        )
    }

    /** Pattern 3: the same, with no fractional part at all. */
    private fun parsePlain(raw: String): Instant? {
        val parts = PLAIN_ISO.matchEntire(raw)?.groupValues ?: return null
        return buildInstant(
            year = parts[1], month = parts[2], day = parts[3],
            hour = parts[4], minute = parts[5], second = parts[6],
            millis = 0, offset = parts[7],
        )
    }

    /**
     * Pattern 4: a bare `YYYY-MM-DD` read as midnight UTC.
     *
     * This is part of the instant chain on iOS too, so a timestamp column
     * that ever comes back as a bare date still decodes. It is separate from
     * [NemaraDateOnlySerializer], which is for `date_of_birth`.
     */
    private fun parseDateOnlyAsInstant(raw: String): Instant? {
        val date = matchDateOnly(raw) ?: return null
        return Instant.fromEpochSeconds(date.toEpochDay() * SECONDS_PER_DAY, 0)
    }

    /** `YYYY-MM-DD` only. Used by the `date_of_birth` serializer. */
    internal fun parseDateOnly(raw: String): LocalDate? {
        val date = matchDateOnly(raw) ?: return null
        return LocalDate(date.year, date.monthValue, date.dayOfMonth)
    }

    // ---------------------------------------------------------------------
    // Writing dates
    // ---------------------------------------------------------------------

    /**
     * Writes pattern 1, which is what iOS encodes with so that reading back
     * what we just wrote gives the same text again.
     *
     * Two measured details:
     *
     * - The offset is written as `Z`, not `+00:00`. Foundation's `XXXXX`
     *   collapses a zero offset to `Z`, so iOS puts
     *   `2026-05-18T22:18:00.183000Z` on the wire.
     *
     * - The value is rounded to the nearest millisecond first, then padded
     *   with zeros out to six digits. Foundation cannot hold anything finer
     *   than a millisecond here, so `.182734` is written as `.183000`.
     *   Measured, including the carry: a value 0.5 microseconds below a whole
     *   second rounds up into the next second.
     *
     * Rounding here and truncating in [millisOf] is what makes a round trip
     * stable: reading drops everything below a millisecond, so by the time a
     * value reaches this function there is nothing left to round.
     */
    internal fun formatInstant(value: Instant): String {
        var seconds = value.epochSeconds
        // nanosecondsOfSecond is always 0 or more, even for instants before
        // 1970, so adding half a millisecond and dividing rounds to nearest.
        var millis = (value.nanosecondsOfSecond + 500_000) / 1_000_000
        if (millis == 1_000) {
            millis = 0
            seconds += 1
        }
        val utc = java.time.LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
        return buildString {
            append(pad(utc.year, 4))
            append('-')
            append(pad(utc.monthValue, 2))
            append('-')
            append(pad(utc.dayOfMonth, 2))
            append('T')
            append(pad(utc.hour, 2))
            append(':')
            append(pad(utc.minute, 2))
            append(':')
            append(pad(utc.second, 2))
            append('.')
            append(pad(millis, 3))
            append("000Z")
        }
    }

    /**
     * Writes `YYYY-MM-DD`, the port of iOS `NemaraJSON.dateOnlyString(from:)`.
     *
     * `LocalDate.toString()` is documented to produce exactly this, and
     * `JsonCodersTest` pins it so a library change cannot alter the shape
     * without failing a test. The backend column is a Postgres `DATE` and
     * pydantic's validator rejects any string carrying a time, so getting
     * this wrong is a 422 on patient creation (`PARITY.md §6`).
     */
    internal fun formatDateOnly(value: LocalDate): String = value.toString()

    /**
     * Same output, taking the [Instant] the models actually hold.
     *
     * UTC on purpose. iOS's `dateOnly` formatter pins
     * `TimeZone(secondsFromGMT: 0)` (JSONCoders.swift:48), so a birth date
     * renders as the same calendar day regardless of where the caregiver is
     * standing. Using the device zone here would move a DOB across midnight
     * for anyone east or west of UTC, silently changing the value the backend
     * stores.
     */
    internal fun formatDateOnlyFrom(value: Instant): String {
        val utc = java.time.LocalDateTime.ofEpochSecond(value.epochSeconds, 0, ZoneOffset.UTC)
        return formatDateOnly(LocalDate(utc.year, utc.monthValue, utc.dayOfMonth))
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    // The calendar arithmetic below uses java.time rather than
    // kotlinx-datetime on purpose. java.time has been stable since Android
    // API 26, which is this app's minimum, whereas kotlinx-datetime has
    // renamed several field accessors across recent versions. The types this
    // file exposes are still the kotlinx ones.
    //
    // One known and accepted difference from iOS falls out of this. All 48
    // wire strings that were compared against the real Swift code agree
    // except `0001-01-01T00:00:00Z`, where the two answers are two days
    // apart: java.time applies today's calendar rules all the way back,
    // while Foundation switches to the older Julian calendar before October
    // 1582. Nothing this app reads predates 1582 — timestamps are from 2020
    // onward and a date of birth is a living person's — so the difference
    // cannot be reached in practice.

    private const val SECONDS_PER_DAY = 86_400L

    /**
     * `Z`, or a sign and two digits of hours optionally followed by minutes
     * and seconds, with or without colons.
     *
     * Measured as accepted by Foundation: `Z`, `+00`, `+0000`, `+00:00`,
     * `+05:30`, `+0530`, `-08:00`, `+14:00` and `-07:52:58`.
     */
    private const val OFFSET = "(Z|[+-]\\d{2}(?::?\\d{2}(?::?\\d{2})?)?)"

    /**
     * Four digits of year, then month and day.
     *
     * One or two digits for month and day because Foundation accepts
     * `2026-2-3`. The year is held to exactly four digits, which is narrower
     * than Foundation — it also accepts `999-01-01` and `20260-01-01`. That
     * leniency is not replicated: no producer emits it, and reading `26` as
     * the year 26 turns a typo into a plausible-looking date instead of a
     * loud failure.
     */
    private const val DATE = "(\\d{4})-(\\d{1,2})-(\\d{1,2})"

    /** Hours, minutes, seconds. One or two digits each, as Foundation allows. */
    private const val TIME = "(\\d{1,2}):(\\d{1,2}):(\\d{1,2})"

    private val FRACTIONAL_ISO = Regex("^" + DATE + "T" + TIME + "\\.(\\d+)" + OFFSET + "$")
    private val PLAIN_ISO = Regex("^" + DATE + "T" + TIME + OFFSET + "$")
    private val DATE_ONLY = Regex("^" + DATE + "$")

    private fun matchDateOnly(raw: String): java.time.LocalDate? {
        val parts = DATE_ONLY.matchEntire(raw)?.groupValues ?: return null
        return calendarDate(parts[1], parts[2], parts[3])
    }

    /**
     * Turns matched digits into a real calendar date, or null if there is no
     * such day. Foundation rejects `2026-02-30` and `2026-13-01` the same
     * way, and accepts `2024-02-29` because 2024 is a leap year.
     */
    private fun calendarDate(year: String, month: String, day: String): java.time.LocalDate? =
        try {
            java.time.LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (e: DateTimeException) {
            null
        }

    private fun buildInstant(
        year: String,
        month: String,
        day: String,
        hour: String,
        minute: String,
        second: String,
        millis: Int,
        offset: String,
    ): Instant? {
        val h = hour.toInt()
        val m = minute.toInt()
        val s = second.toInt()
        // Foundation rejects hour 24 and second 60; there are no leap seconds
        // and no end-of-day 24:00 form here.
        if (h > 23 || m > 59 || s > 59) return null

        val date = calendarDate(year, month, day) ?: return null
        val offsetSeconds = offsetSeconds(offset) ?: return null

        val localSeconds = date.toEpochDay() * SECONDS_PER_DAY + h * 3600L + m * 60L + s
        // The text is a local reading plus the offset that produced it, so
        // subtracting the offset gets back to UTC.
        return Instant.fromEpochSeconds(localSeconds - offsetSeconds, millis * 1_000_000)
    }

    /** How far ahead of UTC the written time is, in seconds. */
    private fun offsetSeconds(raw: String): Long? {
        if (raw == "Z") return 0L
        val sign = if (raw[0] == '-') -1L else 1L
        // The pattern guarantees two, four or six digits once colons are gone.
        val digits = raw.substring(1).filter { it != ':' }
        val hours = digits.substring(0, 2).toInt()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        val seconds = if (digits.length >= 6) digits.substring(4, 6).toInt() else 0
        // Measured: Foundation takes +14:00 and rejects +24:00.
        if (hours > 23 || minutes > 59 || seconds > 59) return null
        return sign * (hours * 3600L + minutes * 60L + seconds)
    }

    /**
     * Milliseconds from the digits after the decimal point.
     *
     * Only the first three digits survive, and the rest are dropped rather
     * than rounded. Measured against Foundation: `.182734`, `.1827` and
     * `.182734567` all give 182 milliseconds, `.18` gives 180, `.1` gives 100
     * and `.0005` gives 0.
     *
     * Keeping the finer digits would look more accurate and would break the
     * round trip, because writing rounds back to a millisecond.
     */
    private fun millisOf(fraction: String): Int =
        fraction.padEnd(3, '0').substring(0, 3).toInt()

    private fun pad(value: Int, width: Int): String =
        value.toString().padStart(width, '0')
}

/**
 * Reads and writes a backend timestamp.
 *
 * Apply it to every [Instant] field that comes from the backend. The simplest
 * way is one line at the top of each model file:
 *
 * ```
 * @file:UseSerializers(NemaraInstantSerializer::class)
 * ```
 *
 * A field left unannotated falls back to the library's own ISO-8601
 * serializer, which keeps microseconds and refuses a bare `YYYY-MM-DD`. That
 * is a quiet difference, not a build error, which is why the annotation
 * belongs at file level rather than on each field.
 */
object NemaraInstantSerializer : KSerializer<Instant> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "com.memoria.caregiver.data.remote.NemaraInstant",
            PrimitiveKind.STRING,
        )

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        // Same wording as the iOS DecodingError, so a report from either
        // platform reads the same.
        return NemaraJson.parseInstant(raw)
            ?: throw SerializationException("Unrecognized date string: $raw")
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(NemaraJson.formatInstant(value))
    }
}

/**
 * `date_of_birth` only: **decodes like any other date, encodes as YYYY-MM-DD.**
 *
 * The asymmetry is the whole point and it is exactly what iOS does. Decoding
 * runs the same four-pattern chain as every other date, because the backend
 * sends `date_of_birth` as a bare `YYYY-MM-DD` that pattern four already
 * handles. Encoding must NOT emit a timestamp: the column is a Postgres `DATE`
 * and pydantic's date validator rejects any value carrying a time of day, so a
 * full ISO string comes back as a 422.
 *
 * iOS expresses this by writing a custom `encode(to:)` on each of the three
 * patient request types, each calling `NemaraJSON.dateOnlyString(from:)` by
 * hand (Patient.swift:177, :224). kotlinx lets one serializer own both
 * directions, so the rule lives in one place and cannot be forgotten on a
 * fourth request type.
 *
 * The type stays [Instant] rather than [LocalDate] to match iOS's `Date?`. A
 * calendar type would be tidier, but it would change `Patient.age` and every
 * call site, and this is a port.
 */
object NemaraDateOfBirthSerializer : KSerializer<Instant> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "com.memoria.caregiver.data.remote.NemaraDateOfBirth",
            PrimitiveKind.STRING,
        )

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return NemaraJson.parseInstant(raw)
            ?: throw SerializationException("Unrecognized date string: $raw")
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(NemaraJson.formatDateOnlyFrom(value))
    }
}
