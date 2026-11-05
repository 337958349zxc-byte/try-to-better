package com.trytobatter.app.core.data

import com.trytobatter.app.core.model.*
import org.json.JSONArray
import org.json.JSONObject

private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
private fun <T> List<T>.array(encode: (T) -> JSONObject) = JSONArray().also { out -> forEach { out.put(encode(it)) } }
private fun <T> JSONObject.list(name: String, decode: (JSONObject) -> T): List<T> {
    val a = optJSONArray(name) ?: return emptyList()
    return (0 until a.length()).map { decode(a.getJSONObject(it)) }
}

object SnapshotCodec {
    fun task(t: TaskItem): JSONObject = obj(
        "id" to t.id, "title" to t.title, "category" to t.category.name, "scheduledAt" to t.scheduledAt,
        "durationMinutes" to t.durationMinutes, "fixed" to t.fixed, "seriesId" to t.seriesId,
        "repeatRule" to t.repeatRule, "steps" to t.steps, "resourceUrl" to t.resourceUrl,
        "criteria" to t.criteria, "note" to t.note, "status" to t.status.name, "revision" to t.revision,
        "accumulatedSeconds" to t.accumulatedSeconds, "runningSinceElapsed" to t.runningSinceElapsed,
        "executionStartedAt" to t.executionStartedAt, "bootId" to t.bootId,
        "executionSnapshot" to t.executionSnapshot, "isDemo" to t.isDemo,
    )

    fun readTask(j: JSONObject): TaskItem = TaskItem(
        id = j.getString("id"), title = j.getString("title"), category = Category.valueOf(j.getString("category")),
        scheduledAt = j.getString("scheduledAt"), durationMinutes = j.getInt("durationMinutes"),
        fixed = j.optBoolean("fixed"), seriesId = j.optString("seriesId").takeIf { it.isNotBlank() && it != "null" },
        repeatRule = j.optString("repeatRule"), steps = j.optString("steps"), resourceUrl = j.optString("resourceUrl"),
        criteria = j.optString("criteria"), note = j.optString("note"), status = TaskStatus.valueOf(j.getString("status")),
        revision = j.optInt("revision", 1), accumulatedSeconds = j.optLong("accumulatedSeconds"),
        runningSinceElapsed = j.optLong("runningSinceElapsed"), executionStartedAt = j.optLong("executionStartedAt"),
        bootId = j.optInt("bootId", -1), executionSnapshot = j.optString("executionSnapshot"), isDemo = j.optBoolean("isDemo"),
    ).also { require(it.durationMinutes in 1..1440 && it.title.isNotBlank() && it.id.isNotBlank() && it.accumulatedSeconds >= 0 && it.revision > 0) { "事项字段不完整" }; it.start }

    fun encode(s: AppSnapshot): String = obj(
        "schemaVersion" to 1, "environment" to "local", "exportedAt" to System.currentTimeMillis(),
        "tasks" to s.tasks.array(::task),
        "records" to s.records.array { r -> obj("id" to r.id, "taskId" to r.taskId, "title" to r.title,
            "category" to r.category.name, "outcome" to r.outcome.name, "seconds" to r.seconds, "result" to r.result,
            "endedAt" to r.endedAt, "correctedFrom" to r.correctedFrom, "snapshot" to r.snapshot, "isDemo" to r.isDemo) },
        "mutations" to s.mutations.array { m -> obj("id" to m.id, "taskId" to m.taskId, "action" to m.action, "createdAt" to m.createdAt) },
        "conflicts" to s.conflicts.array { c -> obj("id" to c.id, "taskId" to c.taskId, "incoming" to task(c.incoming), "reason" to c.reason, "sourceVersion" to c.sourceVersion) },
        "workouts" to s.workouts.array { w -> obj("id" to w.id, "endedAt" to w.endedAt, "seconds" to w.seconds,
            "note" to w.note, "simulated" to w.simulated, "sets" to w.sets.array { x -> obj("id" to x.id,
                "exercise" to x.exercise, "setIndex" to x.setIndex, "autoReps" to x.autoReps,
                "confirmedReps" to x.confirmedReps, "quality" to x.quality, "note" to x.note) }) },
        "planVersion" to s.planVersion, "demoConnected" to s.demoConnected,
        "lastDemoSyncAt" to s.lastDemoSyncAt, "deviceName" to s.deviceName, "workoutDraft" to s.workoutDraft,
        "outbox" to s.outbox.array { o -> obj("id" to o.id, "key" to o.key, "baseRevision" to o.baseRevision, "payload" to o.payload, "deleted" to o.deleted) },
        "shadows" to s.shadows.array(::syncEntity),
        "syncConflicts" to s.syncConflicts.array { c -> obj("key" to c.key, "remote" to syncEntity(c.remote), "reason" to c.reason) },
        "syncCursor" to s.syncCursor, "boundServerId" to s.boundServerId, "lastRealSyncAt" to s.lastRealSyncAt,
        "syncAudit" to JSONArray(s.syncAudit),
    ).toString(2)

    fun decode(text: String): AppSnapshot {
        require(text.length <= 5_000_000) { "备份文件过大" }
        val j = JSONObject(text)
        require(j.getInt("schemaVersion") == 1) { "不支持的备份版本" }
        require(j.getString("environment") in setOf("local-demo", "local")) { "这不是本应用的本机备份" }
        val tasks = j.list("tasks", ::readTask)
        require(tasks.map { it.id }.distinct().size == tasks.size) { "备份中存在重复事项" }
        return AppSnapshot(
            tasks = tasks,
            records = j.list("records") { r -> ExecutionRecord(r.getString("id"), r.getString("taskId"), r.getString("title"),
                Category.valueOf(r.getString("category")), TaskStatus.valueOf(r.getString("outcome")), r.getLong("seconds"),
                r.optString("result"), r.getLong("endedAt"), r.optString("correctedFrom"), r.optString("snapshot"), r.optBoolean("isDemo")) },
            mutations = j.list("mutations") { m -> Mutation(m.getString("id"), m.getString("taskId"), m.getString("action"), m.getLong("createdAt")) },
            conflicts = j.list("conflicts") { c -> PlanConflict(c.getString("id"), c.getString("taskId"), readTask(c.getJSONObject("incoming")), c.getString("reason"), c.getString("sourceVersion")) },
            workouts = j.list("workouts") { w -> WorkoutRecord(w.getString("id"), w.getLong("endedAt"),
                w.list("sets") { x -> WorkoutSet(x.getString("id"), x.getString("exercise"), x.getInt("setIndex"),
                    x.getInt("autoReps"), x.getInt("confirmedReps"), x.optString("quality", "无法判断"), x.optString("note")) },
                w.getLong("seconds"), w.optString("note"), w.optBoolean("simulated", true)) },
            planVersion = j.optInt("planVersion", 1), demoConnected = false,
            lastDemoSyncAt = j.optLong("lastDemoSyncAt"), deviceName = j.optString("deviceName", "我的电脑"), loaded = true, workoutDraft = j.optString("workoutDraft"),
            outbox = j.list("outbox") { o -> SyncOperation(o.getString("id"), o.getString("key"), o.getInt("baseRevision"), o.getString("payload"), o.optBoolean("deleted")) },
            shadows = j.list("shadows", ::readSyncEntity),
            syncConflicts = j.list("syncConflicts") { c -> SyncConflict(c.getString("key"), readSyncEntity(c.getJSONObject("remote")), c.getString("reason")) },
            syncCursor = j.optLong("syncCursor"), boundServerId = j.optString("boundServerId"), lastRealSyncAt = j.optLong("lastRealSyncAt"),
            syncAudit = j.optJSONArray("syncAudit")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        ).also { s ->
            val ended = setOf(TaskStatus.COMPLETED, TaskStatus.PARTIAL, TaskStatus.SKIPPED)
            require(s.records.all { it.seconds >= 0 && it.outcome in ended }) { "执行记录字段无效" }
            require(s.records.map { it.id }.distinct().size == s.records.size && s.workouts.map { it.id }.distinct().size == s.workouts.size) { "备份中存在重复记录" }
            require(s.conflicts.all { c -> tasks.any { it.id == c.taskId } && c.incoming.id == c.taskId }) { "冲突事项关联无效" }
            require(s.workouts.all { w -> w.seconds >= 0 && w.sets.all { it.setIndex > 0 && it.autoReps >= 0 && it.confirmedReps >= 0 } }) { "训练记录字段无效" }
        }
    }
    private fun syncEntity(e: SyncEntity) = obj("key" to e.key, "revision" to e.revision, "payload" to e.payload, "deleted" to e.deleted)
    private fun readSyncEntity(j: JSONObject) = SyncEntity(j.getString("key"), j.getInt("revision"), j.getString("payload"), j.optBoolean("deleted"))
}
