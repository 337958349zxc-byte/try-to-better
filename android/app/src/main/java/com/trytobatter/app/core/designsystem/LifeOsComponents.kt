package com.trytobatter.app.core.designsystem

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.model.*

@Composable fun ScenicPage(image:Int,content:@Composable BoxScope.()->Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Image(painterResource(image),null,Modifier.fillMaxWidth().height(260.dp),contentScale=ContentScale.Crop,alignment=Alignment.TopCenter)
        Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.White.copy(alpha=.06f),Color.Transparent,BatterColors.Background))))
        content()
    }
}
@Composable fun LifePanel(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier=modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier),
        color=MaterialTheme.colorScheme.surface.copy(alpha=.95f),shape=RoundedCornerShape(20.dp),border=BorderStroke(.7.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=1.dp) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
@Composable fun ProgressOrbit(done:Int,total:Int,modifier:Modifier=Modifier) {
    val value=if(total>0) done.toFloat()/total else 0f
    val diameter=72.dp*LocalDensity.current.fontScale.coerceIn(1f,1.5f)
    Box(modifier.size(diameter).semantics(mergeDescendants=true) {contentDescription=if(total>0) "完整完成 $done 项，共 $total 项" else "暂无安排"},contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            drawArc(BatterColors.Primary.copy(alpha=.14f),-90f,360f,false,style=Stroke(7.dp.toPx(),cap=StrokeCap.Round))
            if(total>0 && done>0) drawArc(BatterColors.Success,-90f,360*value.coerceIn(0f,1f),false,style=Stroke(7.dp.toPx(),cap=StrokeCap.Round))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text("$done/$total",style=MaterialTheme.typography.titleMedium,color=BatterColors.PrimaryDeep)
            Text("完整完成",style=MaterialTheme.typography.labelMedium,color=BatterColors.Secondary)
        }
    }
}
fun DayPeriod.icon():ImageVector=when(this) {DayPeriod.MORNING->Icons.Outlined.WbSunny;DayPeriod.AFTERNOON->Icons.Outlined.LightMode;DayPeriod.EVENING->Icons.Outlined.DarkMode}
@Composable fun FoldHeader(title:String,summary:String,expanded:Boolean,onClick:()->Unit,icon:ImageVector=Icons.Outlined.FolderOpen,tag:String="") {
    Surface(color=if(expanded) BatterColors.LightBlue.copy(alpha=.85f) else MaterialTheme.colorScheme.surface.copy(alpha=.9f),shape=RoundedCornerShape(16.dp),
        modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick=onClick).testTag(tag).semantics {stateDescription=if(expanded) "已展开" else "已收起"}) {
        Row(Modifier.heightIn(min=58.dp).padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(icon,null,Modifier.size(22.dp),tint=BatterColors.PrimaryDeep)
            Column(Modifier.weight(1f)) {Text(title,style=MaterialTheme.typography.titleSmall);Text(summary,style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
            Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,tint=BatterColors.Secondary)
        }
    }
}
@Composable fun TimelineTaskLine(task:TaskItem,overlaps:Boolean,alarm:AlarmSnapshot,onAlarm:()->Unit,onClick:()->Unit,first:Boolean=false,last:Boolean=false) {
    val bad=alarm.records[task.id]?.state in setOf(AlarmState.NEEDS_PERMISSION,AlarmState.SCHEDULE_ERROR)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min=56.dp).clip(RoundedCornerShape(12.dp))
        .background(if(task.status==TaskStatus.RUNNING) BatterColors.LightBlue else Color.Transparent).clickable(onClick=onClick).padding(horizontal=4.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(42.dp*LocalDensity.current.fontScale.coerceIn(1f,1.6f))) {
            Text(task.start.format(ClockFormat),style=MaterialTheme.typography.bodySmall)
            Text("${task.durationMinutes}分",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
        }
        Canvas(Modifier.width(12.dp).fillMaxHeight()) {
            val x=size.width/2
            drawLine(BatterColors.Border,androidx.compose.ui.geometry.Offset(x,if(first) size.height/2 else 0f),androidx.compose.ui.geometry.Offset(x,if(last) size.height/2 else size.height),strokeWidth=2.dp.toPx())
            drawCircle(task.category.color(),4.dp.toPx())
        }
        Icon(task.category.icon(),task.category.label,Modifier.size(18.dp),tint=task.category.color())
        Column(Modifier.weight(1f).padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(task.title,style=MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(task.status.label,"固定".takeIf {task.fixed},"重复".takeIf {task.seriesId!=null},"时间冲突".takeIf {overlaps},"闹钟${alarm.records[task.id]?.state?.label}".takeIf {bad}).joinToString(" · "),
                style=MaterialTheme.typography.bodySmall,color=if(bad) BatterColors.Error else if(overlaps) BatterColors.Warning else task.status.color())
        }
        AlarmButton(alarm.settings[task.id],alarm.records[task.id],onAlarm)
    }
}
