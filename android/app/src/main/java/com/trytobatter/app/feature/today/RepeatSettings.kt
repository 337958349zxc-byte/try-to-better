package com.trytobatter.app.feature.today

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.designsystem.BatterColors
import com.trytobatter.app.core.model.*
import java.time.LocalDate

@Composable fun RepeatSettings(rule:String,start:LocalDate,locked:Boolean,showPreview:Boolean=true,onChange:(String)->Unit) {
    val context=LocalContext.current
    val parts=rule.split('|')
    val custom=parts.size==5 && parts[0]=="R1"
    val mode=if(custom) parts[1] else ""
    val interval=if(custom) parts[2] else "1"
    val days=if(custom) parts[3].split(',').filter {it.isNotBlank()}.mapNotNull {it.toIntOrNull()}.toSet() else setOf(start.dayOfWeek.value)
    val end=if(custom) parts[4] else start.plusMonths(3).toString()
    fun change(m:String=mode,i:String=interval,d:Set<Int> = days,e:String=end) {onChange("R1|$m|$i|${d.sorted().joinToString(",")}|$e")}
    Text("重复安排",style=MaterialTheme.typography.titleSmall)
    if(locked) Text(RepeatRules.label(rule)+"\n本次已有执行信息，请从未开始的事项修改重复规则。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
    else {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(rule.isBlank(),{onChange("")},label={Text("不重复")},modifier=Modifier.heightIn(min=48.dp))
            FilterChip(mode=="D",{change(m="D")},label={Text("按天重复")},modifier=Modifier.heightIn(min=48.dp))
            FilterChip(mode=="W",{change(m="W")},label={Text("每周选日")},modifier=Modifier.heightIn(min=48.dp))
        }
        if(rule in setOf("每天","每周")) Text("当前为${RepeatRules.label(rule)}，可选择上方方式延长安排。",style=MaterialTheme.typography.bodySmall)
        if(custom) {
            OutlinedTextField(interval,{change(i=it.filter(Char::isDigit).take(2))},Modifier.fillMaxWidth(),
                label={Text(if(mode=="D") "每隔几天（1–12）" else "每隔几周（1–12）")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,
                isError=interval.toIntOrNull()?.let {it in 1..12}!=true)
            if(mode=="W") FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                (1..7).forEach {day->FilterChip(day in days,{change(d=if(day in days) days-day else days+day)},
                    label={Text("周${"一二三四五六日"[day-1]}")},modifier=Modifier.heightIn(min=48.dp))}
            }
            OutlinedButton(onClick={
                val current=runCatching {LocalDate.parse(end)}.getOrDefault(start.plusMonths(3))
                DatePickerDialog(context,{_,y,m,d->change(e=LocalDate.of(y,m+1,d).toString())},current.year,current.monthValue-1,current.dayOfMonth).show()
            },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("截止日期：$end")}
            if(showPreview) {
                val preview=runCatching {RepeatRules.dates(rule,start)}
                Text(preview.fold({"共${it.size}次 · 最后一次 ${it.last()}\n截止日期内全部保存到手机，最长一年。"},
                    {it.message?:"请检查重复规则"}),style=MaterialTheme.typography.bodySmall,
                    color=if(preview.isFailure) MaterialTheme.colorScheme.error else BatterColors.Secondary)
            } else Text("已保存的重复安排。改期可选择仅本次，或整体顺延本次及以后未执行的安排。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
        }
    }
}
