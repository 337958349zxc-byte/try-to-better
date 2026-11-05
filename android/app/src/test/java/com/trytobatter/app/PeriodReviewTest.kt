package com.trytobatter.app

import com.trytobatter.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PeriodReviewTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val start=LocalDate.parse("2026-11-01")
    private val end=start.plusDays(13)
    private fun time(day:String)=LocalDate.parse(day).atTime(23,59).atZone(zone).toInstant().toEpochMilli()
    private fun task(id:String,day:String="2026-11-01")=TaskItem(id=id,title="计划$id",category=Category.AI,scheduledAt=day+"T09:00",durationMinutes=60)
    private fun record(id:String,taskId:String,day:String="2026-11-01",seconds:Long=120)=ExecutionRecord(id=id,taskId=taskId,title="实际$id",category=Category.AI,outcome=TaskStatus.PARTIAL,seconds=seconds,result="已做练习",endedAt=time(day))

    @Test fun incompleteAndFutureTasksRemainUnknownAndAreExcludedFromTimeDifference() {
        val state=AppSnapshot(tasks=listOf(task("a"),task("b","2026-11-14")),records=listOf(record("r","a")))
        val r=PeriodReview.build(state,start,end,zone)
        assertEquals(120L,r.plannedMinutes);assertEquals(1,r.missingResults)
        assertEquals(60L,r.comparedBudgetMinutes);assertEquals(120L,r.comparedSeconds)
        assertEquals(2,r.count(TaskStatus.PENDING))
    }
    @Test fun plannedAndActualWindowsAreSeparateAndLinkAcrossPeriods() {
        val state=AppSnapshot(tasks=listOf(task("a"),task("outside","2026-10-31")),
            records=listOf(record("late","a","2026-11-15",300),record("inside","outside","2026-11-14",200)))
        val r=PeriodReview.build(state,start,end,zone)
        assertEquals(listOf("a"),r.tasks.map {it.id});assertEquals(listOf("inside"),r.records.map {it.id})
        assertEquals(200L,r.actualSeconds);assertEquals(300L,r.comparedSeconds);assertEquals(0,r.missingResults)
    }
    @Test fun demosAndSimulationsAreExcludedWithoutCountingWorkoutTimeTwice() {
        val state=AppSnapshot(tasks=listOf(task("a"),task("demo").copy(isDemo=true)),
            records=listOf(record("real","a",seconds=180),record("demo","demo").copy(isDemo=true)),
            workouts=listOf(WorkoutRecord(id="w",endedAt=time("2026-11-01"),sets=emptyList(),seconds=180,simulated=false),
                WorkoutRecord(id="demo-w",endedAt=time("2026-11-01"),sets=emptyList(),seconds=900,simulated=true)))
        val r=PeriodReview.build(state,start,end,zone)
        assertEquals(1,r.tasks.size);assertEquals(1,r.records.size);assertEquals(1,r.workouts.size)
        assertEquals(180L,r.actualSeconds)
    }
    @Test fun dateBoundariesUseRequestedZoneAndIncludeLastDay() {
        val r=PeriodReview.build(AppSnapshot(records=listOf(record("first","a"),record("last","b","2026-11-14"),record("later","c","2026-11-15"))),start,end,zone)
        assertEquals(listOf("first","last"),r.records.map {it.id})
    }
    @Test fun exportedReportRetainsPartialReasonsCorrectionSnapshotAndSideNotes() {
        val record=record("r","a").copy(result="读完|练习\n尚未测验",correctedFrom="原结果",snapshot="开始时计划")
        val workout=WorkoutRecord(id="w",endedAt=time("2026-11-02"),seconds=300,simulated=false,
            sets=listOf(WorkoutSet(exercise="分腿蹲",setIndex=1,autoReps=0,confirmedReps=12,note="左6／右6")),note="恢复未记录")
        val r=PeriodReview.build(AppSnapshot(tasks=listOf(task("a").copy(status=TaskStatus.PARTIAL)),records=listOf(record),workouts=listOf(workout)),start,end,zone)
        val text=r.markdown(start.plusDays(3))
        assertTrue(text.contains("部分1项"));assertTrue(text.contains("周期进行中"));assertTrue(text.contains("读完\\|练习 / 尚未测验"))
        assertTrue(text.contains("开始时计划"));assertTrue(text.contains("原结果"));assertTrue(text.contains("左6／右6"));assertTrue(text.contains("未记录保留未知"))
        assertEquals(1,r.learning.size)
    }
    @Test fun emptyPeriodDoesNotInventCompletionOrResults() {
        val r=PeriodReview.build(AppSnapshot(),start,end,zone)
        assertEquals(0,r.tasks.size);assertEquals(0,r.records.size);assertEquals(0,r.comparedTasks.size)
        assertTrue(r.markdown(end.plusDays(1)).contains("实际执行情况未知"))
    }
}
