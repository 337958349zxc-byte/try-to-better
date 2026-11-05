package com.trytobatter.app

import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LifeOsPresentationTest {
    private val date=LocalDate.of(2026,10,30)
    private fun task(id:String,hour:Int,minute:Int=0,duration:Int=30)=TaskItem(id=id,title=id,category=Category.AI,scheduledAt=date.atTime(hour,minute).toString(),durationMinutes=duration)
    @Test fun periodBoundariesUseActualStart() {
        assertEquals(DayPeriod.MORNING,DayPeriod.at(0));assertEquals(DayPeriod.MORNING,DayPeriod.at(11))
        assertEquals(DayPeriod.AFTERNOON,DayPeriod.at(12));assertEquals(DayPeriod.AFTERNOON,DayPeriod.at(17))
        assertEquals(DayPeriod.EVENING,DayPeriod.at(18));assertEquals(DayPeriod.EVENING,DayPeriod.at(23))
    }
    @Test fun nextItemIsFuturePendingAndDoesNotResurrectResults() {
        val tasks=listOf(task("past",8),task("done",13).copy(status=TaskStatus.COMPLETED),task("next",14),task("paused",15).copy(status=TaskStatus.PAUSED))
        assertEquals("next",nextPlanned(tasks,date.atTime(12,0))?.id)
        assertNull(nextPlanned(tasks.filter {it.status!=TaskStatus.PENDING},date.atTime(12,0)))
        assertEquals("past",nextPlanned(tasks,date.atTime(23,0),"next")?.id)
    }
    @Test fun weekAndMonthRangesRespectCalendarBoundaries() {
        assertTrue(ReportPeriod.WEEK.contains(date.minusDays(4),date))
        assertFalse(ReportPeriod.WEEK.contains(date.minusDays(5),date))
        assertTrue(ReportPeriod.WEEK.contains(LocalDate.of(2027,1,1),LocalDate.of(2026,12,31)))
        assertFalse(ReportPeriod.MONTH.contains(LocalDate.of(2026,9,30),date))
        assertTrue(ReportPeriod.ALL.contains(LocalDate.of(2020,1,1),date))
        assertFalse(ReportPeriod.DAY.contains(date.minusDays(1),date))
    }
    @Test fun backToBackTasksShareAColumnWithoutLosingIdentity() {
        val tasks=listOf(task("b",9,30),task("a",9))
        val output=calendarPlacements(tasks)
        assertEquals(listOf("a","b"),output.map {it.task.id})
        assertTrue(output.all {it.columns==1 && it.column==0})
        assertEquals(tasks, listOf(task("b",9,30),task("a",9)))
    }
    @Test fun transitiveOverlapKeepsAllTasksAndReusesColumns() {
        val tasks=listOf(task("a",9,duration=60),task("b",9,30,60),task("c",10,duration=60))
        val output=calendarPlacements(tasks)
        assertEquals(3,output.size);assertTrue(output.all {it.columns==2})
        assertEquals(output[0].column,output[2].column)
        assertNotEquals(output[0].column,output[1].column)
    }
    @Test fun minimumTouchHeightDoesNotInventBusinessConflict() {
        val a=task("a",9,duration=5);val b=task("b",9,15,5)
        assertFalse(TaskRules.overlaps(a,b))
        val output=calendarPlacements(listOf(a,b),60)
        assertTrue(output.all {it.columns==2})
        assertNotEquals(output[0].column,output[1].column)
        assertEquals(5,output[0].task.durationMinutes)
    }
    @Test fun trueConcurrentEventsNeverCoverEachOther() {
        val output=calendarPlacements((1..4).map {task("$it",12,duration=60)})
        assertEquals(4,output.map {it.column}.toSet().size)
        assertTrue(output.all {it.columns==4})
    }
    @Test fun crossMidnightAndEmptyDataStayValid() {
        assertTrue(calendarPlacements(emptyList()).isEmpty())
        val task=task("night",23,50,30)
        assertEquals(date.plusDays(1),calendarPlacements(listOf(task)).single().task.end.toLocalDate())
    }
}
