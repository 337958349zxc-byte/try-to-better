package com.trytobatter.app.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

enum class Category(val label: String) { AI("AI 开发"), ENGLISH("英语沟通"), MEAL("饮食备餐"), WORKOUT("运动训练"), LIFE("工作生活") }
enum class TaskStatus(val label: String) { PENDING("待开始"), RUNNING("进行中"), PAUSED("已暂停"), COMPLETED("完整完成"), PARTIAL("部分完成"), SKIPPED("已跳过") }

data class TaskItem(
    val id: String = newId(),
    val title: String,
    val category: Category,
    val scheduledAt: String,
    val durationMinutes: Int = 30,
    val fixed: Boolean = false,
    val seriesId: String? = null,
    val repeatRule: String = "",
    val steps: String = "",
    val resourceUrl: String = "",
    val criteria: String = "",
    val note: String = "",
    val status: TaskStatus = TaskStatus.PENDING,
    val revision: Int = 1,
    val accumulatedSeconds: Long = 0,
    val runningSinceElapsed: Long = 0,
    val executionStartedAt: Long = 0,
    val bootId: Int = -1,
    val executionSnapshot: String = "",
    val isDemo: Boolean = false,
) {
    val start: LocalDateTime get() = LocalDateTime.parse(scheduledAt)
    val end: LocalDateTime get() = start.plusMinutes(durationMinutes.toLong())
    val day: LocalDate get() = start.toLocalDate()
    val timeLabel: String get() = "${start.format(ClockFormat)}–${end.format(ClockFormat)}"
    val isFinished: Boolean get() = status in setOf(TaskStatus.COMPLETED, TaskStatus.PARTIAL, TaskStatus.SKIPPED)
    val isProtected: Boolean get() = executionStartedAt > 0 || isFinished
}

data class ExecutionRecord(
    val id: String = newId(), val taskId: String, val title: String,
    val category: Category, val outcome: TaskStatus, val seconds: Long,
    val result: String, val endedAt: Long = System.currentTimeMillis(),
    val correctedFrom: String = "", val snapshot: String = "", val isDemo: Boolean = false,
)

data class PlanConflict(
    val id: String = newId(), val taskId: String, val incoming: TaskItem,
    val reason: String = "手机和新计划修改了同一事项", val sourceVersion: String = "演示计划 v2",
)

data class Mutation(val id: String = newId(), val taskId: String, val action: String, val createdAt: Long = System.currentTimeMillis())
data class WorkoutSet(val id: String = newId(), val exercise: String, val setIndex: Int, val autoReps: Int, val confirmedReps: Int, val quality: String = "无法判断", val note: String = "")
data class WorkoutRecord(val id: String = newId(), val endedAt: Long = System.currentTimeMillis(), val sets: List<WorkoutSet>, val seconds: Long, val note: String = "", val simulated: Boolean = true)

data class SyncEntity(val key: String, val revision: Int, val payload: String, val deleted: Boolean = false)
data class SyncOperation(val id: String = newId(), val key: String, val baseRevision: Int, val payload: String, val deleted: Boolean = false)
data class SyncConflict(val key: String, val remote: SyncEntity, val reason: String)
enum class ConnectionPhase { UNPAIRED, SEARCHING, WAITING_APPROVAL, CONNECTING, ONLINE, OFFLINE, AUTH_ERROR }
data class ConnectionInfo(val paired: Boolean = false, val address: String = "http://192.168.0.23:8081", val serverName: String = "电脑", val phase: ConnectionPhase = ConnectionPhase.UNPAIRED, val error: String = "", val syncing: Boolean = false, val requestLabel: String = "")

data class AppSnapshot(
    val tasks: List<TaskItem> = emptyList(), val records: List<ExecutionRecord> = emptyList(),
    val mutations: List<Mutation> = emptyList(), val conflicts: List<PlanConflict> = emptyList(),
    val workouts: List<WorkoutRecord> = emptyList(), val planVersion: Int = 1,
    val demoConnected: Boolean = false, val lastDemoSyncAt: Long = 0,
    val deviceName: String = "我的电脑", val loaded: Boolean = false, val workoutDraft: String = "",
    val outbox: List<SyncOperation> = emptyList(), val shadows: List<SyncEntity> = emptyList(),
    val syncConflicts: List<SyncConflict> = emptyList(), val syncCursor: Long = 0, val boundServerId: String = "",
    val lastRealSyncAt: Long = 0, val syncAudit: List<String> = emptyList(), val connection: ConnectionInfo = ConnectionInfo(),
) {
    val pendingSyncCount get() = if(connection.paired) outbox.size else mutations.size
    val conflictCount get() = conflicts.size + syncConflicts.size
}

object TaskRules {
    fun overlaps(a: TaskItem, b: TaskItem): Boolean = a.id != b.id && a.start < b.end && b.start < a.end && !a.isFinished && !b.isFinished
    fun conflicts(item: TaskItem, tasks: List<TaskItem>) = tasks.filter { overlaps(item, it) }

    fun elapsed(task: TaskItem, nowElapsed: Long): Long = task.accumulatedSeconds +
        if (task.status == TaskStatus.RUNNING) ((nowElapsed - task.runningSinceElapsed).coerceAtLeast(0) / 1000) else 0

    fun start(task: TaskItem, nowWall: Long, nowElapsed: Long, bootId: Int): TaskItem {
        require(!task.isFinished) { "已结束事项请通过历史订正修改" }
        require(task.status != TaskStatus.RUNNING) { "事项已经在进行中" }
        return task.copy(status = TaskStatus.RUNNING, runningSinceElapsed = nowElapsed,
            executionStartedAt = task.executionStartedAt.takeIf { it > 0 } ?: nowWall, bootId = bootId,
            executionSnapshot = task.executionSnapshot.ifBlank { "${task.title}｜${task.scheduledAt}｜${task.durationMinutes} 分钟｜${task.steps}｜${task.criteria}" }, revision = task.revision + 1)
    }

    fun pause(task: TaskItem, nowElapsed: Long) = if (task.status != TaskStatus.RUNNING) task else
        task.copy(status = TaskStatus.PAUSED, accumulatedSeconds = elapsed(task, nowElapsed), runningSinceElapsed = 0, revision = task.revision + 1)

    fun applyIncoming(local: TaskItem, incoming: TaskItem, locallyModified: Boolean): TaskItem {
        require(!local.isProtected) { "本次执行和完成历史受到保护" }
        require(!locallyModified) { "请先处理手机修改冲突" }
        return incoming.copy(id = local.id, revision = local.revision + 1)
    }

    fun restoreAfterBoot(task: TaskItem, bootId: Int) = if (task.status == TaskStatus.RUNNING && task.bootId != bootId)
        task.copy(status = TaskStatus.PAUSED, runningSinceElapsed = 0, note = listOf(task.note, "设备重启，计时已暂停；请核对实际用时").filter { it.isNotBlank() }.joinToString("\n")) else task

    fun focus(tasks: List<TaskItem>, day: LocalDate, now: LocalDateTime): TaskItem? {
        val today = tasks.filter { it.day == day }.sortedBy { it.start }
        return today.firstOrNull { it.status == TaskStatus.RUNNING } ?: today.firstOrNull { it.status == TaskStatus.PAUSED }
            ?: today.firstOrNull { !it.isFinished && now >= it.start && now < it.end }
            ?: today.firstOrNull { !it.isFinished && it.start >= now } ?: today.firstOrNull { !it.isFinished }
    }
}

data class Exercise(val id: String, val name: String, val reps: Int, val sets: Int, val instructions: List<String>, val angle: String)
val Exercises = listOf(
    Exercise("chair", "椅子坐站", 8, 2, listOf("将稳固椅子靠墙固定，双脚踩稳", "平稳起身，再缓慢坐下", "保留能规范完成的余量"), "侧面拍摄，肩部到脚部完整入镜"),
    Exercise("wall", "靠墙俯卧撑", 10, 2, listOf("面对墙面，双手支撑在舒适位置", "身体整体靠近墙面，再推回", "保持躯干稳定，平稳呼吸"), "侧面拍摄，双手和脚部可见"),
)

fun demoSnapshot(day: LocalDate = LocalDate.now()): AppSnapshot {
    fun task(id: String, title: String, cat: Category, hour: Int, minute: Int, duration: Int, steps: String, criteria: String, fixed: Boolean = false, url: String = "") =
        TaskItem(id = id, title = title, category = cat, scheduledAt = day.atTime(hour, minute).toString(), durationMinutes = duration,
            fixed = fixed, steps = steps, criteria = criteria, resourceUrl = url, isDemo = true)
    val tasks = listOf(
        task("demo-ai", "做出一个能保存的小功能", Category.AI, 9, 0, 90, "回顾昨天的修改\n阅读一个最小示例\n亲手实现并刷新验证\n记下一个卡点", "留下一个能解释、能修改的小成果", url = "https://developer.mozilla.org/zh-CN/docs/Learn_web_development"),
        task("demo-meal", "一次准备午餐与晚餐", Category.MEAL, 11, 0, 60, "准备食材与饭盒\n煮饭时完成洗切\n完成肉菜与蔬菜\n分装、冷藏并收拾", "饭菜分装完成，记下实际备餐用时", true),
        task("demo-english", "英语：把自己的意思说清楚", Category.ENGLISH, 13, 30, 90, "精听一个短片段\n跟读并录一小段\n完成情景对话\n记录两个有用表达", "出声完成一次表达，注明独练或互动", url = "https://learnenglish.britishcouncil.org/free-resources/speaking"),
        task("demo-strength", "全身力量训练｜周衡 V1.4", Category.WORKOUT, 16, 0, 25, "查看动作示范\n按提示摆放手机\n完成组次与休息\n核对并保存结果", "按实际完成记录，计数和质量分别核对"),
        task("demo-walk", "出去走一会儿", Category.WORKOUT, 19, 0, 15, "选择平整路线\n按舒适速度步行\n记录实际分钟", "按实际时长记录"),
        task("demo-review", "给今天留下一条记录", Category.LIFE, 20, 0, 10, "写下今天做成的一件事\n记录一个卡点\n确定明天第一步", "留下简短复盘"),
    )
    return AppSnapshot(tasks = tasks, loaded = true)
}
