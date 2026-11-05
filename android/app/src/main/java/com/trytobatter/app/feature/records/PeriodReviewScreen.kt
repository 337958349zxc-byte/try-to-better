package com.trytobatter.app.feature.records

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

@Composable fun PeriodReviewScreen(state:AppSnapshot,onBack:()->Unit,onRecord:(String)->Unit,onNotify:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val today=LocalDate.now()
    var days by rememberSaveable {mutableIntStateOf(7)}
    var startText by rememberSaveable {mutableStateOf(today.minusDays((today.dayOfWeek.value-1).toLong()).toString())}
    val start=LocalDate.parse(startText)
    val end=start.plusDays(days-1L)
    val review=remember(state,start,end) {PeriodReview.build(state,start,end)}
    val exportFile=java.io.File(context.cacheDir,"pending-period-review.md")
    var preparingExport by remember {mutableStateOf(false)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {uri->
        if(uri!=null) scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                check(exportFile.exists()) {"导出暂存已失效，请重新导出"}
                context.contentResolver.openOutputStream(uri)?.use {it.write(exportFile.readBytes())} ?: error("无法打开导出位置")
            }}.onSuccess {onNotify("周期复盘已导出到所选位置")}.onFailure {onNotify("导出失败：${it.message}")}
        }
    }
    DetailScaffold("周报与14天复盘",onBack,actions={
        IconButton(enabled=!preparingExport,onClick={scope.launch {
            preparingExport=true
            runCatching {
                withContext(Dispatchers.IO) {exportFile.writeText(review.markdown(today),Charsets.UTF_8)}
                export.launch("try-to-better-review-$start-$end.md")
            }.onFailure {onNotify("准备导出失败：${it.message}")}
            preparingExport=false
        }}) {Icon(Icons.Outlined.FileDownload,"导出本周期复盘")}
    }) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(Space.Page),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {LifePanel {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(days==7,{days=7;startText=today.minusDays((today.dayOfWeek.value-1).toLong()).toString()},label={Text("周报")},modifier=Modifier.heightIn(min=48.dp))
                    FilterChip(days==14,{days=14;startText=today.minusDays(13).toString()},label={Text("14天复盘")},modifier=Modifier.heightIn(min=48.dp))
                }
                OutlinedButton(onClick={DatePickerDialog(context,{_,y,m,d->startText=LocalDate.of(y,m+1,d).toString()},start.year,start.monthValue-1,start.dayOfMonth).show()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("开始日期：$start")}
                Text("$start 至 $end · 含首尾",style=MaterialTheme.typography.titleSmall)
                Text(if(today<start) "周期尚未开始" else if(today<=end) "周期进行中 · 未来安排保留待开始" else "周期已结束",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                Text("本机保存内容 · 待同步${review.pendingSync}项 · 待确认${review.conflicts}项",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={startText=start.minusDays(days.toLong()).toString()},modifier=Modifier.heightIn(min=48.dp)) {Text("上一周期")}
                    TextButton(onClick={startText=start.plusDays(days.toLong()).toString()},modifier=Modifier.heightIn(min=48.dp)) {Text("下一周期")}
                }
            }}
            item {LifePanel {
                Text("计划与实际",style=MaterialTheme.typography.titleMedium)
                Text("当前计划 ${review.tasks.size}项 · 预算 ${review.plannedMinutes}分钟")
                Text("完整 ${review.count(TaskStatus.COMPLETED)} · 部分 ${review.count(TaskStatus.PARTIAL)} · 跳过 ${review.count(TaskStatus.SKIPPED)}")
                Text("进行中 ${review.count(TaskStatus.RUNNING)} · 暂停 ${review.count(TaskStatus.PAUSED)} · 待开始 ${review.count(TaskStatus.PENDING)}")
                Text("${review.missingResults}项尚无关联结果记录（含未来安排）",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                HorizontalDivider()
                Text(if(review.records.isEmpty()) "本周期暂无执行结果，实际情况未知" else "周期内结束 ${review.records.size}条 · 实际 ${formatDuration(review.actualSeconds)}")
                if(review.comparedTasks.isNotEmpty()) {
                    Text("已有结果的${review.comparedTasks.size}项计划",style=MaterialTheme.typography.titleSmall)
                    Text("当前预算 ${review.comparedBudgetMinutes}分钟 · 对应实际 ${formatDuration(review.comparedSeconds)}")
                    val diff=review.comparedSeconds-review.comparedBudgetMinutes*60
                    Text("实际${if(diff>=0) "多于" else "少于"}当前预算 ${formatDuration(kotlin.math.abs(diff))}",style=MaterialTheme.typography.bodySmall)
                }
                Text("计划按安排日期，实际按结束日期；对应计划的结果可跨期。预算取当前值，原执行快照随记录保留。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
            }}
            item {SectionTitle("分类汇总")}
            items(Category.entries.toList(),key={"category-${it.name}"}) {category->
                val planned=review.tasks.filter {it.category==category}
                val records=review.records.filter {it.category==category}
                LifePanel {
                    Text(category.label,style=MaterialTheme.typography.titleSmall)
                    Text("计划 ${planned.size}项 / ${planned.sumOf {it.durationMinutes}}分钟")
                    Text(if(records.isEmpty()) "周期内暂无结果记录" else "周期内结果 ${records.size}条 / 实际 ${formatDuration(records.sumOf {it.seconds})}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                }
            }
            item {SectionTitle("学习成果与执行原因")}
            item {Text("${review.learning.size}条学习结果 · ${review.records.size}条周期执行结果",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
            if(review.records.isEmpty()) item {Text("暂无成果或原因记录",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
            items(review.records.sortedByDescending {it.endedAt},key={"record-${it.id}"}) {record->
                LifePanel(onClick={onRecord(record.id)}) {
                    Text(record.title,style=MaterialTheme.typography.titleSmall)
                    Text("${record.outcome.label} · ${recordTime(record.endedAt)} · ${formatDuration(record.seconds)}",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    Text(record.result.ifBlank {"成果或原因未填写"},style=MaterialTheme.typography.bodyMedium)
                    if(record.correctedFrom.isNotBlank()) Text("有订正痕迹 · 点按查看",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                }
            }
            item {SectionTitle("训练组次与恢复")}
            if(review.workouts.isEmpty()) item {Text("暂无实际训练记录",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
            items(review.workouts.sortedBy {it.endedAt},key={"workout-${it.id}"}) {workout->
                LifePanel {
                    Text(recordTime(workout.endedAt),style=MaterialTheme.typography.titleSmall)
                    Text("${workout.sets.size}组 · 训练计时 ${formatDuration(workout.seconds)}")
                    workout.sets.groupBy {it.exercise}.forEach {(exercise,sets)->
                        Text("$exercise · ${sets.size}组 · 确认 ${sets.sumOf {it.confirmedReps}}次",style=MaterialTheme.typography.bodySmall)
                    }
                    val metadata=remember(workout.note) {runCatching {JSONObject(workout.note)}.getOrNull()}
                    Text("RPE：${metadata?.optString("rpe")?.takeIf {it.isNotBlank() && it!="null"}?:"未记录"}",style=MaterialTheme.typography.bodySmall)
                    Text("不适：${metadata?.optString("discomfort")?.takeIf {it.isNotBlank()}?:"未记录"}",style=MaterialTheme.typography.bodySmall)
                    Text("恢复：${metadata?.optString("recovery")?.takeIf {it.isNotBlank()}?:"未记录"}",style=MaterialTheme.typography.bodySmall)
                    Text("每侧动作按原记录合计；逐组左右、自动次数和备注随报告导出。训练计时单列，未重复加入执行总时长。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                }
            }
            item {LifePanel {
                Text("统计说明",style=MaterialTheme.typography.titleSmall)
                Text("未记录保留未知；已排除演示事项、演示执行和模拟训练。周期复盘由实际保存的数据生成。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                Text("右上角可导出复盘文件，补充自己的卡点和下周期调整，再交给顾问讨论。",style=MaterialTheme.typography.bodySmall)
            }}
        }
    }
}
