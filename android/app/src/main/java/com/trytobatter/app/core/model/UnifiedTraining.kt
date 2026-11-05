package com.trytobatter.app.core.model

/** One coach-owned template, two explicitly selected loads. No date-based progression. */
data class StrengthMove(val id:String,val name:String,val guide:String,val firstSets:Int,val firstMin:Int,val firstMax:Int,
    val secondSets:Int,val secondMin:Int,val secondMax:Int,val perSide:Boolean=false) {
    fun sets(week2:Boolean)=if(week2) secondSets else firstSets
    fun target(week2:Boolean):String {
        val low=if(week2) secondMin else firstMin;val high=if(week2) secondMax else firstMax
        return (if(perSide) "每侧 " else "")+(if(low==high) "$low" else "$low–$high")+" 次"
    }
}
data class StrengthActual(val exerciseId:String,val index:Int,val left:String="",val right:String="",val done:Boolean=false,val automatic:Int=0) {
    fun countsValid(perSide:Boolean)=left.toIntOrNull()?.let {it in 0..999}==true && (!perSide || right.toIntOrNull()?.let {it in 0..999}==true)
}
object UnifiedTraining {
    const val templateId="WH_FULL_BODY_V1_4"
    const val title="全身力量训练｜周衡 V1.4"
    val moves=listOf(
        StrengthMove("wh1", "慢速椅子深蹲", "稳固椅子；双脚与肩同宽，脚尖自然向外。臀部后移，缓慢下蹲，轻触椅面再站起。下降约3秒，站起约1–2秒；膝盖与脚尖同向，不跌坐。完成标准：幅度稳定、无膝关节疼痛。", 2, 8, 10, 3, 8, 10, false),
        StrengthMove("wh2", "高位俯卧撑", "固定墙面或稳固台面；双手略宽于肩，身体成直线。屈肘下降，胸部靠近支撑面，再推起；下降约2秒，推起约1–2秒。肘自然向后约30–45度，不塌腰。完成标准：身体稳定、完成规定次数。台面过难改用墙壁。", 2, 6, 8, 3, 6, 8, false),
        StrengthMove("wh3", "臀桥", "仰卧屈膝、双脚平放地面，收紧臀部抬起骨盆，顶部停2秒。不要过度挺腰。完成标准：臀部发力明确、腰部无疼痛。", 2, 12, 12, 2, 15, 15, false),
        StrengthMove("wh4", "扶物分腿蹲", "前后分腿，一手扶稳固支撑；缓慢屈膝下降、平稳站起。前脚脚跟贴地，膝盖跟随脚尖；初期浅幅度。完成标准：两侧均保持平衡，无疼痛。", 2, 6, 6, 2, 8, 8, true),
        StrengthMove("wh5", "站姿提踵", "站立，双手扶稳固支撑；缓慢踮起脚跟，再控制下降。上升约1秒、下降约2秒；不弹跳、不借惯性。完成标准：规定次数、动作完整。", 2, 15, 15, 2, 18, 18, false),
        StrengthMove("wh6", "俯卧W抬手", "俯卧，手臂呈W形；轻抬手臂并收紧肩胛，顶部停1秒。不耸肩、不抬头过高。完成标准：控制肩胛运动、无颈肩疼痛。属于低负荷上背控制训练，不能完全替代负重划船。", 2, 10, 10, 2, 12, 12, false),
        StrengthMove("wh7", "Dead Bug", "仰卧，双手朝上，髋膝约90度；对侧手臂和腿缓慢向外伸展，再回起始位。躯干稳定，腰不明显拱起。完成标准：两侧完成规定次数、正常呼吸。", 2, 8, 8, 2, 10, 10, true),
    )
    fun day(task:TaskItem):Int?=Regex("""周期日：D(\d{1,2})(?!\d)""").find(task.note)?.groupValues?.get(1)?.toIntOrNull()?.takeIf {it in 1..14}
    fun isStrength(task:TaskItem)=task.category==Category.WORKOUT && task.title==title && templateId in task.note && day(task) in setOf(1,3,5,8,10,12)
    fun rows(week2:Boolean)=moves.flatMap {m -> (1..m.sets(week2)).map {StrengthActual(m.id,it)} }
    fun validRows(rows:List<StrengthActual>,week2:Boolean):Boolean = rows.map {it.exerciseId to it.index}==this.rows(week2).map {it.exerciseId to it.index}
    fun complete(rows:List<StrengthActual>,week2:Boolean)=validRows(rows,week2) && rows.all {a -> moves.first {it.id==a.exerciseId}.let {m -> val minimum=if(week2) m.secondMin else m.firstMin; a.done && a.countsValid(m.perSide) && (a.left.toIntOrNull()?:-1)>=minimum && (!m.perSide || (a.right.toIntOrNull()?:-1)>=minimum)}}
    fun legacyPending(t:TaskItem):Boolean {
        if(t.category!=Category.WORKOUT || t.status!=TaskStatus.PENDING || t.isProtected || t.accumulatedSeconds!=0L || t.executionSnapshot.isNotBlank()) return false
        return (t.isDemo && t.id=="demo-strength") || t.title in setOf("基础力量 · 跟练 A","基础力量·跟练A","基础力量 · 跟练 B","基础力量·跟练B","力量A｜五项基础动作","周衡｜适应力量训练")
    }
}
