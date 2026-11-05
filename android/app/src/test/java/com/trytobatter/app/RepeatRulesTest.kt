package com.trytobatter.app

import com.trytobatter.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RepeatRulesTest {
    private val start=LocalDate.parse("2026-11-02")
    private fun task(rule:String="")=TaskItem(id="first",title="英语",category=Category.ENGLISH,scheduledAt="2026-11-02T09:00",repeatRule=rule)
    private fun rule(end:String="2026-12-02",days:Set<Int> = setOf(1,3,5),interval:Int=1)=RepeatSchedule("W",interval,days,LocalDate.parse(end)).encode()

    @Test fun weekdaySchedulePassesOldFourOccurrenceLimitAndIncludesEndDate() {
        val dates=RepeatRules.dates(rule(),start)
        assertEquals(14,dates.size)
        assertEquals(start,dates.first());assertEquals(LocalDate.parse("2026-12-02"),dates.last())
        assertTrue(dates.all {it.dayOfWeek.value in setOf(1,3,5)})
    }
    @Test fun alternatingWeeksAreAnchoredToMondayAcrossYearBoundary() {
        val dates=RepeatRules.dates(rule("2027-01-15",setOf(1,5),2),LocalDate.parse("2026-12-28"))
        assertEquals(listOf("2026-12-28","2027-01-01","2027-01-11","2027-01-15"),dates.map {it.toString()})
    }
    @Test fun dailyIntervalsHandleLeapDayAndInclusiveEnd() {
        val r=RepeatSchedule("D",2,emptySet(),LocalDate.parse("2028-03-02")).encode()
        assertEquals(listOf("2028-02-27","2028-02-29","2028-03-02"),RepeatRules.dates(r,LocalDate.parse("2028-02-27")).map {it.toString()})
    }
    @Test fun invalidRulesAndUnboundedRangesAreRejected() {
        assertNull(RepeatRules.parse("R1|W|0|1|2026-12-02"))
        assertNull(RepeatRules.parse("R1|W|1||2026-12-02"))
        for(r in listOf(rule("2026-11-01"),rule("2028-11-02"),rule(days=setOf(2)))) {
            assertTrue(runCatching {RepeatRules.dates(r,start)}.isFailure)
        }
    }
    @Test fun repeatedSaveDoesNotDuplicateMaterializedInstances() {
        val first=task(rule())
        val saved=RepeatRules.save(emptyList(),first,false)
        val again=RepeatRules.save(saved,first,false)
        assertEquals(14,again.size)
        assertEquals(saved.map {it.id}.toSet(),again.map {it.id}.toSet())
        assertEquals(1,again.map {it.seriesId}.distinct().size)
    }
    @Test fun thisOnlyEditPreservesOtherInstancesAndExecutionHistory() {
        val saved=RepeatRules.save(emptyList(),task(rule()),false)
        val history=saved[1].copy(status=TaskStatus.PAUSED,executionStartedAt=123,executionSnapshot="original",accumulatedSeconds=40)
        val input=saved.map {if(it.id==history.id) history else it}
        val result=RepeatRules.save(input,input.first().copy(title="临时修改",scheduledAt="2026-11-03T10:00"),false)
        assertEquals(input.drop(1),result.drop(1));assertEquals(history,result[1])
    }
    @Test fun followingShiftRetainsProtectedAndAlreadyTimedPendingInstances() {
        val saved=RepeatRules.save(emptyList(),task(rule()),false).toMutableList()
        saved[1]=saved[1].copy(status=TaskStatus.COMPLETED,executionStartedAt=10,executionSnapshot="historic")
        saved[2]=saved[2].copy(accumulatedSeconds=40)
        val result=RepeatRules.save(saved,saved.first().copy(title="新标题",scheduledAt="2026-11-03T10:00"),true)
        assertEquals(saved[1],result[1]);assertEquals(saved[2],result[2])
        assertEquals("2026-11-10T10:00",result[3].scheduledAt)
        assertEquals(setOf(2,4,6),RepeatRules.parse(result[3].repeatRule)!!.weekdays)
    }
    @Test fun changedRuleRebuildsOnlyUnstartedFutureAndDoesNotDuplicateProtectedDay() {
        val saved=RepeatRules.save(emptyList(),task(rule("2026-11-13")),false).toMutableList()
        saved[1]=saved[1].copy(status=TaskStatus.PAUSED,executionStartedAt=123,executionSnapshot="snapshot")
        val changed=saved.first().copy(repeatRule=RepeatSchedule("D",1,emptySet(),LocalDate.parse("2026-11-06")).encode())
        assertTrue(runCatching {RepeatRules.save(saved,changed,false)}.isFailure)
        val rebuilt=RepeatRules.save(saved,changed,true)
        assertTrue(rebuilt.contains(saved[1]))
        assertEquals(1,rebuilt.count {it.day==LocalDate.parse("2026-11-04")})
        assertEquals(5,rebuilt.size)
        assertTrue(rebuilt.none {it.day>LocalDate.parse("2026-11-06")})
    }
    @Test fun legacyRulesRemainFiniteAndStoppingRepeatKeepsHistory() {
        assertEquals(7,RepeatRules.dates("每天",start).size)
        val saved=RepeatRules.save(emptyList(),task("每周"),false).toMutableList()
        saved[2]=saved[2].copy(status=TaskStatus.COMPLETED)
        val stopped=RepeatRules.save(saved,saved.first().copy(repeatRule=""),true)
        assertEquals(2,stopped.size);assertTrue(stopped.contains(saved[2]));assertNull(stopped.first {it.id=="first"}.seriesId)
    }
    @Test fun laterContentEditsDoNotResurrectAnExplicitlyDeletedOccurrence() {
        val saved=RepeatRules.save(emptyList(),task(rule()),false)
        val removed=saved[2]
        val remaining=saved.filterNot {it.id==removed.id}
        val changed=RepeatRules.save(remaining,remaining.first().copy(note="更新说明"),true)
        assertEquals(remaining.map {it.id}.toSet(),changed.map {it.id}.toSet())
        assertTrue(changed.none {it.id==removed.id})
    }
    @Test fun explicitlyMovedFirstOccurrenceRemainsAddressableBesideProtectedHistory() {
        val saved=RepeatRules.save(emptyList(),task(rule("2026-11-13")),false).toMutableList()
        saved[1]=saved[1].copy(status=TaskStatus.PAUSED,executionStartedAt=1)
        val changed=saved.first().copy(scheduledAt="2026-11-04T11:00",repeatRule=RepeatSchedule("D",1,emptySet(),LocalDate.parse("2026-11-06")).encode())
        val result=RepeatRules.save(saved,changed,true)
        assertEquals("2026-11-04T11:00",result.first {it.id=="first"}.scheduledAt)
        assertTrue(result.contains(saved[1]))
    }
}
