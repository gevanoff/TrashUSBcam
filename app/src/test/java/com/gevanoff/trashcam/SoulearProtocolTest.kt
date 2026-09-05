package com.gevanoff.trashcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulearProtocolTest {
    @Test
    fun `device info request uses little endian framing`() {
        assertArrayEquals(
            byteArrayOf(
                0xee.toByte(), 0xff.toByte(), 0xee.toByte(), 0xff.toByte(),
                0x01, 0x00,
                0x01, 0x00,
                0x01, 0x00,
                0x00, 0x00
            ),
            SoulearProtocol.request(1, SoulearProtocol.TYPE_GET_DEVICE_INFO)
        )
    }

    @Test
    fun `response parser returns header and payload`() {
        val responseBytes = byteArrayOf(
            0xee.toByte(), 0xff.toByte(), 0xee.toByte(), 0xff.toByte(),
            0x34, 0x12,
            0x01, 0x00,
            0x01, 0x00,
            0x03, 0x00,
            0x41, 0x42, 0x43
        )

        val response = SoulearProtocol.parseResponse(responseBytes)

        requireNotNull(response)
        assertEquals(0x1234, response.id)
        assertEquals(SoulearProtocol.TYPE_GET_DEVICE_INFO, response.type)
        assertEquals(0, response.errorCode)
        assertArrayEquals(byteArrayOf(0x41, 0x42, 0x43), response.payload)
    }

    @Test
    fun `open video request appends port but declares an empty payload`() {
        assertArrayEquals(
            byteArrayOf(
                0xee.toByte(), 0xff.toByte(), 0xee.toByte(), 0xff.toByte(),
                0x02, 0x00,
                0x04, 0x00,
                0x01, 0x00,
                0x00, 0x00,
                0x34, 0x12
            ),
            SoulearProtocol.openVideoRequest(2, 0x1234)
        )
    }

    @Test
    fun `device info parser extracts fixed width strings`() {
        val payload = ByteArray(128)
        "Soulear".toByteArray().copyInto(payload, 1)
        "BK7231U-XRH-FBPRO".toByteArray().copyInto(payload, 33)
        "1.2.3".toByteArray().copyInto(payload, 65)
        "Soulear-394b3".toByteArray().copyInto(payload, 81)

        val info = SoulearProtocol.parseDeviceInfo(payload)

        requireNotNull(info)
        assertEquals("Soulear", info.vendor)
        assertEquals("BK7231U-XRH-FBPRO", info.product)
        assertEquals("1.2.3", info.firmwareVersion)
        assertEquals("Soulear-394b3", info.ssid)
    }

    @Test
    fun `video chunk parser extracts metadata and jpeg payload`() {
        val packet = ByteArray(19)
        packet[0] = 1
        packet[1] = 7
        packet[2] = 9
        packet[3] = 1
        packet[4] = 2
        packet[12] = 0x00
        packet[13] = 0x05
        packet[14] = 0xd0.toByte()
        packet[15] = 0x02
        packet[16] = 0xff.toByte()
        packet[17] = 0xd8.toByte()
        packet[18] = 0x42

        val chunk = SoulearProtocol.parseVideoChunk(packet)

        requireNotNull(chunk)
        assertEquals(7, chunk.sequence)
        assertEquals(9, chunk.frameId)
        assertTrue(chunk.lastChunk)
        assertEquals(2, chunk.chunkMarker)
        assertEquals(1280, chunk.width)
        assertEquals(720, chunk.height)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x42), chunk.jpegPayload)
    }

    @Test
    fun `four byte discovery acknowledgement has magic but no frame`() {
        assertTrue(SoulearProtocol.hasMagic(SoulearProtocol.magicBytes))
        assertNull(SoulearProtocol.parseResponse(SoulearProtocol.magicBytes))
    }

    @Test
    fun `invalid or truncated data is rejected`() {
        assertFalse(SoulearProtocol.hasMagic(byteArrayOf(0xee.toByte(), 0xff.toByte())))
        assertNull(
            SoulearProtocol.parseResponse(
                byteArrayOf(
                    0xee.toByte(), 0xff.toByte(), 0xee.toByte(), 0xff.toByte(),
                    0x01, 0x00,
                    0x01, 0x00,
                    0x01, 0x00,
                    0x02, 0x00,
                    0x41
                )
            )
        )
    }
}
