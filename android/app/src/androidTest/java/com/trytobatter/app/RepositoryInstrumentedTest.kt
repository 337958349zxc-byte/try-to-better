package com.trytobatter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.core.data.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun roomSurvivesRepositoryRecreationAndCompletionIsIdempotentlyProtected() = runBlocking {
        val repo = LocalDemoRepository(context); repo.load(); repo.resetDemo(true)
        val item = TaskItem(title = "持久化验收", category = Category.LIFE, scheduledAt = LocalDate.now().atTime(8, 0).toString())
        repo.saveTask(item); repo.startTask(item.id); repo.pauseTask(item.id)
        val reopened = LocalDemoRepository(context); reopened.load()
        assertEquals(TaskStatus.PAUSED, reopened.state.value.tasks.single().status)
        assertTrue(reopened.state.value.tasks.single().executionSnapshot.isNotBlank())
        reopened.finishTask(item.id, TaskStatus.PARTIAL, "留下可复查成果")
        assertTrue(runCatching { reopened.finishTask(item.id, TaskStatus.COMPLETED, "重复点击") }.isFailure)
        assertEquals(1, reopened.state.value.records.size)
        val record = reopened.state.value.records.single()
        reopened.correctRecord(record.id, TaskStatus.COMPLETED, 90, "手动核对")
        assertTrue(reopened.state.value.records.single().correctedFrom.contains("部分完成"))
        assertEquals(90L, reopened.state.value.records.single().seconds)
        assertTrue(runCatching { reopened.deleteTask(item.id) }.isFailure)
        reopened.resetDemo()
    }

    @Test fun repetitionScopeConflictChoiceAndBackupRoundTrip() = runBlocking {
        val repo = LocalDemoRepository(context); repo.load(); repo.resetDemo(true)
        val item = TaskItem(title = "重复安排", category = Category.ENGLISH, scheduledAt = LocalDate.now().atTime(13, 30).toString(), repeatRule = "每天")
        repo.saveTask(item)
        assertEquals(7, repo.state.value.tasks.size)
        val first = repo.state.value.tasks.first()
        repo.startTask(first.id); repo.pauseTask(first.id)
        val second = repo.state.value.tasks[1]
        val shifted = second.copy(title = "调整后续安排", scheduledAt = second.start.plusDays(1).plusHours(1).toString(), note = "后续共同备注")
        repo.saveTask(shifted, true)
        assertEquals("重复安排", repo.state.value.tasks.first().title)
        assertEquals(6, repo.state.value.tasks.count { it.title == "调整后续安排" })
        assertEquals(shifted.start.plusDays(1), repo.state.value.tasks[2].start)
        assertEquals("后续共同备注", repo.state.value.tasks[2].note)
        repo.simulatePlanUpdate()
        val conflict = repo.state.value.conflicts.single()
        val previous = repo.state.value.tasks.first { it.id == conflict.taskId }
        repo.resolveConflict(conflict.id, true)
        assertEquals(previous, repo.state.value.tasks.first { it.id == conflict.taskId })
        repo.pairDemo("演示电脑"); repo.simulateSync()
        assertTrue(repo.state.value.mutations.isEmpty())
        val backup = SnapshotCodec.decode(SnapshotCodec.encode(repo.state.value))
        assertFalse(backup.demoConnected)
        repo.restore(backup)
        assertEquals(7, repo.state.value.tasks.size)
        assertEquals(TaskStatus.PAUSED, repo.state.value.tasks.first().status)
        repo.resetDemo()
    }

    @Test fun workoutSaveIsIdempotentAndClearsCheckpoint() = runBlocking {
        val repo = LocalDemoRepository(context); repo.load(); repo.resetDemo()
        repo.saveWorkoutDraft("checkpoint")
        val workout = WorkoutRecord(sets = listOf(WorkoutSet(exercise = "椅子坐站", setIndex = 1, autoReps = 0, confirmedReps = 8)), seconds = 50)
        repo.saveWorkout(workout); repo.saveWorkout(workout)
        assertEquals(1, repo.state.value.workouts.size)
        assertEquals("", repo.state.value.workoutDraft)
        repo.resetDemo()
    }

    @Test fun prepareProcessRecoveryFixture() = runBlocking {
        val repo = LocalDemoRepository(context); repo.load(); repo.resetDemo()
        val t = TaskItem(id = "process-recovery", title = "进程恢复验收", category = Category.LIFE,
            scheduledAt = LocalDate.now().atTime(8, 0).toString(), isDemo = true, accumulatedSeconds = 125)
        repo.saveTask(t); repo.startTask(t.id); repo.pauseTask(t.id)
        repo.saveWorkoutDraft(com.trytobatter.app.feature.workout.WorkoutDraft(stage = "paused", reps = 3, seconds = 12).encode())
        assertEquals(125L, repo.state.value.tasks.first { it.id == t.id }.accumulatedSeconds)
    }

    @Test fun malformedBackupIsRejectedBeforeReplacement() = runBlocking {
        val text = SnapshotCodec.encode(demoSnapshot())
        val invalid = org.json.JSONObject(text).apply { getJSONArray("tasks").getJSONObject(0).put("durationMinutes", -1) }.toString()
        assertTrue(runCatching { SnapshotCodec.decode(invalid) }.isFailure)
        assertTrue(runCatching { SnapshotCodec.decode("{}") }.isFailure)
        assertTrue(runCatching { SnapshotCodec.decode(org.json.JSONObject(text).put("environment", "other-app").toString()) }.isFailure)
    }
}
