package com.trytobatter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FrontendFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        ui.runOnUiThread { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(15000) { (ui.activity.application as BatterApplication).repository.state.value.loaded }
        runBlocking { (ui.activity.application as BatterApplication).repository.resetDemo() }
        ui.waitForIdle()
    }
    private fun shot(name: String) { val file = File(ui.activity.getExternalFilesDir(null), "$name.png"); ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
    private fun clickItem(text: String) {
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text))
        ui.onNodeWithText(text).performClick()
    }
    private fun clickSheet(text: String) {
        ui.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText(text))
        ui.onNodeWithText(text).performClick()
    }
    private fun saveForm() {
        clickItem("保存到本机")
        ui.waitForIdle()
        if(ui.onAllNodesWithText("保留重叠并保存").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("保留重叠并保存").performClick()
    }
    private fun awaitText(text: String) { try { ui.waitUntil(5000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle() } catch(e: Throwable) { shot("failure"); throw AssertionError("Missing $text\n" + ui.onRoot().printToString(), e) } }
    @Test fun fiveTabsAndConflictComparisonWorkOnAndroid() {
        ready()
        shot("01-today")
        ui.onNodeWithText("日历", useUnmergedTree = true).performClick()
        ui.onNodeWithText("把时间留给重要的事").assertExists()
        shot("02-calendar")
        ui.onNodeWithText("计划", useUnmergedTree = true).performClick()
        ui.onNodeWithText("我的计划").assertIsDisplayed()
        shot("03-plans")
        ui.onNodeWithText("电脑连接", useUnmergedTree = true).performClick()
        ui.onNodeWithText("电脑连接与同步").assertExists()
        shot("06-connection")
        // Inject only an explicitly labelled fake service event; compare through the real UI.
        runBlocking { (ui.activity.application as BatterApplication).repository.simulatePlanUpdate() }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("查看版本与更新").performClick()
        shot("07-conflict")
        ui.onNodeWithText("保留手机内容", substring = true).performScrollTo().performClick()
        ui.waitUntil(5000) { (ui.activity.application as BatterApplication).repository.state.value.conflicts.isEmpty() }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("记录", useUnmergedTree = true).performClick()
        shot("04-records")
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        shot("05-profile")
    }

    @Test fun editThenManualWorkoutAndResumeCheckpoint() {
        ready()
        ui.onNodeWithText("计划", useUnmergedTree = true).performClick()
        clickItem("运动训练")
        shot("08-workout-overview")
        clickItem("先看动作步骤")
        clickItem("摆放手机")
        clickItem("我已准备好，3 秒后开始")
        ui.waitUntil(8000) { ui.onAllNodesWithText("手工 +1").fetchSemanticsNodes().isNotEmpty() }
        shot("09-workout-training")
        clickItem("手工 +1")
        ui.onNodeWithText("手工 +1").performClick()
        clickItem("结束本组并核对")
        clickItem("提前结束，按实际记录")
        ui.onNodeWithText("模拟自动 0 次 / 确认 2 次").assertExists()
        clickItem("保存训练记录")
        ui.waitUntil(5000) { (ui.activity.application as BatterApplication).repository.state.value.workouts.any { it.sets.any { s -> s.confirmedReps == 2 } } }
    }

    @Test fun createEditExecuteAndCorrectResultThroughUi() {
        ready()
        ui.onNodeWithText("日历", useUnmergedTree = true).performClick()
        ui.onNodeWithContentDescription("新增事项").performClick()
        ui.onNodeWithText("事项名称").performTextInput("界面验收事项")
        shot("10-edit-task")
        saveForm()
        ui.waitUntil(5000) { (ui.activity.application as BatterApplication).repository.state.value.tasks.any { it.title == "界面验收事项" } }
        awaitText("把时间留给重要的事")
        clickItem("界面验收事项")
        awaitText("事项详情")
        ui.onNodeWithContentDescription("编辑事项").performClick()
        ui.onNodeWithText("事项名称").performTextReplacement("界面验收事项 · 已改")
        saveForm()
        awaitText("事项详情")
        clickItem("开始任务")
        ui.waitUntil(5000) { (ui.activity.application as BatterApplication).repository.state.value.tasks.any { it.title == "界面验收事项 · 已改" && it.status == TaskStatus.RUNNING } }
        clickItem("记录完成情况")
        ui.onNodeWithText("部分完成").performClick()
        ui.onNodeWithText("做了哪些，剩余什么？").performTextInput("实际完成第一步")
        clickSheet("保存结果")
        ui.waitUntil(5000) { (ui.activity.application as BatterApplication).repository.state.value.records.any { it.result == "实际完成第一步" } }
        ui.waitUntil(5000) { ui.onAllNodesWithText("记录这次执行").fetchSemanticsNodes().isEmpty() }
        shot("11-task-detail")
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("记录", useUnmergedTree = true).performClick()
        clickItem("界面验收事项 · 已改")
        ui.onNodeWithText("完整完成").performClick()
        ui.onNodeWithText("实际时长（分钟，可小数）").performTextReplacement("2")
        clickSheet("保存结果")
        ui.waitUntil(5000) { (ui.activity.application as BatterApplication).repository.state.value.records.any { it.seconds == 120L && it.correctedFrom.isNotBlank() } }
    }
}
