package com.trytobatter.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class PeriodReview(val start:LocalDate,val end:LocalDate,val tasks:List<TaskItem>,val records:List<ExecutionRecord>,
    val workouts:List<WorkoutRecord>,val linkedRecords:List<ExecutionRecord>,val pendingSync:Int=0,val conflicts:Int=0) {
    val plannedMinutes get()=tasks.sumOf {it.durationMinutes.toLong()}
    val actualSeconds get()=records.sumOf {it.seconds}
    val missingResults get()=tasks.count {t->linkedRecords.none {it.taskId==t.id}}
    val comparedTasks get()=tasks.filter {t->linkedRecords.any {it.taskId==t.id}}
    val comparedBudgetMinutes get()=comparedTasks.sumOf {it.durationMinutes.toLong()}
    val comparedSeconds get()=linkedRecords.sumOf {it.seconds}
    fun count(status:TaskStatus)=tasks.count {it.status==status}
    val learning get()=records.filter {it.category in setOf(Category.AI,Category.ENGLISH)}

    fun markdown(today:LocalDate):String=buildString {
        appendLine("# try to better 周期复盘")
        appendLine();appendLine("周期：$start 至 $end（含首尾）")
        appendLine("生成日期：$today · ${if(today<start) "周期尚未开始" else if(today<=end) "周期进行中" else "周期已结束"}")
        appendLine("数据来自本机保存内容；待同步${pendingSync}项，待确认${conflicts}项。")
        appendLine();appendLine("## 计划与实际")
        appendLine("- 当前计划：${tasks.size}项，预算${plannedMinutes}分钟。")
        appendLine("- 完整${count(TaskStatus.COMPLETED)}项，部分${count(TaskStatus.PARTIAL)}项，跳过${count(TaskStatus.SKIPPED)}项。")
        appendLine("- 进行中${count(TaskStatus.RUNNING)}项，暂停${count(TaskStatus.PAUSED)}项，待开始${count(TaskStatus.PENDING)}项。")
        appendLine("- ${missingResults}项尚无关联结果记录（含未来安排）。")
        appendLine(if(records.isEmpty()) "- 本周期暂无执行结果，实际执行情况未知。" else "- 本周期结束的执行记录${records.size}条，实际${actualSeconds/60}分${actualSeconds%60}秒。")
        appendLine("- 已有结果的周期计划${comparedTasks.size}项：当前预算${comparedBudgetMinutes}分钟，对应实际${comparedSeconds/60}分${comparedSeconds%60}秒（关联结果可跨期）。")
        if(comparedTasks.isNotEmpty()) appendLine("- 上述已记录事项的用时差：${comparedSeconds-comparedBudgetMinutes*60}秒（实际减当前预算）。")
        appendLine();appendLine("## 分类汇总")
        appendLine("| 分类 | 当前计划项数 | 当前预算分钟 | 周期内结束记录 | 周期内实际秒数 |")
        appendLine("| --- | ---: | ---: | ---: | ---: |")
        Category.entries.forEach {c->val planned=tasks.filter {it.category==c};val actual=records.filter {it.category==c}
            appendLine("| ${c.label} | ${planned.size} | ${planned.sumOf {it.durationMinutes}} | ${actual.size} | ${actual.sumOf {it.seconds}} |")}
        appendLine();appendLine("## 周期计划明细")
        tasks.sortedBy {it.start}.forEach {t->appendLine("- ${t.scheduledAt} · ${safe(t.title)} · ${t.status.label} · 预算${t.durationMinutes}分钟")}
        if(tasks.isEmpty()) appendLine("暂无计划。")
        appendLine();appendLine("## 执行结果与成果")
        records.sortedBy {it.endedAt}.forEach {r->
            appendLine("- ${safe(r.title)} · ${r.outcome.label} · 实际${r.seconds}秒 · 结束${Instant.ofEpochMilli(r.endedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()}")
            appendLine("  - 成果/原因：${safe(r.result.ifBlank {"未填写"})}")
            if(r.snapshot.isNotBlank()) appendLine("  - 执行时快照：${safe(r.snapshot)}")
            if(r.correctedFrom.isNotBlank()) appendLine("  - 订正痕迹：${safe(r.correctedFrom)}")
        }
        if(records.isEmpty()) appendLine("暂无结果记录，实际执行情况未知。")
        appendLine();appendLine("## 训练明细")
        workouts.sortedBy {it.endedAt}.forEach {w->
            appendLine("- ${Instant.ofEpochMilli(w.endedAt).atZone(ZoneId.systemDefault()).toLocalDate()} · ${w.sets.size}组 · 训练计时${w.seconds}秒")
            w.sets.forEach {s->appendLine("  - ${safe(s.exercise)} 第${s.setIndex}组：确认${s.confirmedReps}次，自动${s.autoReps}次；${safe(s.note)}；${safe(s.quality)}")}
            if(w.note.isNotBlank()) appendLine("  - 负荷、RPE与恢复原记录：${safe(w.note)}")
        }
        if(workouts.isEmpty()) appendLine("暂无实际训练记录。")
        appendLine();appendLine("## 统计口径")
        appendLine("计划按当前安排日期筛选，执行和训练按结束日期筛选；已有结果事项的对照使用关联结果，可能跨期。预算是当前计划值，执行时快照另行保留。")
        appendLine("未记录保留未知；部分、跳过与未来安排分开显示。演示事项、演示执行及模拟训练已排除。训练计时单列，不重复加到执行总时长。确认次数按原始记录口径展示，每侧说明见逐组备注。")
        appendLine();appendLine("## 我的复盘与下周期调整（待填写）")
        appendLine("- 做成了什么：")
        appendLine("- 主要卡点与原因：")
        appendLine("- 下周期准备调整：")
    }

    companion object {
        fun build(state:AppSnapshot,start:LocalDate,end:LocalDate,zone:ZoneId=ZoneId.systemDefault()):PeriodReview {
            require(end>=start) {"周期结束日期不能早于开始日期"}
            fun inPeriod(millis:Long)=Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().let {it>=start && it<=end}
            val tasks=state.tasks.filter {!it.isDemo && it.day>=start && it.day<=end}
            val ids=tasks.map {it.id}.toSet()
            val actual=state.records.filterNot {it.isDemo}
            return PeriodReview(start,end,tasks,actual.filter {inPeriod(it.endedAt)},state.workouts.filter {!it.simulated && inPeriod(it.endedAt)},actual.filter {it.taskId in ids},state.pendingSyncCount,state.conflictCount)
        }
        private fun safe(value:String)=value.replace("\\","\\\\").replace("|","\\|").replace("<","&lt;").replace(">","&gt;").replace("\n"," / ").replace("\r","")
    }
}
