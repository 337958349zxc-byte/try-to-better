package com.trytobatter.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.feature.workout.LocalPoseDetector
import org.junit.Assert.*
import org.junit.Test

/** Real bundled native model and public sample image; no app data is changed. */
class PoseModelSmokeTest {
    @Test fun bundledModelDetectsPersonAndRejectsBlankFrame() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val blank=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        val person=instrumentation.context.assets.open("pose-smoke.jpg").use {BitmapFactory.decodeStream(it)}
        try {
            LocalPoseDetector(instrumentation.targetContext).use {detector ->
                assertTrue(detector.detect(blank,0).points.isEmpty())
                val pose=detector.detect(person,100)
                assertEquals(33,pose.points.size)
                assertTrue(pose.points.count {it.confidence>=.55f}>=10)
            }
        } finally {blank.recycle();person.recycle()}
    }
}
