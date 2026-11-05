package com.trytobatter.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import com.trytobatter.app.feature.today.TodayScreen
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Rendered test-host states only: no OS alarm creation or health-device calls. */
class LifeOsV3StateTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private fun shot(name:String) {
        ui.waitForIdle()
        val tag=InstrumentationRegistry.getArguments().getString("shotTag","393")
        File(ui.activity.getExternalFilesDir(null),"life-os-v3-$tag-$name.png").outputStream().use {ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun emptyFinishedFutureAndFailuresRemainTruthful() {
        ui.runOnUiThread {ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        val day=LocalDate.now()
        val task=TaskItem(id="state-long",title="长中文事项：阅读资料、整理工作流并核对当天真实执行结果",category=Category.AI,scheduledAt=day.atTime(23,50).toString())
        var state by mutableStateOf(AppSnapshot(loaded=true))
        var alarm by mutableStateOf(AlarmSnapshot())
        ui.setContent {BatterTheme {Scaffold {padding->Box(Modifier.fillMaxSize().padding(padding)) {TodayScreen(state,0,{}, {}, {}, {}, {}, {}, {}, {},alarms=alarm)}}}}
        ui.onNodeWithText("现在没有正在进行的任务").assertIsDisplayed();shot("empty")
        ui.runOnIdle {state=state.copy(tasks=listOf(task))}
        ui.onNodeWithText("现在没有正在进行的任务").assertIsDisplayed();ui.onNodeWithContentDescription("开始下一项").assertExists()
        ui.runOnIdle {alarm=AlarmSnapshot(records=mapOf(task.id to AlarmRecord(task.id,"test","test",0,AlarmState.SCHEDULE_ERROR,error="隔离测试失败")),error="测试提醒异常：需要授权")}
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("测试提醒异常：需要授权"))
        ui.onNodeWithText("测试提醒异常：需要授权").assertIsDisplayed();shot("reminder-error")
        ui.runOnIdle {state=state.copy(tasks=listOf(task.copy(status=TaskStatus.COMPLETED)));alarm=AlarmSnapshot()}
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        ui.onNodeWithText("今天的安排已有结果").assertIsDisplayed()
    }
    @Test fun enabledWithoutRegistrationDoesNotClaimScheduled() {
        var record by mutableStateOf<AlarmRecord?>(null)
        ui.setContent {BatterTheme {AlarmButton(TaskAlarmSettings(enabled=true),record) {}}}
        ui.onNodeWithContentDescription("闹钟：等待安排状态").assertExists()
        ui.onNodeWithContentDescription("闹钟：已安排").assertDoesNotExist()
        ui.runOnIdle {record=AlarmRecord("test","test","test",0,AlarmState.SCHEDULE_ERROR)}
        ui.onNodeWithContentDescription("闹钟：安排失败").assertExists()
        ui.runOnIdle {record=record!!.copy(state=AlarmState.SCHEDULED)}
        ui.onNodeWithContentDescription("闹钟：已安排").assertExists()
    }
}
