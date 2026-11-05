package com.trytobatter.app.feature.calendar

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

@Composable fun ScheduleTimeMap(tasks:List<TaskItem>,allTasks:List<TaskItem>,alarms:AlarmSnapshot,zoomed:Boolean,onTask:(String)->Unit,onAlarm:(String)->Unit) {
    if(tasks.isEmpty()) return
    val scale=if(zoomed) 1.3f else .9f
    val font=LocalDensity.current.fontScale
    val minHeight=100f*font.coerceIn(1f,2f)
    val minMinutes=ceil(minHeight/scale).toLong()
    val placements=calendarPlacements(tasks,minMinutes)
    val columns=placements.maxOf {it.columns}
    val midnight=tasks.first().day.atStartOfDay()
    fun minute(task:TaskItem)=ChronoUnit.MINUTES.between(midnight,task.start).toInt()
    val first=tasks.minOf {minute(it)}/60*60
    val last=ceil(tasks.maxOf {minute(it)+maxOf(it.durationMinutes.toLong(),minMinutes)} /60f).toInt()*60
    val axis=(48f*font.coerceIn(1f,1.6f)).dp
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val colWidth=maxOf((maxWidth-axis-8.dp)/columns,144.dp*font.coerceIn(1f,2f))
            val contentWidth=maxOf(maxWidth,axis+(colWidth+4.dp)*columns)
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Box(Modifier.width(contentWidth).height(((last-first)*scale+12).dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        for(m in first..last step 60) {
                            val y=((m-first)*scale).dp.toPx()
                            drawLine(BatterColors.Border,Offset(axis.toPx(),y),Offset(size.width,y),1.dp.toPx())
                        }
                    }
                    for(m in first..last step 60) Text(if(m>=1440) "次日%02d:00".format(m/60%24) else "%02d:00".format(m/60),
                        Modifier.offset(y=((m-first)*scale).dp).width(axis),style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                    placements.forEach {entry->
                        val task=entry.task
                        val conflict=TaskRules.conflicts(task,allTasks).isNotEmpty()
                        val height=maxOf(task.durationMinutes*scale,minHeight).dp
                        Surface(color=task.category.tint(),shape=RoundedCornerShape(12.dp),border=BorderStroke(.7.dp,task.category.color().copy(alpha=.24f)),
                            modifier=Modifier.offset(x=axis+(colWidth+4.dp)*entry.column,y=((minute(task)-first)*scale).dp).width(colWidth).height(height)
                                .clip(RoundedCornerShape(12.dp)).clickable {onTask(task.id)}.semantics {contentDescription="${task.timeLabel} ${task.title} ${task.status.label}${if(conflict) " 时间冲突" else ""}"}) {
                            Row(Modifier.fillMaxSize().drawBehind {
                                val barX=2.dp.toPx()
                                drawLine(task.category.color(),Offset(barX,0f),Offset(barX,(task.durationMinutes*scale).dp.toPx()),4.dp.toPx())
                            }.padding(start=8.dp,end=0.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                    Text("${task.start.format(ClockFormat)} · ${task.durationMinutes}分",style=MaterialTheme.typography.bodySmall,color=BatterColors.Text)
                                    Text(task.title,style=MaterialTheme.typography.titleSmall,maxLines=if(zoomed) 3 else 2,overflow=TextOverflow.Ellipsis)
                                    Text(task.status.label+if(conflict) " · 时间冲突" else "",style=MaterialTheme.typography.bodySmall,color=if(conflict) BatterColors.Warning else task.status.color())
                                }
                                AlarmButton(alarms.settings[task.id],alarms.records[task.id]) {onAlarm(task.id)}
                            }
                        }
                    }
                }
            }
        }
        if(tasks.any {it.durationMinutes*scale<minHeight}) Text("左侧色条表示真实时长，短事项信息区已放大。点按查看详情，横向滑动查看并列安排。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
    }
}
