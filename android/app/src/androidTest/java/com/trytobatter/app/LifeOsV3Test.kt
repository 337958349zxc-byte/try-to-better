package com.trytobatter.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Dedicated, unpaired emulator fixtures only. */
class LifeOsV3Test {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val repo get()=(ui.activity.application as BatterApplication).repository
    private fun ready() {
        ui.runOnUiThread {ui.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        ui.waitUntil(15000) {repo.state.value.loaded}
        runBlocking {
            repo.disconnect();repo.resetDemo(true)
            val names=listOf("起床与当天准备","早餐与收拾","采购确认与备餐","AI 学习：阅读课程与工作流设计","提示词与工作流实践","午餐备餐窗口","双人午餐与收拾","周衡｜低冲击有氧","英语情景学习与跟读","学习块之间休息","口语练习与复盘","准备晚餐与分装","晚餐与收拾","饮食记录","求职事务预留窗口","日复盘：留一条真实记录","洗漱与睡前准备")
            val cats=listOf(Category.LIFE,Category.MEAL,Category.MEAL,Category.AI,Category.AI,Category.MEAL,Category.MEAL,Category.WORKOUT,Category.ENGLISH,Category.LIFE,Category.ENGLISH,Category.MEAL,Category.MEAL,Category.MEAL,Category.LIFE,Category.LIFE,Category.LIFE)
            val tasks=names.mapIndexed {i,name->TaskItem(id="life-v3-$i",title=name,category=cats[i],scheduledAt=LocalDate.now().atTime(7+i,0).toString(),durationMinutes=30,
                status=if(i==0) TaskStatus.COMPLETED else if(i==7) TaskStatus.PAUSED else TaskStatus.PENDING,accumulatedSeconds=if(i==7) 384 else 0,
                executionStartedAt=if(i==7) System.currentTimeMillis()-384000 else 0,isDemo=true)}
            repo.restore(AppSnapshot(tasks=tasks,records=listOf(ExecutionRecord(taskId=tasks[0].id,title=tasks[0].title,category=tasks[0].category,outcome=TaskStatus.COMPLETED,seconds=240,result="隔离测试夹具：完成当天准备",isDemo=true)),
                workouts=listOf(WorkoutRecord(sets=listOf(WorkoutSet(exercise="椅子坐站",setIndex=1,autoReps=7,confirmedReps=8)),seconds=90,simulated=true)),loaded=true))
        }
        ui.waitUntil(10000) {repo.state.value.tasks.size==17}
        ui.waitForIdle()
    }
    private fun scroll(text:String) {ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text))}
    private fun click(text:String) {scroll(text);ui.onNodeWithText(text).performClick();ui.waitForIdle()}
    private fun top() {ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0);ui.waitForIdle()}
    private fun capsule(period:DayPeriod) {ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasTestTag("capsule-${period.name}"));ui.onNodeWithTag("capsule-${period.name}").performClick()}
    private fun tab(name:String) {ui.onNode(hasText(name) and hasClickAction()).performClick();ui.waitForIdle()}
    private fun shot(name:String) {
        ui.waitUntil(15000) {ui.onAllNodesWithText("事项已保存到本机").fetchSemanticsNodes().isEmpty()}
        ui.waitForIdle();ui.mainClock.advanceTimeBy(400)
        val tag=InstrumentationRegistry.getArguments().getString("shotTag","393")
        File(ui.activity.getExternalFilesDir(null),"life-os-v3-$tag-$name.png").outputStream().use {ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun todayCapsulesPreserveExecution() {
        ready();shot("today_actual")
        val original=DayPeriod.at(LocalTime.now().hour)
        val target=DayPeriod.entries.first {it!=original}
        capsule(target)
        ui.onNodeWithTag("capsule-${target.name}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已展开"))
        ui.onNodeWithTag("capsule-${original.name}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已收起"))
        assertEquals(TaskStatus.PAUSED,repo.state.value.tasks.first {it.id=="life-v3-7"}.status)
        top();click("继续任务")
        ui.waitUntil(5000) {repo.state.value.tasks.any {it.status==TaskStatus.RUNNING}}
        capsule(DayPeriod.EVENING)
        assertEquals(TaskStatus.RUNNING,repo.state.value.tasks.first {it.id=="life-v3-7"}.status)
        top();shot("today_running");click("暂停")
        ui.waitUntil(5000) {repo.state.value.tasks.none {it.status==TaskStatus.RUNNING}}
        // The reminder controller stays composed and reachable independently of task folds.
        ui.onNodeWithText("管理").performClick()
        ui.onNodeWithText("一键设置全部闹钟").assertExists()
        ui.onNodeWithText("发送时钟诊断到电脑").assertExists()
        ui.onNodeWithText("收起").performClick()
        top();ui.onNodeWithText("查看全天").performClick()
        ui.onAllNodesWithText("日历",useUnmergedTree=true).assertCountEquals(2)
    }
    @Test fun fivePagesAndOriginalDestinations() {
        ready();shot("today_actual")
        tab("日历");shot("calendar_actual")
        click("查看已过安排");click("全天");click("当天摘要")
        click("AI 学习：阅读课程与工作流设计")
        ui.onNodeWithContentDescription("编辑事项").performClick()
        ui.onNodeWithText("计划时长（分钟）").performTextReplacement("25")
        shot("editor")
        click("保存到本机")
        ui.waitUntil(5000) {repo.state.value.tasks.first {it.id=="life-v3-3"}.durationMinutes==25}
        ui.onNodeWithContentDescription("返回").performClick()
        tab("计划");shot("plans_actual")
        click("AI 开发");click("查看分类全部安排");ui.onNodeWithText("具体安排").assertExists();ui.onNodeWithContentDescription("返回").performClick()
        scroll("打开双人菜谱");ui.onNodeWithText("打开双人菜谱").assertIsDisplayed()
        click("运动训练");click("进入力量跟练");ui.onNodeWithText("基础力量 · 跟练 A").assertExists();shot("workout-overview");ui.onNodeWithContentDescription("返回").performClick()
        tab("记录");shot("records_actual")
        click("运动");click("查看训练详情");ui.onNodeWithText("椅子坐站 · 第1组").assertExists()
        click("学习");ui.onNodeWithText("还没有执行记录").assertExists()
        tab("我的");shot("profile_actual")
        ui.onNodeWithText("尚未接入 · 暂无健康数据").assertExists()
        click("App 更新");ui.onNodeWithContentDescription("返回").performClick()
        click("通知和闹钟");ui.onNodeWithText("完成").performClick()
        click("外观与字号");ui.onNodeWithText("知道了").performClick()
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasTestTag("profile-data"));ui.onNodeWithTag("profile-data").performClick()
        click("导出、备份与恢复");ui.onNodeWithContentDescription("返回").performClick()
        click("资料与开源来源");ui.onNodeWithText("知道了").performClick()
        click("关于与订阅规划");ui.onNodeWithText("知道了").performClick()
        assertEquals(8,repo.state.value.workouts.single().sets.single().confirmedReps)
        assertEquals(17,repo.state.value.tasks.size)
    }
    @Test fun recordPeriodsAndTrendUseActualDatedResults() {
        ready()
        val original=repo.state.value
        val earlier=(1L..2L).map {offset->ExecutionRecord(id="trend-$offset",taskId="trend-task-$offset",title="历史执行 $offset",category=Category.AI,outcome=TaskStatus.COMPLETED,
            seconds=offset*600,result="隔离测试成果",endedAt=LocalDate.now().minusDays(offset).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),isDemo=true)}
        runBlocking {repo.restore(original.copy(records=original.records+earlier))}
        tab("记录")
        ui.onAllNodesWithText("全部").onFirst().performClick()
        ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("所选周期最近3个有记录日期 · 不含未记录的执行或健康估算"))
        ui.onNodeWithText("所选周期最近3个有记录日期 · 不含未记录的执行或健康估算").assertExists();shot("records-trend")
        top();ui.onNodeWithText("日").performClick();ui.onNodeWithText("执行 1 条").assertExists()
        assertEquals(3,repo.state.value.records.size)
    }
}
