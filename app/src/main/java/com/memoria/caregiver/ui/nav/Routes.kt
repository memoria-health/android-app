package com.memoria.caregiver.ui.nav

/**
 * Every destination in the app, as one closed set.
 *
 * iOS routes with `NavigationStack` plus a `fullScreenCover` for the patient
 * shell; the route names here are the Compose equivalent and are deliberately
 * boring strings rather than a sealed hierarchy, because Navigation Compose
 * matches on strings and a sealed type would only add a translation layer.
 *
 * Kept in one file so the whole map is readable at once — a screen that is not
 * listed here is not reachable, which is the property that makes "every screen
 * is routable" checkable rather than a claim.
 */
object Routes {

    // ---- Signed out ----
    const val WELCOME = "welcome"
    const val LOGIN = "login"
    const val SIGN_UP = "signUp"
    const val FORGOT_PASSWORD = "forgotPassword"

    /**
     * Reached from the `nemara://reset-password?code=…` recovery link, and only
     * from there. The code is the argument.
     */
    const val RESET_PASSWORD = "resetPassword/{code}"
    fun resetPassword(code: String) = "resetPassword/$code"

    /**
     * The terms consent gate. Shown after authentication when the caregiver's
     * accepted version is missing or stale, and it BLOCKS the app behind it —
     * it is not a dismissible sheet.
     */
    const val TERMS_GATE = "termsGate"

    // ---- Signed in ----
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PATIENT = "patient/{patientId}"
    fun patient(patientId: String) = "patient/$patientId"

    // ---- Argument keys ----
    const val ARG_CODE = "code"
    const val ARG_PATIENT_ID = "patientId"
}
