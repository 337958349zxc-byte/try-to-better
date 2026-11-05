package com.trytobatter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trytobatter.app.core.data.*
import com.trytobatter.app.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncEngineInstrumentedTest {
    private fun task(title: String = "原安排") = TaskItem(id = "sync-test", title = title, category = Category.AI, scheduledAt = "2026-10-26T09:00", durationMinutes = 30)
    private fun queued(t: TaskItem = task()) = SyncEngine.queueChanges(AppSnapshot(), AppSnapshot(tasks = listOf(t), loaded = true))
    private fun entity(t: TaskItem, revision: Int) = JSONObject().put("key", "task:${t.id}").put("revision", revision).put("payload", SnapshotCodec.task(t)).put("deleted", false)
    private fun response(cursor: Long, acks: JSONArray = JSONArray(), changes: JSONArray = JSONArray()) = JSONObject().put("cursor", cursor).put("planVersion", 2).put("acknowledged", acks).put("conflicts", JSONArray()).put("changes", changes)

    @Test fun localEditDuringNetworkRequestSurvivesOldAcknowledgement() {
        val original = queued(); val first = original.outbox.single()
        val edited = SyncEngine.queueChanges(original, original.copy(tasks = listOf(task("请求中又改了一次"))))
        val ack = entity(task(), 1).put("operationId", first.id)
        val merged = SyncEngine.merge(edited, listOf(first), response(1, JSONArray().put(ack), JSONArray().put(ack)))
        assertEquals("请求中又改了一次", merged.tasks.single().title)
        assertEquals(1, merged.outbox.single().baseRevision)
        assertNotEquals(first.id, merged.outbox.single().id)
    }
    @Test fun cursorAdvancesWithoutSilentlyAcceptingConflictingRemoteChanges() {
        val s = queued()
        val merged = SyncEngine.merge(s, emptyList(), response(2, changes = JSONArray().put(entity(task("电脑修改"), 2))))
        assertEquals("原安排", merged.tasks.single().title)
        assertEquals(s.outbox, merged.outbox)
        val chosen = SyncEngine.resolve(merged, "task:sync-test", true)
        assertEquals(2, chosen.outbox.single().baseRevision)
        assertTrue(chosen.syncConflicts.isEmpty())
        assertEquals(1, chosen.syncAudit.size)
    }
    @Test fun startedSnapshotCannotBeReplacedEvenByExplicitComputerChoice() {
        val t = TaskRules.start(task(), 1000, 2000, 1)
        val s = AppSnapshot(tasks = listOf(t))
        val merged = SyncEngine.merge(s, emptyList(), response(1, changes = JSONArray().put(entity(task("新计划"), 1))))
        assertEquals(t.executionSnapshot, merged.tasks.single().executionSnapshot)
        assertTrue(runCatching { SyncEngine.resolve(merged, "task:sync-test", false) }.isFailure)
    }
    @Test fun missingOrWrongReceiptCannotRemoveQueuedOperation() {
        val s = queued()
        val wrong = entity(task(), 1).put("operationId", "unknown")
        assertTrue(runCatching { SyncEngine.merge(s, s.outbox, response(1, JSONArray().put(wrong))) }.isFailure)
        assertEquals(1, s.outbox.size)
        assertEquals(s.outbox, SyncEngine.merge(s, emptyList(), response(0)).outbox)
    }
    @Test fun persistedOutboxRetainsIdsForSafeRetryAndDoesNotExportCredentials() {
        val s = queued(); val encoded = SnapshotCodec.encode(s)
        assertEquals(s.outbox, SnapshotCodec.decode(encoded).outbox)
        assertFalse(encoded.contains("\"token\""))
    }
    @Test fun remoteTombstonePersistsAndRepeatedDeliveryDoesNotResurrectTask() {
        val deleted = JSONObject().put("key", "task:sync-test").put("revision", 3).put("payload", JSONObject()).put("deleted", true)
        val s = SyncEngine.merge(AppSnapshot(tasks = listOf(task())), emptyList(), response(3, changes = JSONArray().put(deleted)))
        assertTrue(s.tasks.isEmpty())
        assertTrue(SnapshotCodec.decode(SnapshotCodec.encode(s)).shadows.single().deleted)
        assertTrue(SyncEngine.merge(s, emptyList(), response(3, changes = JSONArray().put(deleted))).tasks.isEmpty())
    }
    @Test fun malformedIncomingTaskDoesNotPartiallyCommit() {
        val e = entity(task(), 1); e.getJSONObject("payload").put("durationMinutes", -1)
        assertTrue(runCatching { SyncEngine.merge(queued(), emptyList(), response(1, changes = JSONArray().put(e))) }.isFailure)
    }
    @Test fun eachBatchContainsOnlyFirstImmutableOperationPerEntity() {
        val s = queued(); val second = SyncEngine.queueChanges(s, s.copy(tasks = listOf(task("后续"))))
        assertEquals(2, second.outbox.size)
        assertEquals(s.outbox, SyncEngine.batch(second))
    }
    @Test fun updateMetadataRejectsDifferentHostAndUnmatchedPackage() {
        val j = JSONObject().put("packageName", "com.trytobatter.app").put("versionCode", 3).put("versionName", "0.2.1").put("size", 100).put("sha256", "a".repeat(64)).put("downloadUrl", "http://192.168.0.23:8080/app.apk")
        assertEquals(3L, AppUpdateManager.parseRelease(j, "192.168.0.23:8081", "com.trytobatter.app").versionCode)
        assertTrue(runCatching { AppUpdateManager.parseRelease(j.put("downloadUrl", "http://192.168.0.99:8080/app.apk"), "192.168.0.23:8081", "com.trytobatter.app") }.isFailure)
        assertTrue(runCatching { LanAddress.normalize("https://example.com") }.isFailure)
    }
}
