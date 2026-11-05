package com.trytobatter.app

import androidx.compose.runtime.*
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.Scaffold
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.designsystem.BatterTheme
import com.trytobatter.app.core.model.*
import com.trytobatter.app.feature.today.TodayScreen
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class UiV2StateTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun alarmErrorsAndEmptyStateAreVisible() {
        ui.runOnUiThread {ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(10000) {ui.activity.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT}
        val task=TaskItem(id="v2-long",title="用于检查大字号的长中文事项名称：阅读资料、整理笔记并保留真实执行记录",category=Category.AI,scheduledAt=LocalDate.now().atTime(23,50).toString())
        var snapshot by mutableStateOf(AppSnapshot(tasks=listOf(task),loaded=true))
        val alarm=AlarmSnapshot(records=mapOf(task.id to AlarmRecord(task.id,"test","test",0,AlarmState.SCHEDULE_ERROR,error="测试安排失败")),error="测试提醒异常：需要授权，尚未安排")
        ui.setContent {BatterTheme {Scaffold {padding -> Box(Modifier.fillMaxSize().padding(padding)) {TodayScreen(snapshot,0,{}, {}, {}, {}, {}, {}, {}, {},alarms=alarm)}}}}
        ui.onNodeWithText("测试提醒异常：需要授权，尚未安排").assertIsDisplayed()
        ui.mainClock.advanceTimeBy(500)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val tag=InstrumentationRegistry.getArguments().getString("shotTag","393")
        fun shot(name:String) {
            // Capture the actual Compose test surface, excluding the host Activity's window transition.
            File(context.getExternalFilesDir(null),"ui-v2-$tag-$name.png").outputStream().use {
                ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
        }
        shot("reminder-error")
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("闹钟安排失败 · 在事项编辑中处理"))
        ui.onNodeWithText("闹钟安排失败 · 在事项编辑中处理").assertIsDisplayed()
        ui.runOnIdle {snapshot=AppSnapshot(loaded=true)}
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        ui.onNodeWithText("今天没有安排事项").assertIsDisplayed()
        shot("empty")
        ui.runOnIdle {snapshot=AppSnapshot(tasks=listOf(task.copy(status=TaskStatus.COMPLETED)),loaded=true)}
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        ui.onNodeWithText("今天的安排已有结果").assertIsDisplayed()
    }
}
