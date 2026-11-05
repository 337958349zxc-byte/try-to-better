package com.trytobatter.app.core.alarm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Journal of handoffs to the installed clock. It cannot query the clock's private alarm database. */
class SystemClockBridge(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir,"timeline-clock-v1.json"))
    private val mutex = Mutex()
    private val _state = MutableStateFlow(read())
    val state = _state.asStateFlow()
    private val tracePreferences=context.getSharedPreferences("clock-handoff-trace",Context.MODE_PRIVATE)
    private val traceMutex=Mutex()
    suspend fun trace(event:String) = withContext(Dispatchers.IO) {traceMutex.withLock {
        val old=runCatching {JSONArray(tracePreferences.getString("events","[]"))}.getOrDefault(JSONArray())
        val next=JSONArray();for(i in maxOf(0,old.length()-79) until old.length()) next.put(old.get(i))
        next.put("${Instant.now()} ${event.take(500)}")
        check(tracePreferences.edit().putString("events",next.toString()).commit()) {"时钟诊断记录保存失败"}
    }}
    private val loadFailed = _state.value.error.isNotBlank()
    private fun read(): TimelineClockState = try {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) TimelineClockState() else {
            val j=JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            val entries=j.optJSONArray("entries") ?: JSONArray()
            TimelineClockState(j.optString("enabledDay"),(0 until entries.length()).map { fromJson(entries.getJSONObject(it)) }.associateBy { it.taskId })
        }
    } catch(e: Exception) { TimelineClockState(error="系统闹钟请求记录读取失败：${e.message}") }
    private suspend fun write(next: TimelineClockState) = withContext(Dispatchers.IO) {
        val bytes=JSONObject().put("enabledDay",next.enabledDay).put("entries",JSONArray().apply { next.entries.values.forEach { put(toJson(it)) } }).toString().toByteArray()
        val stream=file.startWrite()
        try { stream.write(bytes);file.finishWrite(stream);_state.value=next } catch(e:Exception) {file.failWrite(stream);throw e}
    }
    suspend fun enabled(day: String) = mutex.withLock {
        check(!loadFailed) { _state.value.error };write(_state.value.copy(enabledDay=day))
    }
    suspend fun record(entry: ClockEntry) = mutex.withLock {
        check(!loadFailed) { _state.value.error };write(_state.value.copy(entries=_state.value.entries+(entry.taskId to entry)))
    }
    @Suppress("DEPRECATION")
    fun clockApp(): SystemClockApp {
        val pm=context.packageManager
        val candidates=clockCandidates()
        val preferred=pm.resolveActivity(Intent(AlarmClock.ACTION_SET_ALARM),PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val known=runCatching { pm.getApplicationInfo(XIAOMI_CLOCK,0) }.getOrNull()?.takeIf {it.enabled}?.let {
            SystemClockApp(it.packageName,pm.getApplicationLabel(it).toString())
        }
        val xiaomi=listOf(Build.MANUFACTURER,Build.BRAND).any { it.lowercase() in setOf("xiaomi","redmi","poco") }
        return ClockRoutingRules.choose(candidates,preferred,known,xiaomi)
    }
    @Suppress("DEPRECATION")
    fun clockCandidates(): List<SystemClockApp> = context.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM),PackageManager.MATCH_DEFAULT_ONLY)
        .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
        .map { SystemClockApp(it.activityInfo.packageName,it.loadLabel(context.packageManager).toString()) }.distinctBy { it.packageName }

    @Suppress("DEPRECATION")
    fun diagnostics(): String {
        val pm=context.packageManager;val route=clockApp()
        val lines=mutableListOf("try to better 时钟兼容性诊断","版本：${pm.getPackageInfo(context.packageName,0).versionName}","生成时间：${Instant.now()}","设备：${Build.MANUFACTURER} ${Build.MODEL}","Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            "SET_ALARM权限：${context.checkSelfPermission("com.android.alarm.permission.SET_ALARM")==PackageManager.PERMISSION_GRANTED}",
            "选择路径：${route.packageName.ifBlank {"Android系统选择"}}")
        if(route.packageName.isNotBlank()) runCatching {pm.getPackageInfo(route.packageName,0)}.getOrNull()?.let {
            lines += "时钟版本：${it.versionName} / code ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)}"
        }
        for(action in listOf(AlarmClock.ACTION_SET_ALARM,AlarmClock.ACTION_DISMISS_ALARM,AlarmClock.ACTION_SHOW_ALARMS)) {
            val intent=Intent(action).apply {if(route.packageName.isNotBlank()) setPackage(route.packageName)}
            val found=pm.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY)
            lines += "$action：${found.joinToString {"${it.activityInfo.packageName}/${it.activityInfo.name} launchMode=${it.activityInfo.launchMode} exported=${it.activityInfo.exported} enabled=${it.activityInfo.enabled} permission=${it.activityInfo.permission}"}.ifBlank {"查询未返回接收者，实际调用另行确认"}}"
        }
        _state.value.entries.values.filter {it.stage==ClockRequestStage.FAILED}.takeLast(3).forEach {lines += "最近失败：${it.action} ${it.error}"}
        lines += "以下仅为请求交接记录，不代表系统时钟实际创建或关闭结果"
        _state.value.entries.values.toList().takeLast(50).forEach {lines += "${it.action} ${it.stage} ${it.label} @ ${Instant.ofEpochMilli(it.triggerAt)} ${it.error}"}
        val events=runCatching {JSONArray(tracePreferences.getString("events","[]"))}.getOrDefault(JSONArray())
        for(i in 0 until events.length()) lines += events.getString(i)
        return lines.joinToString("\n").take(20000)
    }
    fun requestIntent(entry: ClockEntry): Intent {
        val intent=if(entry.action==ClockAction.SET) {
            val time=Instant.ofEpochMilli(entry.triggerAt).atZone(ZoneId.systemDefault())
            check(time.toLocalDate()==java.time.LocalDate.now() && entry.triggerAt>System.currentTimeMillis()) { "本次提醒时间已过，未发送到系统时钟" }
            Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,time.hour).putExtra(AlarmClock.EXTRA_MINUTES,time.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE,entry.label).putExtra(AlarmClock.EXTRA_SKIP_UI,true).putExtra(AlarmClock.EXTRA_VIBRATE,true)
        } else Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE,AlarmClock.ALARM_SEARCH_MODE_LABEL)
            .putExtra(AlarmClock.EXTRA_MESSAGE,entry.label).putExtra(AlarmClock.EXTRA_SKIP_UI,true)
        if(entry.clockPackage.isNotBlank()) intent.setPackage(entry.clockPackage)
        return intent
    }
    fun showIntent(): Intent {
        val app=clockApp()
        val intent=Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {if(app.packageName.isNotBlank()) setPackage(app.packageName)}
        if(app.packageName.isNotBlank() && intent.resolveActivity(context.packageManager)==null)
            context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {return it}
        return intent
    }
    companion object {
        const val XIAOMI_CLOCK="com.android.deskclock"
        fun failureText(e: Exception): String = when(e) {
            is android.content.ActivityNotFoundException -> "手机未能接收本次闹钟请求，请打开系统时钟并发送时钟诊断到电脑。"
            is SecurityException -> "系统拒绝了本次时钟调用，请发送时钟诊断到电脑核对权限或应用间启动限制。"
            else -> e.message ?: "系统时钟调用未完成，请发送时钟诊断到电脑。"
        }
        fun toJson(e: ClockEntry): JSONObject = JSONObject().put("taskId",e.taskId).put("day",e.day).put("title",e.title).put("triggerAt",e.triggerAt)
            .put("label",e.label).put("clockPackage",e.clockPackage).put("action",e.action.name).put("stage",e.stage.name).put("error",e.error)
        fun fromJson(j: JSONObject) = ClockEntry(j.getString("taskId"),j.getString("day"),j.getString("title"),j.getLong("triggerAt"),j.getString("label"),
            j.getString("clockPackage"),ClockAction.valueOf(j.getString("action")),ClockRequestStage.valueOf(j.getString("stage")),j.optString("error"))
    }
}
