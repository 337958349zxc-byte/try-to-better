package com.trytobatter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.core.data.*
import com.trytobatter.app.core.model.*
import com.trytobatter.app.core.alarm.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LanIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val client get() = LanClient(context)
    private val admin = "http://127.0.0.1:18081" // adb reverse: fixture admin remains loopback-only.
    private val address get() = InstrumentationRegistry.getArguments().getString("lanAddress", "http://192.168.0.23:18081")!!
    private fun desktop() = client.request(admin, "/api/desktop")
    private fun paired(): LocalDemoRepository = runBlocking {
        client.request(admin, "/test/network", JSONObject().put("offline", false))
        val repo = LocalDemoRepository(context); repo.load(); if(repo.state.value.connection.paired) repo.disconnect(); repo.resetDemo(true)
        repo.pair(address, desktop().getString("pairCode")); repo
    }
    private suspend fun cleanup(repo: LocalDemoRepository) { client.request(admin, "/test/network", JSONObject().put("offline", false)); repo.disconnect(); repo.resetDemo() }

    @Test fun clockDiagnosticQueuesOfflineAndPcReceiptSurvivesRepositoryRecreation() = runBlocking {
        val repo=paired()
        try {
            repo.traceClockEvent("诊断传输验收：设置三条、取消三条的请求记录")
            client.request(admin,"/test/network",JSONObject().put("offline",true))
            assertTrue(repo.sendClockDiagnostics().contains("保存在手机"))
            val recreated=LocalDemoRepository(context);recreated.load()
            assertTrue(recreated.clockDiagnosticDelivery.value.contains("保存在手机"))
            client.request(admin,"/test/network",JSONObject().put("offline",false))
            recreated.syncNow()
            assertTrue(recreated.clockDiagnosticDelivery.value.contains("电脑已接收"))
            val reports=client.request(admin,"/api/device-diagnostics").getJSONArray("reports")
            assertTrue((0 until reports.length()).any {reports.getJSONObject(it).getString("report").contains("诊断传输验收")})
            val count=reports.length();recreated.syncNow()
            assertEquals(count,client.request(admin,"/api/device-diagnostics").getJSONArray("reports").length())
            assertTrue(recreated.state.value.outbox.isEmpty())
        } finally {cleanup(repo)}
    }

    @Test fun realPairUploadReceiveAndResolveOfflineConflict() = runBlocking {
        val repo = paired()
        try {
            assertEquals(ConnectionPhase.ONLINE, repo.state.value.connection.phase)
            val t = TaskItem(title = "真实局域网上传", category = Category.LIFE, scheduledAt = LocalDate.now().atTime(9, 0).toString())
            repo.saveTask(t); repo.syncNow()
            assertTrue(repo.state.value.outbox.isEmpty())
            assertTrue(desktop().getJSONArray("entities").toString().contains(t.id))
            val incoming = t.copy(id = newId(), title = "电脑发布到手机")
            client.request(admin, "/api/publish", JSONObject().put("basePlanVersion", desktop().getInt("planVersion")).put("tasks", JSONArray().put(SnapshotCodec.task(incoming))))
            repo.syncNow(); assertTrue(repo.state.value.tasks.any { it.id == incoming.id })
            client.request(admin, "/test/network", JSONObject().put("offline", true))
            repo.saveTask(t.copy(title = "离线修改保留"))
            assertTrue(runCatching { repo.syncNow() }.isFailure)
            assertEquals(ConnectionPhase.OFFLINE, repo.state.value.connection.phase)
            assertTrue(repo.state.value.outbox.isNotEmpty())
            val desktopState = desktop(); val entities = desktopState.getJSONArray("entities"); val serverVersion = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("key") == "task:${t.id}" }.getInt("revision")
            client.request(admin, "/api/publish", JSONObject().put("basePlanVersion", desktopState.getInt("planVersion")).put("expectedRevisions", JSONObject().put(t.id, serverVersion)).put("tasks", JSONArray().put(SnapshotCodec.task(t.copy(title = "电脑同时修改")))))
            client.request(admin, "/test/network", JSONObject().put("offline", false))
            repo.syncNow(); assertTrue(repo.state.value.syncConflicts.any { it.key == "task:${t.id}" })
            assertEquals("离线修改保留", repo.state.value.tasks.first { it.id == t.id }.title)
            repo.resolveSyncConflict("task:${t.id}", true); repo.syncNow()
            assertTrue(repo.state.value.outbox.isEmpty()); assertTrue(desktop().getJSONArray("entities").toString().contains("离线修改保留"))
        } finally { cleanup(repo) }
    }
    @Test fun encryptedPairingSurvivesRepositoryRecreationAndRevocationKeepsLocalData() = runBlocking {
        val repo = paired()
        try {
            val c = CredentialStore(context).read()!!
            val recreated = LocalDemoRepository(context); recreated.load(); recreated.syncNow()
            assertEquals(ConnectionPhase.ONLINE, recreated.state.value.connection.phase)
            val t = TaskItem(title = "撤销后仍可本机编辑", category = Category.AI, scheduledAt = LocalDate.now().atTime(11, 0).toString())
            recreated.saveTask(t)
            client.request(admin, "/api/revoke", JSONObject().put("deviceId", c.deviceId))
            assertTrue(runCatching { recreated.syncNow() }.isFailure)
            assertEquals(ConnectionPhase.AUTH_ERROR, recreated.state.value.connection.phase)
            assertTrue(recreated.state.value.tasks.any { it.id == t.id }); assertTrue(recreated.state.value.outbox.isNotEmpty())
        } finally { cleanup(repo) }
    }
    @Test fun releasedVersionCanBeCheckedOverActualLan() = runBlocking {
        val repo = paired()
        try { repo.checkUpdate(); assertTrue(repo.updates.value.phase in setOf(UpdatePhase.CURRENT, UpdatePhase.AVAILABLE)); assertEquals("com.trytobatter.app", repo.updates.value.release!!.packageName) }
        finally { cleanup(repo) }
    }
    @Test fun desktopReschedulingAndOfflineConflictPreserveLocalAlarmPreferences() = runBlocking {
        val repo=paired()
        try {
            val t=TaskItem(title="同步闹钟联动",category=Category.ENGLISH,scheduledAt=java.time.LocalDateTime.now().plusHours(2).toString())
            repo.saveTask(t);repo.setTaskAlarm(t.id,TaskAlarmSettings(true));repo.syncNow()
            val initial=repo.alarms.value.records.getValue(t.id).triggerAt
            fun publish(hours:Long) {
                val ds=desktop();val es=ds.getJSONArray("entities")
                val revision=(0 until es.length()).map {es.getJSONObject(it)}.first {it.getString("key")=="task:${t.id}"}.getInt("revision")
                client.request(admin,"/api/publish",JSONObject().put("basePlanVersion",ds.getInt("planVersion")).put("expectedRevisions",JSONObject().put(t.id,revision))
                    .put("tasks",JSONArray().put(SnapshotCodec.task(t.copy(scheduledAt=t.start.plusHours(hours).toString())))))
            }
            publish(1);repo.syncNow()
            assertEquals(initial+3_600_000,repo.alarms.value.records.getValue(t.id).triggerAt)
            client.request(admin,"/test/network",JSONObject().put("offline",true))
            val local=repo.state.value.tasks.first {it.id==t.id}
            repo.saveTask(local.copy(scheduledAt=t.start.plusHours(2).toString()))
            assertEquals(initial+7_200_000,repo.alarms.value.records.getValue(t.id).triggerAt)
            publish(3);client.request(admin,"/test/network",JSONObject().put("offline",false));repo.syncNow()
            assertTrue(repo.state.value.syncConflicts.any {it.key=="task:${t.id}"})
            assertEquals(initial+7_200_000,repo.alarms.value.records.getValue(t.id).triggerAt)
            repo.resolveSyncConflict("task:${t.id}",false)
            assertEquals(initial+10_800_000,repo.alarms.value.records.getValue(t.id).triggerAt)
            assertTrue(repo.alarms.value.settings.getValue(t.id).enabled)
        } finally {cleanup(repo)}
    }
}
