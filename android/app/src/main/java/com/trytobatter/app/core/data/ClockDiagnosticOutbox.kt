package com.trytobatter.app.core.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Separate from task synchronization. A report leaves this outbox only after a PC receipt. */
class ClockDiagnosticOutbox(context: Context) {
    private val file=AtomicFile(File(context.filesDir,"clock-diagnostics-outbox.json"))
    private val mutex=Mutex()
    private var journal=try {
        if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists()) JSONObject(file.openRead().use {it.readBytes().toString(Charsets.UTF_8)})
        else JSONObject().put("pending",JSONArray())
    } catch(_:Exception) {JSONObject().put("pending",JSONArray()).put("error","诊断待发送记录读取失败")}
    private val _status=MutableStateFlow(journal.optString("status",journal.optString("error")))
    val status=_status.asStateFlow()
    private fun save(next:JSONObject) {
        val stream=file.startWrite()
        try {stream.write(next.toString().toByteArray());file.finishWrite(stream);journal=next;_status.value=next.optString("status")}
        catch(e:Exception) {file.failWrite(stream);throw e}
    }
    suspend fun enqueue(report:String, serverId:String)=withContext(Dispatchers.IO) {mutex.withLock {
        check(!journal.has("error")) {journal.getString("error")}
        val next=JSONObject(journal.toString());val pending=next.getJSONArray("pending")
        check(pending.length()<20) {"待发送诊断较多，请连接电脑后重试"}
        pending.put(JSONObject().put("id",UUID.randomUUID().toString()).put("serverId",serverId).put("report",report))
        save(next.put("status","诊断已保存在手机，等待电脑接收"))
    }}
    suspend fun flush(client:LanClient, credentials:PairingCredentials)=withContext(Dispatchers.IO) {mutex.withLock {
        val pending=journal.optJSONArray("pending") ?: return@withLock
        if(pending.length()==0) return@withLock
        try {
            check(client.health(credentials.address).getString("serverId")==credentials.serverId) {"电脑身份已变化，诊断保留在手机"}
            while(true) {
                val items=journal.getJSONArray("pending")
                val index=(0 until items.length()).firstOrNull {items.getJSONObject(it).getString("serverId")==credentials.serverId} ?: break
                val item=items.getJSONObject(index)
                val response=client.request(credentials.address,"/api/device-diagnostics",JSONObject().put("id",item.getString("id")).put("report",item.getString("report")),credentials.token)
                check(response.optBoolean("ok") && response.getString("serverId")==credentials.serverId && response.getJSONObject("receipt").getString("id")==item.getString("id")) {"未收到有效电脑回执"}
                val next=JSONObject(journal.toString());next.getJSONArray("pending").remove(index)
                save(next.put("status","电脑已接收时钟诊断 · 回执 ${item.getString("id")}"))
            }
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {save(JSONObject(journal.toString()).put("status","诊断已保存在手机，连接电脑后自动重试"))}
    }}
}
