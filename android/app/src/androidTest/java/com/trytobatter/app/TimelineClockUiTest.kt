package com.trytobatter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.trytobatter.app.core.model.*
import com.trytobatter.app.core.alarm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Dedicated emulator: exercise an actual failed implicit launch without claiming system-clock creation. */
class TimelineClockUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun timelineBulkControlDoesNotClaimCreationWhenNoSystemClockExists() {
        ui.runOnUiThread {ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        val repo=(ui.activity.application as BatterApplication).repository
        assumeTrue(android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).resolveActivity(ui.activity.packageManager)==null)
        val task=TaskItem(id="clock-failure-ui",title="时间轴系统闹钟入口验收",category=Category.ENGLISH,scheduledAt=LocalDateTime.now().plusHours(1).toString())
        runBlocking {
            repo.load();repo.disconnect();repo.resetDemo(true);repo.prepareTimelineClockBatch(false)
            repo.timelineClock.value.entries.values.forEach {repo.recordTimelineClockRequest(it.copy(action=ClockAction.CANCEL,stage=ClockRequestStage.REQUESTED,error=""))}
            repo.saveTask(task);repo.setTaskAlarm(task.id,TaskAlarmSettings(true))
        }
        val original=repo.alarms.value.records.getValue(task.id)
        ui.onNodeWithText("一键设置全部闹钟").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        ui.waitUntil(5000) {repo.timelineClock.value.entries[task.id]?.stage==ClockRequestStage.FAILED}
        ui.onNodeWithText("手机未能接收本次闹钟请求，请打开系统时钟并发送时钟诊断到电脑。").assertExists()
        ui.onNodeWithText("发送时钟诊断到电脑").assertExists()
        ui.waitForIdle()
        assertFalse(repo.timelineClock.value.entries[task.id]?.stage==ClockRequestStage.REQUESTED)
        assertEquals(original.generation,repo.alarms.value.records[task.id]?.generation)
        assertTrue(repo.alarms.value.settings.getValue(task.id).enabled)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(ui.activity.getExternalFilesDir(null),"timeline-system-clock.png"))
        runBlocking {repo.prepareTimelineClockBatch(false);repo.resetDemo(true)}
    }
}
