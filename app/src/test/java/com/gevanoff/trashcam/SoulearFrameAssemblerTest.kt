package com.gevanoff.trashcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulearFrameAssemblerTest {
    @Test
    fun `sequential chunks are emitted when the frame changes`() {
        val assembler = SoulearFrameAssembler()
        val first = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11)
        val second = byteArrayOf(0x22, 0xff.toByte(), 0xd9.toByte())

        assertNull(assembler.accept(chunk(frameId = 3, index = 2, sequence = 9, payload = first)))
        assertNull(assembler.accept(chunk(frameId = 3, index = 2, sequence = 10, payload = second)))
        val frame = assembler.accept(chunk(frameId = 4, index = 1, payload = first))

        requireNotNull(frame)
        assertArrayEquals(first + second, frame.jpeg)
        assertEquals(1280, frame.width)
        assertEquals(720, frame.height)
    }

    @Test
    fun `last chunk can finish a complete frame immediately`() {
        val assembler = SoulearFrameAssembler()
        val frame = assembler.accept(
            chunk(
                frameId = 7,
                index = 1,
                last = true,
                payload = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte())
            )
        )

        requireNotNull(frame)
        assertEquals(5, frame.jpeg.size)
    }

    @Test
    fun `frame with a missing chunk is rejected`() {
        val assembler = SoulearFrameAssembler()
        assembler.accept(chunk(frameId = 1, index = 3, sequence = 1, payload = byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        assembler.accept(chunk(frameId = 1, index = 3, sequence = 3, payload = byteArrayOf(0xff.toByte(), 0xd9.toByte())))

        assertNull(assembler.accept(chunk(frameId = 2, index = 1, payload = byteArrayOf(0xff.toByte(), 0xd8.toByte()))))
    }

    @Test
    fun `oversized incomplete frame is dropped and assembler recovers`() {
        val rejections = mutableListOf<String>()
        val assembler = SoulearFrameAssembler(
            maxFrameBytes = 5,
            onFrameRejected = rejections::add
        )

        assertNull(
            assembler.accept(
                chunk(frameId = 1, index = 2, payload = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11))
            )
        )
        assertNull(
            assembler.accept(
                chunk(frameId = 1, index = 2, sequence = 3, payload = byteArrayOf(0x22, 0x33, 0x44))
            )
        )

        val recovered = assembler.accept(
            chunk(
                frameId = 2,
                index = 1,
                last = true,
                payload = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte())
            )
        )

        assertEquals(1, rejections.size)
        assertTrue(rejections.single().contains("more than 5 bytes"))
        requireNotNull(recovered)
        assertEquals(5, recovered.jpeg.size)
    }

    @Test
    fun `incomplete frame with too many chunks is dropped`() {
        val rejections = mutableListOf<String>()
        val assembler = SoulearFrameAssembler(
            maxFrameChunks = 2,
            onFrameRejected = rejections::add
        )

        assertNull(assembler.accept(chunk(frameId = 9, index = 3, sequence = 1, payload = byteArrayOf(0xff.toByte(), 0xd8.toByte()))))
        assertNull(assembler.accept(chunk(frameId = 9, index = 3, sequence = 2, payload = byteArrayOf(0x11))))
        assertNull(assembler.accept(chunk(frameId = 9, index = 3, sequence = 3, payload = byteArrayOf(0x22))))

        assertEquals(1, rejections.size)
        assertTrue(rejections.single().contains("more than 2 chunks"))
    }

    private fun chunk(
        frameId: Int,
        index: Int,
        sequence: Int = index,
        last: Boolean = false,
        payload: ByteArray
    ) = SoulearProtocol.VideoChunk(
        kind = 1,
        sequence = sequence,
        frameId = frameId,
        lastChunk = last,
        chunkMarker = index,
        width = 1280,
        height = 720,
        jpegPayload = payload
    )
}
