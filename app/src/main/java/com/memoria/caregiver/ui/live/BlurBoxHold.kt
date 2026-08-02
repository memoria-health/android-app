package com.memoria.caregiver.ui.live

/**
 * The detection cadence and the privacy hold. Spec:
 * `Features/Patient/Live/BlurredVideoView.swift`, PARITY.md §12.
 *
 * Two separate rates, and conflating them is the classic way to port this
 * wrong: **detection is throttled to ~15/s, the blur composite runs on every
 * frame.** Between detections the previous boxes are held, so the blur does not
 * strobe at the detector's rate.
 *
 * PARITY.md §12: *"The hold-box behavior is a privacy guarantee, not a smoothing
 * nicety."* A detector that misses one cycle must never un-blur a person for a
 * frame. Boxes therefore carry an expiry and are only allowed to lapse when
 * detection comes back **empty** *and* the hold has run out.
 *
 * Not thread-safe, and does not need to be: iOS runs this on the SDK's serial
 * video thread with no locking, and [BlurVideoRenderer] does the same on the
 * WebRTC decode thread.
 *
 * @param nowMillis a monotonic clock in milliseconds. iOS uses
 *   `CACurrentMediaTime()`, which is monotonic — a wall clock would let a device
 *   time change either freeze detection or expire every held box at once.
 */
class BlurBoxHold(
    private val detectIntervalMillis: Long = DETECT_INTERVAL_MILLIS,
    private val holdMillis: Long = HOLD_MILLIS,
) {

    private class Held(val rect: PixelRect, val expiresAt: Long)

    private var held: List<Held> = emptyList()

    /**
     * Null until the first frame, rather than a sentinel `0`. iOS gets the same
     * effect for free because `CACurrentMediaTime()` is enormous next to its
     * `lastDetectTime = 0` initial value, so its first frame always detects.
     * A `0` sentinel here would work with `elapsedRealtime` too, but only by
     * accident of the device having been up for more than 66 ms.
     */
    private var lastDetectAt: Long? = null

    /**
     * Call once per decoded frame. Runs [detect] when the cadence allows, then
     * returns the boxes to blur on **this** frame.
     *
     * [detect] is a lambda rather than a detector reference so that the cadence
     * cannot be tested without also testing that detection actually got skipped,
     * and so this whole class stays pure JVM.
     */
    fun onFrame(nowMillis: Long, detect: () -> List<PixelRect>): List<PixelRect> {
        val last = lastDetectAt
        if (last == null || nowMillis - last >= detectIntervalMillis) {
            lastDetectAt = nowMillis
            val detected = detect()
            held = if (detected.isNotEmpty()) {
                // A successful detection REPLACES the held set wholesale — it
                // does not merge. Anyone who has actually left the frame stops
                // being blurred as soon as the detector confirms someone else is
                // still there, rather than trailing a ghost box for 1.5 s.
                detected.map { Held(it, nowMillis + holdMillis) }
            } else {
                // Empty detection: keep whatever is still inside its hold.
                held.filter { it.expiresAt > nowMillis }
            }
        }
        if (held.isEmpty()) return emptyList()
        // Recomputed every frame, not only on detection frames, so a box does
        // expire mid-cadence exactly when its hold runs out.
        return held.mapNotNull { if (it.expiresAt > nowMillis) it.rect else null }
    }

    companion object {
        /**
         * ~15 detections a second. iOS `detectInterval: CFTimeInterval = 0.066`.
         * Kept in milliseconds because the whole pipeline clock is millis; 0.066 s
         * is 66 ms exactly, so nothing is rounded away.
         */
        const val DETECT_INTERVAL_MILLIS: Long = 66L

        /**
         * How long a box survives after its last sighting. iOS
         * `holdSeconds: CFTimeInterval = 1.5`, mirroring web `HOLD_MS = 1500`.
         */
        const val HOLD_MILLIS: Long = 1_500L
    }
}
