package com.gevanoff.trashcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SoulearYuv420Test {
    @Test
    fun `black block converts to limited range neutral YUV`() {
        assertArrayEquals(
            byteArrayOf(16, 16, 16, 16, 128.toByte(), 128.toByte()),
            SoulearYuv420.convert(
                IntArray(4) { 0xff000000.toInt() },
                2,
                2,
                SoulearYuv420.Layout.PLANAR
            )
        )
    }

    @Test
    fun `white block converts to semi planar neutral YUV`() {
        assertArrayEquals(
            byteArrayOf(235.toByte(), 235.toByte(), 235.toByte(), 235.toByte(), 128.toByte(), 128.toByte()),
            SoulearYuv420.convert(
                IntArray(4) { 0xffffffff.toInt() },
                2,
                2,
                SoulearYuv420.Layout.SEMI_PLANAR
            )
        )
    }

    @Test
    fun `planar and semi planar layouts place chroma differently`() {
        val pixels = intArrayOf(
            0xffff0000.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff00ff00.toInt(),
            0xffff0000.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff00ff00.toInt()
        )

        val planar = SoulearYuv420.convert(pixels, 4, 2, SoulearYuv420.Layout.PLANAR)
        val semiPlanar = SoulearYuv420.convert(pixels, 4, 2, SoulearYuv420.Layout.SEMI_PLANAR)

        assertArrayEquals(byteArrayOf(90.toByte(), 54, 240.toByte(), 34), planar.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(90.toByte(), 240.toByte(), 54, 34), semiPlanar.copyOfRange(8, 12))
    }

    @Test
    fun `odd dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SoulearYuv420.convert(IntArray(9), 3, 3, SoulearYuv420.Layout.PLANAR)
        }
    }
}
