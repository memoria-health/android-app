@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.onboarding

import com.memoria.caregiver.data.model.Patient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.time.ExperimentalTime

/**
 * The new-patient form's rules. Spec: `NewPatientView.swift`.
 *
 * Two of these guard real damage rather than tidiness. The submit gate is what
 * stops a patient row being created without the date of birth that decides which
 * preset detectors get seeded. The date conversion is what stops a birthday being
 * stored one day early for every caregiver east of UTC.
 */
class NewPatientUiStateTest {

    private val complete = NewPatientState(
        fullName = "Margaret O'Brien",
        dateOfBirth = LocalDate.of(1955, 3, 1),
        gender = "Female",
    )

    // ---- the submit gate ----

    @Test
    fun `a complete form can be submitted`() {
        assertTrue(complete.canSubmit)
    }

    @Test
    fun `a name of nothing but spaces does not count as a name`() {
        assertFalse(complete.copy(fullName = "   ").canSubmit)
    }

    /**
     * The Swift deliberately has no default date: it also decides which preset
     * detector set is seeded, and under 18 gets the child set.
     */
    @Test
    fun `date of birth is required`() {
        assertFalse(complete.copy(dateOfBirth = null).canSubmit)
    }

    /**
     * Deliberately no "Female" default either — with one, a single date tap
     * completed the form.
     */
    @Test
    fun `gender is required`() {
        assertFalse(complete.copy(gender = "").canSubmit)
    }

    /** Relationship is optional in the UI, and country always has a value. */
    @Test
    fun `relationship and photo are not gates`() {
        assertTrue(complete.copy(relation = null, photoPreview = null).canSubmit)
    }

    // ---- the request body ----

    @Test
    fun `the name is trimmed and mirrored into preferred name`() {
        val body = complete.copy(fullName = "  Margaret O'Brien  ").toCreateRequest()
        assertEquals("Margaret O'Brien", body.fullName)
        assertEquals("Margaret O'Brien", body.preferredName)
    }

    /** The backend's `preferred_name` column is 80 characters; over is a 422. */
    @Test
    fun `preferred name is capped at eighty characters`() {
        val long = "M".repeat(120)
        val body = complete.copy(fullName = long).toCreateRequest()
        assertEquals(120, body.fullName.length)
        assertEquals(80, body.preferredName.length)
    }

    /** The column is NOT NULL, so "unselected" has to become something. */
    @Test
    fun `an unselected relationship submits as other`() {
        assertEquals(Patient.Relation.OTHER, complete.copy(relation = null).toCreateRequest().relation)
    }

    @Test
    fun `a selected relationship is carried through`() {
        val body = complete.copy(relation = Patient.Relation.PARENT).toCreateRequest()
        assertEquals(Patient.Relation.PARENT, body.relation)
    }

    @Test
    fun `an empty gender is omitted rather than sent as an empty string`() {
        assertNull(complete.copy(gender = "").toCreateRequest().gender)
        assertEquals("Male", complete.copy(gender = "Male").toCreateRequest().gender)
    }

    @Test
    fun `home address is never collected here`() {
        assertNull(complete.toCreateRequest().homeAddress)
    }

    @Test
    fun `the country defaults to US and is carried through when changed`() {
        assertEquals("US", NewPatientState().country)
        assertEquals("IN", complete.copy(country = "IN").toCreateRequest().country)
    }

    // ---- the date ----

    /**
     * UTC midnight, so the day the caregiver picked is the day the wire carries.
     * `NemaraDateOfBirthSerializer` formats with a UTC-pinned date-only
     * formatter, so a local-midnight instant would render as the previous day
     * anywhere east of UTC.
     */
    @Test
    fun `a date of birth becomes UTC midnight of that day`() {
        val instant = dateOfBirthInstant(LocalDate.of(1955, 3, 1))
        // 1955-03-01T00:00:00Z
        assertEquals(-468288000L, instant.epochSeconds)
        assertEquals(0, instant.nanosecondsOfSecond)
    }

    @Test
    fun `the picker opens seventy years back on the first of January`() {
        assertEquals(LocalDate.of(1956, 1, 1), defaultDateOfBirth(LocalDate.of(2026, 8, 2)))
    }

    /** iOS offers 1920 through the current year, whole. */
    @Test
    fun `the year range runs from 1920 to this year`() {
        val range = dateOfBirthYearRange(LocalDate.of(2026, 8, 2))
        assertEquals(1920, range.first)
        assertEquals(2026, range.last)
    }

    /** The Swift's `yMMMMd` template resolves to the locale's long form. */
    @Test
    fun `the date renders in the locale's long form`() {
        assertEquals(
            "March 1, 1955",
            formatDateOfBirth(LocalDate.of(1955, 3, 1), Locale.US),
        )
    }

    // ---- copy ----

    @Test
    fun `the gender options are the four iOS offers, in order`() {
        assertEquals(
            listOf("Female", "Male", "Non-binary", "Prefer not to say"),
            NewPatientGenders,
        )
    }
}
