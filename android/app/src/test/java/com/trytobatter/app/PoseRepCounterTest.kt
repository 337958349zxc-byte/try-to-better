package com.trytobatter.app

import com.trytobatter.app.feature.workout.*
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class PoseRepCounterTest {
    private class Run(val exercise:String) {
        val counter=PoseRepCounter(exercise)
        var time=0L
        var count=0
        lateinit var lastPose:PoseFrame
        fun hold(angle:Double,frames:Int=12,visible:Boolean=true,right:Boolean=false) {
            repeat(frames) {
                time+=100
                val points=MutableList(33) {PosePoint(.5f,.5f,0f)}
                val offset=if(right) 1 else 0
                fun point(index:Int,x:Double,y:Double) {points[index+offset]=PosePoint(x.toFloat(),y.toFloat(),if(visible) .95f else .1f)}
                val theta=angle*PI/180
                if(exercise=="chair") {
                    point(11,.5,.1);point(23,.5+.3*sin(theta),.55+.3*cos(theta))
                    point(25,.5,.55);point(27,.5,.85)
                } else {
                    point(11,.5+.3*cos(theta),.4+.3*sin(theta));point(13,.5,.4)
                    point(15,.8,.4);point(23,.3,.75);point(27,.3,.93)
                }
                lastPose=PoseFrame(points,100,100,time)
                count+=counter.update(lastPose).delta
            }
        }
    }
    @Test fun chairCountsSeatedStandingSeatedOnce() {
        val run=Run("chair");run.hold(90.0);run.hold(175.0);run.hold(90.0)
        assertEquals(1,run.count);run.hold(90.0,30);assertEquals(1,run.count)
    }
    @Test fun wallCountsStraightBentStraightOnceOnEitherSide() {
        for(right in listOf(false,true)) {
            val run=Run("wall");run.hold(175.0,right=right);run.hold(85.0,right=right);run.hold(175.0,right=right)
            assertEquals(1,run.count)
        }
    }
    @Test fun partialRangeAndStationaryFramesDoNotCount() {
        val run=Run("chair");run.hold(90.0);run.hold(140.0);run.hold(90.0);run.hold(90.0,40)
        assertEquals(0,run.count)
    }
    @Test fun startingHalfwayDoesNotCountTheFirstReturn() {
        val run=Run("chair");run.hold(175.0);run.hold(90.0)
        assertEquals(0,run.count)
    }
    @Test fun occlusionDiscardsAnIncompleteCycle() {
        val run=Run("chair");run.hold(90.0);run.hold(175.0);run.hold(175.0,visible=false);run.hold(90.0)
        assertEquals(0,run.count)
        run.hold(175.0);run.hold(90.0);assertEquals(1,run.count)
    }
    @Test fun longFrameGapAndExplicitPauseDiscardIncompleteCycles() {
        for(pause in listOf(false,true)) {
            val run=Run("wall");run.hold(175.0);run.hold(85.0)
            if(pause) run.counter.reset() else run.time+=1000
            run.hold(175.0);assertEquals(0,run.count)
            run.hold(85.0);run.hold(175.0);assertEquals(1,run.count)
        }
    }
    @Test fun changingVisibleSideDiscardsIncompleteCycle() {
        val run=Run("wall");run.hold(175.0);run.hold(85.0);run.hold(175.0,right=true)
        assertEquals(0,run.count)
    }
    @Test fun staleFrameDoesNotAdvanceTheCycle() {
        val run=Run("chair");run.hold(90.0)
        val stale=run.counter.update(run.lastPose)
        assertFalse(stale.tracked);assertEquals("等待新画面",stale.message);assertEquals(0,stale.delta)
    }
}
