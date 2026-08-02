@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.data.remote

import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 tests for the wire date handling (`PARITY.md §6`, risk register R4).
 *
 * Every expected value in this file was produced by running the real iOS
 * `JSONCoders.swift` on macOS over the same input, not by working backwards
 * from this implementation. Forty-eight wire strings were compared and all
 * agree except a year-0001 timestamp, which differs by two days because of
 * the Julian calendar cutover and cannot occur in this app's data. That
 * comparison is what these assertions encode.
 *
 * Decoding goes through [NemaraJson.json] and real JSON text shaped like the
 * backend's responses, so the tests exercise the same path a network call
 * takes rather than a round trip through this file's own encoder.
 */
class JsonCodersTest {

    // -----------------------------------------------------------------
    // Fixtures shaped like real backend rows
    // -----------------------------------------------------------------

    @Serializable
    private data class EventRow(
        @SerialName("id")
        val id: String,
        @SerialName("created_at")
        @Serializable(with = NemaraInstantSerializer::class)
        val createdAt: Instant,
        @SerialName("reviewed_at")
        @Serializable(with = NemaraInstantSerializer::class)
        val reviewedAt: Instant? = null,
    )

    @Serializable
    private data class TimestampRow(
        @SerialName("created_at")
        @Serializable(with = NemaraInstantSerializer::class)
        val createdAt: Instant,
    )

    @Serializable
    private data class PatientRow(
        @SerialName("full_name")
        val fullName: String,
        @SerialName("date_of_birth")
        // Instant, not LocalDate — iOS types dateOfBirth as `Date?` and runs it
        // through the same decode chain as every other date. Only the ENCODE
        // side is date-only, because pydantic's date validator rejects a
        // timestamp. NemaraDateOfBirthSerializer owns that asymmetry.
        @Serializable(with = NemaraDateOfBirthSerializer::class)
        val dateOfBirth: Instant? = null,
        @SerialName("relation")
        val relation: String = "other",
    )

    /**
     * A birth date as the instant iOS would hold: midnight UTC on that day.
     *
     * UTC, not the device zone. iOS's date-only formatter pins
     * `TimeZone(secondsFromGMT: 0)`, so a DOB renders as the same calendar day
     * wherever the caregiver is. Building these in local time would shift the
     * day across midnight for anyone east or west of UTC.
     */
    private fun utcMidnight(year: Int, month: Int, day: Int): Instant =
        Instant.fromEpochSeconds(
            java.time.LocalDate.of(year, month, day)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toEpochSecond(),
        )

    private fun decode(wire: String): Instant =
        NemaraJson.json
            .decodeFromString<TimestampRow>("""{"created_at":"$wire"}""")
            .createdAt

    private fun assertDecodes(wire: String, expectedEpochMillis: Long) {
        assertEquals(
            "decoding \"$wire\"",
            expectedEpochMillis,
            decode(wire).toEpochMilliseconds(),
        )
    }

    private fun assertRejected(wire: String) {
        assertThrows(
            "expected \"$wire\" to be rejected",
            SerializationException::class.java,
        ) { decode(wire) }
    }

    private fun encode(instant: Instant): String =
        NemaraJson.json.encodeToString(TimestampRow(instant))

    // -----------------------------------------------------------------
    // Pattern 1 — yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXXXX
    // -----------------------------------------------------------------

    @Test
    fun `pattern 1 - six fractional digits with explicit zero offset`() {
        // The exact shape pydantic v2 writes for a timestamp column.
        assertDecodes("2026-05-21T22:18:00.182734+00:00", 1_779_401_880_182L)
    }

    @Test
    fun `pattern 1 - six fractional digits with Z`() {
        assertDecodes("2026-05-21T22:18:00.182734Z", 1_779_401_880_182L)
    }

    @Test
    fun `pattern 1 - epoch itself`() {
        assertDecodes("1970-01-01T00:00:00.000000Z", 0L)
    }

    // -----------------------------------------------------------------
    // Pattern 2 — yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX
    // -----------------------------------------------------------------

    @Test
    fun `pattern 2 - three fractional digits with explicit zero offset`() {
        assertDecodes("2026-05-21T22:18:00.182+00:00", 1_779_401_880_182L)
    }

    @Test
    fun `pattern 2 - three fractional digits with Z`() {
        assertDecodes("2026-05-21T22:18:00.182Z", 1_779_401_880_182L)
    }

    // -----------------------------------------------------------------
    // Pattern 3 — yyyy-MM-dd'T'HH:mm:ssXXXXX
    // -----------------------------------------------------------------

    @Test
    fun `pattern 3 - no fractional part with Z`() {
        assertDecodes("2026-05-21T22:18:00Z", 1_779_401_880_000L)
    }

    @Test
    fun `pattern 3 - no fractional part with explicit zero offset`() {
        assertDecodes("2026-05-21T22:18:00+00:00", 1_779_401_880_000L)
    }

    @Test
    fun `pattern 3 - no fractional part with a negative offset`() {
        assertDecodes("2026-05-21T22:18:00-08:00", 1_779_430_680_000L)
    }

    // -----------------------------------------------------------------
    // Pattern 4 — yyyy-MM-dd, read as midnight UTC
    // -----------------------------------------------------------------

    @Test
    fun `pattern 4 - bare calendar date is midnight UTC`() {
        assertDecodes("2026-05-21", 1_779_321_600_000L)
        assertDecodes("2026-01-01", 1_767_225_600_000L)
    }

    @Test
    fun `pattern 4 - leap day is accepted`() {
        assertDecodes("2024-02-29", 1_709_164_800_000L)
    }

    @Test
    fun `pattern 4 - month and day may be a single digit`() {
        // Foundation accepts this, so the port does too.
        assertDecodes("2026-2-3", 1_770_076_800_000L)
    }

    // -----------------------------------------------------------------
    // Fractional seconds — the detail with the widest blast radius
    // -----------------------------------------------------------------

    @Test
    fun `digits finer than a millisecond are dropped, not rounded`() {
        // 182734 microseconds is 182.734 milliseconds. Foundation keeps 182,
        // it does not round up to 183. Anything else would make a decode then
        // encode cycle unstable, because encoding rounds back to the nearest
        // millisecond.
        val decoded = decode("2026-05-21T22:18:00.182734+00:00")
        assertEquals(1_779_401_880_182L, decoded.toEpochMilliseconds())
        assertEquals(182_000_000, decoded.nanosecondsOfSecond)
    }

    @Test
    fun `fractional part is read as a decimal fraction whatever its length`() {
        // One digit means a tenth of a second, not one millisecond.
        assertDecodes("2026-05-21T22:18:00.1+00:00", 1_779_401_880_100L)
        assertDecodes("2026-05-21T22:18:00.18+00:00", 1_779_401_880_180L)
        assertDecodes("2026-05-21T22:18:00.182+00:00", 1_779_401_880_182L)
        assertDecodes("2026-05-21T22:18:00.1827+00:00", 1_779_401_880_182L)
        assertDecodes("2026-05-21T22:18:00.182734+00:00", 1_779_401_880_182L)
        assertDecodes("2026-05-21T22:18:00.182734567+00:00", 1_779_401_880_182L)
    }

    @Test
    fun `fractional edges`() {
        assertDecodes("2026-05-21T22:18:00.0+00:00", 1_779_401_880_000L)
        assertDecodes("2026-05-21T22:18:00.500+00:00", 1_779_401_880_500L)
        assertDecodes("2026-05-21T22:18:00.999+00:00", 1_779_401_880_999L)
        // Below a millisecond, so it disappears entirely. It does not round up.
        assertDecodes("2026-05-21T22:18:00.0005+00:00", 1_779_401_880_000L)
        // Just under a whole second stays in the same second.
        assertDecodes("2026-05-21T22:18:00.9999995+00:00", 1_779_401_880_999L)
    }

    // -----------------------------------------------------------------
    // Time zone offsets
    // -----------------------------------------------------------------

    @Test
    fun `offset with hours and minutes`() {
        // India, the second deployment region.
        assertDecodes("2026-05-21T22:18:00.182734+05:30", 1_779_382_080_182L)
        assertDecodes("2026-05-21T22:18:00+05:30", 1_779_382_080_000L)
    }

    @Test
    fun `largest real offset is accepted`() {
        assertDecodes("2026-05-21T22:18:00.182734+14:00", 1_779_351_480_182L)
    }

    @Test
    fun `offset without colons is accepted`() {
        // Foundation's XXXXX takes the compact forms as well as the extended
        // one, so a producer using strftime style output still decodes.
        assertDecodes("2026-05-21T22:18:00.182734+0000", 1_779_401_880_182L)
        assertDecodes("2026-05-21T22:18:00.182734+00", 1_779_401_880_182L)
        assertDecodes("2026-05-21T22:18:00.182734+0530", 1_779_382_080_182L)
    }

    @Test
    fun `offset carrying seconds is accepted`() {
        assertDecodes("2026-05-21T22:18:00.182734-07:52:58", 1_779_430_258_182L)
    }

    @Test
    fun `time fields may be a single digit`() {
        assertDecodes("2026-5-21T22:18:00Z", 1_779_401_880_000L)
        assertDecodes("2026-05-2T22:18:00Z", 1_777_760_280_000L)
        assertDecodes("2026-05-21T2:18:00Z", 1_779_329_880_000L)
        assertDecodes("2026-05-21T22:8:00Z", 1_779_401_280_000L)
        assertDecodes("2026-05-21T22:18:0Z", 1_779_401_880_000L)
    }

    // -----------------------------------------------------------------
    // Rejections
    // -----------------------------------------------------------------

    @Test
    fun `rejects a timestamp with no offset`() {
        // Every pattern requires an offset, so a naive timestamp fails all
        // four rather than quietly being read as UTC or as midnight on that
        // date. Worth pinning: a Postgres column typed `timestamp` instead of
        // `timestamptz` would arrive in exactly this shape.
        assertRejected("2026-05-21T22:18:00.182734")
        assertRejected("2026-05-21T22:18:00")
    }

    @Test
    fun `rejects a space between date and time`() {
        assertRejected("2026-05-21 22:18:00+00:00")
    }

    @Test
    fun `rejects trailing text`() {
        assertRejected("2026-05-21T22:18:00.182734+00:00extra")
    }

    @Test
    fun `rejects empty and non-date text`() {
        assertRejected("")
        assertRejected("not a date")
    }

    @Test
    fun `rejects days that do not exist`() {
        assertRejected("2026-02-30")
        assertRejected("2026-13-01")
        assertRejected("2026-05-32")
        assertRejected("2026-02-30T00:00:00Z")
    }

    @Test
    fun `rejects out of range times`() {
        assertRejected("2026-05-21T25:18:00Z")
        // No leap seconds.
        assertRejected("2026-05-21T22:18:60Z")
    }

    @Test
    fun `rejects out of range offsets`() {
        assertRejected("2026-05-21T22:18:00.182734+24:00")
        assertRejected("2026-05-21T22:18:00.182734+99:99")
    }

    @Test
    fun `rejects a decimal point with no digits after it`() {
        assertRejected("2026-05-21T22:18:00.+00:00")
    }

    @Test
    fun `rejection message matches the iOS wording`() {
        val error = assertThrows(SerializationException::class.java) {
            decode("not a date")
        }
        assertEquals("Unrecognized date string: not a date", error.message)
    }

    // -----------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------

    @Test
    fun `encodes pattern 1 with six digits and a Z suffix`() {
        // Foundation collapses a zero offset to Z, so this is what iOS puts
        // on the wire, not "+00:00".
        assertEquals(
            """{"created_at":"2026-05-18T22:18:00.182000Z"}""",
            encode(Instant.fromEpochSeconds(1_779_142_680L, 182_000_000)),
        )
    }

    @Test
    fun `encodes a whole second as six zeros`() {
        assertEquals(
            """{"created_at":"2026-05-18T22:18:00.000000Z"}""",
            encode(Instant.fromEpochSeconds(1_779_142_680L, 0)),
        )
        assertEquals(
            """{"created_at":"1970-01-01T00:00:00.000000Z"}""",
            encode(Instant.fromEpochSeconds(0L, 0)),
        )
    }

    @Test
    fun `encoding rounds to the nearest millisecond`() {
        // Foundation cannot hold anything finer here, and it rounds rather
        // than truncating. This is the opposite of what decoding does, and
        // both were measured.
        assertEquals(
            """{"created_at":"2026-05-18T22:18:00.183000Z"}""",
            encode(Instant.fromEpochSeconds(1_779_142_680L, 182_734_000)),
        )
        assertEquals(
            """{"created_at":"2026-05-18T22:18:00.001000Z"}""",
            encode(Instant.fromEpochSeconds(1_779_142_680L, 500_000)),
        )
        assertEquals(
            """{"created_at":"2026-05-18T22:18:00.000000Z"}""",
            encode(Instant.fromEpochSeconds(1_779_142_680L, 400_000)),
        )
        assertEquals(
            """{"created_at":"2026-05-18T22:18:00.002000Z"}""",
            encode(Instant.fromEpochSeconds(1_779_142_680L, 1_500_000)),
        )
    }

    @Test
    fun `rounding up past a whole second carries into the next second`() {
        assertEquals(
            """{"created_at":"2026-05-18T22:18:01.000000Z"}""",
            encode(Instant.fromEpochSeconds(1_779_142_680L, 999_999_000)),
        )
    }

    @Test
    fun `encodes instants before 1970`() {
        assertEquals(
            """{"created_at":"1969-12-31T23:59:59.817000Z"}""",
            encode(Instant.fromEpochSeconds(-1L, 817_266_000)),
        )
        // Rounds up across the epoch boundary.
        assertEquals(
            """{"created_at":"1970-01-01T00:00:00.000000Z"}""",
            encode(Instant.fromEpochSeconds(-1L, 999_500_000)),
        )
    }

    @Test
    fun `decode then encode settles after one pass`() {
        // The wire value carries microseconds that neither platform can hold,
        // so the first pass drops them. From then on the text must not move.
        val once = encode(decode("2026-05-21T22:18:00.182734+00:00"))
        assertEquals("""{"created_at":"2026-05-21T22:18:00.182000Z"}""", once)

        val wire = "2026-05-21T22:18:00.182000Z"
        assertEquals("""{"created_at":"$wire"}""", encode(decode(wire)))
    }

    // -----------------------------------------------------------------
    // date_of_birth — a calendar date, never a timestamp
    // -----------------------------------------------------------------

    @Test
    fun `date of birth decodes as a calendar date`() {
        val row = NemaraJson.json.decodeFromString<PatientRow>(
            """{"full_name":"Ada Lovelace","date_of_birth":"1944-05-12"}""",
        )
        assertEquals(utcMidnight(1944, 5, 12), row.dateOfBirth)
    }

    /**
     * A timestamp in `date_of_birth` DECODES, and that is correct parity.
     *
     * iOS has one `dateDecodingStrategy` for every `Date`, so `date_of_birth`
     * runs the same four-pattern chain as `created_at` and would accept a
     * timestamp too. The backend only ever sends `YYYY-MM-DD`, so this never
     * happens in practice — but rejecting it here would be stricter than iOS.
     *
     * The invariant that actually matters is on the way OUT: whatever was
     * decoded, re-encoding must emit date-only, because pydantic's date
     * validator turns a timestamp into a 422.
     */
    @Test
    fun `a timestamp in date of birth decodes, and re-encodes as date only`() {
        val row = NemaraJson.json.decodeFromString<PatientRow>(
            """{"full_name":"Ada","date_of_birth":"1944-05-12T13:45:00+00:00"}""",
        )
        assertEquals(utcMidnight(1944, 5, 12) + 13.hours + 45.minutes, row.dateOfBirth)

        val body = NemaraJson.json.encodeToString(row)
        assertTrue(body, body.contains(""""date_of_birth":"1944-05-12""""))
        assertTrue("must not carry a time of day: $body", !body.contains("T13:45"))
    }

    @Test
    fun `date of birth refuses a day that does not exist`() {
        assertThrows(SerializationException::class.java) {
            NemaraJson.json.decodeFromString<PatientRow>(
                """{"full_name":"Ada","date_of_birth":"1944-02-30"}""",
            )
        }
    }

    @Test
    fun `date of birth is written as year, month and day only`() {
        // Sending anything with a time of day here is a 422 on patient
        // creation (PARITY.md section 6).
        val body = NemaraJson.json.encodeToString(
            PatientRow(fullName = "Ada Lovelace", dateOfBirth = utcMidnight(1944, 5, 12)),
        )
        assertTrue(body, body.contains(""""date_of_birth":"1944-05-12""""))
        assertTrue("must not carry a time of day: $body", !body.contains("T00:00"))
    }

    @Test
    fun `date of birth pads single digit months and days`() {
        val body = NemaraJson.json.encodeToString(
            PatientRow(fullName = "Ada", dateOfBirth = utcMidnight(1944, 1, 2)),
        )
        assertTrue(body, body.contains(""""date_of_birth":"1944-01-02""""))
    }

    @Test
    fun `an absent date of birth is left out of the request body`() {
        // iOS uses encodeIfPresent here, so a patient with no date of birth
        // sends no date_of_birth key at all rather than an explicit null.
        val body = NemaraJson.json.encodeToString(PatientRow(fullName = "Ada"))
        assertTrue("must omit the key entirely: $body", !body.contains("date_of_birth"))
    }

    // -----------------------------------------------------------------
    // Json configuration
    // -----------------------------------------------------------------

    @Test
    fun `unknown keys are ignored`() {
        // Supabase Realtime ships the raw database row, which carries columns
        // the API schema does not. A new column must never break decoding.
        val row = NemaraJson.json.decodeFromString<EventRow>(
            """
            {
              "id": "evt_1",
              "created_at": "2026-05-21T22:18:00.182734+00:00",
              "camera_id": "cam_9",
              "some_column_added_next_month": true
            }
            """.trimIndent(),
        )
        assertEquals("evt_1", row.id)
        assertEquals(1_779_401_880_182L, row.createdAt.toEpochMilliseconds())
    }

    @Test
    fun `a missing nullable timestamp becomes null`() {
        val row = NemaraJson.json.decodeFromString<EventRow>(
            """{"id":"evt_1","created_at":"2026-05-21T22:18:00.182734+00:00"}""",
        )
        assertNull(row.reviewedAt)
    }

    @Test
    fun `an explicit null nullable timestamp becomes null`() {
        val row = NemaraJson.json.decodeFromString<EventRow>(
            """
            {
              "id": "evt_1",
              "created_at": "2026-05-21T22:18:00.182734+00:00",
              "reviewed_at": null
            }
            """.trimIndent(),
        )
        assertNull(row.reviewedAt)
    }

    @Test
    fun `null fields are left out when encoding`() {
        val body = NemaraJson.json.encodeToString(
            EventRow(id = "evt_1", createdAt = Instant.fromEpochSeconds(0L, 0)),
        )
        assertEquals(
            """{"id":"evt_1","created_at":"1970-01-01T00:00:00.000000Z"}""",
            body,
        )
    }

    @Test
    fun `a field holding its default value is still written`() {
        // Swift always writes a field that is not optional, even when it
        // happens to hold the value Kotlin uses as the default. The backend
        // treats a missing required field as a 422, so leaving it out would
        // fail the request (PARITY.md section 9, R5).
        val body = NemaraJson.json.encodeToString(PatientRow(fullName = "Ada"))
        assertTrue("relation must be written: $body", body.contains(""""relation":"other""""))
    }
}
