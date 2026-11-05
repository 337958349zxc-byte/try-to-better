package com.trytobatter.app.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trytobatter.app.R
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import com.trytobatter.app.core.alarm.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable fun CalendarScreen(state:AppSnapshot,onTask:(String)->Unit,onAdd:(String)->Unit,alarms:AlarmSnapshot=AlarmSnapshot(),onAlarm:(String)->Unit={}) {
    val now by produceState(LocalDateTime.now()) {while(true) {value=LocalDateTime.now();delay(30_000)}}
    val today=now.toLocalDate()
    var selected by rememberSaveable {mutableStateOf(today.toString())}
    var showPast by rememberSaveable {mutableStateOf(false)}
    var summary by rememberSaveable {mutableStateOf(false)}
    var zoomed by rememberSaveable {mutableStateOf(false)}
    var period by rememberSaveable(selected) {mutableStateOf(DayPeriod.at(now.hour).name)}
    val day=LocalDate.parse(selected)
    val weekStart=day.with(DayOfWeek.MONDAY)
    LaunchedEffect(today,showPast) {if(!showPast && LocalDate.parse(selected).isBefore(today)) selected=today.toString()}
    fun visible(task:TaskItem)=showPast || task.end.isAfter(now) || task.status in setOf(TaskStatus.RUNNING,TaskStatus.PAUSED)
    val all=state.tasks.filter {it.day==day}.sortedBy {it.start}
    val visible=all.filter(::visible)
    val tasks=visible.filter {period=="ALL" || it.period().name==period}
    val dates=(0..6).map {weekStart.plusDays(it.toLong())}.filter {showPast || !it.isBefore(today)}
    ScenicPage(R.drawable.ttb_v3_calendar_header) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {PageHeader("日历","时间地图 · ${day.monthValue}月${day.dayOfMonth}日") {IconButton(onClick={onAdd(selected)}) {Icon(Icons.Outlined.Add,"新增事项",tint=BatterColors.PrimaryDeep)}}}
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(day.format(DateTimeFormatter.ofPattern("yyyy年 M月")),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={selected=today.toString();showPast=false}) {Text("今天")}
                IconButton(onClick={val d=day.minusWeeks(1);if(d.isBefore(today)) showPast=true;selected=d.toString()}) {Icon(Icons.Outlined.ChevronLeft,"上一周")}
                IconButton(onClick={selected=day.plusWeeks(1).toString()}) {Icon(Icons.Outlined.ChevronRight,"下一周")}
            }}
            item {LazyRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {items(dates,key={it.toString()}) {date->
                Surface(Modifier.width(48.dp).clickable {selected=date.toString()},shape=RoundedCornerShape(14.dp),color=if(date==day) BatterColors.Primary else Color.White.copy(alpha=.65f),contentColor=if(date==day) Color.White else BatterColors.Text) {
                    Column(Modifier.heightIn(min=60.dp).padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(listOf("一","二","三","四","五","六","日")[date.dayOfWeek.value-1],style=MaterialTheme.typography.bodySmall)
                        Text("${date.dayOfMonth}",style=MaterialTheme.typography.titleMedium)
                        Text(if(state.tasks.any {it.day==date}) "•" else " ",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }}}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={showPast=!showPast;if(!showPast && day.isBefore(today)) selected=today.toString()}) {Text(if(showPast) "收起已过安排" else "查看已过安排")}
                TextButton(onClick={summary=!summary}) {Text(if(summary) "时间地图" else "当天摘要")}
                if(!summary) TextButton(onClick={zoomed=!zoomed}) {Text(if(zoomed) "紧凑视图" else "放大时间轴")}
            }}
            item {LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                item {FilterChip(period=="ALL",{period="ALL"},label={Text("全天")})}
                items(DayPeriod.entries) {p->FilterChip(period==p.name,{period=p.name},label={Text(p.label)})}
            }}
            item {Text("${visible.size}项可见 · 完整完成${visible.count {it.status==TaskStatus.COMPLETED}}项${if(all.size>visible.size) " · 已收起${all.size-visible.size}项过往安排" else ""}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
            if(tasks.isEmpty()) item {EmptyState("这个时段留白了",if(all.size>visible.size) "过往安排已收起，可查看历史或切换其他时段。" else "切换时段、查看全天或添加事项。",action="添加事项",onAction={onAdd(selected)})}
            if(summary) items(tasks,key={it.id}) {t->TaskRow(t,TaskRules.conflicts(t,state.tasks).isNotEmpty(),alarm={AlarmButton(alarms.settings[t.id],alarms.records[t.id]) {onAlarm(t.id)}}) {onTask(t.id)}}
            else if(tasks.isNotEmpty()) item {ScheduleTimeMap(tasks,state.tasks,alarms,zoomed,onTask,onAlarm)}
            item {Text("点按任务进入详情、编辑或改期；固定、重复事项及冲突规则保持原样。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
        }
    }
}
