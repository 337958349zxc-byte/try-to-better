package com.trytobatter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Run only on the dedicated emulator: uses deterministic local fixtures. */
class CompactUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun captureMainPages() {
        ui.runOnUiThread { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        val repo = (ui.activity.application as BatterApplication).repository
        runBlocking {
            repo.load(); repo.disconnect(); repo.resetDemo(true)
            listOf("整理今天的安排", "英语听说练习", "准备午餐和晚餐", "基础力量跟练", "复习开发笔记", "晚间回顾").forEachIndexed { i, title ->
                repo.saveTask(TaskItem(id="compact-$i", title=title, category=Category.entries[i % 5], scheduledAt=LocalDate.now().atTime(8+i*2,0).toString(), durationMinutes=30))
            }
        }
        ui.waitUntil(5000) { repo.state.value.tasks.size == 6 }
        fun shot(name: String) {
            ui.waitForIdle()
            val file = File(ui.activity.getExternalFilesDir(null), "compact-$name.png")
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)
        }
        shot("today")
        ui.onNodeWithText("日历", useUnmergedTree=true).performClick(); shot("calendar")
        ui.onNodeWithContentDescription("新增事项").performClick(); shot("editor")
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("保存到本机"))
        ui.onNodeWithText("保存到本机").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        for ((tab,name) in listOf("计划" to "plans", "记录" to "records", "我的" to "profile")) {
            ui.onNodeWithText(tab,useUnmergedTree=true).performClick(); shot(name)
        }
    }
}
