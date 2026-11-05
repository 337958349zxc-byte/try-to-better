package com.trytobatter.app.core.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Bounded schedules are fully materialized, using the existing task/sync schema. */
data class RepeatSchedule(val mode:String, val interval:Int, val weekdays:Set<Int>, val until:LocalDate) {
    fun encode()="R1|$mode|$interval|${weekdays.sorted().joinToString(",")}|$until"
}

object RepeatRules {
    fun parse(rule:String):RepeatSchedule? = runCatching {
        val p=rule.split('|')
        require(p.size==5 && p[0]=="R1" && p[1] in setOf("D","W"))
        RepeatSchedule(p[1],p[2].toInt(),p[3].split(',').filter {it.isNotBlank()}.map {it.toInt()}.toSet(),LocalDate.parse(p[4])).also {
            require(it.interval in 1..12 && it.weekdays.all {d->d in 1..7} && (it.mode=="D" || it.weekdays.isNotEmpty()))
        }
    }.getOrNull()

    fun label(rule:String):String = parse(rule)?.let {r ->
        val frequency=if(r.mode=="D") (if(r.interval==1) "每天" else "每${r.interval}天")
            else (if(r.interval==1) "每周" else "每${r.interval}周")+r.weekdays.sorted().joinToString("、") {"一二三四五六日"[it-1].toString()}
        "$frequency · 至${r.until}"
    } ?: when(rule) {"每天"->"每天 · 原7天安排";"每周"->"每周 · 原4次安排";""->"不重复";else->"重复安排"}

    fun dates(rule:String,start:LocalDate):List<LocalDate> {
        if(rule.isBlank()) return listOf(start)
        if(rule=="每天") return (0..6).map {start.plusDays(it.toLong())}
        if(rule=="每周") return (0..3).map {start.plusWeeks(it.toLong())}
        val r=parse(rule) ?: error("重复规则无效，请检查间隔和星期")
        require(r.until>=start && r.until<=start.plusYears(1)) {"截止日期需在开始日期至一年内"}
        require(r.mode=="D" || start.dayOfWeek.value in r.weekdays) {"开始日期需属于选中的星期"}
        val monday=start.minusDays((start.dayOfWeek.value-1).toLong())
        return (0..ChronoUnit.DAYS.between(start,r.until).toInt()).map {start.plusDays(it.toLong())}.filter {day ->
            if(r.mode=="D") ChronoUnit.DAYS.between(start,day)%r.interval==0L
            else day.dayOfWeek.value in r.weekdays && ChronoUnit.WEEKS.between(monday,day.minusDays((day.dayOfWeek.value-1).toLong()))%r.interval==0L
        }
    }

    fun editable(t:TaskItem)=t.status==TaskStatus.PENDING && !t.isProtected && t.accumulatedSeconds==0L && t.executionSnapshot.isBlank()

    private fun generate(task:TaskItem,series:String?,excluded:Set<LocalDate> = emptySet()):List<TaskItem> =
        dates(task.repeatRule,task.day).filterNot {it!=task.day && it in excluded}.map {date ->
            task.copy(id=if(date==task.day) task.id else UUID.nameUUIDFromBytes("ttb-repeat|$series|$date".toByteArray(Charsets.UTF_8)).toString(),
                seriesId=series,scheduledAt=date.atTime(task.start.toLocalTime()).toString(),revision=if(date==task.day) task.revision else 1)
        }

    fun save(tasks:List<TaskItem>,task:TaskItem,following:Boolean):List<TaskItem> {
        require(task.title.isNotBlank() && task.durationMinutes in 1..1440) {"请填写名称与有效时长"}
        task.start
        val old=tasks.find {it.id==task.id}
        val edited=task.copy(seriesId=old?.seriesId?:task.seriesId,revision=(old?.revision?:0)+1)
        if(old==null || old.seriesId==null && old.repeatRule!=task.repeatRule) {
            require(old==null || editable(old)) {"已开始事项请保留本次安排，从未开始事项建立重复规则"}
            return tasks.filterNot {it.id==task.id}+generate(edited,if(task.repeatRule.isBlank()) null else newId())
        }
        val ruleChanged=old.repeatRule!=task.repeatRule
        if(ruleChanged && old.seriesId!=null) {
            require(following) {"修改重复规则请选择本次及以后"}
            require(editable(old)) {"已开始事项的重复规则受保护，请选择未开始的事项"}
            val removed=tasks.filter {it.seriesId==old.seriesId && it.start>=old.start && editable(it)}.map {it.id}.toSet()
            val kept=tasks.filterNot {it.id in removed}
            val protectedDates=kept.filter {it.seriesId==old.seriesId && !editable(it)}.map {it.day}.toSet()
            return kept+generate(edited.copy(seriesId=null),if(task.repeatRule.isBlank()) null else newId(),protectedDates)
        }
        val delta=ChronoUnit.DAYS.between(old.day,task.day)
        val shiftedRule=if(following) parse(task.repeatRule)?.let {r ->r.copy(
            weekdays=r.weekdays.map {Math.floorMod(it-1+delta.toInt(),7)+1}.toSet(),until=r.until.plusDays(delta)).encode()} ?: task.repeatRule else task.repeatRule
        return tasks.map {current -> when {
            current.id==task.id -> edited.copy(repeatRule=shiftedRule)
            following && old.seriesId!=null && current.seriesId==old.seriesId && current.start>=old.start && editable(current) ->
                current.copy(title=task.title,category=task.category,scheduledAt=current.day.plusDays(delta).atTime(task.start.toLocalTime()).toString(),
                    durationMinutes=task.durationMinutes,steps=task.steps,resourceUrl=task.resourceUrl,criteria=task.criteria,
                    fixed=task.fixed,note=task.note,repeatRule=shiftedRule,revision=current.revision+1)
            else -> current
        } }
    }
}
