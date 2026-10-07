package com.gevanoff.trashcam

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOrientationTest {
    @Test fun squareFrameRetainsFullHeightAtEveryAngle() {
        for (turn in 0..3) assertEquals(1f, PreviewOrientation.fitScale(720f, 720f, 1280f, 720f, turn), 0.0001f)
    }
    @Test fun landscapeFrameFitsAfterQuarterTurn() {
        assertEquals(0.5625f, PreviewOrientation.fitScale(1280f, 720f, 1280f, 720f, 1), 0.0001f)
    }
    @Test fun portraitFrameCanGrowWhenRotatedIntoLandscape() {
        assertEquals(1.7777778f, PreviewOrientation.fitScale(405f, 720f, 1280f, 720f, 3), 0.0001f)
    }
    @Test fun unmeasuredViewUsesIdentityScale() {
        assertEquals(1f, PreviewOrientation.fitScale(0f, 0f, 0f, 0f, 1), 0f)
    }
}
