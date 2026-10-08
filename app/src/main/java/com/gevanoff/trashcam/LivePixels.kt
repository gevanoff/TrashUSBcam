package com.gevanoff.trashcam

/** BT.601 limited-range planar YUV for WebRTC's I420 input. */
internal object LivePixels {
    fun i420(pixels: IntArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(pixels.size == width * height)
        val size = width * height
        val out = ByteArray(size * 3 / 2)
        fun channel(value: Int) = value.coerceIn(0, 255).toByte()
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var red = 0; var green = 0; var blue = 0
            for (dy in 0..1) for (dx in 0..1) {
                val index = (y + dy) * width + x + dx
                val p = pixels[index]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                out[index] = channel(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                red += r; green += g; blue += b
            }
            val r = red / 4; val g = green / 4; val b = blue / 4
            val index = (y / 2) * (width / 2) + x / 2
            out[size + index] = channel(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
            out[size + size / 4 + index] = channel(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
        }
        return out
    }
}
