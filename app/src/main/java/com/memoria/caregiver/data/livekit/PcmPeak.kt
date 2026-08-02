package com.memoria.caregiver.data.livekit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Full-scale magnitude used to normalise a peak into 0.0-1.0. `Int16.max` on
 * iOS; the same number here, because WebRTC's audio buffers carry int16-ranged
 * values whichever width they are stored in (see [pcmPeak]).
 */
internal const val PCM_FULL_SCALE: Float = 32767f

/**
 * Peak magnitude of one 10ms PCM buffer, normalised to 0.0-1.0, or null if the
 * buffer cannot be read.
 *
 * Cheap on purpose: one pass taking a maximum, no FFT and no allocation. It
 * runs on the SDK's audio thread for every buffer, 20-50 times a second per
 * tap.
 *
 * ### Why this reads two sample widths
 *
 * On iOS the equivalent function had a real bug that shipped: it did
 * `guard let … floatChannelData`, the audio processor actually hands renderers
 * **Int16** buffers, `floatChannelData` returns nil on those, and so the guard
 * bailed on *every single buffer*. The renderer fired continuously and audio
 * flowed both directions, and the waveform sat dead flat. The fix was to handle
 * both layouts instead of assuming one.
 *
 * That lesson is what this function carries over, even though the Android
 * failure mode would be different. `AudioProcessorInterface.processAudio` gets
 * a direct buffer written by WebRTC's external-processing shim, which copies
 * `float` samples — but nothing in the API declares the width, so the width is
 * **derived** from the buffer's own size rather than assumed. If a future SDK
 * hands over int16, the waveform keeps working instead of going flat.
 *
 * ### Scale
 *
 * WebRTC's `AudioBuffer` holds float samples on the **int16 scale** (±32768),
 * not the ±1.0 that `AVAudioPCMBuffer` uses on iOS, so the float branch
 * normalises by [PCM_FULL_SCALE] where the iOS float branch does not. The
 * int16 branch widens to `Int` before taking the magnitude, because
 * `abs(Short.MIN_VALUE)` overflows a `Short` — the same widening the Swift
 * does.
 *
 * Channels and bands are not separated: this takes the maximum over every
 * sample in the buffer, so interleaving and band ordering do not matter, and a
 * stereo track cannot visually double itself.
 *
 * @param sampleCount `numBands * numFrames * numChannels`, used only to derive
 *   the sample width. A wrong channel count still yields a usable peak, since
 *   the scan itself covers the whole buffer.
 */
internal fun pcmPeak(buffer: ByteBuffer, sampleCount: Int): Float? {
    if (sampleCount <= 0) return null

    // duplicate() so the SDK's own position and byte order are untouched. The
    // explicit native order matters: a direct buffer handed over from JNI
    // defaults to big-endian on the Java side while the bytes in it were
    // written host-endian, which would read every sample as noise.
    val view = buffer.duplicate().order(ByteOrder.nativeOrder())
    val base = view.position()
    val available = view.remaining()
    if (available <= 0) return null

    val bytesPerSample = available / sampleCount
    var raw = 0f
    when {
        bytesPerSample >= Float.SIZE_BYTES -> {
            val samples = available / Float.SIZE_BYTES
            for (i in 0 until samples) {
                val magnitude = abs(view.getFloat(base + i * Float.SIZE_BYTES))
                if (magnitude > raw) raw = magnitude
            }
        }

        bytesPerSample >= Short.SIZE_BYTES -> {
            val samples = available / Short.SIZE_BYTES
            var maxMagnitude = 0
            for (i in 0 until samples) {
                // Widen before abs: abs(Short.MIN_VALUE) overflows Short.
                val magnitude = abs(view.getShort(base + i * Short.SIZE_BYTES).toInt())
                if (magnitude > maxMagnitude) maxMagnitude = magnitude
            }
            raw = maxMagnitude.toFloat()
        }

        else -> return null
    }

    val level = raw / PCM_FULL_SCALE
    return if (level > 1f) 1f else level
}
