package com.trytobatter.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.core.model.*
import com.trytobatter.app.feature.workout.WorkoutDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only the reported entry/result/checkpoint path; isolated emulator data. */
class WorkoutEntryUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as BatterApplication).repository
    private fun ready() {
        ui.waitUntil(15000) {repo.state.value.loaded}
        runBlocking {repo.disconnect();repo.resetDemo(true)}
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission("com.trytobatter.app",Manifest.permission.CAMERA)
        ui.waitForIdle()
    }
    private fun click(text:String) {
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text))
        ui.onNodeWithText(text).performClick()
    }
    private fun openWorkout() {
        ui.onNodeWithText("计划",useUnmergedTree=true).performClick();click("运动训练")
    }
    private fun completedSet()=WorkoutSet(exercise="椅子坐站",setIndex=1,autoReps=0,confirmedReps=12)
    @Test fun restoredPartialResultCanContinueWithCountsPreserved() {
        ready()
        val draft=WorkoutDraft(id="entry-partial",stage="result",seconds=17,sets=listOf(completedSet()))
        runBlocking {repo.saveWorkoutDraft(draft.encode())}
        openWorkout();ui.onNodeWithText("自动 0 次 / 确认 12 次").assertExists()
        click("继续跟练")
        ui.onNodeWithTag("workout-camera").assertIsDisplayed()
        ui.onNodeWithText("椅子坐站 · 第 2 / 2 组").assertExists()
        ui.waitUntil(5000) {WorkoutDraft.decode(repo.state.value.workoutDraft).stage=="prepare"}
        val resumed=WorkoutDraft.decode(repo.state.value.workoutDraft)
        assertEquals(1,resumed.sets.size);assertEquals(12,resumed.sets.single().confirmedReps)
        assertEquals(0,resumed.sets.single().autoReps);assertEquals(17,resumed.seconds)
        assertEquals(2,resumed.set)
    }
    @Test fun freshTrainingGoesStraightToCameraPreparationAndManualCountingStillWorks() {
        ready();openWorkout();click("开始跟练")
        ui.onNodeWithTag("workout-camera").assertIsDisplayed()
        click("我已准备好，3 秒后开始")
        ui.waitUntil(8000) {ui.onAllNodesWithText("手工 +1").fetchSemanticsNodes().isNotEmpty()}
        click("手工 +1");click("手工 +1");click("结束本组并核对")
        click("提前结束，按实际记录");ui.onNodeWithText("自动 0 次 / 确认 2 次").assertExists()
        click("保存训练记录")
        ui.waitUntil(5000) {repo.state.value.workouts.size==1}
        // Simulate a late lifecycle checkpoint after final save.
        val record=repo.state.value.workouts.single()
        runBlocking {repo.saveWorkoutDraft(WorkoutDraft(id=record.id,stage="result",sets=record.sets).encode())}
        assertTrue(repo.state.value.workoutDraft.isBlank())
        openWorkout();ui.onNodeWithText("开始跟练").assertExists()
    }
    @Test fun legacyCheckpointOfAnAlreadySavedRecordDoesNotReopenItsResult() {
        ready()
        val record=WorkoutRecord(id="entry-saved",sets=listOf(completedSet()),seconds=17,simulated=false)
        runBlocking {
            repo.saveWorkout(record)
            repo.restore(repo.state.value.copy(workoutDraft=WorkoutDraft(id=record.id,stage="result",sets=record.sets).encode()))
        }
        openWorkout();ui.onNodeWithText("开始跟练").assertExists()
        ui.onNodeWithText("按实际情况留下记录").assertDoesNotExist()
        assertEquals(12,repo.state.value.workouts.single().sets.single().confirmedReps)
    }
}
