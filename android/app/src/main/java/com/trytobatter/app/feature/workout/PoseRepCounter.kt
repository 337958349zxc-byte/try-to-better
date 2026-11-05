package com.trytobatter.app.feature.workout

import kotlin.math.*

data class PosePoint(val x:Float,val y:Float,val confidence:Float)
data class PoseFrame(val points:List<PosePoint>,val width:Int,val height:Int,val timestamp:Long)
data class RepUpdate(val tracked:Boolean,val delta:Int=0,val message:String)

/** Initial per-exercise side-view rules. Counts complete cycles, not pose detections. */
class PoseRepCounter(private val exercise:String) {
    private var side=-1
    private var phase=0
    private var candidate=""
    private var candidateSince=0L
    private var movementSince=-1L
    private var lastFrame=-1L
    private var lastTracked=-1L
    private var smoothed:Double?=null
    fun reset() {side=-1;phase=0;candidate="";movementSince=-1;lastFrame=-1;lastTracked=-1;smoothed=null}
    fun update(frame:PoseFrame):RepUpdate {
        if(frame.timestamp<=lastFrame) return RepUpdate(false,message="等待新画面")
        if(lastFrame>=0 && frame.timestamp-lastFrame>750 || lastTracked>=0 && frame.timestamp-lastTracked>750) reset()
        lastFrame=frame.timestamp
        if(frame.points.size<33 || frame.width<=0 || frame.height<=0) return missing("未检测到人体，请调整距离与光线")
        if(exercise !in listOf("chair","wall")) return missing("当前动作暂不支持自动计数")
        // Count from the joints actually used by this action; feet do not gate elbow motion.
        val required=if(exercise=="chair") listOf(23,25,27) else listOf(11,13,15)
        val names=if(exercise=="chair") listOf("髋","膝","脚踝") else listOf("肩","肘","手腕")
        fun readable(p:PosePoint)=p.x in 0f..1f && p.y in 0f..1f && p.confidence.isFinite() && p.confidence>=.55f
        fun quality(offset:Int)=required.minOf {index -> val p=frame.points[index+offset]
            if(readable(p)) p.confidence else 0f }
        val left=quality(0);val right=quality(1)
        if(max(left,right)<.55f) {
            val best=listOf(0,1).maxBy {offset -> required.count {readable(frame.points[it+offset])}}
            val absent=required.indices.filter {!readable(frame.points[required[it]+best])}.joinToString("、") {names[it]}
            return missing("已检测到人体，${absent}不清晰；请侧身让同一侧${names.joinToString("、")}入镜")
        }
        if(side<0 || quality(side)<.55f) {reset();lastFrame=frame.timestamp;side=if(left>=right) 0 else 1}
        val p=required.map {frame.points[it+side]}
        fun vector(a:PosePoint,b:PosePoint)=Pair((a.x-b.x)*frame.width.toDouble(),(a.y-b.y)*frame.height.toDouble())
        val a=vector(p[0],p[1]);val b=vector(p[2],p[1]);val length=hypot(a.first,a.second)*hypot(b.first,b.second)
        if(length<1e-5) return missing("关节位置重叠，请将手机调整到身体侧面")
        val angle=acos(((a.first*b.first+a.second*b.second)/length).coerceIn(-1.0,1.0))*180/PI
        lastTracked=frame.timestamp
        val filtered=smoothed?.let {it*.4+angle*.6} ?: angle;smoothed=filtered
        val position=when {filtered>=155 -> "up";filtered<=if(exercise=="chair") 110 else 105 -> "down";else -> "moving"}
        val ready=if(exercise=="chair") "down" else "up"
        val peak=if(exercise=="chair") "up" else "down"
        if(position!=candidate) {candidate=position;candidateSince=frame.timestamp}
        if(phase==1 && position!=ready && movementSince<0) movementSince=frame.timestamp
        var delta=0
        // Two successive analysis frames suffice; a normal turn need not hold its endpoint.
        if(frame.timestamp-candidateSince>=80) when {
            phase==0 && position==ready -> {phase=1;movementSince=-1}
            phase==1 && position==peak -> phase=2
            phase==2 && position==ready -> {
                if(movementSince>=0 && frame.timestamp-movementSince>=700) delta=1
                phase=1;movementSince=-1
            }
        }
        val message=when {
            delta>0 -> "完整动作已计 1 次"
            phase==0 -> if(exercise=="chair") "先坐稳，膝角降到110°以内" else "先伸直手臂，肘角达到155°"
            phase==2 -> if(exercise=="chair") "已站起，坐回至膝角110°以内后计数" else "已屈臂，推回至肘角155°后计数"
            else -> if(exercise=="chair") "起身至膝角155°，再平稳坐回" else "屈臂至肘角105°以内，再平稳推回"
        }
        return RepUpdate(true,delta,"${if(side==0) "左" else "右"}侧${if(exercise=="chair") "膝" else "肘"}角 ${filtered.roundToInt()}° · $message")
    }
    // Brief low-confidence frames pause counting without discarding a visible partial cycle.
    // update() clears it after 750ms without a valid angle; missing frames never add reps.
    private fun missing(message:String):RepUpdate = RepUpdate(false,message=message)
}
