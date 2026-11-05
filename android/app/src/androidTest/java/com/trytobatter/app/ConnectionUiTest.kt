package com.trytobatter.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trytobatter.app.core.data.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ConnectionUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun clickItem(text: String) { ui.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text)); ui.onNodeWithText(text).performClick() }
    private fun shot(name: String) { val file = File(ui.activity.getExternalFilesDir(null), "$name.png"); file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
    @Test fun pairFromUiThenAutomaticallyExchangePlansAndResolveConflict() {
        ui.runOnUiThread { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val repo = (ui.activity.application as BatterApplication).repository
        val client = LanClient(ui.activity)
        val admin = "http://127.0.0.1:18081"
        fun desktop() = client.request(admin, "/api/desktop")
        ui.waitUntil(15000) { repo.state.value.loaded }
        runBlocking { if(repo.state.value.connection.paired) repo.disconnect(); repo.resetDemo(true) }
        ui.onNodeWithText("我的", useUnmergedTree = true).performClick()
        clickItem("电脑连接")
        ui.onNodeWithText("电脑局域网地址").performScrollTo().performTextReplacement("http://192.168.0.23:18081")
        ui.onNodeWithText("电脑显示的 6 位配对码").performScrollTo().performTextInput(desktop().getString("pairCode"))
        clickItem("配对并连接电脑")
        try {
            ui.waitUntil(10000) { repo.state.value.connection.phase == ConnectionPhase.ONLINE }
            shot("14-real-connection")
            val task = TaskItem(title = "前台自动同步验收", category = Category.LIFE, scheduledAt = LocalDate.now().atTime(10, 0).toString())
            runBlocking { repo.saveTask(task) }
            ui.waitUntil(12000) { repo.state.value.outbox.isEmpty() }
            check(desktop().getJSONArray("entities").toString().contains(task.id))
            client.request(admin, "/test/network", JSONObject().put("offline", true))
            runBlocking { repo.saveTask(task.copy(title = "离线手机内容")) }
            ui.waitUntil(12000) { repo.state.value.connection.phase == ConnectionPhase.OFFLINE }
            shot("15-real-offline")
            val ds = desktop(); val entities = ds.getJSONArray("entities"); val revision = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("key") == "task:${task.id}" }.getInt("revision")
            client.request(admin, "/api/publish", JSONObject().put("basePlanVersion", ds.getInt("planVersion")).put("expectedRevisions", JSONObject().put(task.id, revision)).put("tasks", JSONArray().put(SnapshotCodec.task(task.copy(title = "电脑新安排")))))
            client.request(admin, "/test/network", JSONObject().put("offline", false))
            ui.waitUntil(12000) { repo.state.value.syncConflicts.any { it.key == "task:${task.id}" } }
            clickItem("查看计划更新与冲突")
            shot("16-real-conflict")
            clickItem("保留手机内容")
            ui.waitUntil(12000) { repo.state.value.outbox.isEmpty() && repo.state.value.syncConflicts.isEmpty() }
            check(desktop().getJSONArray("entities").toString().contains("离线手机内容"))
            ui.onNodeWithContentDescription("返回").performClick()
            clickItem("检查 App 更新")
            shot("17-app-updates")
        } finally {
            client.request(admin, "/test/network", JSONObject().put("offline", false))
            runBlocking { repo.disconnect(); repo.resetDemo() }
        }
    }
}
