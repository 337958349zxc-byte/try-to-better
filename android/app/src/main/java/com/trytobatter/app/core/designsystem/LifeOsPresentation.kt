package com.trytobatter.app.core.designsystem

import com.trytobatter.app.core.model.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Presentation-only grouping. Never changes task dates, state or persistence. */
enum class DayPeriod(val label:String) {
    MORNING("上午"), AFTERNOON("下午"), EVENING("晚上");
    companion object {
        fun at(hour:Int)=when {hour<12->MORNING;hour<18->AFTERNOON;else->EVENING}
    }
}
fun TaskItem.period()=DayPeriod.at(start.hour)
fun nextPlanned(tasks:List<TaskItem>,now:LocalDateTime,excluding:String?=null):TaskItem? {
    val candidates=tasks.filter {it.id!=excluding && !it.isFinished && it.status==TaskStatus.PENDING}.sortedBy {it.start}
    return candidates.firstOrNull {it.start>=now} ?: candidates.firstOrNull()
}
enum class ReportPeriod(val label:String) { DAY("日"), WEEK("周"), MONTH("月"), ALL("全部") }
fun ReportPeriod.contains(date:LocalDate,today:LocalDate):Boolean=when(this) {
    ReportPeriod.DAY->date==today
    ReportPeriod.WEEK->date>=today.with(DayOfWeek.MONDAY) && date<=today.with(DayOfWeek.SUNDAY)
    ReportPeriod.MONTH->date.year==today.year && date.month==today.month
    ReportPeriod.ALL->true
}

data class MapPlacement(val task:TaskItem,val column:Int,val columns:Int)
/** Transitive overlapping groups share a column grid, so no task covers another. */
fun calendarPlacements(tasks:List<TaskItem>,minimumVisualMinutes:Long=0):List<MapPlacement> {
    val result=mutableListOf<MapPlacement>()
    val sorted=tasks.sortedWith(compareBy<TaskItem> {it.start}.thenBy {it.end}.thenBy {it.id})
    var group=mutableListOf<TaskItem>()
    var groupEnd:LocalDateTime?=null
    fun displayEnd(task:TaskItem)=maxOf(task.end,task.start.plusMinutes(minimumVisualMinutes))
    fun flush() {
        val ends=mutableListOf<LocalDateTime>()
        val positions=group.map {task->
            var column=ends.indexOfFirst {it<=task.start}
            if(column<0) {column=ends.size;ends+=displayEnd(task)} else ends[column]=displayEnd(task)
            task to column
        }
        positions.forEach {(task,column)->result+=MapPlacement(task,column,ends.size)}
        group=mutableListOf();groupEnd=null
    }
    sorted.forEach {task->
        if(groupEnd?.let {task.start>=it}==true) flush()
        group+=task
        if(groupEnd==null || displayEnd(task)>groupEnd!!) groupEnd=displayEnd(task)
    }
    if(group.isNotEmpty()) flush()
    return result
}
