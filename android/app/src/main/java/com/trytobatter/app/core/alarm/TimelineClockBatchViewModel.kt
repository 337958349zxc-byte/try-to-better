package com.trytobatter.app.core.alarm

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.trytobatter.app.BatterApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ClockBatchProgress(val id:String="",val queue:List<ClockEntry> = emptyList(),val flight:ClockEntry?=null,
    val launched:Boolean=false,val left:Boolean=false,val returned:Boolean=false,val preparing:Boolean=false,
    val stop:Boolean=false,val closing:Boolean=false,val total:Int=0,val finished:Int=0,val issue:String="") {
    val busy get()=preparing || stop || flight!=null || queue.isNotEmpty()
}

/** Owns the foreground handoff queue across Compose disposal and activity recreation. */
class TimelineClockBatchViewModel(application:Application,private val saved:SavedStateHandle):AndroidViewModel(application) {
    private val repository=(application as BatterApplication).repository
    private val _state=MutableStateFlow(restore(saved.get<String>("clockBatch")))
    val state=_state.asStateFlow()
    private fun update(change:(ClockBatchProgress)->ClockBatchProgress) {
        val next=change(_state.value);_state.value=next
        saved["clockBatch"]=JSONObject().put("id",next.id).put("queue",JSONArray().apply {next.queue.forEach {put(SystemClockBridge.toJson(it))}})
            .put("flight",next.flight?.let {SystemClockBridge.toJson(it)}).put("launched",next.launched).put("left",next.left)
            .put("returned",next.returned).put("stop",next.stop).put("closing",next.closing).put("total",next.total).put("finished",next.finished).put("issue",next.issue).toString()
    }
    private suspend fun trace(message:String) {
        try {repository.traceClockEvent("批次 ${_state.value.id} $message")}
        catch(e:CancellationException) {throw e} catch(_:Exception) { /* Diagnostic IO cannot stand in for a clock result. */ }
    }
    private fun upload() {viewModelScope.launch {try {repository.sendClockDiagnostics()} catch(e:CancellationException) {throw e} catch(_:Exception) {}}}
    fun begin(enabled:Boolean) {
        if(_state.value.busy) {
            if(!enabled) {update {it.copy(stop=true,queue=emptyList(),closing=true)};viewModelScope.launch {trace("用户停止剩余创建并要求取消关联闹钟")}}
            return
        }
        prepare(enabled)
    }
    private fun prepare(enabled:Boolean) {
        update {it.copy(preparing=true,stop=false,issue="",closing=!enabled)}
        viewModelScope.launch {
            try {
                val entries=repository.prepareTimelineClockBatch(enabled)
                update {it.copy(id=UUID.randomUUID().toString(),queue=if(it.stop) emptyList() else entries,total=entries.size,finished=0)}
                trace("准备 ${if(enabled) "设置" else "取消"} ${entries.size} 项")
                if(entries.isEmpty()) upload()
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {update {it.copy(issue=e.message ?: "系统闹钟请求未完成")};upload()}
            finally {update {it.copy(preparing=false)}}
        }
    }
    fun leftApp() {if(_state.value.flight!=null && _state.value.launched) update {it.copy(left=true)}}
    fun returned(resultCode:Int) {
        if(_state.value.flight==null) return
        update {it.copy(returned=true)}
        viewModelScope.launch {trace("Activity 返回 resultCode=$resultCode；此码不确认创建或取消")}
    }
    /** This method never suspends. IO uses viewModelScope, so its own state changes cannot cancel it. */
    fun pump(resumed:Boolean,launch:(Intent)->Unit) {
        val current=_state.value
        if(!resumed || current.preparing) return
        val entry=current.flight
        if(entry!=null) {
            if(!current.launched) {
                if(current.stop && entry.action==ClockAction.SET) {update {it.copy(flight=null)};return}
                try {
                    val intent=repository.systemClockIntent(entry)
                    update {it.copy(launched=true,left=false,returned=false)}
                    launch(intent)
                    viewModelScope.launch {trace("已调用 ${entry.action} ${entry.label}")}
                } catch(e:Exception) {failed(entry,e)}
            } else if(current.returned || current.left) finish(entry,if(current.returned) "activity-result" else "foreground-return")
            return
        }
        if(current.stop) {prepare(false);return}
        val next=current.queue.firstOrNull() ?: return
        update {it.copy(preparing=true,flight=next,queue=it.queue.drop(1),launched=false,left=false,returned=false)}
        viewModelScope.launch {
            try {repository.recordTimelineClockRequest(next.copy(stage=ClockRequestStage.PREPARING));trace("准备调用 ${next.action} ${next.label}")}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {failed(next,e)}
            finally {update {it.copy(preparing=false)}}
        }
    }
    private fun finish(entry:ClockEntry,via:String) {
        update {it.copy(preparing=true)}
        viewModelScope.launch {
            try {
                repository.recordTimelineClockRequest(entry.copy(stage=ClockRequestStage.REQUESTED))
                update {it.copy(flight=null,launched=false,left=false,returned=false,finished=it.finished+1)}
                trace("交接结束 $via ${entry.action} ${entry.label}；剩余 ${_state.value.queue.size}；实际效果未核验")
                if(_state.value.queue.isEmpty() && !_state.value.stop) upload()
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {failed(entry,e)}
            finally {update {it.copy(preparing=false)}}
        }
    }
    private fun failed(entry:ClockEntry,e:Exception) {
        update {it.copy(preparing=true,queue=emptyList(),flight=null,launched=false,issue=SystemClockBridge.failureText(e))}
        viewModelScope.launch {
            try {repository.recordTimelineClockRequest(entry.copy(stage=ClockRequestStage.FAILED,error="${e.javaClass.simpleName}: ${e.message}"));trace("调用失败 ${entry.action} ${e.javaClass.simpleName}: ${e.message}")}
            finally {update {it.copy(preparing=false)};upload()}
        }
    }
    companion object {
        private fun restore(text:String?):ClockBatchProgress = try {
            if(text==null) ClockBatchProgress() else {
                val j=JSONObject(text);val queue=j.optJSONArray("queue") ?: JSONArray()
                if(j.optBoolean("probe") || j.optJSONObject("flight")?.optString("probeId").orEmpty().isNotBlank() ||
                    (0 until queue.length()).any {queue.getJSONObject(it).optString("probeId").isNotBlank()}) ClockBatchProgress() else {
                    val flight=j.optJSONObject("flight")?.let {SystemClockBridge.fromJson(it)}
                    ClockBatchProgress(j.optString("id"),(0 until queue.length()).map {SystemClockBridge.fromJson(queue.getJSONObject(it))},flight,
                        j.optBoolean("launched"),left=flight!=null && j.optBoolean("launched"),returned=j.optBoolean("returned"),
                        stop=j.optBoolean("stop"),closing=j.optBoolean("closing"),total=j.optInt("total"),finished=j.optInt("finished"),issue=j.optString("issue"))
                }
            }
        } catch(_:Exception) {ClockBatchProgress(issue="上次批次记录不可用，请重新设置或关闭关联闹钟")}
    }
}
