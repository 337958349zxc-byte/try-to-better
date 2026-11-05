package com.trytobatter.app.core.model

import com.trytobatter.app.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Durable session snapshot. Blank counts are unknown, never the prescribed repetitions. */
data class UnifiedWorkoutDraft(
    val id:String=newId(),val taskId:String,val day:Int,val week2:Boolean=false,
    val rows:List<StrengthActual> = UnifiedTraining.rows(week2),val startedAt:Long=0,
    val seconds:Long=0,val running:Boolean=false,val rpe:String="",val discomfort:String="",
    val recovery:String="",val outcome:TaskStatus=TaskStatus.PARTIAL,val taskSnapshot:String="",
) {
    fun encode():String=JSONObject().apply {
        put("templateId",UnifiedTraining.templateId);put("id",id);put("taskId",taskId);put("day",day);put("week2",week2)
        put("startedAt",startedAt);put("seconds",seconds);put("running",running);put("rpe",rpe)
        put("discomfort",discomfort);put("recovery",recovery);put("outcome",outcome.name);put("taskSnapshot",taskSnapshot)
        put("rows",JSONArray().apply {rows.forEach {r -> put(JSONObject().apply {
            put("exerciseId",r.exerciseId);put("index",r.index);put("left",r.left);put("right",r.right);put("done",r.done);put("automatic",r.automatic)
        })}})
    }.toString()
    fun validation():String? = when {
        !UnifiedTraining.validRows(rows,week2) -> "训练组次与模板不一致，请保留进度并检查"
        startedAt<=0 -> "请先开始训练；未执行可在事项结果中记录跳过"
        rpe.isNotBlank() && rpe.toIntOrNull()?.let {it in 0..10}!=true -> "RPE请输入0–10，未知可留空"
        rows.any {r -> (r.left.isNotBlank() || r.right.isNotBlank() || r.done) && !r.countsValid(UnifiedTraining.moves.first {it.id==r.exerciseId}.perSide)} -> "已填写的组次需填完整整数；单侧动作请分别填左右"
        outcome==TaskStatus.COMPLETED && !UnifiedTraining.complete(rows,week2) -> "完整完成需核对全部组次；否则请选择部分完成"
        else -> null
    }
    fun record():WorkoutRecord {
        require(validation()==null) {validation().orEmpty()}
        val actual=rows.filter {it.left.isNotBlank() || it.right.isNotBlank()}.map {r ->
            val m=UnifiedTraining.moves.first {it.id==r.exerciseId}
            WorkoutSet(exercise=m.name,setIndex=r.index,autoReps=r.automatic,
                confirmedReps=r.left.toInt()+(if(m.perSide) r.right.toInt() else 0),quality="无法判断",
                note="逐组人工确认；计划 ${m.target(week2)}；"+(if(m.perSide) "左 ${r.left}／右 ${r.right} 次；确认次数为左右合计；" else "")+
                    if(r.done) "本组已确认完成" else "本组部分完成")
        }
        val plan=JSONArray().apply {UnifiedTraining.moves.forEach {m ->put(JSONObject().apply {
            put("exercise",m.name);put("sets",m.sets(week2));put("target",m.target(week2));put("guide",m.guide)
        })}}
        val metadata=JSONObject().apply {
            put("templateId",UnifiedTraining.templateId);put("taskId",taskId);put("day",day)
            put("week2Confirmed",week2);put("startedAt",startedAt);put("endedAt",System.currentTimeMillis())
            put("outcome",outcome.name);put("rpe",rpe.toIntOrNull()?:JSONObject.NULL)
            put("discomfort",discomfort);put("recovery",recovery);put("plannedExercises",plan)
        }
        return WorkoutRecord(id=id,sets=actual,seconds=seconds,simulated=false,note=metadata.toString())
    }
    companion object {
        fun decode(raw:String):UnifiedWorkoutDraft?=runCatching {
            val j=JSONObject(raw);require(j.optString("templateId")==UnifiedTraining.templateId)
            val a=j.getJSONArray("rows")
            UnifiedWorkoutDraft(id=j.getString("id"),taskId=j.getString("taskId"),day=j.getInt("day"),week2=j.getBoolean("week2"),
                rows=(0 until a.length()).map {i ->val r=a.getJSONObject(i);StrengthActual(r.getString("exerciseId"),r.getInt("index"),r.optString("left"),r.optString("right"),r.optBoolean("done"),r.optInt("automatic"))},
                startedAt=j.optLong("startedAt"),seconds=j.optLong("seconds"),running=false,rpe=j.optString("rpe"),
                discomfort=j.optString("discomfort"),recovery=j.optString("recovery"),outcome=TaskStatus.valueOf(j.optString("outcome","PARTIAL")),taskSnapshot=j.optString("taskSnapshot"))
                .also {require(it.day in setOf(1,3,5,8,10,12) && (!it.week2 || it.day>=8) && it.seconds>=0 && it.startedAt>=0 && it.outcome in setOf(TaskStatus.COMPLETED,TaskStatus.PARTIAL,TaskStatus.SKIPPED) && UnifiedTraining.validRows(it.rows,it.week2))}
        }.getOrNull()
    }
}
