package com.example.countdown

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object AudioGenerator {

    const val SAMPLE_RATE = 44100
    private const val BEEP_FREQ = 880f          // Hz
    private const val FINAL_BEEP_FREQ = 1320f   // Hz (higher for final beep)
    private const val BEEP_DURATION_MS = 120
    private const val FINAL_BEEP_DURATION_MS = 400
    private const val AMPLITUDE = 0.6f

    /**
     * Generates a short beep as PCM 16-bit mono samples.
     */
    fun generateBeep(isFinal: Boolean = false): ShortArray {
        val durationMs = if (isFinal) FINAL_BEEP_DURATION_MS else BEEP_DURATION_MS
        val freq = if (isFinal) FINAL_BEEP_FREQ else BEEP_FREQ
        val totalSamples = (SAMPLE_RATE * durationMs / 1000)
        val samples = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            // Apply envelope to avoid clicks
            val env = envelope(i.toFloat() / totalSamples.toFloat())
            val value = (AMPLITUDE * env * sin(2.0 * Math.PI * freq * t)).toFloat()
            samples[i] = (value * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    /** Generates silence as PCM samples. */
    fun generateSilence(durationMs: Int): ShortArray {
        val totalSamples = (SAMPLE_RATE * durationMs / 1000)
        return ShortArray(totalSamples)
    }

    /** Converts PCM shorts to ByteBuffer (16-bit LE). */
    fun pcmToByteBuffer(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        buffer.flip()
        return buffer
    }

    private fun envelope(x: Float): Float {
        // Attack 5%, sustain 80%, release 15%
        return when {
            x < 0.05f -> x / 0.05f
            x < 0.85f -> 1f
            else -> (1f - x) / 0.15f
        }
    }
}
