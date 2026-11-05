package com.trytobatter.app.feature.today

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.trytobatter.app.R
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable fun TodayScreen(state:AppSnapshot,nowElapsed:Long,onTask:(String)->Unit,onStart:(TaskItem)->Unit,
    onPause:(String)->Unit,onOutcome:(TaskItem)->Unit,onEdit:(String)->Unit,onAdd:()->Unit,onSync:()->Unit,onConflicts:()->Unit,
    availableVersion:String?=null,onUpdate:()->Unit={},alarms:AlarmSnapshot=AlarmSnapshot(),onAlarm:(String)->Unit={},
    timelineControl:(@Composable ()->Unit)?=null,onCalendar:()->Unit={}) {
    val now by produceState(LocalDateTime.now()) {while(true) {value=LocalDateTime.now();delay(30_000)}}
    val day=now.toLocalDate()
    val tasks=state.tasks.filter {it.day==day}.sortedBy {it.start}
    val active=tasks.firstOrNull {it.status==TaskStatus.RUNNING} ?: tasks.firstOrNull {it.status==TaskStatus.PAUSED}
    val next=nextPlanned(tasks,now,active?.id)
    var expanded by rememberSaveable(day.toString()) {mutableStateOf(DayPeriod.at(now.hour).name)}
    var manuallySelected by rememberSaveable(day.toString()) {mutableStateOf(false)}
    var allInPeriod by rememberSaveable(day.toString(),expanded) {mutableStateOf(false)}
    LaunchedEffect(now.hour) {if(!manuallySelected) expanded=DayPeriod.at(now.hour).name}
    val failures=tasks.filter {alarms.records[it.id]?.state in setOf(AlarmState.NEEDS_PERMISSION,AlarmState.SCHEDULE_ERROR)}
    val largeText=LocalDensity.current.fontScale>1.25f
    ScenicPage(R.drawable.ttb_v3_today_header) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val reminderMax=maxHeight*.45f
        Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item("header") {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).padding(vertical=10.dp)) {
                        Text("今天",style=MaterialTheme.typography.headlineLarge)
                        Text(day.format(DateTimeFormatter.ofPattern("M月d日 EEEE",Locale.CHINESE)),style=MaterialTheme.typography.bodySmall,color=BatterColors.Text)
                        if(largeText) Row {
                            IconButton(onClick=onSync) {Icon(Icons.Outlined.Sync,"电脑连接与同步",tint=BatterColors.PrimaryDeep)}
                            IconButton(onClick=onAdd) {Icon(Icons.Outlined.Add,"新增事项",tint=BatterColors.PrimaryDeep)}
                        }
                    }
                    if(!largeText) {
                        IconButton(onClick=onSync) {Icon(Icons.Outlined.Sync,"电脑连接与同步",tint=BatterColors.PrimaryDeep)}
                        IconButton(onClick=onAdd) {Icon(Icons.Outlined.Add,"新增事项",tint=BatterColors.PrimaryDeep)}
                    }
                    ProgressOrbit(tasks.count {it.status==TaskStatus.COMPLETED},tasks.size)
                }
            }
            item("focus") {
                if(active!=null) FocusTaskPanel(active,nowElapsed,{onStart(active)},{onPause(active.id)},{onOutcome(active)})
                else LifePanel {
                    Text(if(tasks.isNotEmpty() && tasks.all {it.isFinished}) "今天的安排已有结果" else "现在没有正在进行的任务",style=MaterialTheme.typography.titleMedium)
                    Text(if(tasks.isEmpty()) "今天没有安排事项，添加一项或从电脑接收计划。" else if(next==null) "在下方回看完成、部分完成与跳过的事项。" else "按实际节奏开始下一项，计时会保存在本机。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    if(tasks.isEmpty()) TextButton(onClick=onAdd) {Text("添加事项")}
                }
            }
            item("next") {
                if(next!=null) LifePanel(onClick={onTask(next.id)}) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Column {Text("下一项",style=MaterialTheme.typography.bodySmall,color=BatterColors.PrimaryDeep);Text(next.start.format(ClockFormat),style=MaterialTheme.typography.titleMedium)}
                        Column(Modifier.weight(1f)) {Text(next.title,style=MaterialTheme.typography.titleSmall);Text("计划${next.durationMinutes}分钟",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
                        if(active==null) IconButton(onClick={onStart(next)}) {Icon(Icons.Outlined.PlayCircle,"开始下一项",tint=BatterColors.PrimaryDeep)}
                        else Icon(Icons.Outlined.ChevronRight,null,tint=BatterColors.Secondary)
                    }
                } else Text("没有下一项安排",Modifier.padding(horizontal=8.dp),style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
            }
            if(state.connection.error.isNotBlank()) item("sync-error") {Notice(state.connection.error,"查看",true,onSync)}
            if(state.connection.phase==ConnectionPhase.WAITING_APPROVAL) item("approval") {Notice("首次请在电脑连接页点允许","查看",onClick=onSync)}
            if(state.conflictCount>0) item("conflicts") {Notice("新计划有${state.conflictCount}项需要确认","处理",true,onConflicts)}
            if(availableVersion!=null) item("update") {Notice("新版本 $availableVersion 已发布","去更新",onClick=onUpdate)}
            if(alarms.error.isNotBlank()) item("alarm-error") {Notice(alarms.error,warning=true)}
            if(failures.isNotEmpty()) item("alarm-failures") {Notice("${failures.size}项闹钟需授权或安排失败","处理",true) {onAlarm(failures.first().id)}}
            item("timeline-heading") {SectionTitle("今天安排","查看全天",onCalendar)}
            DayPeriod.entries.forEach {period->
                val group=tasks.filter {it.period()==period}
                val open=expanded==period.name
                val running=group.firstOrNull {it.status in setOf(TaskStatus.RUNNING,TaskStatus.PAUSED)}
                val upcoming=nextPlanned(group,now)
                item("capsule-${period.name}") {
                    FoldHeader(period.label,"${group.size}项 · ${group.count {it.status==TaskStatus.COMPLETED}}项完整完成"+
                        (running?.let {" · ${it.status.label}"} ?: upcoming?.let {" · 下一项 ${it.start.format(ClockFormat)}"}.orEmpty()),open,
                        {manuallySelected=true;expanded=if(open) "NONE" else period.name},period.icon(),"capsule-${period.name}")
                }
            }
            if(expanded!="NONE") item("period-details") {
                val period=DayPeriod.valueOf(expanded)
                val group=tasks.filter {it.period()==period}
                LifePanel(Modifier.animateContentSize()) {
                    Text("${period.label} · 时间线",style=MaterialTheme.typography.labelLarge,color=BatterColors.PrimaryDeep)
                    if(group.isEmpty()) Text("这个时段没有安排",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    val preview=if(allInPeriod) group else group.take(2)
                    preview.forEachIndexed {i,task->key(task.id) {
                        TimelineTaskLine(task,TaskRules.conflicts(task,state.tasks).isNotEmpty(),alarms,{onAlarm(task.id)},{onTask(task.id)},i==0,i==preview.lastIndex)
                    }}
                    if(group.size>2) TextButton(onClick={allInPeriod=!allInPeriod},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text(if(allInPeriod) "收起时段明细" else "查看${period.label}全部${group.size}项")
                    }
                }
            }
            item("sync") {LifePanel(onClick=onSync) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CloudSync,null,tint=if(state.connection.error.isNotBlank()) BatterColors.Error else BatterColors.PrimaryDeep)
                    Column(Modifier.weight(1f)) {
                        Text("异常与同步",style=MaterialTheme.typography.titleSmall)
                        Text("${when(state.connection.phase) {ConnectionPhase.ONLINE->"电脑已连接";ConnectionPhase.WAITING_APPROVAL->"等待电脑授权";ConnectionPhase.OFFLINE,ConnectionPhase.AUTH_ERROR->"电脑连接不可用";else->"本机模式"}} · ${state.pendingSyncCount}项待同步 · ${state.conflictCount}项待确认",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    }
                    Icon(Icons.Outlined.ChevronRight,null)
                }
            }}

        }
        // Always composed while Today is visible, independently of lazy items and task folds.
        if(timelineControl!=null) Box(Modifier.fillMaxWidth().heightIn(max=reminderMax).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=4.dp)) {timelineControl()}
        }
        }
    }
}

@Composable private fun FocusTaskPanel(task:TaskItem,nowElapsed:Long,onStart:()->Unit,onPause:()->Unit,onOutcome:()->Unit) {
    val elapsed=TaskRules.elapsed(task,nowElapsed)
    LifePanel {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            StateBadge(task.status.label,task.status.color())
            Text("实际 ${formatDuration(elapsed)}",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=BatterColors.PrimaryDeep)
            Icon(task.category.icon(),task.category.label,tint=task.category.color())
        }
        Text(task.title,style=MaterialTheme.typography.titleMedium)
        Text("计划 ${task.timeLabel} · ${task.durationMinutes}分钟",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
        LinearProgressIndicator(progress={(elapsed.toFloat()/(task.durationMinutes.coerceAtLeast(1)*60)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(4.dp),color=BatterColors.Primary,trackColor=BatterColors.LightBlue,gapSize=0.dp,drawStopIndicator={})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(onClick=if(task.status==TaskStatus.RUNNING) onPause else onStart,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {
                Icon(if(task.status==TaskStatus.RUNNING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,null,Modifier.size(18.dp))
                Text(if(task.status==TaskStatus.RUNNING) "暂停" else "继续任务")
            }
            OutlinedButton(onClick=onOutcome,modifier=Modifier.heightIn(min=48.dp)) {Text("记录结果")}
        }
    }
}

@Composable fun MiniStat(label:String,number:String,unit:String,modifier:Modifier=Modifier) {
    Surface(modifier,shape=RoundedCornerShape(Space.Radius),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$number $unit",style=MaterialTheme.typography.titleLarge)
        }
    }
}
