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
    }

    private val typeface: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "font.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

    fun generate(durationSeconds: Int, outputFile: File, onProgress: (Int) -> Unit) {
        outputFile.delete()

        // 1. Video Encoder Setup
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = videoCodec.createInputSurface()
        videoCodec.start()

        // 2. Audio Encoder Setup
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioCodec.start()

        // 3. Muxer Setup
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false

        // 4. Text Painting Setup
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = this@VideoGenerator.typeface
            textSize = 240f
            isAntiAlias = true
        }

        // 5. Pre-generate Audio
        val fullAudioSamples = AudioGenerator.generateFullAudio(durationSeconds)
        var audioSamplesWritten = 0
        val totalAudioSamples = fullAudioSamples.size
        val totalFrames = durationSeconds * FRAME_RATE

        var videoInputDone = false
        var audioInputDone = false
        var videoOutputDone = false
        var audioOutputDone = false
        val info = MediaCodec.BufferInfo()

        try {
            for (frameIndex in 0..totalFrames) {
                val remainingSeconds = durationSeconds - (frameIndex / FRAME_RATE)
                val timeText = formatTime(remainingSeconds)
                val label = if (frameIndex == 0) "GET READY" else "SECONDS LEFT"
                val fullText = "$timeText\n$label"

                // Draw Frame
                val surfaceCanvas = inputSurface.lockCanvas(null)
                surfaceCanvas.drawColor(Color.BLACK)
                
                val textAreaX = (VIDEO_WIDTH - TEXT_WIDTH) / 2f
                val textAreaY = (VIDEO_HEIGHT - TEXT_HEIGHT) / 2f

                val layout = StaticLayout.Builder
                    .obtain(fullText, 0, fullText.length, paint, TEXT_WIDTH)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.1f)
                    .setMaxLines(4)
                    .build()

                val layoutY = textAreaY + (TEXT_HEIGHT - layout.height) / 2f
                surfaceCanvas.save()
                surfaceCanvas.translate(textAreaX, layoutY)
                layout.draw(surfaceCanvas)
                surfaceCanvas.restore()
                inputSurface.unlockCanvasAndPost(surfaceCanvas)

                val presentationTimeUs = (frameIndex * 1_000_000L) / FRAME_RATE

                // Feed Video
                if (!videoInputDone) {
                    val inIdx = videoCodec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val flags = if (frameIndex == totalFrames) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        videoCodec.queueInputBuffer(inIdx, 0, 0, presentationTimeUs, flags)
                        if (flags != 0) videoInputDone = true
                    }
                }

                // Feed Audio
                if (!videoInputDone) {
                    val samplesPerFrame = AUDIO_SAMPLE_RATE / FRAME_RATE
                    var produced = 0
                    while (produced < samplesPerFrame && audioSamplesWritten < totalAudioSamples) {
                        val inIdx = audioCodec.dequeueInputBuffer(10000)
                        if (inIdx >= 0) {
                            val chunk = minOf(samplesPerFrame - produced, totalAudioSamples - audioSamplesWritten)
                            val inBuf = audioCodec.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            inBuf.asShortBuffer().put(fullAudioSamples, audioSamplesWritten, chunk)
                            
                            val timeUs = (audioSamplesWritten.toLong() * 1_000_000L) / AUDIO_SAMPLE_RATE
                            audioCodec.queueInputBuffer(inIdx, 0, chunk * 2, timeUs, 0)
                            
                            audioSamplesWritten += chunk
                            produced += chunk
                        } else break
                    }
                }

                // Signal Audio EOS
                if (videoInputDone && audioSamplesWritten >= totalAudioSamples && !audioInputDone) {
                    val inIdx = audioCodec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        audioCodec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioInputDone = true
                    }
                }

                // Drain Codecs
                drainCodec(videoCodec, info, 
                    onFormat = { fmt -> videoTrackIndex = muxer.addTrack(fmt) },
                    onFrame = { buf, bi -> if (muxerStarted && videoTrackIndex >= 0) muxer.writeSampleData(videoTrackIndex, buf, bi) },
                    onEos = { videoOutputDone = true }
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
                    onEos = { audioOutputDone = true }
                )

                if (frameIndex % 15 == 0) onProgress((frameIndex * 100) / totalFrames)
                if (videoOutputDone && audioOutputDone) break
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

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) String.format("%d:%02d", m, s) else s.toString()
    }
}
