package com.trytobatter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.data.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/** Dedicated emulator only. Tests do not contact the production LAN server. */
@RunWith(AndroidJUnit4::class)
class AlarmIntegrationTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BatterApplication
    private val repo get()=app.repository
    private fun item(seconds:Long=120)=TaskItem(title="本机闹钟验收",category=Category.ENGLISH,scheduledAt=LocalDateTime.now().plusSeconds(seconds).toString(),durationMinutes=60)
    private val setting=TaskAlarmSettings(true,0,AlarmMode.RING)
    private suspend fun reset() {repo.load();repo.disconnect();repo.resetDemo(true)}
    private fun await(timeout:Long=15_000, test:()->Boolean) {val end=System.currentTimeMillis()+timeout;while(!test() && System.currentTimeMillis()<end) Thread.sleep(100);assertTrue("Timed out",test())}

    @Test fun settingsSurviveRecreationRescheduleCancelAndPermissionChecks()= runBlocking {
        reset(); val task=item();repo.saveTask(task)
        assertNull(repo.alarms.value.settings[task.id])
        repo.setTaskAlarm(task.id,setting)
        val first=repo.alarms.value.records.getValue(task.id)
        assertEquals(AlarmState.SCHEDULED,first.state)
        assertEquals(setting,TaskAlarmCoordinator(app).state.value.settings[task.id])
        repo.refreshAlarms();assertEquals(first.generation,repo.alarms.value.records.getValue(task.id).generation)
        val edited=task.copy(scheduledAt=task.start.plusMinutes(60).toString())
        repo.saveTask(edited)
        val second=repo.alarms.value.records.getValue(task.id)
        assertEquals(first.triggerAt+60*60_000,second.triggerAt);assertNotEquals(first.generation,second.generation)
        assertFalse(app.alarms.trigger(repo.state.value.tasks,task.id,first.generation))
        repo.startTask(task.id);assertEquals(AlarmState.CANCELLED,repo.alarms.value.records[task.id]?.state)
        repo.finishTask(task.id,TaskStatus.PARTIAL,"已做一部分")
        repo.correctRecord(repo.state.value.records.single().id,TaskStatus.COMPLETED,90,"核对结果")
        assertEquals(AlarmState.CANCELLED,repo.alarms.value.records[task.id]?.state)
        reset()
    }

    @Test fun finiteRepeatedInstancesHaveIndependentSchedulesAndSingleScope()=runBlocking {
        reset(); val first=item().copy(repeatRule="每天");repo.saveTask(first);repo.setTaskAlarm(first.id,setting,true)
        assertEquals(7,repo.alarms.value.records.size)
        assertEquals(7,repo.alarms.value.records.values.map {it.generation}.toSet().size)
        repo.setTaskAlarm(first.id,setting.copy(enabled=false))
        assertEquals(6,repo.alarms.value.records.values.count {it.state==AlarmState.SCHEDULED})
        repo.deleteTask(first.id,true)
        assertTrue(repo.alarms.value.settings.isEmpty());assertTrue(repo.alarms.value.records.isEmpty())
        reset()
    }

    @Test fun undoDeletionRestoresRepeatedAlarmPreferencesAndRegistrations()=runBlocking {
        reset(); val task=item().copy(repeatRule="每天")
        repo.saveTask(task);repo.setTaskAlarm(task.id,setting,true)
        val previous=repo.alarms.value.records.getValue(task.id)
        repo.deleteTask(task.id,true)
        assertTrue(repo.alarms.value.records.isEmpty())
        repo.undo()
        assertEquals(7,repo.state.value.tasks.size)
        assertEquals(7,repo.alarms.value.records.values.count {it.state==AlarmState.SCHEDULED})
        assertEquals(setting,repo.alarms.value.settings[task.id])
        assertEquals(previous.triggerAt,repo.alarms.value.records.getValue(task.id).triggerAt)
        assertNotEquals(previous.generation,repo.alarms.value.records.getValue(task.id).generation)
        reset()
    }

    @Test fun oneTapEnableUsesTenMinuteRingAndOffCancelsPlatformAlarm()=runBlocking {
        reset();val task=item(3600);repo.saveTask(task)
        repo.setTaskAlarm(task.id,TaskAlarmSettings(false,30,AlarmMode.NOTIFICATION))
        repo.toggleTaskAlarm(task.id)
        assertEquals(TaskAlarmSettings(true,10,AlarmMode.RING),repo.alarms.value.settings[task.id])
        val record=repo.alarms.value.records.getValue(task.id)
        assertEquals(AlarmState.SCHEDULED,record.state)
        assertEquals(AlarmRules.triggerAt(task,TaskAlarmSettings(true)),record.triggerAt)
        repo.toggleTaskAlarm(task.id)
        assertEquals(AlarmState.DISABLED,repo.alarms.value.records[task.id]?.state)
        assertNotEquals(record.triggerAt,app.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime)
        reset()
    }

    @Test fun systemUpcomingAlarmDetailsDoNotCancelTheSchedule()=runBlocking {
        reset();val task=item().copy(title="查看下次闹钟不能取消")
        repo.saveTask(task);repo.setTaskAlarm(task.id,setting)
        val previous=repo.alarms.value.records.getValue(task.id)
        val alarm=app.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock!!
        assertEquals(previous.triggerAt,alarm.triggerTime)
        alarm.showIntent.send()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.text(task.title)),8000))
        assertEquals(AlarmState.SCHEDULED,repo.alarms.value.records[task.id]?.state)
        assertEquals(previous.generation,repo.alarms.value.records[task.id]?.generation)
        reset()
    }

    @Test fun coldRepositoryLoadRepairsLostFuturePlatformRegistration()=runBlocking {
        reset();val task=item();repo.saveTask(task);repo.setTaskAlarm(task.id,setting)
        val previous=repo.alarms.value.records.getValue(task.id)
        val manager=app.getSystemService(android.app.AlarmManager::class.java)
        val operation=android.app.PendingIntent.getBroadcast(app,0,
            android.content.Intent(app,TaskAlarmReceiver::class.java).setAction(TaskAlarmCoordinator.ACTION_FIRE)
                .setData(android.net.Uri.Builder().scheme("batter-alarm").authority("occurrence").appendPath(task.id).build()),
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)!!
        manager.cancel(operation)
        assertNotEquals(previous.triggerAt,manager.nextAlarmClock?.triggerTime)
        LocalDemoRepository(app).load()
        val restored=repo.alarms.value.records.getValue(task.id)
        assertEquals(AlarmState.SCHEDULED,restored.state)
        assertNotEquals(previous.generation,restored.generation)
        assertEquals(previous.triggerAt,manager.nextAlarmClock?.triggerTime)
        repo.dismissTaskAlarm(task.id,previous.generation)
        assertEquals(AlarmState.SCHEDULED,repo.alarms.value.records[task.id]?.state)
        reset()
    }

    @Test fun unresolvedRemoteChangeKeepsLocalAlarmThenAcceptedTimeReschedules()=runBlocking {
        reset();val task=item();repo.saveTask(task);repo.setTaskAlarm(task.id,setting)
        val before=repo.alarms.value.records.getValue(task.id)
        val remote=task.copy(scheduledAt=task.start.plusHours(1).toString())
        val change=JSONObject().put("key","task:${task.id}").put("revision",2).put("payload",SnapshotCodec.task(remote)).put("deleted",false)
        val response=JSONObject().put("cursor",1).put("planVersion",2).put("acknowledged",JSONArray()).put("conflicts",JSONArray()).put("changes",JSONArray().put(change))
        val merged=SyncEngine.merge(repo.state.value,emptyList(),response)
        app.alarms.reconcile(merged.tasks)
        assertEquals(before.triggerAt,repo.alarms.value.records.getValue(task.id).triggerAt)
        assertEquals(setting,repo.alarms.value.settings[task.id]);assertTrue(merged.syncConflicts.isNotEmpty())
        val resolved=SyncEngine.resolve(merged,"task:${task.id}",false)
        app.alarms.reconcile(resolved.tasks)
        assertEquals(before.triggerAt+3_600_000,repo.alarms.value.records.getValue(task.id).triggerAt)
        assertEquals(setting,repo.alarms.value.settings[task.id])
        reset()
    }

    @Test fun realBackgroundAlarmPlaysStopsAndStartIntentUsesExistingExecution()=runBlocking {
        reset();val task=item(12);repo.saveTask(task);repo.setTaskAlarm(task.id,setting)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
        await(25_000) {TaskAlarmService.playbackActive}
        assertEquals(AlarmState.RINGING,repo.alarms.value.records[task.id]?.state)
        val r=repo.alarms.value.records.getValue(task.id)
        repo.snoozeTaskAlarm(task.id,r.generation)
        await { !TaskAlarmService.playbackActive }
        assertFalse(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {it.id==2203})
        assertEquals(AlarmState.SNOOZED,repo.alarms.value.records[task.id]?.state)
        assertEquals(1,repo.alarms.value.records[task.id]?.snoozeCount)
        assertEquals(task.scheduledAt,repo.state.value.tasks.single().scheduledAt)
        app.alarms.openTask(task.id,true).send()
        await {repo.state.value.tasks.single().status==TaskStatus.RUNNING}
        assertTrue(repo.state.value.tasks.single().executionSnapshot.isNotBlank())
        assertEquals(AlarmState.CANCELLED,repo.alarms.value.records[task.id]?.state)
        reset()
    }

    @Test fun simultaneousAlarmsSharePlayerAndCloseIndependently()=runBlocking {
        reset();val a=item(12);val b=a.copy(id=newId(),title="同一时间的第二项")
        repo.saveTask(a);repo.saveTask(b);repo.setTaskAlarm(a.id,setting);repo.setTaskAlarm(b.id,setting)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
        await(25_000) {TaskAlarmService.playbackActive && repo.alarms.value.records.values.count {it.state==AlarmState.RINGING}==2}
        repo.dismissTaskAlarm(a.id)
        assertEquals(AlarmState.RINGING,repo.alarms.value.records[b.id]?.state)
        assertTrue(TaskAlarmService.playbackActive)
        repo.dismissTaskAlarm(b.id);await {!TaskAlarmService.playbackActive}
        assertFalse(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {it.id==2203})
        reset()
    }
}
