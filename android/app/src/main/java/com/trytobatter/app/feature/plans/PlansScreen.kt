package com.trytobatter.app.feature.plans

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.trytobatter.app.R
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import java.time.LocalDate
import java.time.LocalDateTime

@Composable fun PlansScreen(state:AppSnapshot,onCategory:(Category)->Unit,onUpdates:()->Unit,onSync:()->Unit,onWorkout:()->Unit,onRecipes:()->Unit={},onTask:(String)->Unit={}) {
    var range by rememberSaveable {mutableStateOf(ReportPeriod.WEEK.name)}
    var expanded by rememberSaveable {mutableStateOf("")}
    val period=ReportPeriod.valueOf(range)
    val today=LocalDate.now()
    val tasks=state.tasks.filter {period.contains(it.day,today)}
    val done=tasks.count {it.status==TaskStatus.COMPLETED}
    ScenicPage(R.drawable.ttb_v3_plans_header) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {PageHeader("计划","计划 v${state.planVersion} · ${state.tasks.size}项安排")}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(ReportPeriod.WEEK,ReportPeriod.MONTH,ReportPeriod.ALL).forEach {p->FilterChip(range==p.name,{range=p.name},label={Text(when(p) {ReportPeriod.WEEK->"本周";ReportPeriod.MONTH->"本月";else->"全部"})})}
            }}
            item {LifePanel {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    ProgressOrbit(done,tasks.size)
                    Column(Modifier.weight(1f)) {
                        Text("${when(period) {ReportPeriod.WEEK->"本周";ReportPeriod.MONTH->"本月";else->"全部"}}总体进度",style=MaterialTheme.typography.titleMedium)
                        Text(if(tasks.isEmpty()) "暂无可统计的完成数据" else "完整完成 $done / ${tasks.size} 项 · ${done*100/tasks.size}%",style=MaterialTheme.typography.bodyMedium,color=BatterColors.PrimaryDeep)
                        Text("部分完成和跳过保留在执行记录中",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    }
                }
            }}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=onUpdates) {Icon(Icons.Outlined.Update,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("查看版本与更新")}
                TextButton(onClick=onSync) {Icon(Icons.Outlined.Computer,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("电脑连接")}
            }}
            if(state.conflictCount>0) item {Notice("${state.conflictCount}项更新待确认，手机内容已保留","处理",true,onUpdates)}
            if(state.pendingSyncCount>0 || state.connection.error.isNotBlank()) item {Notice("${state.pendingSyncCount}项待同步${state.connection.error.takeIf {it.isNotBlank()}?.let {" · $it"}.orEmpty()}","查看",state.connection.error.isNotBlank(),onSync)}
            item {SectionTitle("我的计划")}
            items(Category.entries,key={it.name}) {category->
                val categoryTasks=tasks.filter {it.category==category}
                val complete=categoryTasks.count {it.status==TaskStatus.COMPLETED}
                val next=nextPlanned(categoryTasks,LocalDateTime.now())
                LifePanel(onClick={expanded=if(expanded==category.name) "" else category.name}) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        CategoryIcon(category,Modifier.size(40.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(category.label,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                                Text("$complete/${categoryTasks.size} 完成",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                            }
                            if(categoryTasks.isNotEmpty()) LinearProgressIndicator(progress={complete.toFloat()/categoryTasks.size},modifier=Modifier.fillMaxWidth().height(4.dp),color=category.color(),trackColor=category.tint(),gapSize=0.dp,drawStopIndicator={})
                            Text(next?.let {"下一步 · ${it.title}"} ?: if(categoryTasks.isEmpty()) "所选周期暂无安排" else "暂无待开始事项",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        Icon(if(expanded==category.name) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,tint=category.color())
                    }
                    if(category==Category.MEAL) TextButton(onClick=onRecipes,modifier=Modifier.heightIn(min=48.dp)) {Icon(Icons.Outlined.Restaurant,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("打开双人菜谱")}
                    if(expanded==category.name) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        if(next!=null) Text(next.title,style=MaterialTheme.typography.bodyMedium)
                        next?.let {task->TextButton(onClick={onTask(task.id)}) {Text("查看下一步详情")}}
                        TextButton(onClick={onCategory(category)}) {Text("查看分类全部安排")}
                        if(category==Category.WORKOUT) Button(onClick=onWorkout,modifier=Modifier.heightIn(min=48.dp)) {Text("进入力量跟练")}
                    }
                }
            }
        }
    }
}
