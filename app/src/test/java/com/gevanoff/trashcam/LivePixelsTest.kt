package com.gevanoff.trashcam

import org.junit.Assert.*
import org.junit.Test

class LivePixelsTest {
    @Test fun blackAndWhiteUseLimitedRangeAndNeutralChroma() {
        assertArrayEquals(byteArrayOf(16, 16, 16, 16, 128.toByte(), 128.toByte()),
            LivePixels.i420(IntArray(4) { 0xff000000.toInt() }, 2, 2))
        assertArrayEquals(byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte()),
            LivePixels.i420(IntArray(4) { 0xffffffff.toInt() }, 2, 2))
    }
    @Test fun redHasExpectedLumaAndChromaPlanes() {
        val converted = LivePixels.i420(IntArray(8) { 0xffff0000.toInt() }, 4, 2)
        assertEquals(12, converted.size)
        assertTrue(converted.take(8).all { (it.toInt() and 255) == 82 })
        assertEquals(90, converted[8].toInt() and 255)
        assertEquals(240, converted[10].toInt() and 255)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddDimensions() { LivePixels.i420(IntArray(6), 3, 2) }
}
