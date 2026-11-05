package com.trytobatter.app.core.alarm

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationManagerCompat
import com.trytobatter.app.MainActivity
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Local-only preferences/runtime ledger; the existing Room snapshot and LAN payload are unchanged. */
class TaskAlarmCoordinator(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val file = AtomicFile(File(context.filesDir, "task-alarms-v1.json"))
    private val mutex = Mutex()
    private val _state = MutableStateFlow(read())
    private val loadFailed = _state.value.error.isNotBlank()
    val state = _state.asStateFlow()
    init { createChannels() }

    private fun read(): AlarmSnapshot = try {
        if(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) AlarmSnapshot() else {
            val j = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val settings = j.getJSONObject("settings").let { o -> o.keys().asSequence().associateWith { id -> o.getJSONObject(id).let { s ->
                TaskAlarmSettings(s.optBoolean("enabled"), s.optInt("leadMinutes", 10), AlarmMode.valueOf(s.optString("mode", "RING"))) } } }
            val records = j.getJSONObject("records").let { o -> o.keys().asSequence().associateWith { id -> o.getJSONObject(id).let { r ->
                AlarmRecord(id, r.getString("fingerprint"), r.getString("generation"), r.getLong("triggerAt"), AlarmState.valueOf(r.getString("state")),
                    r.optInt("snoozeCount"), r.optLong("scheduledAt"), r.optString("error"), r.optLong("deliveredAt")) } } }
            AlarmSnapshot(settings, records)
        }
    } catch(e: Exception) { AlarmSnapshot(error = "本机闹钟数据读取失败：${e.message}") }

    private suspend fun write(value: AlarmSnapshot) = withContext(Dispatchers.IO) {
        val j = JSONObject().put("schema", 1)
        val settings = JSONObject(); value.settings.forEach { (id, s) -> settings.put(id, JSONObject().put("enabled", s.enabled).put("leadMinutes", s.leadMinutes).put("mode", s.mode.name)) }
        val records = JSONObject(); value.records.forEach { (id, r) -> records.put(id, JSONObject().put("fingerprint", r.fingerprint).put("generation", r.generation).put("triggerAt", r.triggerAt)
            .put("state", r.state.name).put("snoozeCount", r.snoozeCount).put("scheduledAt", r.scheduledAt).put("error", r.error).put("deliveredAt", r.deliveredAt)) }
        j.put("settings", settings).put("records", records)
        val stream = file.startWrite()
        try { stream.write(j.toString().toByteArray()); file.finishWrite(stream); _state.value = value } catch(e: Exception) { file.failWrite(stream); throw e }
    }

    fun createChannels() {
        notifications.createNotificationChannel(NotificationChannel(RING_CHANNEL, "事项响铃", NotificationManager.IMPORTANCE_HIGH).apply { description = "本地事项闹钟和关闭操作"; setSound(null, null); enableVibration(true) })
        notifications.createNotificationChannel(NotificationChannel(NOTICE_CHANNEL, "事项通知", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun permissionError(mode: AlarmMode): String? = when {
        !NotificationManagerCompat.from(context).areNotificationsEnabled() -> "系统尚未允许通知"
        notifications.getNotificationChannel(if(mode == AlarmMode.RING) RING_CHANNEL else NOTICE_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> "所选提醒渠道已关闭"
        mode == AlarmMode.RING && Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms() -> "系统尚未允许精确闹钟"
        else -> null
    }
    fun fullScreenAllowed() = Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()
    fun failure(message: String) { _state.value = _state.value.copy(error = message) }

    suspend fun setSettings(tasks: List<TaskItem>, taskId: String, setting: TaskAlarmSettings, following: Boolean = false) {
        require(setting.leadMinutes in listOf(0,5,10,30)) { "请选择有效的提前时间" }
        mutex.withLock {
            check(!loadFailed) { _state.value.error }
            val target = tasks.first { it.id == taskId }
            val ids = tasks.filter { it.id == taskId || following && target.seriesId != null && it.seriesId == target.seriesId && it.start >= target.start && !it.isProtected }.map { it.id }
            write(_state.value.copy(settings = _state.value.settings + ids.associateWith { setting }))
        }
        reconcile(tasks)
    }

    suspend fun restoreDeleted(tasks: List<TaskItem>, snapshot: AlarmSnapshot) {
        mutex.withLock {
            check(!loadFailed) { _state.value.error }
            val liveIds = tasks.map { it.id }.toSet()
            val settings = snapshot.settings.filterKeys { it in liveIds && it !in _state.value.settings }
            val records = snapshot.records.filterKeys { it in settings }.mapValues { (_, record) ->
                if(record.state == AlarmState.RINGING) record.copy(state = AlarmState.CANCELLED) else record
            }
            write(_state.value.copy(settings = _state.value.settings + settings, records = _state.value.records + records))
        }
        reconcile(tasks, restoreFuture = true)
    }

    private fun pending(id: String, generation: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? = PendingIntent.getBroadcast(context, 0,
        Intent(context, TaskAlarmReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.Builder().scheme("batter-alarm").authority("occurrence").appendPath(id).build())
            .putExtra("taskId", id).putExtra("generation", generation), flags or PendingIntent.FLAG_IMMUTABLE)
    private fun cancel(id: String, generation: String) {
        pending(id, generation, PendingIntent.FLAG_NO_CREATE)?.let { manager.cancel(it); it.cancel() }
        notifications.cancel(id, NOTICE_ID)
    }
    private fun arm(record: AlarmRecord, setting: TaskAlarmSettings) {
        val operation = pending(record.taskId, record.generation)!!
        if(setting.mode == AlarmMode.RING) manager.setAlarmClock(AlarmManager.AlarmClockInfo(record.triggerAt, openTask(record.taskId)), operation)
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.triggerAt, operation)
    }
    fun openTask(id: String, start: Boolean = false, dismissAlarm: Boolean = false, generation: String? = null): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).setAction(if(start) "alarm.START_TASK" else "alarm.OPEN_TASK")
            .setData(Uri.Builder().scheme("batter-task").authority("task").appendPath(id).appendPath(if(start) "start" else if(dismissAlarm) "dismiss-open" else "open").appendPath(generation.orEmpty()).build())
            .putExtra("alarmTaskId", id).putExtra("alarmStartTask", start).putExtra("alarmDismiss", dismissAlarm).putExtra("alarmGeneration", generation)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    suspend fun reconcile(tasks: List<TaskItem>, force: Boolean = false, restoreFuture: Boolean = false) = mutex.withLock {
        if(loadFailed) return@withLock
        val now = System.currentTimeMillis(); var value = _state.value.copy(error="")
        val liveIds = tasks.map { it.id }.toSet()
        for ((id, r) in value.records) if(id !in liveIds) cancel(id,r.generation)
        value = value.copy(settings=value.settings.filterKeys { it in liveIds }, records=value.records.filterKeys { it in liveIds })
        for (task in tasks) {
            val setting = value.settings[task.id] ?: continue
            val old = value.records[task.id]; val fp = AlarmRules.fingerprint(task, setting)
            val error = permissionError(setting.mode)
            val same = old?.fingerprint == fp
            if(same && old?.state == AlarmState.RINGING && setting.mode==AlarmMode.RING && now-old.deliveredAt>120_000) {
                cancel(task.id,old.generation); value=value.copy(records=value.records+(task.id to old.copy(state=AlarmState.CANCELLED))); continue
            }
            if(same && old != null && error == null && !force && !(restoreFuture && old.triggerAt > now) && setting.enabled && AlarmRules.eligible(task) &&
                (old.state == AlarmState.RINGING || old.state in setOf(AlarmState.CANCELLED, AlarmState.MISSED) ||
                old.state in setOf(AlarmState.SCHEDULED, AlarmState.SNOOZED) && (old.triggerAt > now || now-old.triggerAt <= 5*60_000 || setting.mode==AlarmMode.NOTIFICATION && now < task.end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))) continue
            if(same && old != null && old.state in setOf(AlarmState.CANCELLED, AlarmState.MISSED) && setting.enabled && error == null) continue
            val trigger = if(same && old?.state == AlarmState.SNOOZED) old.triggerAt else AlarmRules.triggerAt(task, setting)
            val desired = if(same && old?.state == AlarmState.SNOOZED && AlarmRules.eligible(task) && setting.enabled && trigger > now)
                if(error == null) AlarmState.SCHEDULED else AlarmState.NEEDS_PERMISSION else AlarmRules.desired(task,setting,error,now)
            old?.let { cancel(task.id,it.generation) }
            var record = AlarmRecord(task.id,fp,newId(),trigger,desired,if(same) old?.snoozeCount ?: 0 else 0,error=error.orEmpty())
            if(desired == AlarmState.SCHEDULED) {
                // Save an intent generation before arming; only a successful platform call earns SCHEDULED.
                record = record.copy(state=AlarmState.SCHEDULE_ERROR,error="系统安排尚未确认")
                value=value.copy(records=value.records+(task.id to record)); write(value)
                record = try { arm(record,setting); record.copy(state=if(same && old?.state == AlarmState.SNOOZED) AlarmState.SNOOZED else AlarmState.SCHEDULED,scheduledAt=now,error="") }
                catch(e: Exception) { cancel(task.id,record.generation); record.copy(error=e.message ?: "系统拒绝安排闹钟") }
            }
            value=value.copy(records=value.records+(task.id to record))
        }
        if(value != _state.value) { write(value); TaskAlarmService.refresh(context) }
    }

    suspend fun trigger(tasks: List<TaskItem>, id: String, generation: String): Boolean = mutex.withLock {
        val task = tasks.find { it.id == id }; val setting = _state.value.settings[id] ?: return@withLock false
        val r = _state.value.records[id]; val now=System.currentTimeMillis()
        if(!AlarmRules.validDelivery(task,setting,r,generation,now)) return@withLock false
        val error=permissionError(setting.mode)
        write(_state.value.copy(records=_state.value.records+(id to r!!.copy(state=if(error == null) AlarmState.RINGING else AlarmState.NEEDS_PERMISSION,deliveredAt=now,error=error.orEmpty()))))
        error == null
    }
    suspend fun dismiss(id: String, generation: String? = null) = mutex.withLock {
        val r=_state.value.records[id] ?: return@withLock
        if(generation != null && r.generation != generation) return@withLock
        cancel(id,r.generation)
        write(_state.value.copy(records=_state.value.records+(id to r.copy(state=AlarmState.CANCELLED))))
    }
    suspend fun snooze(tasks: List<TaskItem>, id: String, generation: String) = mutex.withLock {
        val r=_state.value.records[id]; val task=tasks.find { it.id==id }; val setting=_state.value.settings[id] ?: return@withLock
        if(r?.generation != generation || !AlarmRules.canSnooze(task,r,System.currentTimeMillis())) return@withLock
        val error=permissionError(setting.mode)
        var next=r!!.copy(generation=newId(),triggerAt=System.currentTimeMillis()+5*60_000,snoozeCount=r.snoozeCount+1,state=AlarmState.SCHEDULE_ERROR,error=error ?: "系统安排尚未确认")
        cancel(id,r.generation); write(_state.value.copy(records=_state.value.records+(id to next)))
        next=if(error != null) next.copy(state=AlarmState.NEEDS_PERMISSION) else try { arm(next,setting); next.copy(state=AlarmState.SNOOZED,error="",scheduledAt=System.currentTimeMillis()) } catch(e: Exception) { next.copy(error=e.message.orEmpty()) }
        write(_state.value.copy(records=_state.value.records+(id to next)))
    }
    suspend fun deliveryFailed(id: String, message: String) = mutex.withLock {
        val r=_state.value.records[id] ?: return@withLock
        write(_state.value.copy(records=_state.value.records+(id to r.copy(state=AlarmState.SCHEDULE_ERROR,error=message))))
    }
    companion object {
        const val RING_CHANNEL="task-ring-v1"; const val NOTICE_CHANNEL="task-notice-v1"; const val NOTICE_ID=2202
        const val ACTION_FIRE="com.trytobatter.app.ALARM_FIRE"; const val ACTION_DISMISS="alarm.DISMISS"; const val ACTION_SNOOZE="alarm.SNOOZE"; const val ACTION_REFRESH="alarm.REFRESH"
    }
}
