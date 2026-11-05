package com.trytobatter.app.core.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.Room
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import org.json.JSONObject
import org.json.JSONArray

interface AppRepository {
    val timelineClock: StateFlow<com.trytobatter.app.core.alarm.TimelineClockState>
    fun systemClockApp(): com.trytobatter.app.core.alarm.SystemClockApp?
    fun systemClockDiagnostics(): String
    val clockDiagnosticDelivery: StateFlow<String>
    suspend fun sendClockDiagnostics(): String
    suspend fun traceClockEvent(event: String)
    suspend fun prepareTimelineClockBatch(enabled: Boolean): List<com.trytobatter.app.core.alarm.ClockEntry>
    suspend fun recordTimelineClockRequest(entry: com.trytobatter.app.core.alarm.ClockEntry)
    fun systemClockIntent(entry: com.trytobatter.app.core.alarm.ClockEntry): android.content.Intent
    fun showSystemClockIntent(): android.content.Intent
    fun recipeUrl(resourceUrl: String = ""): String
    val alarms: StateFlow<com.trytobatter.app.core.alarm.AlarmSnapshot>
    suspend fun toggleTaskAlarm(id: String)
    suspend fun setTaskAlarm(id: String, setting: com.trytobatter.app.core.alarm.TaskAlarmSettings, following: Boolean = false)
    suspend fun refreshAlarms(force: Boolean = false)
    suspend fun dismissTaskAlarm(id: String, generation: String? = null)
    suspend fun snoozeTaskAlarm(id: String, generation: String)
    suspend fun handleTaskAlarmTrigger(id: String, generation: String): Boolean
    val state: StateFlow<AppSnapshot>
    val updates: StateFlow<UpdateInfo>
    suspend fun load()
    suspend fun saveTask(task: TaskItem, following: Boolean = false)
    suspend fun deleteTask(id: String, following: Boolean = false)
    suspend fun startTask(id: String)
    suspend fun pauseTask(id: String)
    suspend fun finishTask(id: String, outcome: TaskStatus, result: String)
    suspend fun correctRecord(id: String, outcome: TaskStatus, seconds: Long, result: String)
    suspend fun undo()
    suspend fun pairDemo(name: String)
    suspend fun disconnect()
    suspend fun simulateSync()
    suspend fun simulatePlanUpdate()
    suspend fun resolveConflict(id: String, keepPhone: Boolean)
    suspend fun saveUnifiedWorkout(draft: String)
    suspend fun saveWorkout(record: WorkoutRecord)
    suspend fun saveWorkoutDraft(draft: String)
    suspend fun restore(snapshot: AppSnapshot)
    suspend fun resetDemo(empty: Boolean = false)
    suspend fun pair(address: String, code: String)
    suspend fun autoConnect(manual: Boolean = false)
    suspend fun syncNow()
    suspend fun openOnComputer(taskId: String, resourceUrl: String = ""): String
    suspend fun resolveSyncConflict(key: String, keepPhone: Boolean)
    suspend fun checkUpdate()
    suspend fun downloadUpdate()
    fun updateInstallIntent(): android.content.Intent
}

/** Room persists local edits and immutable operations before authenticated LAN synchronization. */
class LocalDemoRepository(private val context: Context) : AppRepository {
    private val systemClock get() = (context.applicationContext as com.trytobatter.app.BatterApplication).systemClock
    override val timelineClock get() = systemClock.state
    override fun systemClockApp() = systemClock.clockApp()
    override fun systemClockDiagnostics() = systemClock.diagnostics()
    private val clockDiagnosticOutbox = ClockDiagnosticOutbox(context)
    override val clockDiagnosticDelivery get() = clockDiagnosticOutbox.status
    override suspend fun traceClockEvent(event: String) = systemClock.trace(event)
    override suspend fun sendClockDiagnostics(): String {
        val paired=credentials ?: return "请先连接电脑，再发送时钟诊断"
        clockDiagnosticOutbox.enqueue(systemClockDiagnostics(),paired.serverId)
        clockDiagnosticOutbox.flush(client,paired)
        BackgroundSyncWorker.once(context)
        return clockDiagnosticDelivery.value
    }
    override fun systemClockIntent(entry: com.trytobatter.app.core.alarm.ClockEntry): android.content.Intent {
        if(entry.action==com.trytobatter.app.core.alarm.ClockAction.SET) check(
            com.trytobatter.app.core.alarm.TimelineClockRules.targets(_state.value.tasks,entry.clockPackage).any {it.taskId==entry.taskId && it.label==entry.label && it.triggerAt==entry.triggerAt}
        ) { "事项已变化，请更新系统闹钟" }
        return systemClock.requestIntent(entry)
    }
    override fun showSystemClockIntent() = systemClock.showIntent()
    override suspend fun recordTimelineClockRequest(entry: com.trytobatter.app.core.alarm.ClockEntry) = mutex.withLock {
        systemClock.record(entry)
        if(entry.action==com.trytobatter.app.core.alarm.ClockAction.SET && entry.stage==com.trytobatter.app.core.alarm.ClockRequestStage.REQUESTED &&
            _state.value.tasks.any {it.id==entry.taskId} && alarms.value.settings[entry.taskId]?.enabled==true)
            alarmCoordinator.setSettings(_state.value.tasks,entry.taskId,alarms.value.settings.getValue(entry.taskId).copy(enabled=false))
    }
    override suspend fun prepareTimelineClockBatch(enabled: Boolean) = mutex.withLock {
        val app = systemClockApp() ?: error("此设备没有可用的系统时钟，尚未设置系统闹钟")
        check(_state.value.loaded) { "事项尚未加载" }
        val tasks = _state.value.tasks
        val batch = com.trytobatter.app.core.alarm.TimelineClockRules.plan(tasks,timelineClock.value,app.packageName,enabled)
        batch.forEach { systemClock.requestIntent(it) }
        systemClock.enabled(if(enabled) LocalDate.now().toString() else "")
        batch
    }
    override fun recipeUrl(resourceUrl: String): String {
        val base = LanAddress.normalize(_state.value.connection.address)
        val linked = runCatching { java.net.URI(resourceUrl) }.getOrNull()?.takeIf {
            it.scheme in listOf("http", "https") && it.path?.startsWith("/recipes/") == true
        }
        return "$base/recipes/" + (linked?.rawQuery?.let { "?$it" } ?: "") + (linked?.rawFragment?.let { "#$it" } ?: "")
    }
    private val alarmCoordinator get() = (context.applicationContext as com.trytobatter.app.BatterApplication).alarms
    override val alarms get() = alarmCoordinator.state
    override suspend fun toggleTaskAlarm(id: String) = mutex.withLock {
        val previous = alarms.value.settings[id]
        val setting = if(previous?.enabled == true) previous.copy(enabled = false) else com.trytobatter.app.core.alarm.TaskAlarmSettings(enabled = true)
        alarmCoordinator.setSettings(_state.value.tasks,id,setting)
    }
    override suspend fun setTaskAlarm(id: String, setting: com.trytobatter.app.core.alarm.TaskAlarmSettings, following: Boolean) = mutex.withLock {
        alarmCoordinator.setSettings(_state.value.tasks,id,setting,following)
    }
    override suspend fun refreshAlarms(force: Boolean) = mutex.withLock { alarmCoordinator.reconcile(_state.value.tasks,force) }
    override suspend fun dismissTaskAlarm(id: String, generation: String?) { alarmCoordinator.dismiss(id,generation); com.trytobatter.app.core.alarm.TaskAlarmService.refresh(context) }
    override suspend fun snoozeTaskAlarm(id: String, generation: String) = mutex.withLock { alarmCoordinator.snooze(_state.value.tasks,id,generation); com.trytobatter.app.core.alarm.TaskAlarmService.refresh(context) }
    override suspend fun handleTaskAlarmTrigger(id: String, generation: String) = mutex.withLock { alarmCoordinator.trigger(_state.value.tasks,id,generation) }
    private val database = Room.databaseBuilder(context, BatterDatabase::class.java, "batter-ui-demo.db").build()
    private val dao = database.snapshotDao()
    private val bootId = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AppSnapshot())
    override val state = _state.asStateFlow()
    private var undoState: AppSnapshot? = null
    private var undoRevision: AppSnapshot? = null
    private var undoAlarms: com.trytobatter.app.core.alarm.AlarmSnapshot? = null
    private val credentialsStore = CredentialStore(context)
    private val client = LanClient(context)
    private val updater = AppUpdateManager(context)
    override val updates = updater.state
    private val syncMutex = Mutex()
    private val pairingMutex = Mutex()
    private data class SyncAttempt(val credentials: PairingCredentials, val generation: Int, val operations: List<SyncOperation>, val cursor: Long)
    @Volatile private var credentials: PairingCredentials? = null
    private var generation = 0
    private val autoMutex = Mutex()
    private val autoPreferences = context.getSharedPreferences("batter-auto-connect", Context.MODE_PRIVATE)
    private var lastSearch = -60_000L
    private data class PendingConnection(val address: String, val serverId: String, val name: String, val id: String, val secret: String, val expires: Long, val generation: Int)
    private var pendingConnection: PendingConnection? = null

    override suspend fun load() = mutex.withLock {
        if(_state.value.loaded) return@withLock
        val saved = withContext(Dispatchers.IO) { dao.read() }
        val initial = saved?.let { SnapshotCodec.decode(it.payload) } ?: demoSnapshot()
        val credentialResult = runCatching { credentialsStore.read() }
        credentials = credentialResult.getOrNull()
        var restored = SyncEngine.queueChanges(initial, initial.copy(tasks = initial.tasks.map { TaskRules.restoreAfterBoot(it, bootId) }, demoConnected = false, loaded = true,
            connection = credentials?.let { ConnectionInfo(true, it.address, it.serverName, ConnectionPhase.CONNECTING) }
                ?: ConnectionInfo(error = if(credentialResult.isFailure) "设备凭证不可用，请重新配对" else "")))
        val beforeTrainingMigration=restored
        val legacy=restored.tasks.filter(UnifiedTraining::legacyPending)
        val legacyDraft=restored.workoutDraft.takeIf {it.isNotBlank() && runCatching {JSONObject(it).optString("templateId")!=UnifiedTraining.templateId}.getOrDefault(false)}
        if(legacy.isNotEmpty() || legacyDraft!=null) {
            val archive=JSONObject().put("kind","training-v14-archive").put("at",System.currentTimeMillis())
                .put("tasks",JSONArray().apply {legacy.forEach {put(SnapshotCodec.task(it))}})
            if(legacyDraft!=null) archive.put("workoutDraft",legacyDraft)
            restored=SyncEngine.queueChanges(beforeTrainingMigration,restored.copy(
                tasks=restored.tasks.filterNot {t ->legacy.any {it.id==t.id}},
                workoutDraft=if(legacyDraft!=null) "" else restored.workoutDraft,syncAudit=restored.syncAudit+archive.toString()))
        }
        credentials?.let { restored = SyncEngine.seed(restored, it.serverId); BackgroundSyncWorker.schedule(context) }
        val rollback=context.getSharedPreferences("clock-rollback-0-2-9",Context.MODE_PRIVATE)
        if(!rollback.getBoolean("restored",false)) {
            val paused=withContext(Dispatchers.IO) {
                val file=android.util.AtomicFile(java.io.File(context.filesDir,"clock-close-probe-v1.json"))
                runCatching {JSONObject(file.openRead().use {it.readBytes().toString(Charsets.UTF_8)}).optBoolean("suspendTimeline",false)}.getOrDefault(false)
            }
            if(paused) systemClock.enabled("")
            rollback.edit().putBoolean("restored",true).apply()
        }
        // Restore future platform registrations on cold start without replacing an alarm currently being delivered.
        persist(restored, restoreFutureAlarms = true)
    }

    private suspend fun persist(next: AppSnapshot, restoreFutureAlarms: Boolean = false, restoredAlarms: com.trytobatter.app.core.alarm.AlarmSnapshot? = null) {
        withContext(Dispatchers.IO) { dao.write(SnapshotEntity(1, SnapshotCodec.encode(next))) }
        _state.value = next
        // Local effective task state is authoritative even while offline or conflicted.
        try {
            if(restoredAlarms != null) alarmCoordinator.restoreDeleted(next.tasks,restoredAlarms)
            else alarmCoordinator.reconcile(next.tasks,restoreFuture = restoreFutureAlarms)
        } catch(e: Exception) { alarmCoordinator.failure("闹钟维护失败：${e.message}") }
    }

    private suspend fun change(undoable: Boolean = false, block: (AppSnapshot) -> AppSnapshot) = mutex.withLock {
        check(_state.value.loaded) { "本地数据尚未准备好" }
        val old = _state.value
        val next = SyncEngine.queueChanges(old, block(old))
        val deletedIds = old.tasks.map { it.id }.toSet() - next.tasks.map { it.id }.toSet()
        val deletedAlarms = alarms.value.let { a -> a.copy(settings = a.settings.filterKeys { it in deletedIds }, records = a.records.filterKeys { it in deletedIds }, error = "") }
        persist(next)
        if (undoable) { undoState = old; undoRevision = next; undoAlarms = deletedAlarms } else { undoState = null; undoRevision = null; undoAlarms = null }
    }

    override suspend fun saveTask(task: TaskItem, following: Boolean) = change(true) { s ->
        val original = s.tasks.find { it.id == task.id }
        if(following && original?.seriesId!=null && original.repeatRule!=task.repeatRule) {
            val affected=s.tasks.filter {it.seriesId==original.seriesId && it.start>=original.start && RepeatRules.editable(it)}.map {it.id}.toSet()
            require(s.conflicts.none {it.taskId in affected} && s.syncConflicts.none {it.key.removePrefix("task:") in affected}) {
                "这组安排有待确认的计划或同步冲突，请先处理冲突再修改重复规则"
            }
        }
        val updated = RepeatRules.save(s.tasks, task, following)
        s.copy(tasks = updated, mutations = s.mutations + Mutation(taskId = task.id, action = if (original == null) "新增事项" else "修改事项"))
    }

    override suspend fun deleteTask(id: String, following: Boolean) = change(true) { s ->
        val original = s.tasks.first { it.id == id }
        require(!original.isProtected) { "已执行事项请保留历史，通过订正修改结果" }
        val deleted = s.tasks.filter { !it.isProtected && (it.id == id || (following && original.seriesId != null && it.seriesId == original.seriesId && it.start >= original.start)) }
        s.copy(tasks = s.tasks - deleted.toSet(), mutations = s.mutations + deleted.map { Mutation(taskId = it.id, action = "删除未来事项") },
            conflicts = s.conflicts.filterNot { c -> deleted.any { it.id == c.taskId } })
    }

    override suspend fun startTask(id: String) = change { s ->
        val nowElapsed = SystemClock.elapsedRealtime()
        s.copy(tasks = s.tasks.map { t -> when {
            t.id == id -> TaskRules.start(t, System.currentTimeMillis(), nowElapsed, bootId)
            t.status == TaskStatus.RUNNING -> TaskRules.pause(t, nowElapsed)
            else -> t
        } }, mutations = s.mutations + Mutation(taskId = id, action = "开始或继续"))
    }

    override suspend fun pauseTask(id: String) = change { s ->
        s.copy(tasks = s.tasks.map { if (it.id == id) TaskRules.pause(it, SystemClock.elapsedRealtime()) else it },
            mutations = s.mutations + Mutation(taskId = id, action = "暂停"))
    }

    override suspend fun finishTask(id: String, outcome: TaskStatus, result: String) = change { s ->
        require(outcome in setOf(TaskStatus.COMPLETED, TaskStatus.PARTIAL, TaskStatus.SKIPPED))
        val task = s.tasks.first { it.id == id }
        require(!task.isFinished) { "结果已保存，请通过历史订正调整" }
        val seconds = TaskRules.elapsed(task, SystemClock.elapsedRealtime())
        val record = ExecutionRecord(taskId = id, title = task.title, category = task.category, outcome = outcome,
            seconds = seconds, result = result, snapshot = task.executionSnapshot.ifBlank { SnapshotCodec.task(task).toString() }, isDemo = task.isDemo)
        s.copy(tasks = s.tasks.map { if (it.id == id) it.copy(status = outcome, accumulatedSeconds = seconds, runningSinceElapsed = 0, revision = it.revision + 1) else it },
            records = s.records + record, mutations = s.mutations + Mutation(taskId = id, action = "记录执行结果"))
    }

    override suspend fun correctRecord(id: String, outcome: TaskStatus, seconds: Long, result: String) = change { s ->
        val old = s.records.first { it.id == id }
        require(seconds >= 0 && outcome in setOf(TaskStatus.COMPLETED, TaskStatus.PARTIAL, TaskStatus.SKIPPED))
        val updated = old.copy(outcome = outcome, seconds = seconds, result = result,
            correctedFrom = "${old.correctedFrom}\n${old.outcome.label}｜${old.seconds} 秒｜${old.result}".trim())
        s.copy(records = s.records.map { if (it.id == id) updated else it },
            tasks = s.tasks.map { if (it.id == old.taskId) it.copy(status = outcome, accumulatedSeconds = seconds) else it },
            mutations = s.mutations + Mutation(taskId = old.taskId, action = "历史订正"))
    }

    override suspend fun undo() = mutex.withLock {
        val previous = undoState ?: error("当前没有可撤销的事项修改")
        val after = undoRevision ?: error("当前没有可撤销的事项修改")
        val current = _state.value
        val touched = (previous.tasks.map { it.id } + after.tasks.map { it.id }).distinct().filter { id -> previous.tasks.find { it.id == id } != after.tasks.find { it.id == id } }
        require(touched.all { id -> current.tasks.find { it.id == id } == after.tasks.find { it.id == id } }) { "内容已变化，请重新检查后修改" }
        val next = current.copy(tasks = current.tasks.filterNot { it.id in touched } + previous.tasks.filter { it.id in touched }, mutations = current.mutations + Mutation(taskId = "local", action = "撤销上次事项修改"))
        persist(SyncEngine.queueChanges(current, next), restoredAlarms = undoAlarms)
        undoState = null; undoRevision = null; undoAlarms = null
    }

    override suspend fun pairDemo(name: String) = change { it.copy(demoConnected = true, deviceName = name.ifBlank { "演示电脑" }) }
    override suspend fun disconnect() {
        val old = mutex.withLock {
            val previous = credentials
            credentialsStore.clear(); credentials = null; generation++
            autoPreferences.edit().putBoolean("enabled", false).apply(); pendingConnection = null
            persist(_state.value.copy(connection = ConnectionInfo(address = previous?.address ?: _state.value.connection.address), demoConnected = false))
            BackgroundSyncWorker.cancel(context)
            previous
        }
        if(old != null) withContext(Dispatchers.IO) { runCatching { client.request(old.address, "/api/unpair", JSONObject(), old.token) } }
    }
    override suspend fun simulateSync() = change {
        require(it.demoConnected) { "请先开启模拟连接" }
        it.copy(mutations = emptyList(), lastDemoSyncAt = System.currentTimeMillis())
    }

    override suspend fun simulatePlanUpdate() = change { s ->
        val target = s.tasks.firstOrNull { it.category == Category.ENGLISH && !it.isProtected } ?: s.tasks.firstOrNull { !it.isProtected }
            ?: error("请先添加一个尚未执行的事项")
        if (s.conflicts.any { it.taskId == target.id }) return@change s
        val incoming = target.copy(scheduledAt = target.start.plusMinutes(30).toString(), title = target.title + " · 调整版", revision = target.revision + 1)
        s.copy(planVersion = s.planVersion + 1, conflicts = s.conflicts + PlanConflict(taskId = target.id, incoming = incoming, sourceVersion = "演示计划 v${s.planVersion + 1}"))
    }

    override suspend fun resolveConflict(id: String, keepPhone: Boolean) = change { s ->
        val conflict = s.conflicts.first { it.id == id }
        val current = s.tasks.first { it.id == conflict.taskId }
        require(keepPhone || !current.isProtected) { "本次执行已开始，请保留手机内容" }
        s.copy(tasks = s.tasks.map { if (it.id == current.id && !keepPhone) TaskRules.applyIncoming(current, conflict.incoming, false) else it },
            conflicts = s.conflicts.filterNot { it.id == id }, mutations = s.mutations + Mutation(taskId = current.id, action = if (keepPhone) "冲突保留手机内容" else "冲突采用新计划"))
    }

    override suspend fun saveUnifiedWorkout(draft: String) = change { s ->
        val d=UnifiedWorkoutDraft.decode(draft) ?: error("训练进度格式无效，尚未保存")
        if(s.workouts.any {it.id==d.id}) return@change s
        require(d.validation()==null) {d.validation().orEmpty()}
        val task=s.tasks.firstOrNull {it.id==d.taskId} ?: error("关联事项已不在有效安排，进度仍保留")
        require(UnifiedTraining.isStrength(task) && !task.isFinished) {"事项已结束或模板已变化，请先核对记录"}
        val workout=d.record()
        val result="周衡V1.4 · ${d.outcome.label} · 实际RPE ${d.rpe.ifBlank { "未记录" }}\n不适：${d.discomfort.ifBlank { "未记录" }}\n恢复：${d.recovery.ifBlank { "待次日补记" }}\n逐组记录ID：${d.id}"
        val execution=ExecutionRecord(taskId=task.id,title=task.title,category=task.category,outcome=d.outcome,
            seconds=d.seconds,result=result,snapshot=d.taskSnapshot.ifBlank {SnapshotCodec.task(task).toString()},isDemo=task.isDemo)
        s.copy(workouts=s.workouts+workout,records=s.records+execution,workoutDraft="",
            tasks=s.tasks.map {if(it.id==task.id) it.copy(status=d.outcome,accumulatedSeconds=d.seconds,runningSinceElapsed=0,revision=it.revision+1) else it},
            mutations=s.mutations+Mutation(taskId=task.id,action="保存全身力量实际组次与结果"))
    }

    override suspend fun saveWorkout(record: WorkoutRecord) = change { s ->
        if (s.workouts.any { it.id == record.id }) s else s.copy(workouts = s.workouts + record, workoutDraft = "",
            mutations = s.mutations + Mutation(taskId = record.id, action = "保存训练记录"))
    }

    override suspend fun saveWorkoutDraft(draft: String) = change { s ->
        // A delayed checkpoint/ON_STOP must not resurrect a session already saved as a record.
        val id=runCatching {JSONObject(draft).optString("id")}.getOrDefault("")
        if(id.isNotBlank() && s.workouts.any {it.id==id}) s else s.copy(workoutDraft=draft)
    }

    override suspend fun restore(snapshot: AppSnapshot) = change { old ->
        snapshot.copy(loaded = true, demoConnected = false, tasks = snapshot.tasks.map { task ->
            if (task.status == TaskStatus.RUNNING) task.copy(status = TaskStatus.PAUSED, runningSinceElapsed = 0) else task
        }, mutations = old.mutations + Mutation(taskId = "restore", action = "恢复本机备份"), connection = old.connection,
            outbox = old.outbox, shadows = old.shadows, syncCursor = old.syncCursor, boundServerId = old.boundServerId,
            syncConflicts = old.syncConflicts, lastRealSyncAt = old.lastRealSyncAt, syncAudit = old.syncAudit + snapshot.syncAudit)
    }

    override suspend fun resetDemo(empty: Boolean) = mutex.withLock {
        require(credentials == null) { "请先断开电脑，再重置本机数据" }
        persist(if(empty) AppSnapshot(loaded = true) else demoSnapshot(LocalDate.now()))
        undoState = null; undoRevision = null; undoAlarms = null
    }

    override suspend fun pair(address: String, code: String) = pairingMutex.withLock {
        require(code.matches(Regex("[0-9]{6}"))) { "请填写电脑显示的 6 位配对码" }
        val normalized = LanAddress.normalize(address)
        mutex.withLock { _state.value = _state.value.copy(connection = _state.value.connection.copy(syncing = true, error = "")) }
        try {
        val result = withContext(Dispatchers.IO) {
            val health = client.health(normalized)
            client.request(normalized, "/api/pair", JSONObject().put("code", code).put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")).also {
                require(it.getString("serverId") == health.getString("serverId")) { "电脑身份发生变化，请重试" }
            }
        }
        acceptConnection(normalized, result)
        runCatching { syncNow() }
        } catch(e: Exception) {
            mutex.withLock { _state.value = _state.value.copy(connection = _state.value.connection.copy(syncing = false, error = e.message ?: "配对未完成，请重试")) }
            throw e
        }
        Unit
    }

    private suspend fun acceptConnection(address: String, result: JSONObject, expectedGeneration: Int? = null): Boolean = mutex.withLock {
            if(expectedGeneration != null && expectedGeneration != generation) return@withLock false
            val next = PairingCredentials(address, result.getString("serverId"), result.getString("serverName"), result.getString("deviceId"), result.getString("token"))
            credentialsStore.save(next); credentials = next; generation++
            autoPreferences.edit().putBoolean("enabled", true).apply(); pendingConnection = null
            persist(SyncEngine.seed(_state.value, next.serverId).copy(connection = ConnectionInfo(true, address, next.serverName, ConnectionPhase.CONNECTING)))
            BackgroundSyncWorker.schedule(context)
            true
    }

    override suspend fun autoConnect(manual: Boolean) = autoMutex.withLock auto@ {
        if(!_state.value.loaded) return@auto
        if(manual) {
            autoPreferences.edit().putBoolean("enabled", true).apply()
            pendingConnection = null; lastSearch = -60_000L
            if(_state.value.connection.phase == ConnectionPhase.AUTH_ERROR) mutex.withLock {
                credentialsStore.clear(); credentials = null; generation++
                _state.value = _state.value.copy(connection = _state.value.connection.copy(paired = false, phase = ConnectionPhase.UNPAIRED))
            }
        }
        if(!autoPreferences.getBoolean("enabled", true)) return@auto
        val old = credentials
        if(old != null) {
            if(_state.value.connection.phase != ConnectionPhase.OFFLINE || SystemClock.elapsedRealtime() - lastSearch < 15_000) return@auto
            lastSearch = SystemClock.elapsedRealtime()
            val found = LanDiscovery(context).find(old.address, old.serverId) ?: return@auto
            if(found.address != old.address) mutex.withLock {
                if(credentials != old) return@withLock
                val updated = old.copy(address = found.address); credentialsStore.save(updated); credentials = updated; generation++
                _state.value = _state.value.copy(connection = _state.value.connection.copy(address = found.address, phase = ConnectionPhase.CONNECTING, error = ""))
            }
            return@auto
        }
        try {
            var pending = pendingConnection
            if(pending != null && pending.expires <= SystemClock.elapsedRealtime()) { pendingConnection = null; pending = null }
            if(pending == null) {
                if(!manual && SystemClock.elapsedRealtime() - lastSearch < 15_000) return@auto
                lastSearch = SystemClock.elapsedRealtime()
                val capturedGeneration = generation
                _state.value = _state.value.copy(connection = _state.value.connection.copy(phase = ConnectionPhase.SEARCHING, syncing = true, error = ""))
                val found = LanDiscovery(context).find(_state.value.connection.address, _state.value.boundServerId.takeIf { it.isNotBlank() })
                    ?: error("还没找到电脑，请确认电脑已启动，手机与电脑连接同一局域网")
                val id = newId(); val secret = newId() + newId()
                val result = withContext(Dispatchers.IO) { client.request(found.address, "/api/connection-request", JSONObject().put("id", id).put("secret", secret).put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")) }
                if(capturedGeneration != generation || !autoPreferences.getBoolean("enabled", true)) return@auto
                pending = PendingConnection(found.address, found.serverId, found.name, id, secret, SystemClock.elapsedRealtime() + 120_000L, capturedGeneration)
                pendingConnection = pending
            }
            val active = pending
            val result = withContext(Dispatchers.IO) { client.request(active.address, "/api/connection-claim", JSONObject().put("id", active.id).put("secret", active.secret)) }
            when(result.getString("status")) {
                "approved" -> {
                    require(result.getString("serverId") == active.serverId) { "电脑身份已变化，请重试" }
                    if(acceptConnection(active.address, result, active.generation)) runCatching { syncNow() }
                }
                "rejected" -> {
                    pendingConnection = null; autoPreferences.edit().putBoolean("enabled", false).apply()
                    error("电脑没有允许此次连接，可在手机点自动连接后重试")
                }
                else -> if(active.generation == generation) _state.value = _state.value.copy(connection = ConnectionInfo(false, active.address, active.name, ConnectionPhase.WAITING_APPROVAL, requestLabel = active.id.takeLast(4).uppercase()))
            }
        } catch(e: CancellationException) {
            if(_state.value.connection.phase == ConnectionPhase.SEARCHING) _state.value = _state.value.copy(connection = _state.value.connection.copy(phase = ConnectionPhase.UNPAIRED, syncing = false))
            throw e
        }
        catch(e: Exception) {
            if(e is LanApiException && e.status == 410) pendingConnection = null
            if(credentials == null) _state.value = _state.value.copy(connection = _state.value.connection.copy(phase = ConnectionPhase.UNPAIRED, syncing = false, error = e.message ?: "自动连接暂时不可用，请重试"))
        }
    }

    override suspend fun syncNow() = syncMutex.withLock sync@ {
        val captured = mutex.withLock {
            val c = credentials ?: error("请先配对电脑")
            val s = _state.value; check(s.loaded)
            _state.value = s.copy(connection = s.connection.copy(syncing = true, phase = if(s.lastRealSyncAt + 12_000 < System.currentTimeMillis()) ConnectionPhase.CONNECTING else s.connection.phase))
            SyncAttempt(c, generation, SyncEngine.batch(s), s.syncCursor)
        }
        val c = captured.credentials
        try {
            val result = withContext(Dispatchers.IO) {
                val health = client.health(c.address)
                require(health.getString("serverId") == c.serverId) { "电脑身份已变化，请重新配对" }
                client.request(c.address, "/api/sync", JSONObject().put("cursor", captured.cursor).put("operations", JSONArray().apply { captured.operations.forEach { put(SyncEngine.operation(it)) } }), c.token)
            }
            require(result.getString("serverId") == c.serverId) { "电脑身份不一致" }
            mutex.withLock {
                if(generation != captured.generation) return@withLock
                val merged = SyncEngine.merge(_state.value, captured.operations, result)
                persist(merged.copy(lastRealSyncAt = System.currentTimeMillis(), connection = merged.connection.copy(phase = ConnectionPhase.ONLINE, error = "", syncing = false)))
            }
            if(credentials?.serverId==c.serverId) clockDiagnosticOutbox.flush(client,c)
        } catch(error: CancellationException) { throw error }
        catch(error: Exception) {
            mutex.withLock { if(generation == captured.generation) _state.value = _state.value.copy(connection = _state.value.connection.copy(
                phase = if(error is LanApiException && error.status == 401) ConnectionPhase.AUTH_ERROR else ConnectionPhase.OFFLINE,
                error = when(error) { is java.net.SocketTimeoutException -> "连接超时，请确认电脑服务已启动"; is java.net.ConnectException -> "无法连接电脑，请检查地址、局域网和服务"; else -> error.message ?: "连接暂时不可用" }, syncing = false)) }
            throw error
        } finally {
            withContext(NonCancellable) { mutex.withLock { if(generation == captured.generation) _state.value = _state.value.copy(connection = _state.value.connection.copy(syncing = false)) } }
        }
    }

    override suspend fun openOnComputer(taskId: String, resourceUrl: String): String {
        syncNow()
        val (paired, task) = mutex.withLock {
            val paired = credentials ?: error("请先连接电脑")
            check(_state.value.syncConflicts.none { it.key == "task:$taskId" }) { "这项课程有同步冲突，请先比较确认" }
            val task = _state.value.tasks.find { it.id == taskId } ?: error("事项已不存在")
            check(task.category in setOf(Category.AI, Category.ENGLISH) && !task.isDemo && LearningResources.from(task).isNotEmpty()) { "这项学习任务还没有可用资料" }
            paired to task
        }
        val selectedUrl = resourceUrl.ifBlank { task.resourceUrl }
        check(LearningResources.from(task).any { it.url == selectedUrl }) { "资料已变化，请重新选择课程资源" }
        val result = withContext(Dispatchers.IO) {
            client.request(paired.address, "/api/open-on-computer", JSONObject().put("id", newId()).put("taskId", task.id).put("resourceUrl", selectedUrl), paired.token)
        }
        check(result.optBoolean("ok")) { result.optString("message", "电脑暂未确认打开请求") }
        return result.getString("message")
    }

    override suspend fun resolveSyncConflict(key: String, keepPhone: Boolean) = mutex.withLock {
        persist(SyncEngine.resolve(_state.value, key, keepPhone))
    }
    override suspend fun checkUpdate() { updater.check(_state.value.connection.address, credentials?.serverId) }
    override suspend fun downloadUpdate() { updater.download() }
    override fun updateInstallIntent() = updater.installIntent()
}
