package com.gevanoff.trashcam

import java.io.ByteArrayOutputStream

/** Reassembles the camera's unordered MJPEG UDP chunks into complete JPEG images. */
internal class SoulearFrameAssembler {
    private var currentFrameId: Int? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private var expectedSequence: Int? = null
    private var frameIsComplete = true
    private var finalChunkMarker = 0
    private val chunks = mutableListOf<ByteArray>()

    fun accept(chunk: SoulearProtocol.VideoChunk): Frame? {
        val completed = if (currentFrameId != null && chunk.frameId != currentFrameId) {
            finishCurrentFrame()
        } else {
            null
        }

        if (currentFrameId != chunk.frameId) {
            beginFrame(chunk)
        }
        appendChunk(chunk)

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
        chunks.clear()
    }

    private fun beginFrame(chunk: SoulearProtocol.VideoChunk) {
        currentFrameId = chunk.frameId
        currentWidth = chunk.width
        currentHeight = chunk.height
        expectedSequence = null
        frameIsComplete = true
        finalChunkMarker = 0
        chunks.clear()
    }

    private fun appendChunk(chunk: SoulearProtocol.VideoChunk) {
        expectedSequence?.let { expected ->
            if (chunk.sequence != expected) frameIsComplete = false
        }
        expectedSequence = (chunk.sequence + 1) and 0xff
        finalChunkMarker = chunk.chunkMarker
        chunks += chunk.jpegPayload
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
        private const val JPEG_MARKER: Byte = 0xff.toByte()
        private const val JPEG_START: Byte = 0xd8.toByte()
        private const val JPEG_END: Byte = 0xd9.toByte()
    }
}
