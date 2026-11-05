package com.trytobatter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class AlarmUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun quickControlReflectsRealScheduleAndEditorRetainsDraft() {
        ui.runOnUiThread {ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        val repo=(ui.activity.application as BatterApplication).repository
        val task=TaskItem(id="alarm-ui",title="闹钟设置界面验收",category=Category.ENGLISH,scheduledAt=LocalDateTime.now().plusMinutes(45).toString())
        runBlocking {repo.load();repo.disconnect();repo.resetDemo(true);repo.saveTask(task)}
        ui.waitUntil(5000) {ui.onAllNodesWithContentDescription("闹钟：未开启").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithContentDescription("闹钟：未开启").performClick()
        ui.waitUntil(5000) {repo.alarms.value.records[task.id]?.state==AlarmState.SCHEDULED}
        assertEquals(TaskAlarmSettings(true,10,AlarmMode.RING),repo.alarms.value.settings[task.id])
        ui.onNodeWithContentDescription("闹钟：已安排").assertIsOn().performClick()
        ui.waitUntil(5000) {repo.alarms.value.records[task.id]?.state==AlarmState.DISABLED}
        ui.onNodeWithContentDescription("闹钟：未开启").performClick()
        ui.waitUntil(5000) {repo.alarms.value.records[task.id]?.state==AlarmState.SCHEDULED}
        val shot=File(ui.activity.getExternalFilesDir(null),"alarm-settings.png")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(shot)
        ui.onAllNodes(hasText(task.title)).onLast().performClick()
        ui.onNodeWithContentDescription("编辑事项").performClick()
        ui.onNodeWithText("跟随事项闹钟").assertExists()
        ui.onAllNodes(isToggleable()).onFirst().assertIsOn()
        assertTrue(repo.alarms.value.settings.getValue(task.id).enabled)
        runBlocking {repo.resetDemo(true)}
    }
}
