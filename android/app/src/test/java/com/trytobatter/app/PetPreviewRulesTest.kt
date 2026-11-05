package com.trytobatter.app

import com.trytobatter.app.feature.petpreview.*
import org.junit.Assert.*
import org.junit.Test

class PetPreviewRulesTest {
    @Test fun completePetStaysInsideSafeAreaAtAllSizes() {
        for (width in listOf(320f, 360f, 393f, 430f)) for (height in listOf(480f, 640f, 800f)) for (size in listOf(112f, 176f, 208f)) {
            val b = petBounds(width, height, size)
            assertTrue(b.left >= 8f); assertTrue(b.top >= 8f)
            assertTrue(b.right + size <= width - 8f)
            assertTrue(b.bottom + size <= height - 8f)
            assertEquals(b.left, b.x(-100f)); assertEquals(b.bottom, b.y(10000f))
            assertEquals(b.left, b.dock(b.left)); assertEquals(b.right, b.dock(b.right))
        }
    }
    @Test fun selectedAtlasFramesStayInRangeAndNonLoopsHoldLastPose() {
        for (state in PetMotion.entries) {
            val clip = state.clip()
            assertTrue(clip.durations.all { it > 0 })
            val last = clip.durations.lastIndex
            assertEquals(0, clip.frameAt(0))
            assertEquals(last, clip.frameAt(clip.durationMs - 1L))
            assertEquals(if (clip.loop) 0 else last, clip.frameAt(clip.durationMs.toLong()))
        }
    }
}
