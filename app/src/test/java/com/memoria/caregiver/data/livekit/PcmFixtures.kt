package com.memoria.caregiver.data.livekit

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM buffers shaped the way the SDK's audio thread delivers them: a direct-ish
 * buffer in **native** byte order, positioned at the start of the data.
 *
 * Native order is the point of these helpers. A buffer handed over from JNI
 * carries host-endian bytes while Java defaults a buffer to big-endian, so a
 * reader that forgets to set the order sees noise rather than audio — and the
 * only symptom is a waveform that looks alive but is wrong.
 */

/** A float32 buffer. WebRTC's float samples sit on the int16 scale, so pass values in ±32768. */
internal fun floatPcm(vararg samples: Float): ByteBuffer {
    val buffer = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    samples.forEach { buffer.putFloat(it) }
    buffer.flip()
    return buffer
}

/** An int16 buffer, the layout the iOS renderer turned out to be getting. */
internal fun shortPcm(vararg samples: Short): ByteBuffer {
    val buffer = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
    samples.forEach { buffer.putShort(it) }
    buffer.flip()
    return buffer
}

/** A buffer of raw bytes, for widths the reader is expected to refuse. */
internal fun bytePcm(count: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(count).order(ByteOrder.nativeOrder())
    repeat(count) { buffer.put(0x7F) }
    buffer.flip()
    return buffer
}
