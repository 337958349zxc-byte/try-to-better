package com.trytobatter.app.core.alarm

import com.trytobatter.app.core.model.TaskItem
import com.trytobatter.app.core.model.TaskStatus
import java.time.ZoneId

enum class AlarmMode { NOTIFICATION, RING }
enum class AlarmState(val label: String) {
    DISABLED("未开启"), NEEDS_PERMISSION("需要授权"), SCHEDULED("已安排"), RINGING("正在提醒"),
    SNOOZED("稍后提醒"), MISSED("本次提醒时间已过"), CANCELLED("本次提醒已结束"), SCHEDULE_ERROR("安排失败")
}
data class TaskAlarmSettings(val enabled: Boolean = false, val leadMinutes: Int = 10, val mode: AlarmMode = AlarmMode.RING)
data class AlarmRecord(val taskId: String, val fingerprint: String, val generation: String, val triggerAt: Long,
    val state: AlarmState, val snoozeCount: Int = 0, val scheduledAt: Long = 0, val error: String = "", val deliveredAt: Long = 0)
data class AlarmSnapshot(val settings: Map<String, TaskAlarmSettings> = emptyMap(), val records: Map<String, AlarmRecord> = emptyMap(), val error: String = "")

/** One materialized TaskItem ID is already one occurrence in the existing limited-repeat model. */
object AlarmRules {
    fun triggerAt(task: TaskItem, setting: TaskAlarmSettings, zone: ZoneId = ZoneId.systemDefault()): Long =
        task.start.atZone(zone).toInstant().toEpochMilli() - setting.leadMinutes * 60_000L
    fun fingerprint(task: TaskItem, setting: TaskAlarmSettings): String =
        "${task.id}|${task.revision}|${task.scheduledAt}|${task.status}|${task.executionStartedAt}|$setting|${triggerAt(task, setting)}"
    fun eligible(task: TaskItem?) = task != null && task.status == TaskStatus.PENDING && task.executionStartedAt == 0L
    fun desired(task: TaskItem?, setting: TaskAlarmSettings, permissionError: String?, now: Long): AlarmState = when {
        !setting.enabled -> AlarmState.DISABLED
        !eligible(task) -> AlarmState.CANCELLED
        triggerAt(task!!, setting) <= now -> AlarmState.MISSED
        permissionError != null -> AlarmState.NEEDS_PERMISSION
        else -> AlarmState.SCHEDULED
    }
    fun validDelivery(task: TaskItem?, setting: TaskAlarmSettings, record: AlarmRecord?, generation: String, now: Long): Boolean =
        record != null && setting.enabled && eligible(task) && record.generation == generation &&
            record.fingerprint == fingerprint(task!!, setting) && record.state in setOf(AlarmState.SCHEDULED, AlarmState.SNOOZED) &&
            now >= record.triggerAt - 500 && (if(setting.mode==AlarmMode.RING) now <= record.triggerAt + 5 * 60_000L
                else now < task.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    fun canSnooze(task: TaskItem?, record: AlarmRecord?, now: Long) = eligible(task) && record != null &&
        record.state == AlarmState.RINGING && record.snoozeCount < 2 && now + 5 * 60_000L < task!!.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
