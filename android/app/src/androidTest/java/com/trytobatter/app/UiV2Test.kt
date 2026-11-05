package com.trytobatter.app

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Deterministic UI fixtures, run only on isolated MuMu index 1. Never pair with the real PC. */
class UiV2Test {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repo get() = (ui.activity.application as BatterApplication).repository
    private fun ready() {
        ui.runOnUiThread { ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(15000) {repo.state.value.loaded}
        runBlocking {
            repo.disconnect();repo.resetDemo(true)
            val titles=listOf("起床、洗漱与当天准备","采购确认与下单","早餐：燕麦与鸡蛋","AI Agent 基础与工作流设计","提示词设计与工作流测试","备午餐：鸡肉、蔬菜与米饭","午餐与收拾","低冲击有氧","英语情景视频听力","学习块之间休息","口语诊断与真实交流","备晚餐：番茄豆腐与包菜","晚餐与收拾","饮食数据回填","求职事务预留窗口","日复盘：留一条真实记录","洗漱与睡前准备")
            val categories=listOf(Category.LIFE,Category.MEAL,Category.MEAL,Category.AI,Category.AI,Category.MEAL,Category.MEAL,Category.WORKOUT,Category.ENGLISH,Category.LIFE,Category.ENGLISH,Category.MEAL,Category.MEAL,Category.MEAL,Category.LIFE,Category.LIFE,Category.LIFE)
            val tasks=titles.mapIndexed {i,title -> TaskItem(id="v2-$i",title=title,category=categories[i],scheduledAt=LocalDate.now().atTime(7+i,0).toString(),durationMinutes=30,
                status=if(i==0) TaskStatus.COMPLETED else if(i==7) TaskStatus.PAUSED else TaskStatus.PENDING,
                accumulatedSeconds=if(i==7) 384 else 0,executionStartedAt=if(i==7) System.currentTimeMillis()-384000 else 0,isDemo=true)}
            repo.restore(AppSnapshot(tasks=tasks,records=listOf(ExecutionRecord(taskId="v2-0",title=titles[0],category=Category.LIFE,outcome=TaskStatus.COMPLETED,seconds=240,result="测试记录：已准备好当天用品",isDemo=true)),
                workouts=listOf(WorkoutRecord(sets=listOf(WorkoutSet(exercise="椅子坐站",setIndex=1,autoReps=7,confirmedReps=8)),seconds=80,note="测试记录：本机计数与手工订正，质量未评分",simulated=true)),loaded=true))
        }
        ui.waitUntil(10000) {repo.state.value.tasks.size==17}
        ui.waitForIdle()
    }
    private fun click(text:String) {
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text))
        ui.onNodeWithText(text).performClick()
        ui.waitForIdle()
    }
    private fun tab(text:String) {ui.onNodeWithText(text,useUnmergedTree=true).performClick();ui.waitForIdle()}
    private fun shot(name:String) {
        ui.waitUntil(15000) {
            ui.onAllNodesWithText("计时已开始，进度保存在本机").fetchSemanticsNodes().isEmpty() &&
                ui.onAllNodesWithText("计时已暂停").fetchSemanticsNodes().isEmpty()
        }
        ui.waitForIdle()
        ui.mainClock.advanceTimeBy(400)
        val tag=InstrumentationRegistry.getArguments().getString("shotTag","393")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(ui.activity.getExternalFilesDir(null),"ui-v2-$tag-$name.png"))
    }
    @Test fun mainPagesAndReminderControls() {
        ready();shot("01-today-paused")
        ui.onNodeWithText("一键设置全部闹钟").assertDoesNotExist()
        click("管理")
        ui.onNodeWithText("一键设置全部闹钟").assertExists()
        ui.onNodeWithText("查看系统闹钟").assertExists()
        ui.onNodeWithText("发送时钟诊断到电脑").assertExists()
        shot("today-reminders-expanded");click("收起")
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        click("继续任务")
        ui.waitUntil(5000) {repo.state.value.tasks.any {it.status==TaskStatus.RUNNING}}
        shot("today-running")
        click("暂停")
        ui.waitUntil(5000) {repo.state.value.tasks.none {it.status==TaskStatus.RUNNING}}
        tab("日历");shot("02-calendar")
        click("查看已过安排");ui.onNodeWithText("收起已过安排").assertExists()
        tab("计划");shot("03-plans")
        ui.onNodeWithText("打开双人菜谱").assertExists()
        tab("记录");shot("04-records")
        click("运动");click("查看训练详情");ui.onNodeWithText("椅子坐站 · 第1组").assertExists()
        click("学习");ui.onNodeWithText("还没有执行记录").assertExists()
        tab("我的");shot("05-profile")
        click("通知和闹钟");ui.onNodeWithText("完成").performClick()
        click("外观与字号");ui.onNodeWithText("知道了").performClick()
        click("App 更新");ui.onNodeWithContentDescription("返回").performClick()
        click("导出、备份与恢复");ui.onNodeWithContentDescription("返回").performClick()
    }
    @Test fun taskResultAndEditingPreserveData() {
        ready()
        ui.onNodeWithText("记录结果").performClick()
        ui.onNodeWithText("部分完成").performClick()
        ui.onNodeWithText("做了哪些，剩余什么？").performTextInput("测试结果：完成第一部分")
        ui.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("保存结果"))
        ui.onNodeWithText("保存结果").performClick()
        ui.waitUntil(5000) {repo.state.value.records.any {it.result=="测试结果：完成第一部分"}}
        assertEquals(TaskStatus.PARTIAL,repo.state.value.tasks.first {it.id=="v2-7"}.status)
        ui.onNodeWithContentDescription("新增事项").performClick()
        ui.onNodeWithText("事项名称").performTextInput("视觉改版新增事项验证")
        shot("editor")
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("保存到本机"))
        ui.onNodeWithText("保存到本机").performClick()
        ui.waitForIdle()
        if(ui.onAllNodesWithText("保留重叠并保存").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("保留重叠并保存").performClick()
        ui.waitUntil(5000) {repo.state.value.tasks.any {it.title=="视觉改版新增事项验证"}}
        assertEquals(18,repo.state.value.tasks.size)
        assertTrue(repo.state.value.workouts.single().sets.single().confirmedReps==8)
    }
    @Test fun secondaryDestinationsAndTaskEditingRemainUsable() {
        ready()
        click("AI Agent 基础与工作流设计")
        ui.onNodeWithContentDescription("编辑事项").performClick()
        ui.onNodeWithText("事项名称").performTextReplacement("测试编辑并缩短的事项")
        ui.onNodeWithText("计划时长（分钟）").performTextReplacement("20")
        click("保存到本机")
        ui.waitUntil(5000) {repo.state.value.tasks.any {it.title=="测试编辑并缩短的事项" && it.durationMinutes==20}}
        shot("task-detail")
        click("删除事项");ui.onNodeWithText("删除").performClick()
        ui.waitUntil(5000) {repo.state.value.tasks.none {it.id=="v2-3"}}
        assertEquals(TaskStatus.COMPLETED,repo.state.value.tasks.first {it.id=="v2-0"}.status)
        tab("计划")
        click("查看版本与更新");ui.onNodeWithText("计划版本与更新").assertExists()
        ui.onNodeWithContentDescription("返回").performClick()
        click("电脑连接");ui.onNodeWithText("电脑连接与同步").assertExists();shot("connection")
        ui.onNodeWithContentDescription("返回").performClick()
        listOf(Category.AI,Category.ENGLISH,Category.MEAL,Category.LIFE).forEach {category ->
            click(category.label);ui.onNodeWithText("具体安排").assertExists()
            ui.onNodeWithContentDescription("返回").performClick()
        }
        click("运动训练");ui.onNodeWithText("基础力量 · 跟练 A").assertExists();shot("workout-overview")
        ui.onNodeWithContentDescription("返回").performClick()
        tab("我的")
        listOf("资料与开源来源","关于与订阅规划").forEach {name ->click(name);ui.onNodeWithText("知道了").performClick()}
        click("电脑连接");ui.onNodeWithText("电脑连接与同步").assertExists()
    }
}
