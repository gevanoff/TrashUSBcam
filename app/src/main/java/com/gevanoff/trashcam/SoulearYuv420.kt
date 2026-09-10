package com.gevanoff.trashcam

/** Converts Android ARGB pixels to tightly packed YUV420 accepted by video encoders. */
internal object SoulearYuv420 {
    enum class Layout { PLANAR, SEMI_PLANAR }

    fun convert(
        pixels: IntArray,
        width: Int,
        height: Int,
        layout: Layout
    ): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "YUV420 dimensions must be positive and even"
        }
        require(pixels.size >= width * height) { "Not enough ARGB pixels" }

        val frameSize = width * height
        val result = ByteArray(frameSize * 3 / 2)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                result[y * width + x] = luma(pixel).toByte()
            }
        }

        var planarU = frameSize
        var planarV = frameSize + frameSize / 4
        var interleavedUv = frameSize
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val chroma = averageChroma(pixels, width, x, y)
                if (layout == Layout.PLANAR) {
                    result[planarU++] = chroma.first.toByte()
                    result[planarV++] = chroma.second.toByte()
                } else {
                    result[interleavedUv++] = chroma.first.toByte()
                    result[interleavedUv++] = chroma.second.toByte()
                }
            }
        }
        return result
    }

    private fun luma(pixel: Int): Int {
        val red = pixel shr 16 and 0xff
        val green = pixel shr 8 and 0xff
        val blue = pixel and 0xff
        return (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16).coerceIn(0, 255)
    }

    private fun averageChroma(
        pixels: IntArray,
        width: Int,
        x: Int,
        y: Int
    ): Pair<Int, Int> {
        var red = 0
        var green = 0
        var blue = 0
        for (dy in 0..1) {
            for (dx in 0..1) {
                val pixel = pixels[(y + dy) * width + x + dx]
                red += pixel shr 16 and 0xff
                green += pixel shr 8 and 0xff
                blue += pixel and 0xff
            }
        }
        red /= 4
        green /= 4
        blue /= 4
        val u = (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128).coerceIn(0, 255)
        val v = (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128).coerceIn(0, 255)
        return u to v
    }
}
