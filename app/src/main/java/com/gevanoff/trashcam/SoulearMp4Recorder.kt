package com.gevanoff.trashcam

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Encodes decoded Soulear frames as a bounded, video-only H.264 MP4 recording. */
internal class SoulearMp4Recorder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val listener: Listener
) : Closeable {

    interface Listener {
        fun onRecordingStarted(encoderName: String)
        fun onRecordingComplete(file: File, frameCount: Int)
        fun onRecordingError(reason: String)
    }

    private data class PendingFrame(val pixels: IntArray, val timestampNanos: Long)
    private data class EncoderChoice(
        val codecName: String,
        val colorFormat: Int,
        val layout: SoulearYuv420.Layout
    )

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val acceptingFrames = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val pumpScheduled = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<PendingFrame?>()
    private val lastAcceptedNanos = AtomicLong(Long.MIN_VALUE)
    private val completionLatch = CountDownLatch(1)

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var colorLayout = SoulearYuv420.Layout.PLANAR
    private var firstFrameNanos = Long.MIN_VALUE
    private var lastPresentationTimeUs = 0L
    private var queuedFrames = 0

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "Recording dimensions must be positive and even"
        }
    }

    fun start() {
        try {
            worker.execute {
                try {
                    initializeEncoder()
                    if (stopRequested.get()) {
                        finishRecording()
                    } else {
                        acceptingFrames.set(true)
                        listener.onRecordingStarted(requireNotNull(encoder).name)
                    }
                } catch (error: Exception) {
                    fail(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            fail(error)
        }
    }

    /** Copies at most 20 decoded frames per second and keeps only the newest pending frame. */
    fun offerFrame(bitmap: Bitmap, timestampNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        if (!acceptingFrames.get() || stopRequested.get()) return
        if (bitmap.width != width || bitmap.height != height) return

        while (true) {
            val previous = lastAcceptedNanos.get()
            if (previous != Long.MIN_VALUE && timestampNanos - previous < FRAME_INTERVAL_NANOS) return
            if (lastAcceptedNanos.compareAndSet(previous, timestampNanos)) break
        }

        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Could not copy a Soulear frame for recording", error)
            return
        }
        pendingFrame.set(PendingFrame(pixels, timestampNanos))
        schedulePump()
    }

    fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        acceptingFrames.set(false)
        pendingFrame.set(null)
        try {
            worker.execute(::finishRecording)
        } catch (_: RejectedExecutionException) {
            // Initialization already failed and released the worker.
        }
    }

    fun stopAndWait(timeoutMs: Long): Boolean {
        stop()
        return try {
            completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun schedulePump() {
        if (!pumpScheduled.compareAndSet(false, true)) return
        try {
            worker.execute(::pumpFrames)
        } catch (_: RejectedExecutionException) {
            pumpScheduled.set(false)
        }
    }

    private fun pumpFrames() {
        try {
            while (acceptingFrames.get() && !stopRequested.get()) {
                val frame = pendingFrame.getAndSet(null) ?: break
                encode(frame)
            }
        } catch (error: Exception) {
            fail(error)
        } finally {
            pumpScheduled.set(false)
            if (pendingFrame.get() != null && acceptingFrames.get()) schedulePump()
        }
    }

    private fun initializeEncoder() {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException("Could not replace recording output")
        }

        val choice = chooseEncoder()
        colorLayout = choice.layout
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, choice.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, maxOf(MIN_BIT_RATE, width * height * BITS_PER_PIXEL))
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
        }

        val newEncoder = MediaCodec.createByCodecName(choice.codecName)
        encoder = newEncoder
        newEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        newEncoder.start()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.i(
            TAG,
            "Soulear recorder configured: codec=${choice.codecName} " +
                "color=${choice.colorFormat} layout=${choice.layout} size=${width}x$height"
        )
    }

    private fun encode(frame: PendingFrame) {
        val activeEncoder = requireNotNull(encoder)
        var inputIndex = activeEncoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) {
            drainEncoder(waitForEnd = false)
            inputIndex = activeEncoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        }
        if (inputIndex < 0) return

        val yuv = SoulearYuv420.convert(frame.pixels, width, height, colorLayout)
        val input = activeEncoder.getInputBuffer(inputIndex)
            ?: throw IOException("Encoder returned no input buffer")
        if (input.capacity() < yuv.size) {
            throw IOException("Encoder input buffer is too small for a $width x $height frame")
        }
        input.clear()
        input.put(yuv)

        if (firstFrameNanos == Long.MIN_VALUE) firstFrameNanos = frame.timestampNanos
        val calculatedTimeUs = (frame.timestampNanos - firstFrameNanos) / 1_000L
        val presentationTimeUs = maxOf(calculatedTimeUs, lastPresentationTimeUs + if (queuedFrames == 0) 0 else 1)
        lastPresentationTimeUs = presentationTimeUs
        activeEncoder.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
        queuedFrames++
        drainEncoder(waitForEnd = false)
    }

    private fun finishRecording() {
        if (completed.get() || encoder == null) return
        acceptingFrames.set(false)
        pendingFrame.set(null)
        try {
            signalEndOfStream()
            if (!drainEncoder(waitForEnd = true)) {
                throw IOException("Timed out finalizing the H.264 stream")
            }
            if (queuedFrames == 0 || !muxerStarted) {
                throw IOException("No video frames were recorded")
            }
            releaseEncoder()
            if (!outputFile.isFile || outputFile.length() == 0L) {
                throw IOException("The MP4 file was not created")
            }
            if (completed.compareAndSet(false, true)) {
                try {
                    listener.onRecordingComplete(outputFile, queuedFrames)
                } finally {
                    completionLatch.countDown()
                }
            }
        } catch (error: Exception) {
            fail(error)
        } finally {
            worker.shutdown()
        }
    }

    private fun signalEndOfStream() {
        val activeEncoder = requireNotNull(encoder)
        val deadline = SystemClock.elapsedRealtime() + INPUT_EOS_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val inputIndex = activeEncoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                activeEncoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    lastPresentationTimeUs + FRAME_INTERVAL_US,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return
            }
            drainEncoder(waitForEnd = false)
        }
        throw IOException("Could not signal the end of the H.264 stream")
    }

    private fun drainEncoder(waitForEnd: Boolean): Boolean {
        val activeEncoder = requireNotNull(encoder)
        val bufferInfo = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + OUTPUT_EOS_TIMEOUT_MS

        while (true) {
            val outputIndex = activeEncoder.dequeueOutputBuffer(
                bufferInfo,
                if (waitForEnd) OUTPUT_TIMEOUT_US else 0L
            )
            when {
                outputIndex >= 0 -> {
                    val encoded = activeEncoder.getOutputBuffer(outputIndex)
                        ?: throw IOException("Encoder returned no output buffer")
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) throw IOException("Encoder produced video before its output format")
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        requireNotNull(muxer).writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    val ended = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    activeEncoder.releaseOutputBuffer(outputIndex, false)
                    if (ended) return true
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IOException("Encoder output format changed twice")
                    trackIndex = requireNotNull(muxer).addTrack(activeEncoder.outputFormat)
                    requireNotNull(muxer).start()
                    muxerStarted = true
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEnd) return false
                    if (SystemClock.elapsedRealtime() >= deadline) return false
                }
            }
        }
    }

    private fun fail(error: Exception) {
        Log.e(TAG, "Soulear recording failed", error)
        acceptingFrames.set(false)
        stopRequested.set(true)
        pendingFrame.set(null)
        releaseEncoder()
        if (outputFile.exists()) outputFile.delete()
        if (completed.compareAndSet(false, true)) {
            try {
                listener.onRecordingError(error.message ?: error.javaClass.simpleName)
            } finally {
                completionLatch.countDown()
            }
        }
        worker.shutdown()
    }

    private fun releaseEncoder() {
        val activeMuxer = muxer
        muxer = null
        if (activeMuxer != null) {
            if (muxerStarted) {
                try {
                    activeMuxer.stop()
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Could not stop MP4 muxer cleanly", error)
                }
            }
            try {
                activeMuxer.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not release MP4 muxer cleanly", error)
            }
        }
        muxerStarted = false

        val activeEncoder = encoder
        encoder = null
        if (activeEncoder != null) {
            try {
                activeEncoder.stop()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not stop H.264 encoder cleanly", error)
            }
            try {
                activeEncoder.release()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not release H.264 encoder cleanly", error)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseEncoder(): EncoderChoice {
        val preferredFormats = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar to SoulearYuv420.Layout.SEMI_PLANAR,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar to SoulearYuv420.Layout.PLANAR,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible to SoulearYuv420.Layout.PLANAR,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar to SoulearYuv420.Layout.SEMI_PLANAR,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar to SoulearYuv420.Layout.PLANAR
        )
        for (codecInfo in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!codecInfo.isEncoder || codecInfo.supportedTypes.none {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                }) continue
            val supported = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.toSet()
            preferredFormats.firstOrNull { it.first in supported }?.let { (colorFormat, layout) ->
                return EncoderChoice(codecInfo.name, colorFormat, layout)
            }
        }
        throw IOException("No H.264 encoder accepts YUV420 video")
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val TAG = "SoulearRecorder"
        private const val TARGET_FRAME_RATE = 20
        private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / TARGET_FRAME_RATE
        private const val FRAME_INTERVAL_US = 1_000_000L / TARGET_FRAME_RATE
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val BITS_PER_PIXEL = 6
        private const val MIN_BIT_RATE = 1_500_000
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val INPUT_EOS_TIMEOUT_MS = 2_000L
        private const val OUTPUT_EOS_TIMEOUT_MS = 5_000L
    }
}
