package com.gevanoff.trashcam

/** Uniform scale that fits the rotated rendered frame inside its viewport. */
internal object PreviewOrientation {
    fun legacyQuarterTurns(flipped180: Boolean): Int = if (flipped180) 2 else 0

    fun fitScale(frameWidth: Float, frameHeight: Float, viewportWidth: Float,
                 viewportHeight: Float, quarterTurns: Int): Float {
        if (frameWidth <= 0f || frameHeight <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) return 1f
        val sideways = quarterTurns % 2 != 0
        val width = if (sideways) frameHeight else frameWidth
        val height = if (sideways) frameWidth else frameHeight
        return minOf(viewportWidth / width, viewportHeight / height)
    }
}
