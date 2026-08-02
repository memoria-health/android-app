package com.memoria.caregiver.analytics

/**
 * Test double that records what was logged, in order.
 *
 * Order matters and is asserted on: iOS logs the tap *before* running the
 * action, so a component that logs afterwards would lose the event whenever the
 * action navigates away or throws.
 */
class RecordingAnalytics : Analytics {

    data class Entry(val type: String, val name: String, val value: String? = null)

    val entries = mutableListOf<Entry>()

    /** Interleaved trace of analytics calls and anything the test pushes in. */
    val trace = mutableListOf<String>()

    override fun logButton(name: String) {
        entries += Entry("button", name)
        trace += "log:button:$name"
    }

    override fun logToggle(name: String, isOn: Boolean) {
        entries += Entry("toggle", name, if (isOn) "on" else "off")
        trace += "log:toggle:$name:${if (isOn) "on" else "off"}"
    }

    override fun logScreen(name: String) {
        entries += Entry("screen", name)
        trace += "log:screen:$name"
    }

    override fun logLifecycle(name: String) {
        entries += Entry("lifecycle", name)
        trace += "log:lifecycle:$name"
    }

    fun names(type: String): List<String> = entries.filter { it.type == type }.map { it.name }
}
