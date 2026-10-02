package com.example.countdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.text.Layout.Alignment
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

class VideoGenerator(private val context: Context) {

    companion object {
        const val VIDEO_WIDTH = 1920
        const val VIDEO_HEIGHT = 1080
        const val TEXT_WIDTH = 1320
        const val TEXT_HEIGHT = 480
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL = 1
        private const val BIT_RATE = 8_000_000
    }

    private val typeface: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "font.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

    /**
     * Generates a countdown video.
     * @param durationSeconds total countdown length
     * @param outputFile destination mp4 file
     * @param onProgress 0..100
     */
    fun generate(
        durationSeconds: Int,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
        outputFile.delete()

        // ----- VIDEO ENCODER -----
        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = videoCodec.createInputSurface()
        videoCodec.start()

        // ----- AUDIO ENCODER (AAC) -----
        val sampleRate = AudioGenerator.SAMPLE_RATE
        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec.start()

        // ----- MUXER -----
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        // ----- PAINT / TEXT -----
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = this@VideoGenerator.typeface
            textSize = 240f
            isAntiAlias = true
        }

        val beepSamples = AudioGenerator.generateBeep(false)
        val finalBeepSamples = AudioGenerator.generateBeep(true)
        val beepBuffer = AudioGenerator.pcmToByteBuffer(beepSamples)
        val finalBeepBuffer = AudioGenerator.pcmToByteBuffer(finalBeepSamples)

        val totalFrames = durationSeconds * FRAME_RATE
        val totalAudioSamples = durationSeconds * sampleRate
        var audioSamplesWritten = 0
        var nextBeepAtSample = 0                 // next beep start (in audio samples)
        var beepBufferPos = 0                    // how much of current beep already queued
        var currentBeepBuffer: ByteBuffer = beepBuffer.duplicate()
        var isFinalBeep = false

        val info = MediaCodec.BufferInfo()
        val canvas = Canvas()

        try {
            for (frameIndex in 0 until totalFrames) {
                val remainingSeconds = durationSeconds - (frameIndex / FRAME_RATE)
                val timeText = formatTime(remainingSeconds)
                val label = if (remainingSeconds == durationSeconds) "GET READY" else "SECONDS LEFT"

                // ---- Draw frame ----
                val surfaceCanvas: Canvas = inputSurface.lockCanvas(null)
                surfaceCanvas.drawColor(Color.BLACK)

                // Center the text area
                val textAreaX = (VIDEO_WIDTH - TEXT_WIDTH) / 2f
                val textAreaY = (VIDEO_HEIGHT - TEXT_HEIGHT) / 2f

                val fullText = "$timeText\n$label"
                val layout = StaticLayout.Builder
                    .obtain(fullText, 0, fullText.length, paint, TEXT_WIDTH)
                    .setAlignment(Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.1f)
                    .setMaxLines(4)
                    .build()

                // Vertically center the layout inside the text area
                val layoutY = textAreaY + (TEXT_HEIGHT - layout.height) / 2f
                surfaceCanvas.save()
                surfaceCanvas.translate(textAreaX, layoutY)
                layout.draw(surfaceCanvas)
                surfaceCanvas.restore()

                inputSurface.unlockCanvasAndPost(surfaceCanvas)

                // Drain video encoder
                drainEncoder(videoCodec, muxer, info,
                    onFrame = { idx, buf, bi ->
                        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                            muxer.start()
                            muxerStarted = true
                        }
                        if (muxerStarted && videoTrackIndex >= 0) {
                            muxer.writeSampleData(videoTrackIndex, buf, bi)
                        }
                    },
                    onFormatChange = { fmt ->
                        videoTrackIndex = muxer.addTrack(fmt)
                    })

                // ---- Feed audio (beep at each second boundary) ----
                val currentSample = (frameIndex.toLong() * sampleRate) / FRAME_RATE
                val samplesNeeded = sampleRate / FRAME_RATE   // samples for one frame

                var produced = 0
                while (produced < samplesNeeded) {
                    // If we finished a beep, insert silence until next second boundary
                    if (beepBufferPos >= currentBeepBuffer.limit()) {
                        nextBeepAtSample += sampleRate          // next second
                        beepBufferPos = 0
                        currentBeepBuffer = beepBuffer.duplicate()
                        isFinalBeep = false
                    }
                    // If we reached a second boundary, trigger a beep
                    if (currentSample + produced >= nextBeepAtSample && beepBufferPos == 0) {
                        val secsLeft = durationSeconds - ((currentSample + produced) / sampleRate).toInt()
                        isFinalBeep = (secsLeft <= 0)
                        currentBeepBuffer = (if (isFinalBeep) finalBeepBuffer else beepBuffer).duplicate()
                        beepBufferPos = 0
                    }

                    val remainingInBeep = currentBeepBuffer.limit() - beepBufferPos
                    val remainingInFrame = samplesNeeded - produced
                    val chunk = minOf(remainingInBeep, remainingInFrame)

                    // Write chunk to audio encoder input
                    val inIdx = audioCodec.dequeueInputBuffer(2000)
                    if (inIdx >= 0) {
                        val inBuf = audioCodec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val src = currentBeepBuffer.duplicate()
                        src.position(beepBufferPos)
                        src.limit(beepBufferPos + chunk)
                        inBuf.put(src)
                        beepBufferPos += chunk
                        produced += chunk
                        audioCodec.queueInputBuffer(
                            inIdx, 0, chunk * 2,
                            (audioSamplesWritten.toLong() * 1_000_000) / sampleRate,
                            0
                        )
                        audioSamplesWritten += chunk
                    } else {
                        produced += chunk
                        audioSamplesWritten += chunk
                        beepBufferPos += chunk
                    }
                }

                // Drain audio encoder
                drainEncoder(audioCodec, muxer, info,
                    onFrame = { idx, buf, bi ->
                        if (muxerStarted && audioTrackIndex >= 0) {
                            muxer.writeSampleData(audioTrackIndex, buf, bi)
                        }
                    },
                    onFormatChange = { fmt ->
                        audioTrackIndex = muxer.addTrack(fmt)
                        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                            muxer.start()
                            muxerStarted = true
                        }
                    })

                if (frameIndex % 15 == 0) {
                    onProgress((frameIndex * 100) / totalFrames)
                }
            }

            // Signal EOS
            signalEOS(videoCodec)
            drainEncoder(videoCodec, muxer, info,
                onFrame = { _, buf, bi ->
                    if (muxerStarted && videoTrackIndex >= 0 &&
                        bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                        muxer.writeSampleData(videoTrackIndex, buf, bi)
                    }
                }, onFormatChange = {})

            signalEOS(audioCodec)
            drainEncoder(audioCodec, muxer, info,
                onFrame = { _, buf, bi ->
                    if (muxerStarted && audioTrackIndex >= 0 &&
                        bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                        muxer.writeSampleData(audioTrackIndex, buf, bi)
                    }
                }, onFormatChange = {})

        } finally {
            try { inputSurface.release() } catch (_: Exception) {}
            try { videoCodec.stop() } catch (_: Exception) {}
            try { videoCodec.release() } catch (_: Exception) {}
            try { audioCodec.stop() } catch (_: Exception) {}
            try { audioCodec.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
        onProgress(100)
    }

    private fun signalEOS(codec: MediaCodec) {
        val idx = codec.dequeueInputBuffer(2000)
        if (idx >= 0) {
            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        onFormatChange: (MediaFormat) -> Unit,
        onFrame: (Int, ByteBuffer, MediaCodec.BufferInfo) -> Unit
    ) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, 5000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormatChange(codec.outputFormat)
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        onFrame(outIdx, buf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> break
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) String.format("%d:%02d", m, s) else s.toString()
    }
}
