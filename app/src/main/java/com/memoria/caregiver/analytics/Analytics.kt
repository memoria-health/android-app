package com.memoria.caregiver.analytics

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Analytics sink. Spec: PARITY.md §14 and Services/Analytics.swift.
 *
 * Phase 1 defines the seam only. The buffering, hourly flush, persistence and
 * batch upload are Phase 8; nothing here should grow those responsibilities
 * early.
 *
 * The seam exists now because `NemaraButton` and `NemaraToggle` call it from
 * *inside* the component. That is the whole design: it is the single
 * instrumentation point for the app, so analytics coverage cannot rot as
 * screens are added, and a copy change cannot silently rename an event.
 * Building those components first would guarantee someone later sprinkles
 * logging across hundreds of call sites instead.
 *
 * All four methods must stay **synchronous and non-throwing** — iOS calls this
 * "the safety core". A logging call sits directly in front of every user action,
 * so it must not be able to delay, alter, or fail that action.
 */
interface Analytics {

    /** A button tap. [name] is the explicit track label when set, else the title. */
    fun logButton(name: String)

    /** A toggle flip, with its new value. */
    fun logToggle(name: String, isOn: Boolean)

    /**
     * Record and become the current screen. Drives the screen-flow funnel and
     * supplies the screen context stamped onto later button and toggle events.
     */
    fun logScreen(name: String)

    /** An app-lifecycle transition: app_launch / app_foreground / app_background. */
    fun logLifecycle(name: String)
}

/** Default sink. Discards everything; the real one is wired in Phase 8. */
object NoOpAnalytics : Analytics {
    override fun logButton(name: String) = Unit
    override fun logToggle(name: String, isOn: Boolean) = Unit
    override fun logScreen(name: String) = Unit
    override fun logLifecycle(name: String) = Unit
}

/**
 * iOS reaches analytics through the `Analytics.shared` singleton. Android uses a
 * CompositionLocal instead — same observable behavior, but a process-wide mutable
 * singleton would be a test-isolation hazard here, because JUnit runs a class's
 * tests in one JVM and one test's recorded events would leak into the next.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf`: the sink is
 * swapped approximately never, so there is no reason to pay for read tracking on
 * every component that logs.
 */
val LocalAnalytics = staticCompositionLocalOf<Analytics> { NoOpAnalytics }
