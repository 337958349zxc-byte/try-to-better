package com.trytobatter.app

import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineClockRulesTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun epoch(time:String)=LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli()
    private val now=epoch("2026-10-27T12:00")
    private val task=TaskItem(id="one",title="英语",category=Category.ENGLISH,scheduledAt="2026-10-27T14:00")
    private fun targets(tasks:List<TaskItem>)=TimelineClockRules.targets(tasks,"system.clock",now,zone)
    private fun plan(tasks:List<TaskItem>,s:TimelineClockState,on:Boolean=true)=TimelineClockRules.plan(tasks,s,"system.clock",on,now,zone)
    private val sent get()=targets(listOf(task)).single().copy(stage=ClockRequestStage.REQUESTED)
    @Test fun todayAllFutureOccurrencesUseTenMinutesAndDistinctLabels() {
        val result=targets(listOf(task,task.copy(id="two",scheduledAt="2026-10-27T15:00")))
        assertEquals(2,result.size);assertEquals(epoch("2026-10-27T13:50"),result.first().triggerAt)
        assertNotEquals(result[0].label,result[1].label)
    }
    @Test fun pastStartedFinishedAndOtherDaysAreExcluded() {
        val tasks=listOf(task.copy(scheduledAt="2026-10-27T12:05"),task.copy(status=TaskStatus.COMPLETED),
            task.copy(status=TaskStatus.RUNNING,executionStartedAt=1),task.copy(scheduledAt="2026-10-28T14:00"))
        assertTrue(targets(tasks).isEmpty())
    }
    @Test fun minuteRoundingCannotAccidentallyCreateTomorrowAlarm() {
        assertTrue(targets(listOf(task.copy(scheduledAt="2026-10-27T12:10:30"))).isEmpty())
    }
    @Test fun unchangedRequestsAreNotSentAgain() {
        assertTrue(plan(listOf(task),TimelineClockState(entries=mapOf(task.id to sent))).isEmpty())
    }
    @Test fun rescheduleCancelsOldLabelBeforeSettingNewTime() {
        val result=plan(listOf(task.copy(scheduledAt="2026-10-27T15:00")),TimelineClockState(entries=mapOf(task.id to sent)))
        assertEquals(listOf(ClockAction.CANCEL,ClockAction.SET),result.map { it.action })
        assertEquals(sent.label,result.first().label);assertEquals(epoch("2026-10-27T14:50"),result.last().triggerAt)
    }
    @Test fun deletionAndGlobalOffOnlyCancelThisDaysLinkedLabels() {
        val state=TimelineClockState(entries=mapOf(task.id to sent,"yesterday" to sent.copy(taskId="yesterday",day="2026-10-26")))
        assertEquals(listOf(ClockAction.CANCEL),plan(emptyList(),state).map { it.action })
        assertEquals(sent.label,plan(listOf(task),state,false).single().label)
    }
    @Test fun uncertainPreviousHandoffIsCancelledBeforeRetry() {
        val result=plan(listOf(task),TimelineClockState(entries=mapOf(task.id to sent.copy(stage=ClockRequestStage.PREPARING))))
        assertEquals(listOf(ClockAction.CANCEL,ClockAction.SET),result.map { it.action })
    }
    @Test fun launchFailureCanRetrySettingWithoutRequiringACancelHandler() {
        val result=plan(listOf(task),TimelineClockState(entries=mapOf(task.id to sent.copy(stage=ClockRequestStage.FAILED))))
        assertEquals(listOf(ClockAction.SET),result.map {it.action})
    }
    @Test fun explicitCloseRetriesReturnedButUnverifiedCancellation() {
        val state=TimelineClockState(entries=mapOf(task.id to sent.copy(action=ClockAction.CANCEL)))
        assertEquals(listOf(ClockAction.CANCEL),plan(listOf(task),state,false).map {it.action})
        assertEquals(listOf(ClockAction.SET),plan(listOf(task),state,true).map {it.action})
    }
}
