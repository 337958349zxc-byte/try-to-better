package com.trytobatter.app.feature.plans

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import com.trytobatter.app.core.data.SyncEngine
import org.json.JSONObject

@Composable fun ConflictScreen(state: AppSnapshot, onBack: () -> Unit, onResolve: (String, Boolean) -> Unit, onConnection: () -> Unit, onResolveSync: (String, Boolean) -> Unit = { _, _ -> }) {
    var pending by remember { mutableStateOf<PlanConflict?>(null) }
    var pendingSync by remember { mutableStateOf<SyncConflict?>(null) }
    DetailScaffold("计划版本与更新", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DemoNote("计划 v${state.planVersion} · 先比较，再确认变化") }
            item { AppCard {
                Text("安排变了，记录还在", style = MaterialTheme.typography.headlineMedium)
                Text("无冲突的未来事项可更新。手机修改、正在执行的内容和完成历史会受到保护。", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary)
                StateBadge("${state.conflictCount} 项待确认", if(state.conflictCount == 0) BatterColors.Primary else BatterColors.Warning)
            } }
            if(state.conflictCount == 0) item { EmptyState("没有待处理冲突", "可以回到今天继续执行，也可以查看电脑连接状态。", action = "查看电脑连接", onAction = onConnection) }
            items(state.syncConflicts, key = { it.key }) { conflict ->
                val current = state.tasks.find { "task:${it.id}" == conflict.key }
                val incoming = SyncEngine.remoteTask(conflict)
                val localPayload = SyncEngine.payloads(state)[conflict.key]
                AppCard {
                    Text("双方内容需要确认", style = MaterialTheme.typography.titleMedium)
                    Text(conflict.reason, style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary)
                    if(current != null) ComparisonBlock("手机当前内容", current) else SyncSummary("手机当前内容", localPayload)
                    if(incoming != null) ComparisonBlock("电脑新内容", incoming) else SyncSummary("电脑新内容", if(conflict.remote.deleted) null else conflict.remote.payload)
                    if(current?.isProtected == true) Notice("本次执行已开始，请保留手机内容。", warning = true)
                    Button(onClick = { onResolveSync(conflict.key, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保留手机内容") }
                    OutlinedButton(onClick = { if(incoming != null && TaskRules.conflicts(incoming, state.tasks).isNotEmpty()) pendingSync = conflict else onResolveSync(conflict.key, false) }, enabled = current?.isProtected != true, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("采用电脑内容") }
                    Text("这次比较与选择会保存在本机备份中。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
                }
            }
            items(state.conflicts, key = { it.id }) { conflict ->
                val current = state.tasks.find { it.id == conflict.taskId }
                if(current != null) AppCard {
                    Text("事项变更需要确认", style = MaterialTheme.typography.titleMedium)
                    Text(conflict.reason, style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary)
                    ComparisonBlock("手机当前内容", current)
                    ComparisonBlock(conflict.sourceVersion, conflict.incoming)
                    if(current.isProtected) Notice("本次执行已经开始，请保留手机内容。", warning = true)
                    Button(onClick = { onResolve(conflict.id, true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保留手机内容") }
                    OutlinedButton(onClick = { if(TaskRules.conflicts(conflict.incoming, state.tasks).isNotEmpty()) pending = conflict else onResolve(conflict.id, false) }, enabled = !current.isProtected, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("采用新计划") }
                }
            }
        }
    }
    pending?.let { conflict -> AlertDialog(onDismissRequest = { pending = null }, title = { Text("新时间仍有重叠") }, text = { Text(TaskRules.conflicts(conflict.incoming, state.tasks).joinToString("\n") { "${it.title} ${it.timeLabel}" } + "\n\n确认保留重叠后采用新计划？") }, confirmButton = { TextButton(onClick = { pending = null; onResolve(conflict.id, false) }) { Text("确认采用") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("返回比较") } }) }
    pendingSync?.let { c -> AlertDialog(onDismissRequest = { pendingSync = null }, title = { Text("新时间仍有重叠") }, text = { Text("采用电脑安排后会与手机其他事项重叠，确认保留？") }, confirmButton = { TextButton(onClick = { pendingSync = null; onResolveSync(c.key, false) }) { Text("保留重叠并采用") } }, dismissButton = { TextButton(onClick = { pendingSync = null }) { Text("返回比较") } }) }
}

@Composable private fun SyncSummary(label: String, payload: String?) {
    AppCard { Text(label, style = MaterialTheme.typography.labelMedium, color = BatterColors.Primary)
        if(payload == null) Text("这项内容已删除") else { val j = JSONObject(payload); Text(j.optString("title", "训练记录"), style = MaterialTheme.typography.titleSmall); if(j.has("result")) Text(j.optString("result").ifBlank { "未填写成果" }); if(j.has("seconds")) Text("实际 ${j.optLong("seconds")} 秒"); if(j.has("sets")) Text("${j.getJSONArray("sets").length()} 组训练") }
    }
}

@Composable private fun ComparisonBlock(label: String, task: TaskItem) {
    Surface(color = BatterColors.Background, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = BatterColors.Primary)
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            Text("${task.day}  ${task.timeLabel}", style = MaterialTheme.typography.bodyMedium)
            Text("计划 ${task.durationMinutes} 分钟 · 版本 ${task.revision}", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
        }
    }
}
