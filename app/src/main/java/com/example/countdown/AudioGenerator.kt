package com.example.countdown

import kotlin.math.sin

object AudioGenerator {
    const val SAMPLE_RATE = 44100
    private const val BEEP_FREQ = 880f          // Standard beep
    private const val FINAL_BEEP_FREQ = 1320f   // Higher pitch for final second
    private const val BEEP_DURATION_MS = 120
    private const val FINAL_BEEP_DURATION_MS = 400
    private const val AMPLITUDE = 0.6f

    /**
     * Pre-generates the entire audio track in memory. 
     * For 60 seconds, this is only ~5.2 MB, perfectly safe for Android heap.
     */
    fun generateFullAudio(durationSeconds: Int): ShortArray {
        val totalSamples = durationSeconds * SAMPLE_RATE
        val samples = ShortArray(totalSamples)
        
        for (sec in 0 until durationSeconds) {
            val isFinalBeep = (sec == durationSeconds - 1)
            val beepDurationSamples = (if (isFinalBeep) FINAL_BEEP_DURATION_MS else BEEP_DURATION_MS) * SAMPLE_RATE / 1000
            val freq = if (isFinalBeep) FINAL_BEEP_FREQ else BEEP_FREQ
            
            for (i in 0 until beepDurationSamples.toInt()) {
                val sampleIndex = sec * SAMPLE_RATE + i
                if (sampleIndex >= totalSamples) break
                
                val t = i.toFloat() / SAMPLE_RATE
                val env = envelope(i.toFloat() / beepDurationSamples.toFloat())
                val value = (AMPLITUDE * env * sin(2.0 * Math.PI * freq * t)).toFloat()
                samples[sampleIndex] = (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return samples
    }
    
    private fun envelope(x: Float): Float {
        // Attack 5%, sustain 80%, release 15% to prevent audio "clicking"
        return when {
            x < 0.05f -> x / 0.05f
            x < 0.85f -> 1f
            else -> (1f - x) / 0.15f
        }
    }
}
