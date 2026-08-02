@file:OptIn(ExperimentalTime::class)

package com.memoria.caregiver.ui.onboarding

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.memoria.caregiver.data.model.CreatePatientRequest
import com.memoria.caregiver.data.model.Patient
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * State and pure logic behind [NewPatientScreen]. Spec:
 * `Features/Onboarding/NewPatientView.swift`.
 *
 * Everything the Swift computes inside the view — what makes the form
 * submittable, how the request body is assembled, how the date is displayed and
 * which years the picker offers — lives here so it can be tested without a
 * composition. The screen renders this and reports taps; the host owns the API
 * call and the photo upload.
 */

/**
 * The four gender options, in the Swift's order. Verbatim copy: these strings go
 * to the backend's free-text `gender` column, so a reworded label is a different
 * stored value.
 */
val NewPatientGenders: List<String> =
    listOf("Female", "Male", "Non-binary", "Prefer not to say")

/**
 * Country the home is in, ISO alpha-2. Defaults to US to match the web
 * onboarding default; the data-residency region is derived from it server-side
 * and is immutable once the patient exists.
 */
const val NEW_PATIENT_DEFAULT_COUNTRY: String = "US"

/**
 * Where the date picker opens when no date of birth has been chosen: January 1
 * of the year 70 years ago. The Swift seeds its wheels with
 * `dobMonth = 1`, `dobDay = 1`, `dobYear = currentYear - 70`.
 */
const val NEW_PATIENT_DEFAULT_DOB_YEARS_AGO: Int = 70

/** Earliest year the Swift's year wheel offers (`ForEach(Array(1920...currentYear))`). */
const val NEW_PATIENT_MIN_DOB_YEAR: Int = 1920

/**
 * The backend's `preferred_name` column limit. Preferred name is no longer
 * collected, so it is mirrored from the full name and capped here — an
 * over-length name would 422 the create.
 */
const val PREFERRED_NAME_MAX_LENGTH: Int = 80

/**
 * Page 1 of patient onboarding: the basic profile.
 *
 * [dateOfBirth] is required with no silent default — the caregiver must pick a
 * date, because it also decides which preset detectors get seeded (under 18 =
 * the child set). [gender] starts empty for the same reason: a "Female" default
 * meant one date tap completed the form.
 *
 * [photoPreview] is the picked image, shown in place of the initials avatar. The
 * JPEG bytes themselves stay with the host: the photo can only be uploaded after
 * `createPatient` returns an id, because the upload endpoint is keyed by
 * patient_id, so the bytes have to outlive this state anyway.
 */
@Immutable
data class NewPatientState(
    val fullName: String = "",
    val dateOfBirth: LocalDate? = null,
    val gender: String = "",
    /**
     * Null = not selected. Relationship is optional in the UI; the backend
     * column is NOT NULL, so an unselected relationship submits as
     * [Patient.Relation.OTHER].
     */
    val relation: Patient.Relation? = null,
    val country: String = NEW_PATIENT_DEFAULT_COUNTRY,
    val photoPreview: ImageBitmap? = null,
    val isLoadingPhoto: Boolean = false,
    val isSubmitting: Boolean = false,
    /** Surfaced inline so the caregiver knows the patient was not created. */
    val submitError: String? = null,
) {
    /**
     * Name, date of birth and gender. Relationship, country and photo are not
     * gates — country always has a value and the other two are optional.
     */
    val canSubmit: Boolean
        get() = fullName.trim().isNotEmpty() && dateOfBirth != null && gender.isNotEmpty()
}

/**
 * The `CreatePatientRequest` the Swift's `submit()` builds.
 *
 * Three details that are easy to lose: the name is trimmed once and used for
 * both fields, `preferred_name` is capped at [PREFERRED_NAME_MAX_LENGTH], and an
 * unselected relationship becomes [Patient.Relation.OTHER] rather than being
 * omitted.
 *
 * Callers must check [NewPatientState.canSubmit] first — this reads
 * [NewPatientState.dateOfBirth] as non-null.
 */
fun NewPatientState.toCreateRequest(): CreatePatientRequest {
    // iOS trims `.whitespaces`, which is spaces and tabs but not newlines.
    // Kotlin's trim() also drops newlines; the field is single-line, so no
    // input can tell the two apart.
    val name = fullName.trim()
    return CreatePatientRequest(
        fullName = name,
        preferredName = name.take(PREFERRED_NAME_MAX_LENGTH),
        relation = relation ?: Patient.Relation.OTHER,
        dateOfBirth = dateOfBirth?.let(::dateOfBirthInstant),
        gender = gender.ifEmpty { null },
        homeAddress = null,
        country = country,
    )
}

/**
 * The picked calendar day as the [Instant] the request model carries.
 *
 * **UTC midnight, deliberately.** `NemaraDateOfBirthSerializer` renders the
 * instant with a UTC-pinned date-only formatter, and the decoder builds date-only
 * values the same way (`JsonCoders.kt`). The Swift builds *device-local*
 * midnight and encodes it through the same UTC formatter, which moves a birth
 * date to the previous day for any caregiver east of UTC — a real defect in
 * India, which is the pilot market. Constructing UTC midnight sends the day the
 * caregiver picked. See DIFFERENCES.md.
 */
fun dateOfBirthInstant(date: LocalDate): Instant =
    Instant.fromEpochSeconds(date.atStartOfDay(ZoneOffset.UTC).toEpochSecond())

/**
 * Where the picker opens with nothing selected: 1 January of the year
 * [NEW_PATIENT_DEFAULT_DOB_YEARS_AGO] back.
 */
fun defaultDateOfBirth(today: LocalDate): LocalDate =
    LocalDate.of(today.year - NEW_PATIENT_DEFAULT_DOB_YEARS_AGO, 1, 1)

/**
 * Years the picker offers: [NEW_PATIENT_MIN_DOB_YEAR] through the current year,
 * as the Swift's year wheel does. The current year is offered whole, so a date
 * later this year is selectable on both platforms.
 */
fun dateOfBirthYearRange(today: LocalDate): IntRange = NEW_PATIENT_MIN_DOB_YEAR..today.year

/**
 * The date as the field renders it — "March 1, 1955" in en-US.
 *
 * iOS uses `setLocalizedDateFormatFromTemplate("yMMMMd")`, which resolves to the
 * locale's long date form. `FormatStyle.LONG` is that form, so both platforms
 * localize rather than hard-coding month-day-year.
 */
fun formatDateOfBirth(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date)
