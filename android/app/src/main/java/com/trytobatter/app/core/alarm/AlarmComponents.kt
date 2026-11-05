package com.trytobatter.app.core.alarm

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trytobatter.app.BatterApplication
import com.trytobatter.app.R
import com.trytobatter.app.core.model.TaskItem
import kotlinx.coroutines.*
import com.trytobatter.app.core.alarm.TaskAlarmCoordinator.Companion.ACTION_DISMISS
import com.trytobatter.app.core.alarm.TaskAlarmCoordinator.Companion.ACTION_SNOOZE
import com.trytobatter.app.core.alarm.TaskAlarmCoordinator.Companion.ACTION_REFRESH
import com.trytobatter.app.core.alarm.TaskAlarmCoordinator.Companion.RING_CHANNEL
import com.trytobatter.app.core.alarm.TaskAlarmCoordinator.Companion.NOTICE_CHANNEL
import com.trytobatter.app.core.alarm.TaskAlarmCoordinator.Companion.NOTICE_ID

private fun action(context: Context, id: String, generation: String, name: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
    Intent(context, TaskAlarmActionReceiver::class.java).setAction(name).setData(android.net.Uri.parse("batter-action://task/$id/$name/$generation"))
        .putExtra("taskId",id).putExtra("generation",generation),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

internal fun alarmNotification(context: Context, task: TaskItem, record: AlarmRecord, ring: Boolean): Notification {
    val alarms=(context.applicationContext as BatterApplication).alarms
    return NotificationCompat.Builder(context,if(ring) RING_CHANNEL else NOTICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher).setContentTitle(task.title).setContentText("计划开始 ${task.start.toLocalTime()} · ${if(ring) "事项闹钟" else "事项提醒（系统可能延迟）"}")
        .setCategory(if(ring) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(alarms.openTask(task.id,dismissAlarm=true,generation=record.generation)).setDeleteIntent(action(context,task.id,record.generation,ACTION_DISMISS)).setOngoing(ring).setAutoCancel(!ring).setOnlyAlertOnce(true)
        .addAction(0,"关闭",action(context,task.id,record.generation,ACTION_DISMISS))
        .apply { if(record.snoozeCount<2) addAction(0,"稍后5分钟",action(context,task.id,record.generation,ACTION_SNOOZE)) }
        .addAction(0,"开始事项",alarms.openTask(task.id,true,true,record.generation))
        .apply { if(ring && alarms.fullScreenAllowed()) setFullScreenIntent(PendingIntent.getActivity(context,0,
            Intent(context,TaskAlarmActivity::class.java).setData(android.net.Uri.parse("batter-ring://task/${task.id}")),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),true) }
        .build()
}

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending=goAsync(); val app=context.applicationContext as BatterApplication
        app.alarmScope.launch {
            try {
                app.repository.load()
                val id=intent.getStringExtra("taskId") ?: return@launch
                val generation=intent.getStringExtra("generation") ?: return@launch
                if(!app.repository.handleTaskAlarmTrigger(id,generation)) return@launch
                val task=app.repository.state.value.tasks.find { it.id==id } ?: return@launch
                val record=app.alarms.state.value.records[id]?.takeIf {it.generation==generation && it.state==AlarmState.RINGING} ?: return@launch
                if(app.alarms.state.value.settings[id]?.mode == AlarmMode.RING) {
                    try { ContextCompat.startForegroundService(context,Intent(context,TaskAlarmService::class.java).putExtra("taskId",id).putExtra("generation",generation)) }
                    catch(e:Exception) { app.alarms.deliveryFailed(id,"系统未允许启动响铃：${e.message}") }
                } else {
                    context.getSystemService(NotificationManager::class.java).notify(id,NOTICE_ID,alarmNotification(context,task,record,false))
                    if(app.alarms.state.value.records[id]?.let {it.generation==generation && it.state==AlarmState.RINGING}!=true)
                        context.getSystemService(NotificationManager::class.java).cancel(id,NOTICE_ID)
                }
                app.alarms.reconcile(app.repository.state.value.tasks)
            } catch(e: Exception) { app.alarms.failure("闹钟触发处理失败：${e.message}") }
            finally { pending.finish() }
        }
    }
}

class TaskAlarmRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending=goAsync(); val app=context.applicationContext as BatterApplication
        app.alarmScope.launch {
            try { app.repository.load(); app.repository.refreshAlarms(true) }
            catch(e:Exception) { app.alarms.failure("恢复闹钟失败：${e.message}") }
            finally { pending.finish() }
        }
    }
}

class TaskAlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf(ACTION_SNOOZE,ACTION_DISMISS)) return
        val pending=goAsync(); val app=context.applicationContext as BatterApplication
        app.alarmScope.launch {
            try {
                app.repository.load()
                val id=intent.getStringExtra("taskId") ?: return@launch
                val generation=intent.getStringExtra("generation") ?: return@launch
                if(intent.action == ACTION_SNOOZE) app.repository.snoozeTaskAlarm(id,generation)
                else app.alarms.dismiss(id,generation)
                TaskAlarmService.refresh(context)
            } finally { pending.finish() }
        }
    }
}

/** One player for all simultaneous alarms. Exact-alarm permission qualifies this alarm-only FGS. */
class TaskAlarmService : Service() {
    private val app get()=application as BatterApplication
    private var player: MediaPlayer?=null
    private var focus: AudioFocusRequest?=null
    private var wake: PowerManager.WakeLock?=null
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val active get()=app.alarms.state.value.records.values.filter { r ->
        val t=app.repository.state.value.tasks.find {it.id==r.taskId};val s=app.alarms.state.value.settings[r.taskId]
        r.state==AlarmState.RINGING && s?.mode==AlarmMode.RING && AlarmRules.eligible(t) && r.fingerprint==AlarmRules.fingerprint(t!!,s)
    }
    override fun onBind(intent:Intent?) : IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val records=active
        val first=records.firstOrNull(); val task=app.repository.state.value.tasks.find { it.id==first?.taskId }
        if(first==null || task==null) { stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).cancel(first.taskId,NOTICE_ID)
            ServiceCompat.startForeground(this,FOREGROUND_ID,alarmNotification(this,task,first,true),
                if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0)
            running=true
            for(r in records.drop(1)) app.repository.state.value.tasks.find { it.id==r.taskId }?.let {
                getSystemService(NotificationManager::class.java).notify(r.taskId,NOTICE_ID,alarmNotification(this,it,r,true))
            }
            if(player==null) startSound()
        } catch(e:Exception) {
            scope.launch { records.forEach { app.alarms.deliveryFailed(it.taskId,"响铃失败：${e.message}") }; stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun startSound() {
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val audio=getSystemService(AudioManager::class.java)
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> if(change==AudioManager.AUDIOFOCUS_LOSS) stopSelf() }.build()
        check(audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "未获得闹钟音频焦点" }
        val uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player=MediaPlayer().apply { setAudioAttributes(attrs); setDataSource(this@TaskAlarmService,uri); isLooping=true; prepare(); start() }
        playbackActive=player?.isPlaying==true
        android.util.Log.i("BatterAlarm","Local alarm player started=$playbackActive")
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"batter:task-alarm").apply { acquire(120_000) }
        handler.postDelayed({ scope.launch { active.forEach { app.alarms.dismiss(it.taskId,it.generation) }; stopSelf() } },120_000)
    }
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        running=false; playbackActive=false; handler.removeCallbacksAndMessages(null)
        player?.run { runCatching { stop() }; release() }; player=null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        wake?.let { if(it.isHeld) it.release() }; scope.cancel()
        // A service killed unexpectedly is not restarted into another ring session.
        val ids=active.map { it.taskId to it.generation }
        app.alarmScope.launch { ids.forEach { (id,generation) -> app.alarms.dismiss(id,generation) } }
        super.onDestroy()
    }
    companion object {
        private const val FOREGROUND_ID=2203
        @Volatile private var running=false
        @Volatile var playbackActive=false
            private set
        fun refresh(context:Context) { if(running) runCatching { context.startService(Intent(context,TaskAlarmService::class.java).setAction(ACTION_REFRESH)) } }
    }
}
