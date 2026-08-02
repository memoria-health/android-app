package com.memoria.caregiver.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * What follows a successful create, in order. Spec: `NewPatientView.swift`'s
 * private `PostCreateEnrollmentFlow`.
 *
 * The Swift swaps content inside one `fullScreenCover` rather than dismissing
 * and re-presenting, so there is no flash between steps. This composable is that
 * cover: one host, three steps, and the same instance of each screen's state
 * across the swap.
 */
enum class PostCreateEnrollmentStep {
    /** Pick the patient's detectors; the recommended set is pre-checked. */
    Detectors,

    /** "Would you like to add facial detection?" */
    FaceConsent,

    /** The guided capture. */
    FaceEnrollment,
}

/**
 * Runs the three post-create steps.
 *
 * The two screens this flow does not own are passed in, each receiving the
 * callback that advances the flow: [detectorLibrary] gets the detector library's
 * Continue, [faceEnrollment] gets enrollment's completion. Both belong to other
 * units and need API access this composable deliberately does not have.
 *
 * [onDontAskAgain] is called *in addition to* [onFinish] and only records the
 * choice — see [FaceConsentPrefs]. Both "Maybe later" and "Don't ask me again"
 * simply end the flow today; the reminder banner that would read the flag is a
 * future addition, as it is on iOS.
 */
@Composable
fun PostCreateEnrollmentFlow(
    modifier: Modifier = Modifier,
    onFinish: () -> Unit,
    onDontAskAgain: () -> Unit,
    detectorLibrary: @Composable (onContinue: () -> Unit) -> Unit,
    faceEnrollment: @Composable (onComplete: () -> Unit) -> Unit,
) {
    // Saveable rather than remembered: the flow spans a camera permission
    // prompt, and a low-memory activity restart behind that dialog would
    // otherwise drop the caregiver back onto the detector list with the patient
    // already created.
    var step by rememberSaveable { mutableStateOf(PostCreateEnrollmentStep.Detectors) }

    Box(modifier) {
        when (step) {
            PostCreateEnrollmentStep.Detectors ->
                detectorLibrary { step = PostCreateEnrollmentStep.FaceConsent }

            PostCreateEnrollmentStep.FaceConsent ->
                FaceDetectionConsentScreen(
                    onProceed = { step = PostCreateEnrollmentStep.FaceEnrollment },
                    onMaybeLater = onFinish,
                    onDontAskAgain = {
                        onDontAskAgain()
                        onFinish()
                    },
                )

            PostCreateEnrollmentStep.FaceEnrollment -> faceEnrollment(onFinish)
        }
    }
}

/**
 * Local record of the "Don't ask me again" choice, per patient. Port of the
 * Swift's `FaceConsentPrefs`, which uses `UserDefaults`.
 *
 * Nothing reads this yet on either platform: the reminder banner it is meant to
 * suppress does not exist. It is written now so the choice is not lost when that
 * banner arrives.
 *
 * Stores a patient id and a boolean — no token, no name, no contact detail.
 */
object FaceConsentPrefs {

    private const val FILE_NAME = "face_consent"

    /** Same key shape as iOS, minus its bundle-id namespace (DECISIONS.md D-006). */
    private fun key(patientId: String): String = "faceConsentDismissed.$patientId"

    fun isDismissed(context: Context, patientId: String): Boolean =
        prefs(context).getBoolean(key(patientId), false)

    fun setDismissed(context: Context, patientId: String) {
        prefs(context).edit().putBoolean(key(patientId), true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
