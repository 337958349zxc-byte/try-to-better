@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.trytobatter.app.core.alarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trytobatter.app.BatterApplication
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.TaskItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun alarmTime(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

@Composable fun AlarmButton(setting: TaskAlarmSettings?, record: AlarmRecord?, onClick: () -> Unit) {
    val state=record?.state ?: AlarmState.DISABLED
    val bad=state in setOf(AlarmState.NEEDS_PERMISSION,AlarmState.SCHEDULE_ERROR)
    val scheduled=state in setOf(AlarmState.SCHEDULED,AlarmState.RINGING,AlarmState.SNOOZED)
    val pending=setting?.enabled==true && record==null
    IconToggleButton(checked=setting?.enabled==true,onCheckedChange={onClick()},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)) {
        Icon(if(bad) Icons.Outlined.NotificationImportant else if(scheduled) Icons.Outlined.AlarmOn else if(pending) Icons.Outlined.Alarm else Icons.Outlined.AlarmOff,
            "闹钟：${if(pending) "等待安排状态" else state.label}",Modifier.size(20.dp),tint=if(bad) BatterColors.Error else if(scheduled) BatterColors.Primary else BatterColors.Secondary)
    }
}

@Composable fun AlarmPermissionControls(mode: AlarmMode, onRefresh: () -> Unit) {
    val context=LocalContext.current
    val coordinator=(context.applicationContext as BatterApplication).alarms
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }
    val error=coordinator.permissionError(mode)
    if(error!=null) {
        Notice("闹钟尚未安排：$error",warning=true)
        TextButton(onClick={
            if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else {
                val intent=if(mode==AlarmMode.RING && Build.VERSION.SDK_INT>=31 && !context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms())
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}"))
                else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                runCatching { context.startActivity(intent) }
            }
        }) { Text("前往授权 / 设置") }
        if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            TextButton(onClick={ runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) } }) { Text("打开通知设置") }
        }
    }
    if(mode==AlarmMode.RING && !coordinator.fullScreenAllowed()) {
        Text("锁屏全屏展示受限，仍可通过通知操作。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
        if(Build.VERSION.SDK_INT>=34) TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:${context.packageName}"))) }) { Text("查看全屏提醒权限") }
    }
}

@Composable fun AlarmEditor(setting: TaskAlarmSettings, record: AlarmRecord?, scheduledAt: String, onChange:(TaskAlarmSettings)->Unit, onRefresh:()->Unit, saved:Boolean=false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AppCard {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("跟随事项闹钟",style=MaterialTheme.typography.titleSmall)
                Text(if(!setting.enabled) "未开启" else "${if(setting.leadMinutes==0) "开始时" else "提前${setting.leadMinutes}分钟"} · ${if(setting.mode==AlarmMode.RING) "响铃" else "通知"}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
            }
            Switch(setting.enabled,{ onChange(setting.copy(enabled=it)); if(it) expanded=true })
        }
        if(setting.enabled) {
            val at=runCatching { java.time.LocalDateTime.parse(scheduledAt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()-setting.leadMinutes*60_000L }.getOrNull()
            if(at!=null) Text("预计提醒 ${alarmTime(at)}",style=MaterialTheme.typography.bodyMedium)
            if(saved && record!=null) Text("状态：${record.state.label}${record.error.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                style=MaterialTheme.typography.bodySmall,color=if(record.state in setOf(AlarmState.NEEDS_PERMISSION,AlarmState.SCHEDULE_ERROR)) BatterColors.Error else BatterColors.Secondary)
            else Text(if(at!=null && at<=System.currentTimeMillis()) "本次提醒时间已过，请调整时间" else "保存事项后按权限安排本机闹钟",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
            AlarmPermissionControls(setting.mode,onRefresh)
            TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起闹钟设置" else "调整闹钟设置") }
            if(expanded) {
                Text("提前时间",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(0,5,10,30).forEach { minutes -> FilterChip(setting.leadMinutes==minutes,{onChange(setting.copy(leadMinutes=minutes))},{Text(if(minutes==0) "开始时" else "提前${minutes}分")}) }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { AlarmMode.entries.forEach { mode ->
                    FilterChip(setting.mode==mode,{onChange(setting.copy(mode=mode))},{Text(if(mode==AlarmMode.RING) "响铃闹钟" else "普通通知")})
                } }
                Text(if(setting.mode==AlarmMode.RING) "响铃最多持续2分钟；稍后5分钟最多2次。" else "普通通知由系统安排，可能延迟，不播放持续闹钟声。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
            }
        }
    }
}

@Composable fun TaskAlarmSheet(task:TaskItem, snapshot:AlarmSnapshot, onDismiss:()->Unit,onChange:(TaskAlarmSettings,Boolean)->Unit,onRefresh:()->Unit) {
    var following by rememberSaveable(task.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(task.title,style=MaterialTheme.typography.titleLarge)
            if(snapshot.error.isNotBlank()) Notice(snapshot.error,warning=true)
            if(task.seriesId!=null) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(following,{following=it}); Text("应用到本次及以后未执行安排") }
            AlarmEditor(snapshot.settings[task.id] ?: TaskAlarmSettings(),snapshot.records[task.id],task.scheduledAt,{onChange(it,following)},onRefresh,true)
            TextButton(onClick=onRefresh) { Text("重新检查并安排") }
            Button(onClick=onDismiss,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("完成") }
        }
    }
}
