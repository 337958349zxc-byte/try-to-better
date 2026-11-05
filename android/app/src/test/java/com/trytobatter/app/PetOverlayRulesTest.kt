package com.trytobatter.app

import com.trytobatter.app.feature.petoverlay.overlayBounds
import org.junit.Assert.*
import org.junit.Test

class PetOverlayRulesTest {
    @Test fun fullCharacterRespectsInsetsInPortraitAndLandscape() {
        for ((w,h) in listOf(1080 to 2400, 2400 to 1080, 720 to 1280)) for (size in listOf(224,352,416)) {
            val b = overlayBounds(w,h,24,80,24,120,size,24)
            assertTrue(b.left >= 48); assertTrue(b.top >= 104)
            assertTrue(b.right + size <= w - 48); assertTrue(b.bottom + size <= h - 144)
            assertEquals(b.left,b.x(-9999)); assertEquals(b.right,b.x(99999))
            assertEquals(b.top,b.y(-9999)); assertEquals(b.bottom,b.y(99999))
        }
    }
    @Test fun visibleKeyboardReducesSafeBottom() {
        val normal=overlayBounds(1080,2400,0,80,0,100,484,33)
        val keyboard=overlayBounds(1080,2400,0,80,0,800,484,33)
        assertEquals(normal.bottom-700,keyboard.bottom)
    }
}
