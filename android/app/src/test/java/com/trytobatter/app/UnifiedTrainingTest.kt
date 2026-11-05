package com.trytobatter.app

import com.trytobatter.app.core.model.*
import org.junit.Assert.*
import org.junit.Test

class UnifiedTrainingTest {
    @Test fun singleTemplateHasBothExactLoadsAndStableOrder() {
        assertEquals(listOf("慢速椅子深蹲","高位俯卧撑","臀桥","扶物分腿蹲","站姿提踵","俯卧W抬手","Dead Bug"),UnifiedTraining.moves.map {it.name})
        assertEquals(listOf(2,2,2,2,2,2,2),UnifiedTraining.moves.map {it.firstSets})
        assertEquals(listOf(3,3,2,2,2,2,2),UnifiedTraining.moves.map {it.secondSets})
        assertEquals(listOf(8,6,12,6,15,10,8),UnifiedTraining.moves.map {it.firstMin})
        assertEquals(listOf(8,6,15,8,18,12,10),UnifiedTraining.moves.map {it.secondMin})
        assertTrue(UnifiedTraining.moves.all {it.guide.isNotBlank()})
    }
    @Test fun plannedRowsNeverBecomeActualCompletion() {
        val rows=UnifiedTraining.rows(false)
        assertEquals(14,rows.size)
        assertTrue(rows.all {it.left.isBlank() && it.right.isBlank() && !it.done})
        assertFalse(UnifiedTraining.complete(rows,false))
        assertEquals(16,UnifiedTraining.rows(true).size)
    }
    @Test fun completionRequiresActualCountsForBothSidesAndExplicitConfirmation() {
        val filled=UnifiedTraining.rows(false).map {r ->val m=UnifiedTraining.moves.first {it.id==r.exerciseId};r.copy(left=m.firstMin.toString(),right=if(m.perSide) m.firstMin.toString() else "",done=true)}
        assertTrue(UnifiedTraining.complete(filled,false))
        assertFalse(UnifiedTraining.complete(filled.map {if(it.exerciseId=="wh4") it.copy(right="") else it},false))
        assertFalse(UnifiedTraining.complete(filled.map {it.copy(done=false)},false))
        assertFalse(UnifiedTraining.complete(filled.map {it.copy(left="0")},false))
        assertFalse(UnifiedTraining.complete(filled,true))
    }
    @Test fun secondWeekCannotBeSelectedByDateOrUnmarkedTask() {
        val task=TaskItem(title=UnifiedTraining.title,category=Category.WORKOUT,scheduledAt="2026-11-08T14:00",note="训练模板：${UnifiedTraining.templateId}\n周期日：D8")
        assertTrue(UnifiedTraining.isStrength(task))
        assertFalse(UnifiedTraining.isStrength(task.copy(note="")))
        assertFalse(UnifiedTraining.isStrength(task.copy(note="${UnifiedTraining.templateId}\n周期日：D9")))
        assertTrue(UnifiedTraining.rows(false).all {it.left.isBlank()})
    }
    @Test fun legacyCleanupPreservesAnyExecutionAndUnrelatedTasks() {
        val old=TaskItem(title="基础力量 · 跟练 A",category=Category.WORKOUT,scheduledAt="2026-10-30T14:15")
        assertTrue(UnifiedTraining.legacyPending(old))
        assertFalse(UnifiedTraining.legacyPending(old.copy(status=TaskStatus.PAUSED)))
        assertFalse(UnifiedTraining.legacyPending(old.copy(status=TaskStatus.COMPLETED)))
        assertFalse(UnifiedTraining.legacyPending(old.copy(executionStartedAt=1)))
        assertFalse(UnifiedTraining.legacyPending(old.copy(accumulatedSeconds=1)))
        assertFalse(UnifiedTraining.legacyPending(old.copy(executionSnapshot="已开始")))
        assertFalse(UnifiedTraining.legacyPending(old.copy(title="我的其他训练")))
    }
}
