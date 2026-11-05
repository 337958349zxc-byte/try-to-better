package com.trytobatter.app

import android.content.*
import android.provider.AlarmClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

class TimelineClockBatchTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun threeRequestsAndThreeLinkedCancellationsCrossRealActivityLifecycle() = verifyBatch(false)
    @Test fun singleTaskClockCanReturnWithoutPortableActivityResult() = verifyBatch(true)
    @Test fun closingAnActiveBatchStopsUnsentCreates() = verifyBatch(false,true)
    private fun verifyBatch(singleTask:Boolean,stopEarly:Boolean=false) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val events=CopyOnWriteArrayList<Intent>()
        val receiver=object:BroadcastReceiver() {override fun onReceive(c:Context,i:Intent) {events+=i}}
        ContextCompat.registerReceiver(context,receiver,IntentFilter("com.trytobatter.app.CLOCK_CONTRACT"),ContextCompat.RECEIVER_EXPORTED)
        fun enabled(value:Boolean) {
            val activity=if(singleTask) "ClockSingleTaskContractActivity" else "ClockContractActivity"
            val output=instrumentation.uiAutomation.executeShellCommand("run-as com.trytobatter.app.test pm ${if(value) "enable" else "disable"} com.trytobatter.app.test/com.trytobatter.app.$activity")
                .use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8)}
            assertTrue(output,output.contains("new state: ${if(value) "enabled" else "disabled"}"))
        }
        val repo=(ui.activity.application as BatterApplication).repository
        ui.runOnUiThread {ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        val items=(1..3).map { n -> TaskItem(id="clock-batch-$n",title="协议批次 $n",category=Category.LIFE,scheduledAt=LocalDateTime.now().plusMinutes(30L*n).toString()) }
        assumeTrue(items.all {it.day==java.time.LocalDate.now()})
        try {
            enabled(true)
            runBlocking {
                repo.load();repo.disconnect();repo.resetDemo(true)
                // Isolate the fixture's journal from earlier emulator-only protocol runs.
                repo.prepareTimelineClockBatch(false)
                repo.timelineClock.value.entries.values.forEach {repo.recordTimelineClockRequest(it.copy(action=ClockAction.CANCEL,stage=ClockRequestStage.REQUESTED,error=""))}
                items.forEach {repo.saveTask(it)}
            }
            ui.waitUntil(5000) {ui.onAllNodesWithText("一键设置全部闹钟").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("一键设置全部闹钟").performScrollTo().performClick()
            ui.waitUntil(5000) {events.any {it.getStringExtra("action")==AlarmClock.ACTION_SET_ALARM}}
            if(stopEarly) {
                lateinit var controller:TimelineClockBatchViewModel
                ui.runOnUiThread {controller=androidx.lifecycle.ViewModelProvider(ui.activity)[TimelineClockBatchViewModel::class.java];controller.begin(false)}
                ui.waitUntil(15000) {!controller.state.value.busy}
                assertEquals(1,events.count {it.getStringExtra("action")==AlarmClock.ACTION_SET_ALARM})
                val created=events.first {it.getStringExtra("action")==AlarmClock.ACTION_SET_ALARM}.getStringExtra("label")
                assertTrue(events.any {it.getStringExtra("action")==AlarmClock.ACTION_DISMISS_ALARM && it.getStringExtra("label")==created})
                assertEquals("",repo.timelineClock.value.enabledDay)
                return
            }
            if(!singleTask) {
                ui.activityRule.scenario.recreate()
                // recreate() preserves PAUSED when called while the clock is on top.
                // Simulate the user returning to our foreground before expecting the queue to continue.
                ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            ui.waitUntil(15000) { events.count {it.getStringExtra("action")==AlarmClock.ACTION_SET_ALARM}==3 }
            ui.waitUntil(5000) {items.all {repo.timelineClock.value.entries[it.id]?.stage==ClockRequestStage.REQUESTED}}
            val sets=events.filter {it.getStringExtra("action")==AlarmClock.ACTION_SET_ALARM}
            assertEquals(3,sets.map {it.getStringExtra("label")}.distinct().size)
            items.forEach {task -> val set=sets.single {it.getStringExtra("label")!!.contains("[${task.id}]")}
                assertEquals(task.start.minusMinutes(10).hour,set.getIntExtra("hour",-1))
                assertEquals(task.start.minusMinutes(10).minute,set.getIntExtra("minute",-1)) }
            val linkedLabels=repo.timelineClock.value.entries.values.filter {it.day==java.time.LocalDate.now().toString()}.map {it.label}.toSet()
            ui.onNodeWithText("全部关闭").performScrollTo().performClick()
            ui.waitUntil(15000) {events.count {it.getStringExtra("action")==AlarmClock.ACTION_DISMISS_ALARM}==linkedLabels.size}
            ui.waitUntil(5000) {items.all {repo.timelineClock.value.entries[it.id]?.action==ClockAction.CANCEL && repo.timelineClock.value.entries[it.id]?.stage==ClockRequestStage.REQUESTED}}
            val cancels=events.filter {it.getStringExtra("action")==AlarmClock.ACTION_DISMISS_ALARM}
            assertEquals(linkedLabels,cancels.map {it.getStringExtra("label")}.toSet())
            assertTrue(cancels.map {it.getStringExtra("label")}.containsAll(sets.map {it.getStringExtra("label")}))
            assertTrue(cancels.all {it.getStringExtra("search")==AlarmClock.ALARM_SEARCH_MODE_LABEL})
            // A returned cancellation is not verified native state: explicit retry must remain available.
            ui.onNodeWithText("全部关闭").performScrollTo().performClick()
            ui.waitUntil(15000) {events.count {it.getStringExtra("action")==AlarmClock.ACTION_DISMISS_ALARM}==linkedLabels.size*2}
            ui.waitUntil(5000) {items.all {repo.timelineClock.value.entries[it.id]?.action==ClockAction.CANCEL && repo.timelineClock.value.entries[it.id]?.stage==ClockRequestStage.REQUESTED}}
        } finally {
            ui.runOnUiThread {println("Clock batch final state="+androidx.lifecycle.ViewModelProvider(ui.activity)[TimelineClockBatchViewModel::class.java].state.value)}
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).takeScreenshot(java.io.File(context.getExternalFilesDir(null),"clock-batch-final.png"))
            println("Clock protocol received SET=${events.count {it.getStringExtra("action")==AlarmClock.ACTION_SET_ALARM}} CANCEL=${events.count {it.getStringExtra("action")==AlarmClock.ACTION_DISMISS_ALARM}}")
            enabled(false);context.unregisterReceiver(receiver)
            runBlocking {repo.prepareTimelineClockBatch(false);repo.resetDemo(true)}
        }
    }
}
