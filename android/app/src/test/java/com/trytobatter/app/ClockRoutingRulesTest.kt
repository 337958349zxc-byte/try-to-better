package com.trytobatter.app

import com.trytobatter.app.core.alarm.*
import org.junit.Assert.*
import org.junit.Test

class ClockRoutingRulesTest {
    private val clock=SystemClockApp("com.android.deskclock","时钟")
    private val other=SystemClockApp("user.clock","闹钟")
    @Test fun xiaomiClockIsChosenWithSeveralHandlersAndNoDefault() {
        assertEquals(clock,ClockRoutingRules.choose(listOf(clock,other),"android",clock,true))
    }
    @Test fun validDefaultDoesNotNeedASystemFlag() {
        assertEquals(other,ClockRoutingRules.choose(listOf(clock,other),other.packageName,null,false))
    }
    @Test fun multipleActivitiesOfOnePackageAreOneClock() {
        assertEquals(clock,ClockRoutingRules.choose(listOf(clock,clock.copy(label="设置闹钟")),null,null,false))
    }
    @Test fun multiplePackagesWithoutDefaultUseAndroidChoice() {
        assertEquals("",ClockRoutingRules.choose(listOf(clock,other),"android",null,false).packageName)
    }
    @Test fun installedKnownClockCanBeUsedWhenIntentQueryIsEmpty() {
        assertEquals(clock,ClockRoutingRules.choose(emptyList(),null,clock,true))
    }
    @Test fun emptyQueryDoesNotPreventAnActualPlatformAttempt() {
        assertEquals("",ClockRoutingRules.choose(emptyList(),null,null,false).packageName)
    }
}
