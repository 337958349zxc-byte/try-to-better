package com.trytobatter.app.core.data

import com.trytobatter.app.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/** No network or clocks here: response merging is an atomic, testable state transition. */
object SyncEngine {
    fun canonical(value: Any?): String = when(value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun payloads(s: AppSnapshot): Map<String, String> {
        val data = JSONObject(SnapshotCodec.encode(s.copy(outbox = emptyList(), shadows = emptyList(), syncConflicts = emptyList(), syncAudit = emptyList())))
        return buildMap { listOf("task" to "tasks", "record" to "records", "workout" to "workouts").forEach { (kind, field) ->
            val array = data.getJSONArray(field)
            for(i in 0 until array.length()) { val entity = array.getJSONObject(i); put("$kind:${entity.getString("id")}", canonical(entity)) }
        } }
    }
    fun queueChanges(before: AppSnapshot, after: AppSnapshot): AppSnapshot {
        val old = payloads(before); val next = payloads(after)
        val additions = (old.keys + next.keys).filter { old[it] != next[it] }.map { key ->
            SyncOperation(key = key, baseRevision = after.shadows.find { it.key == key }?.revision ?: 0, payload = next[key] ?: "{}", deleted = key !in next)
        }
        return after.copy(outbox = after.outbox + additions)
    }
    fun seed(s: AppSnapshot, serverId: String): AppSnapshot {
        if(s.boundServerId != serverId) return s.copy(boundServerId = serverId, syncCursor = 0, shadows = emptyList(), syncConflicts = emptyList(),
            outbox = payloads(s).map { (key, value) -> SyncOperation(key = key, baseRevision = 0, payload = value) })
        val missing = payloads(s).filter { (key, _) -> s.shadows.none { it.key == key } && s.outbox.none { it.key == key } }
        return s.copy(outbox = s.outbox + missing.map { (key, value) -> SyncOperation(key = key, baseRevision = 0, payload = value) })
    }
    fun batch(s: AppSnapshot) = s.outbox.filter { op -> s.syncConflicts.none { it.key == op.key } }.distinctBy { it.key }.take(48)
    fun operation(o: SyncOperation) = JSONObject().put("id", o.id).put("key", o.key).put("baseRevision", o.baseRevision).put("payload", JSONObject(o.payload)).put("deleted", o.deleted)
    private fun remote(j: JSONObject) = SyncEntity(j.getString("key"), j.getInt("revision"), canonical(j.getJSONObject("payload")), j.getBoolean("deleted")).also {
        require(it.revision >= 0) { "电脑版本无效" }; applyEntity(AppSnapshot(), it)
    }
    private fun shadow(s: AppSnapshot, e: SyncEntity) = s.copy(shadows = s.shadows.filterNot { it.key == e.key } + e)
    fun remoteTask(c: SyncConflict): TaskItem? = if(c.key.startsWith("task:") && !c.remote.deleted) SnapshotCodec.readTask(JSONObject(c.remote.payload)) else null

    fun merge(initial: AppSnapshot, request: List<SyncOperation>, response: JSONObject): AppSnapshot {
        require(response.getLong("cursor") >= initial.syncCursor) { "电脑同步版本回退，请重新配对" }
        var s = initial
        val acknowledgements = response.getJSONArray("acknowledged")
        for(i in 0 until acknowledgements.length()) {
            val ack = acknowledgements.getJSONObject(i); val id = ack.getString("operationId"); val e = remote(ack)
            val sent = request.find { it.id == id } ?: error("电脑返回了未知操作回执")
            require(sent.key == e.key && sent.payload == e.payload && sent.deleted == e.deleted && e.revision > 0) { "电脑回执内容不一致" }
            s = shadow(s, e).copy(outbox = s.outbox.filterNot { it.id == id }.map { if(it.key == e.key) it.copy(baseRevision = e.revision) else it })
        }
        val conflicts = response.getJSONArray("conflicts")
        for(i in 0 until conflicts.length()) {
            val c = conflicts.getJSONObject(i); val e = remote(c.getJSONObject("remote"))
            s = s.copy(syncConflicts = s.syncConflicts.filterNot { it.key == e.key } + SyncConflict(e.key, e, c.optString("reason", "同一记录有双方修改")))
        }
        val changes = response.getJSONArray("changes")
        for(i in 0 until changes.length()) {
            val e = remote(changes.getJSONObject(i))
            if(e.revision <= (s.shadows.find { it.key == e.key }?.revision ?: -1)) continue
            val local = payloads(s)[e.key]
            val protected = e.key.startsWith("task:") && s.tasks.find { "task:${it.id}" == e.key }?.isProtected == true
            val history = !e.key.startsWith("task:") && local != null && local != e.payload
            if(s.outbox.any { it.key == e.key } || (protected && local != e.payload) || history) {
                s = s.copy(syncConflicts = s.syncConflicts.filterNot { it.key == e.key } + SyncConflict(e.key, e, if(protected) "本次执行已开始，需保留手机内容" else "电脑与手机内容需要比较确认"))
            } else { s = applyEntity(s, e); s = shadow(s, e) }
        }
        return s.copy(syncCursor = response.getLong("cursor"), planVersion = response.getInt("planVersion"),
            mutations = if(s.outbox.isEmpty() && s.syncConflicts.isEmpty()) emptyList() else s.mutations)
    }

    fun resolve(s: AppSnapshot, key: String, keepPhone: Boolean): AppSnapshot {
        val c = s.syncConflicts.first { it.key == key }; val local = payloads(s)[key]
        val protected = s.tasks.find { "task:${it.id}" == key }?.isProtected == true
        require(keepPhone || !protected) { "本次执行已开始，请保留手机内容" }
        val audit = JSONObject().put("key", key).put("choice", if(keepPhone) "phone" else "computer").put("local", local ?: "{}").put("remote", c.remote.payload).put("at", System.currentTimeMillis()).toString()
        var result = shadow(s, c.remote).copy(outbox = s.outbox.filterNot { it.key == key }, syncConflicts = s.syncConflicts.filterNot { it.key == key }, syncAudit = s.syncAudit + audit)
        if(keepPhone) result = result.copy(outbox = result.outbox + SyncOperation(key = key, baseRevision = c.remote.revision, payload = local ?: "{}", deleted = local == null))
        else result = applyEntity(result, c.remote)
        return result
    }
    private fun applyEntity(s: AppSnapshot, e: SyncEntity): AppSnapshot {
        val kind = e.key.substringBefore(':'); val id = e.key.substringAfter(':')
        require(kind in setOf("task", "record", "workout") && id.isNotBlank()) { "电脑记录类型无效" }
        if(e.deleted) return when(kind) {
            "task" -> s.copy(tasks = s.tasks.filterNot { it.id == id }, conflicts = s.conflicts.filterNot { it.taskId == id })
            "record" -> s.copy(records = s.records.filterNot { it.id == id })
            else -> s.copy(workouts = s.workouts.filterNot { it.id == id })
        }
        val payload = JSONObject(e.payload); require(payload.getString("id") == id) { "电脑记录关联无效" }
        if(kind == "task") {
            var task = SnapshotCodec.readTask(payload)
            if(task.status == TaskStatus.RUNNING) task = task.copy(status = TaskStatus.PAUSED, runningSinceElapsed = 0, note = task.note + "\n来自另一设备的执行已暂停，请核对用时")
            return s.copy(tasks = s.tasks.filterNot { it.id == id } + task)
        }
        // Reuse the validated backup decoder for record/workout fields.
        val document = JSONObject(SnapshotCodec.encode(AppSnapshot()))
        document.put(if(kind == "record") "records" else "workouts", JSONArray().put(payload))
        val decoded = SnapshotCodec.decode(document.toString())
        return if(kind == "record") s.copy(records = s.records.filterNot { it.id == id } + decoded.records.single())
            else s.copy(workouts = s.workouts.filterNot { it.id == id } + decoded.workouts.single())
    }
}
