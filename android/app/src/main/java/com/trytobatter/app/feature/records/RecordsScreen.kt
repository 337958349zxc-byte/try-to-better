package com.trytobatter.app.feature.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.trytobatter.app.R
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun RecordsScreen(state:AppSnapshot,onRecord:(String)->Unit,onData:()->Unit,onReview:()->Unit = {}) {
    var range by rememberSaveable {mutableStateOf(ReportPeriod.WEEK.name)}
    var filter by rememberSaveable {mutableStateOf("全部")}
    var expanded by rememberSaveable {mutableStateOf("execution")}
    LaunchedEffect(filter) {expanded=when(filter) {"学习"->"learning";"运动"->"workout";else->"execution"}}
    val today=LocalDate.now()
    val period=ReportPeriod.valueOf(range)
    fun date(millis:Long)=Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val records=state.records.filter {period.contains(date(it.endedAt),today)}.sortedByDescending {it.endedAt}
    val workouts=state.workouts.filter {period.contains(date(it.endedAt),today)}.sortedByDescending {it.endedAt}
    val tasks=state.tasks.filter {period.contains(it.day,today)}
    val learning=records.filter {it.category in setOf(Category.AI,Category.ENGLISH)}
    val execution=records.filter {it.category !in setOf(Category.AI,Category.ENGLISH)}
    ScenicPage(R.drawable.ttb_v3_records_header) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {PageHeader("记录","实际执行 · 计划与结果分别保存") {IconButton(onClick=onData) {Icon(Icons.Outlined.IosShare,"导出与备份")}}}
            item {OutlinedButton(onClick=onReview,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Icon(Icons.Outlined.Assessment,null);Spacer(Modifier.width(8.dp));Text("周报与14天复盘")}}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {ReportPeriod.entries.forEach {p->FilterChip(range==p.name,{range=p.name},label={Text(p.label)})}}}
            item {LifePanel {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    ProgressOrbit(tasks.count {it.status==TaskStatus.COMPLETED},tasks.size)
                    Column(Modifier.weight(1f)) {
                        Text("计划中的完整完成",style=MaterialTheme.typography.titleMedium)
                        Text(if(tasks.isEmpty()) "所选周期没有计划分母" else "${tasks.count {it.status==TaskStatus.COMPLETED}} / ${tasks.size}项",style=MaterialTheme.typography.bodyMedium,color=BatterColors.PrimaryDeep)
                        Text("部分完成与跳过按实际结果保留",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("执行 ${records.size} 条",style=MaterialTheme.typography.bodyMedium)
                    Text("实际 ${records.sumOf {it.seconds}/60} 分钟",style=MaterialTheme.typography.bodyMedium)
                    Text("训练 ${workouts.sumOf {it.sets.size}} 组",style=MaterialTheme.typography.bodyMedium)
                }
                Category.entries.filter {c->tasks.any {it.category==c}}.forEach {c->
                    val categoryTasks=tasks.filter {it.category==c};val done=categoryTasks.count {it.status==TaskStatus.COMPLETED}
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(c.icon(),null,Modifier.size(16.dp),tint=c.color())
                        Text(c.label,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        Text("$done/${categoryTasks.size}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    }
                }
            }}
            item {LifePanel {
                Text("实际执行用时",style=MaterialTheme.typography.titleMedium)
                val days=records.groupBy {date(it.endedAt)}.toSortedMap().entries.toList().takeLast(7)
                if(days.size<2) Text("${if(days.isEmpty()) "所选周期暂无记录" else "仅有1天记录"}，暂不绘制趋势。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                else {
                    val maximum=days.maxOf {it.value.sumOf {r->r.seconds}}.coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        days.forEach {(d,entries)->val seconds=entries.sumOf {it.seconds}
                            Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("${seconds/60}分",style=MaterialTheme.typography.bodySmall,color=BatterColors.PrimaryDeep)
                                Box(Modifier.fillMaxWidth().height((seconds.toFloat()/maximum*72).dp).clip(RoundedCornerShape(6.dp)).background(BatterColors.Primary.copy(alpha=.7f)))
                                Text("${d.monthValue}/${d.dayOfMonth}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                            }
                        }
                    }
                    Text("所选周期最近${days.size}个有记录日期 · 不含未记录的执行或健康估算",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                }
            }}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("全部","学习","运动").forEach {choice->FilterChip(filter==choice,{filter=choice},label={Text(choice)})}}}
            if(filter=="全部") item {
                FoldHeader("执行记录","${execution.size}条 · 点击展开与订正",expanded=="execution",{expanded=if(expanded=="execution") "" else "execution"},Icons.Outlined.TaskAlt,"records-execution")
                if(expanded=="execution") RecordDetails(execution,onRecord)
            }
            if(filter!="运动") item {
                FoldHeader("学习成果","${learning.size}条 · 实际成果与用时",expanded=="learning",{expanded=if(expanded=="learning") "" else "learning"},Icons.Outlined.MenuBook,"records-learning")
                if(expanded=="learning") RecordDetails(learning,onRecord)
            }
            if(filter!="学习") item {
                FoldHeader("运动记录","${workouts.size}次 · ${workouts.sumOf {it.sets.size}}组",expanded=="workout",{expanded=if(expanded=="workout") "" else "workout"},Icons.Outlined.FitnessCenter,"records-workout")
                if(expanded=="workout") Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(workouts.isEmpty()) Text("本周期还没有训练记录",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    workouts.forEach {workout->key(workout.id) {
                        var details by rememberSaveable(workout.id) {mutableStateOf(false)}
                        LifePanel {
                            Row(verticalAlignment=Alignment.CenterVertically) {Text(if("WH_FULL_BODY_V1_4" in workout.note) UnifiedTraining.title else "历史力量训练",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);if(workout.simulated) StateBadge("模拟识别演示",BatterColors.PrimaryDeep)}
                            Text(recordTime(workout.endedAt),style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                            Text("${workout.sets.size}组 · 自动 ${workout.sets.sumOf {it.autoReps}} 次 / 确认 ${workout.sets.sumOf {it.confirmedReps}} 次",style=MaterialTheme.typography.bodyMedium)
                            Text("本机姿态计数与手工订正 · ${if(workout.sets.any {it.quality=="无法判断"}) "动作质量未评分" else "质量反馈见逐组记录"}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                            TextButton(onClick={details=!details}) {Text(if(details) "收起训练详情" else "查看训练详情")}
                            if(details) {
                                workout.sets.forEach {set->
                                    Text("${set.exercise} · 第${set.setIndex}组",style=MaterialTheme.typography.titleSmall)
                                    Text("自动 ${set.autoReps} 次 / 确认 ${set.confirmedReps} 次 · ${if(set.quality=="无法判断") "动作质量未评分" else set.quality}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                                    if(set.note.isNotBlank()) Text(set.note,style=MaterialTheme.typography.bodySmall)
                                }
                                if(workout.note.isNotBlank()) Text(workout.note,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }}
                }
            }
        }
    }
}
@Composable private fun RecordDetails(records:List<ExecutionRecord>,onRecord:(String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(records.isEmpty()) Text("还没有执行记录",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
        records.forEach {record->key(record.id) {
            var details by rememberSaveable(record.id) {mutableStateOf(false)}
            LifePanel(onClick={onRecord(record.id)}) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    CategoryIcon(record.category)
                    Column(Modifier.weight(1f)) {Text(record.title,style=MaterialTheme.typography.titleSmall);Text(recordTime(record.endedAt),style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
                    Icon(Icons.Outlined.ChevronRight,"查看与订正记录")
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {StateBadge(record.outcome.label,record.outcome.color());StateBadge("实际 ${formatDuration(record.seconds)}")}
                if(record.correctedFrom.isNotBlank()) Text("已订正 · 原记录痕迹保留",style=MaterialTheme.typography.bodySmall,color=BatterColors.PrimaryDeep)
                if(record.result.isNotBlank() || record.snapshot.isNotBlank()) {
                    TextButton(onClick={details=!details}) {Text(if(details) "收起成果" else "查看成果")}
                    if(details) {if(record.result.isNotBlank()) Text(record.result,style=MaterialTheme.typography.bodyMedium);if(record.snapshot.isNotBlank()) Text("执行时计划快照：${record.snapshot}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
                }
            }
        }}
    }
}
fun recordTime(millis:Long):String=Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
