package com.trytobatter.app

import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AlarmRulesTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val task=TaskItem(id="a",title="英语",category=Category.ENGLISH,scheduledAt="2026-10-27T20:00",durationMinutes=60)
    private val setting=TaskAlarmSettings(true)
    private fun millis(s:String)=LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    @Test fun tenMinutesBeforeStart() { assertEquals(millis("2026-10-27T19:50"),AlarmRules.triggerAt(task,setting,zone)) }
    @Test fun shiftInvalidatesOldCalculation() { assertEquals(millis("2026-10-27T20:50"),AlarmRules.triggerAt(task.copy(scheduledAt="2026-10-27T21:00"),setting,zone)) }
    @Test fun acrossMidnight() { assertEquals(millis("2026-10-26T23:55"),AlarmRules.triggerAt(task.copy(scheduledAt="2026-10-27T00:05"),setting,zone)) }
    @Test fun deletedIsCancelled() { assertEquals(AlarmState.CANCELLED,AlarmRules.desired(null,setting,null,0)) }
    @Test fun allFinishedStatusesAreCancelled() { for(s in listOf(TaskStatus.COMPLETED,TaskStatus.PARTIAL,TaskStatus.SKIPPED)) assertEquals(AlarmState.CANCELLED,AlarmRules.desired(task.copy(status=s),setting,null,0)) }
    @Test fun startedAndPausedDoNotRemindAgain() { assertFalse(AlarmRules.eligible(task.copy(executionStartedAt=1,status=TaskStatus.PAUSED))) }
    @Test fun repeatInstancesHaveIndependentIdentities() { assertNotEquals(AlarmRules.fingerprint(task,setting),AlarmRules.fingerprint(task.copy(id="b",scheduledAt="2026-10-28T20:00"),setting)) }
    @Test fun oldGenerationCannotDeliver() { val r=AlarmRecord("a",AlarmRules.fingerprint(task,setting),"new",1000,AlarmState.SCHEDULED); assertFalse(AlarmRules.validDelivery(task,setting,r,"old",1000)) }
    @Test fun revisionInvalidatesPendingDelivery() { val r=AlarmRecord("a",AlarmRules.fingerprint(task,setting),"v",1000,AlarmState.SCHEDULED); assertFalse(AlarmRules.validDelivery(task.copy(revision=2),setting,r,"v",1000)) }
    @Test fun missingPermissionNeverClaimsScheduled() { assertEquals(AlarmState.NEEDS_PERMISSION,AlarmRules.desired(task,setting,"denied",0)) }
    @Test fun expiredNeverBecomesImmediateAlarm() { assertEquals(AlarmState.MISSED,AlarmRules.desired(task,setting,null,Long.MAX_VALUE)) }
    @Test fun existingItemsDefaultDisabled() { assertFalse(TaskAlarmSettings().enabled); assertEquals(10,TaskAlarmSettings().leadMinutes) }
    @Test fun handledDeliveryCannotRingAgain() { val r=AlarmRecord("a",AlarmRules.fingerprint(task,setting),"v",1000,AlarmState.CANCELLED); assertFalse(AlarmRules.validDelivery(task,setting,r,"v",1000)) }
    @Test fun snoozeLimitedAndDoesNotChangeTaskTime() { val r=AlarmRecord("a","fp","v",1000,AlarmState.RINGING,snoozeCount=2); assertFalse(AlarmRules.canSnooze(task,r,0)); assertEquals("2026-10-27T20:00",task.scheduledAt) }
}
