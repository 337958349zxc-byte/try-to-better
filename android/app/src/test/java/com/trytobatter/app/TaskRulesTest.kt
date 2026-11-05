package com.trytobatter.app

import com.trytobatter.app.core.model.*
import com.trytobatter.app.feature.workout.*
import org.junit.Assert.*
import org.junit.Test

class TaskRulesTest {
    private fun task(id: String = "one", time: String = "2026-10-26T09:00") = TaskItem(id = id, title = "学习", category = Category.AI, scheduledAt = time, durationMinutes = 30)

    @Test fun adjacentSchedulesAreAllowedButRealOverlapIsFound() {
        assertFalse(TaskRules.overlaps(task(), task("two", "2026-10-26T09:30")))
        assertTrue(TaskRules.overlaps(task(), task("two", "2026-10-26T09:29")))
        assertFalse(TaskRules.overlaps(task(), task().copy(status = TaskStatus.COMPLETED)))
    }
    @Test fun timerUsesMonotonicTimeAndRetainsExecutionSnapshotAcrossResume() {
        val started = TaskRules.start(task(), 100_000L, 20_000L, 2)
        assertEquals(32L, TaskRules.elapsed(started, 52_500L))
        val paused = TaskRules.pause(started, 52_500L)
        assertEquals(32L, TaskRules.elapsed(paused, 500_000L))
        val resumed = TaskRules.start(paused.copy(title = "新计划名称"), 1L, 600_000L, 2)
        assertEquals(42L, TaskRules.elapsed(resumed, 610_000L))
        assertEquals(started.executionSnapshot, resumed.executionSnapshot)
        assertEquals(100_000L, resumed.executionStartedAt)
    }
    @Test(expected = IllegalArgumentException::class) fun completedExecutionCannotBeRestarted() { TaskRules.start(task().copy(status = TaskStatus.COMPLETED), 1L, 1L, 1) }
    @Test(expected = IllegalArgumentException::class) fun doubleStartIsRejected() { TaskRules.start(task().copy(status = TaskStatus.RUNNING), 1L, 1L, 1) }
    @Test(expected = IllegalArgumentException::class) fun incomingPlanCannotOverwriteRunningExecution() {
        TaskRules.applyIncoming(TaskRules.start(task(), 1L, 1L, 1), task().copy(title = "另一版本"), false)
    }
    @Test(expected = IllegalArgumentException::class) fun incomingPlanCannotOverwriteLocalEdit() { TaskRules.applyIncoming(task(), task(), true) }
    @Test fun rebootPausesUnknownTimerRatherThanInventingElapsedTime() {
        val running = TaskRules.start(task().copy(accumulatedSeconds = 12), 1L, 999999L, 1)
        val restored = TaskRules.restoreAfterBoot(running, 2)
        assertEquals(TaskStatus.PAUSED, restored.status)
        assertEquals(12L, TaskRules.elapsed(restored, 30L))
        assertTrue(restored.note.contains("核对"))
    }
    @Test fun staleWrongContextAndDuplicateFeedbackCannotBeAccepted() {
        val gate = FeedbackGate()
        fun event(sequence: Long = 1, expires: Long = 200) = AnalysisEvent("s", "chair", 1, sequence, expires, "辅助建议")
        assertFalse(gate.accept(event(expires = 100), "s", "chair", 1, 100))
        assertFalse(gate.accept(event(), "s", "wall", 1, 100))
        assertFalse(gate.accept(event(), "s", "chair", 2, 100))
        assertTrue(gate.accept(event(), "s", "chair", 1, 100))
        assertFalse(gate.accept(event(), "s", "chair", 1, 100))
        assertTrue(gate.accept(event(2), "s", "chair", 1, 100))
        assertFalse(gate.accept(event(1), "s", "chair", 1, 100))
    }
}
