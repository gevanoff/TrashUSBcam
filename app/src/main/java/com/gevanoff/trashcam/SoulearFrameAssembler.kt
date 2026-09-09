package com.gevanoff.trashcam

import java.io.ByteArrayOutputStream

/** Reassembles the camera's unordered MJPEG UDP chunks into complete JPEG images. */
internal class SoulearFrameAssembler(
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val maxFrameChunks: Int = DEFAULT_MAX_FRAME_CHUNKS,
    private val onFrameRejected: (String) -> Unit = {}
) {
    private var currentFrameId: Int? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var expectedSequence: Int? = null
    private var frameIsComplete = true
    private var finalChunkMarker = 0
    private var frameBytes = 0
    private val chunks = mutableListOf<ByteArray>()

    init {
        require(maxFrameBytes > 0) { "Maximum frame size must be positive" }
        require(maxFrameChunks > 0) { "Maximum frame chunk count must be positive" }
    }

    fun accept(chunk: SoulearProtocol.VideoChunk): Frame? {
        val completed = if (currentFrameId != null && chunk.frameId != currentFrameId) {
            finishCurrentFrame()
        } else {
            null
        }

        if (currentFrameId != chunk.frameId) {
            beginFrame(chunk)
        }
        if (!appendChunk(chunk)) return completed

        return completed ?: if (chunk.lastChunk && chunk.jpegPayload.endsWithJpegMarker()) {
            finishCurrentFrame().also {
                currentFrameId = null
                chunks.clear()
            }
        } else {
            null
        }
    }

    fun reset() {
        currentFrameId = null
        currentWidth = 0
        currentHeight = 0
        expectedSequence = null
        frameIsComplete = true
        finalChunkMarker = 0
        frameBytes = 0
        chunks.clear()
    }

    private fun beginFrame(chunk: SoulearProtocol.VideoChunk) {
        currentFrameId = chunk.frameId
        currentWidth = chunk.width
        currentHeight = chunk.height
        expectedSequence = null
        frameIsComplete = true
        finalChunkMarker = 0
        frameBytes = 0
        chunks.clear()
    }

    private fun appendChunk(chunk: SoulearProtocol.VideoChunk): Boolean {
        val rejectionReason = when {
            chunks.size >= maxFrameChunks -> "more than $maxFrameChunks chunks"
            chunk.jpegPayload.size > maxFrameBytes - frameBytes -> "more than $maxFrameBytes bytes"
            else -> null
        }
        if (rejectionReason != null) {
            val rejectedFrameId = currentFrameId
            reset()
            onFrameRejected("Dropping MJPEG frame $rejectedFrameId: $rejectionReason")
            return false
        }

        expectedSequence?.let { expected ->
            if (chunk.sequence != expected) frameIsComplete = false
        }
        expectedSequence = (chunk.sequence + 1) and 0xff
        finalChunkMarker = chunk.chunkMarker
        frameBytes += chunk.jpegPayload.size
        chunks += chunk.jpegPayload
        return true
    }

    private fun finishCurrentFrame(): Frame? {
        if (chunks.isEmpty()) return null
        if (!frameIsComplete || finalChunkMarker != chunks.size) return null

        val output = ByteArrayOutputStream(chunks.sumOf { it.size })
        chunks.forEach { output.write(it) }
        val jpeg = output.toByteArray()
        if (!jpeg.startsWithJpegMarker()) return null
        val end = jpeg.lastJpegEndIndex()
        if (end < 0) return null
        return Frame(jpeg.copyOfRange(0, end + 2), currentWidth, currentHeight)
    }

    private fun ByteArray.startsWithJpegMarker(): Boolean {
        return size >= 2 && this[0] == JPEG_MARKER && this[1] == JPEG_START
    }

    private fun ByteArray.endsWithJpegMarker(): Boolean {
        return size >= 2 && this[size - 2] == JPEG_MARKER && this[size - 1] == JPEG_END
    }

    private fun ByteArray.lastJpegEndIndex(): Int {
        for (index in size - 2 downTo 0) {
            if (this[index] == JPEG_MARKER && this[index + 1] == JPEG_END) return index
        }
        return -1
    }

    data class Frame(val jpeg: ByteArray, val width: Int, val height: Int)

    companion object {
        private const val DEFAULT_MAX_FRAME_BYTES = 8 * 1024 * 1024
        private const val DEFAULT_MAX_FRAME_CHUNKS = 255
        private const val JPEG_MARKER: Byte = 0xff.toByte()
        private const val JPEG_START: Byte = 0xd8.toByte()
        private const val JPEG_END: Byte = 0xd9.toByte()
    }
}
