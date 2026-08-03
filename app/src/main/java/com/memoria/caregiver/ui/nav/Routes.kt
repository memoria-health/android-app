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
     * **Reset password is deliberately not a route.** It is reached from the
     * `nemara://reset-password?code=…` recovery link and nowhere else, and the
     * link carries a single-use credential.
     *
     * A route would take that code as a path argument, which puts it in the back
     * stack entry's arguments, which Navigation Compose saves into the Activity's
     * saved-state Bundle — so the code would outlive the screen that consumes it,
     * on disk, and stay in the back stack afterwards. It would also be reachable
     * only while signed out, because this graph is not composed once there is a
     * caregiver, whereas iOS covers the dashboard too.
     *
     * So `AuthNavHost` draws `ResetPasswordDestination` over the whole app from a
     * view-model field instead, exactly as iOS presents it as a `fullScreenCover`
     * on RootView. See DECISIONS.md, "Reset password is a cover, not a route".
     */

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
    const val ARG_PATIENT_ID = "patientId"
}
