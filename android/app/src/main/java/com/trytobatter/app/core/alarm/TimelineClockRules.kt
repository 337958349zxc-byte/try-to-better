package com.trytobatter.app.core.alarm

import com.trytobatter.app.core.model.TaskItem
import java.time.ZoneId

enum class ClockAction { SET, CANCEL }
enum class ClockRequestStage { PREPARING, REQUESTED, FAILED }
data class ClockEntry(val taskId: String, val day: String, val title: String, val triggerAt: Long, val label: String,
    val clockPackage: String, val action: ClockAction = ClockAction.SET, val stage: ClockRequestStage = ClockRequestStage.PREPARING,
    val error: String = "")
data class TimelineClockState(val enabledDay: String = "", val entries: Map<String, ClockEntry> = emptyMap(), val error: String = "")
data class SystemClockApp(val packageName: String, val label: String)

object ClockRoutingRules {
    fun choose(candidates: List<SystemClockApp>, preferredPackage: String?, knownClock: SystemClockApp?, xiaomi: Boolean): SystemClockApp {
        val apps=candidates.distinctBy { it.packageName }
        if(xiaomi && knownClock!=null) return knownClock
        return apps.firstOrNull { it.packageName==preferredPackage } ?: apps.singleOrNull() ?: knownClock
            ?: SystemClockApp("","由手机选择时钟")
    }
}

object TimelineClockRules {
    fun targets(tasks: List<TaskItem>, clockPackage: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<ClockEntry> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return tasks.filter { it.day == today && AlarmRules.eligible(it) }.mapNotNull { task ->
            val at = task.start.minusMinutes(10).withSecond(0).withNano(0)
            if(at.toLocalDate() != today || at.atZone(zone).toInstant().toEpochMilli() <= now) null else
                ClockEntry(task.id,today.toString(),task.title,at.atZone(zone).toInstant().toEpochMilli(),
                    "TTB ${today} ${task.title.take(60)} [${task.id}]",clockPackage)
        }.sortedBy { it.triggerAt }
    }
    fun plan(tasks: List<TaskItem>, state: TimelineClockState, clockPackage: String, enabled: Boolean,
        now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<ClockEntry> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val desired = if(enabled) targets(tasks,clockPackage,now,zone).associateBy { it.taskId } else emptyMap()
        val previous = state.entries.filterValues { it.day == today }
        fun same(a: ClockEntry, b: ClockEntry) = a.triggerAt == b.triggerAt && a.label == b.label && a.clockPackage == b.clockPackage
        val cancel = previous.values.filter { old ->
            (old.action == ClockAction.SET && old.stage != ClockRequestStage.FAILED &&
                (desired[old.taskId]?.let { !same(old,it) } != false || old.stage != ClockRequestStage.REQUESTED)) ||
                (old.action == ClockAction.CANCEL && (!enabled || old.stage != ClockRequestStage.REQUESTED)) }
            .map { it.copy(action = ClockAction.CANCEL,stage = ClockRequestStage.PREPARING,error = "") }
        val set = desired.values.filter { target -> previous[target.taskId]?.let {
            it.action == ClockAction.SET && it.stage == ClockRequestStage.REQUESTED && same(it,target)
        } != true }
        return cancel + set
    }
}
