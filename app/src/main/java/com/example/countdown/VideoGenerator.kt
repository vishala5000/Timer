package com.example.countdown

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sin

class VideoGenerator(private val context: Context) {

    companion object {
        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val TEXT_WIDTH = 1320
        const val TEXT_HEIGHT = 480
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL = 1
        private const val BIT_RATE = 8_000_000
        
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BIT_RATE = 128_000
        private const val BEEP_FREQ = 880f
        private const val FINAL_BEEP_FREQ = 1320f
        private const val BEEP_DURATION_MS = 120
        private const val FINAL_BEEP_DURATION_MS = 400
        private const val AMPLITUDE = 0.6f
    }

    private val typeface: Typeface by lazy {
        try { Typeface.createFromAsset(context.assets, "font.ttf") } 
        catch (e: Exception) { Typeface.DEFAULT_BOLD }
    }

    fun generate(durationSeconds: Int, outputFile: File, onProgress: (Int) -> Unit) {
        if (durationSeconds <= 0) return
        outputFile.delete()

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = videoCodec.createInputSurface()
        videoCodec.start()

        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = this@VideoGenerator.typeface
            textSize = 160f
        }

        val totalFrames = durationSeconds * FRAME_RATE
        val totalAudioSamples = (durationSeconds.toLong() * AUDIO_SAMPLE_RATE)
        val frameDurationNs = 1_000_000_000L / FRAME_RATE
        
        var frameIndex = 0
        var currentAudioSample = 0L
        var videoEosQueued = false
        var audioEosQueued = false
        var videoEosReceived = false
        var audioEosReceived = false
        val info = MediaCodec.BufferInfo()
        var lastFrameTimeNs = System.nanoTime()

        try {
            while (!videoEosReceived || !audioEosReceived) {
                
                if (!videoEosQueued) {
                    val currentTimeNs = System.nanoTime()
                    val elapsedNs = currentTimeNs - lastFrameTimeNs
                    if (elapsedNs < frameDurationNs) {
                        Thread.sleep((frameDurationNs - elapsedNs) / 1_000_000)
                    }
                    lastFrameTimeNs = System.nanoTime()
                    
                    val remainingSeconds = durationSeconds - (frameIndex / FRAME_RATE)
                    val timeText = if (remainingSeconds > 0) {
                        val m = remainingSeconds / 60
                        val s = remainingSeconds % 60
                        if (m > 0) String.format("%d:%02d", m, s) else s.toString()
                    } else "0"
                    
                    val label = if (frameIndex < FRAME_RATE) "GET READY" else "SECONDS LEFT"
                    val fullText = "$timeText\n$label"

                    val surfaceCanvas = inputSurface.lockCanvas(null)
                    surfaceCanvas.drawColor(Color.BLACK)
                    
                    val textAreaX = (VIDEO_WIDTH - TEXT_WIDTH) / 2f
                    val textAreaY = (VIDEO_HEIGHT - TEXT_HEIGHT) / 2f

                    val layout = StaticLayout.Builder
                        .obtain(fullText, 0, fullText.length, paint, TEXT_WIDTH)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.1f)
                        .build()

                    val layoutY = textAreaY + (TEXT_HEIGHT - layout.height) / 2f
                    surfaceCanvas.save()
                    surfaceCanvas.translate(textAreaX, layoutY)
                    layout.draw(surfaceCanvas)
                    surfaceCanvas.restore()
                    inputSurface.unlockCanvasAndPost(surfaceCanvas)

                    frameIndex++
                    // ✅ FIXED: Call instance method on videoCodec, not static MediaCodec
                    if (frameIndex >= totalFrames) {
                        videoCodec.signalEndOfInputStream()
                        videoEosQueued = true
                    }
                }

                if (!audioEosQueued && currentAudioSample < totalAudioSamples) {
                    val inIdx = audioCodec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inBuf = audioCodec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.order(ByteOrder.LITTLE_ENDIAN)
                        
                        val maxSamples = inBuf.remaining() / 2
                        val remaining = (totalAudioSamples - currentAudioSample).toInt()
                        val chunkSamples = min(maxSamples, remaining)
                        
                        val shortBuf = inBuf.asShortBuffer()
                        for (i in 0 until chunkSamples) {
                            val sec = (currentAudioSample / AUDIO_SAMPLE_RATE).toInt()
                            val sampleInSec = (currentAudioSample % AUDIO_SAMPLE_RATE).toInt()
                            val isFinal = (sec == durationSeconds - 1)
                            val durSamples = (if (isFinal) FINAL_BEEP_DURATION_MS else BEEP_DURATION_MS) * AUDIO_SAMPLE_RATE / 1000
                            val freq = if (isFinal) FINAL_BEEP_FREQ else BEEP_FREQ
                            
                            if (sampleInSec < durSamples) {
                                val t = sampleInSec.toFloat() / AUDIO_SAMPLE_RATE
                                val env = when {
                                    (sampleInSec.toFloat() / durSamples) < 0.05f -> (sampleInSec.toFloat() / durSamples) / 0.05f
                                    (sampleInSec.toFloat() / durSamples) < 0.85f -> 1f
                                    else -> (1f - (sampleInSec.toFloat() / durSamples)) / 0.15f
                                }
                                val value = (AMPLITUDE * env * sin(2.0 * Math.PI * freq * t)).toFloat()
                                shortBuf.put((value * Short.MAX_VALUE).toInt().toShort())
                            } else {
                                shortBuf.put(0)
                            }
                            currentAudioSample++
                        }
                        
                        val bytesWritten = chunkSamples * 2
                        val startSample = currentAudioSample - chunkSamples
                        val timeUs = (startSample * 1_000_000L) / AUDIO_SAMPLE_RATE
                        val isEos = (currentAudioSample >= totalAudioSamples)
                        
                        audioCodec.queueInputBuffer(inIdx, 0, bytesWritten, timeUs, if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (isEos) audioEosQueued = true
                    }
                }

                drainCodec(videoCodec, info,
                    onFormat = { fmt -> videoTrackIndex = muxer.addTrack(fmt) },
                    onFrame = { buf, bi -> if (muxerStarted && videoTrackIndex >= 0) muxer.writeSampleData(videoTrackIndex, buf, bi) },
                    onEos = { videoEosReceived = true }
                )

                drainCodec(audioCodec, info,
                    onFormat = { fmt -> 
                        audioTrackIndex = muxer.addTrack(fmt)
                        if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !muxerStarted) {
                            muxer.start()
                            muxerStarted = true
                        }
                    },
                    onFrame = { buf, bi -> if (muxerStarted && audioTrackIndex >= 0) muxer.writeSampleData(audioTrackIndex, buf, bi) },
                    onEos = { audioEosReceived = true }
                )

                if (frameIndex % 15 == 0) {
                    onProgress(min(100, (frameIndex * 100) / totalFrames))
                }
            }
        } finally {
            inputSurface.release()
            videoCodec.stop(); videoCodec.release()
            audioCodec.stop(); audioCodec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
        onProgress(100)
    }

    private fun drainCodec(
        codec: MediaCodec, info: MediaCodec.BufferInfo,
        onFormat: (MediaFormat) -> Unit,
        onFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
        onEos: () -> Unit
    ) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 10000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        onFrame(buf, info)
                    }
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (isEos) { onEos(); break }
                }
                else -> break
            }
        }
    }
}
