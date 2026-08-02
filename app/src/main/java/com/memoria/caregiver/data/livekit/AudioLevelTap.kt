package com.memoria.caregiver.data.livekit

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer

/**
 * Reads the peak amplitude of every audio buffer that passes through the
 * room's processing chain and forwards it. Two of these drive the Talk-mode
 * waveform: one on the mixed remote signal, one on the local microphone.
 *
 * ### Why the taps are combined rather than per-track
 *
 * The obvious approach — attach a renderer to each subscribed
 * `RemoteAudioTrack` — was **dead on real devices** on iOS. Across camera
 * reconnects the subscribed-track callback fired four times and the render
 * callback never fired once, most likely the multicast adapter's weak-object
 * table going stale through the resubscribe thrash. What works is a tap on the
 * combined signal, which sits at a different seam in the SDK and only runs when
 * audio actually reaches the speaker. The Android analogue of that seam is
 * `AudioProcessingController`'s render pre-processor and capture post-processor,
 * so that is where these two are installed.
 *
 * ### Threading
 *
 * [processAudio] runs on the SDK's audio thread, once per 10ms buffer. It must
 * not block and it must not touch UI state directly; [onPeak] is expected to
 * hop onto the client's single dispatcher, exactly as the Swift closure hops
 * onto the main actor.
 *
 * ### It observes, it does not process
 *
 * The buffer is read through a duplicate view and never written. This is a
 * meter, not a filter — anything that mutated the samples here would alter what
 * the caregiver and the patient actually hear.
 */
internal class AudioLevelTap(
    private val tapName: String,
    private val onPeak: (Float) -> Unit,
) : AudioProcessorInterface {

    /**
     * Written on the audio thread by [initializeAudioProcessing] and read by
     * [processAudio]. Volatile so a rate/channel change is visible to the very
     * next buffer rather than a cached one.
     */
    @Volatile
    private var channels: Int = 1

    override fun isEnabled(): Boolean = true

    override fun getName(): String = tapName

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
        channels = if (numChannels > 0) numChannels else 1
    }

    override fun resetAudioProcessing(newRate: Int) {
        // Rate changes do not affect a peak measurement, and the channel count
        // is re-announced through initializeAudioProcessing when it changes.
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        val peak = pcmPeak(buffer, numBands * numFrames * channels) ?: return
        onPeak(peak)
    }
}
