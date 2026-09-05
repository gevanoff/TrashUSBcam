package com.gevanoff.trashcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
