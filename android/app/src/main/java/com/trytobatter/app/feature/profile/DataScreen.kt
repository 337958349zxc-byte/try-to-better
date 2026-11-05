package com.trytobatter.app.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.data.SnapshotCodec
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun DataScreen(state: AppSnapshot, onBack: () -> Unit, onRestore: (AppSnapshot) -> Unit, onReset: (Boolean) -> Unit, onNotify: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoreCandidate by remember { mutableStateOf<AppSnapshot?>(null) }
    var resetChoice by remember { mutableStateOf<Boolean?>(null) }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) scope.launch { runCatching {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(SnapshotCodec.encode(state).toByteArray(Charsets.UTF_8)) } ?: error("无法打开导出位置") }
        }.onSuccess { onNotify("JSON 备份已写入所选位置") }.onFailure { onNotify("导出失败：${it.message}") } }
    }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if(uri != null) scope.launch { runCatching {
            val lines = listOf(listOf("事项", "分类", "完成情况", "实际秒数", "成果", "订正痕迹")) + state.records.map { listOf(it.title, it.category.label, it.outcome.label, it.seconds.toString(), it.result, it.correctedFrom) }
            val csv = "\uFEFF" + lines.joinToString("\r\n") { row -> row.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" } }
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) } ?: error("无法打开导出位置") }
        }.onSuccess { onNotify("执行记录 CSV 已导出") }.onFailure { onNotify("导出失败：${it.message}") } }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch { runCatching {
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192); val out = java.io.ByteArrayOutputStream()
                while(true) { val n = stream.read(buffer); if(n < 0) break; require(out.size() + n <= 5_000_000) { "备份超过 5 MB" }; out.write(buffer, 0, n) }
                SnapshotCodec.decode(out.toString("UTF-8"))
            } ?: error("无法读取备份文件") }
        }.onSuccess { restoreCandidate = it }.onFailure { onNotify("备份校验失败：${it.message}") } }
    }
    DetailScaffold("导出、备份与恢复", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DemoNote("当前数据范围：本机计划与执行记录") }
            item { AppCard {
                Icon(Icons.Outlined.FolderOpen, null, tint = BatterColors.Primary)
                Text("把自己的记录留在手里", style = MaterialTheme.typography.titleLarge)
                Text("事项 ${state.tasks.size} 项 · 执行记录 ${state.records.size} 条 · 训练 ${state.workouts.size} 次", color = BatterColors.Secondary)
                Text("备份包含计划、事项、执行结果、订正及同步队列。配对凭证和本机闹钟设置单独保存在设备上，不随备份导出。", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary)
            } }
            item { SectionTitle("导出与备份") }
            item { Button(onClick = { jsonExport.launch("try-to-batter-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("导出 JSON 备份") } }
            item { OutlinedButton(onClick = { csvExport.launch("batter-records-${LocalDate.now()}.csv") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("导出执行记录 CSV") } }
            item { SectionTitle("从备份恢复") }
            item { AppCard {
                Text("先校验，再确认", style = MaterialTheme.typography.titleSmall)
                Text("读取本 App 生成的 JSON 备份。确认后替换当前本机内容，连接电脑时会作为修改同步，建议先导出当前备份。", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary)
                OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("选择备份文件") }
            } }
            item { SectionTitle("本机数据管理") }
            if(state.connection.paired) item { Notice("请先断开电脑，再重置本机数据。") }
            item { OutlinedButton(onClick = { resetChoice = false }, enabled = !state.connection.paired, modifier = Modifier.fillMaxWidth()) { Text("重新载入示例计划") } }
            item { TextButton(onClick = { resetChoice = true }, enabled = !state.connection.paired, modifier = Modifier.fillMaxWidth()) { Text("清空本机数据", color = BatterColors.Error) } }
        }
    }
    restoreCandidate?.let { candidate -> AlertDialog(onDismissRequest = { restoreCandidate = null }, title = { Text("确认恢复这份备份？") }, text = { Text("校验通过：${candidate.tasks.size} 项事项、${candidate.records.size} 条执行记录、${candidate.workouts.size} 次训练。\n\n将替换当前本机内容，正在执行的任务会先暂停。连接电脑时会作为修改同步。") }, confirmButton = { TextButton(onClick = { restoreCandidate = null; onRestore(candidate) }) { Text("确认恢复") } }, dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("取消") } }) }
    resetChoice?.let { empty -> AlertDialog(onDismissRequest = { resetChoice = null }, title = { Text(if(empty) "清空本机数据？" else "重新载入示例计划？") }, text = { Text("这会替换本机事项、执行和训练记录。需要保留时，请先导出 JSON 备份。") }, confirmButton = { TextButton(onClick = { resetChoice = null; onReset(empty) }) { Text("确认操作", color = BatterColors.Error) } }, dismissButton = { TextButton(onClick = { resetChoice = null }) { Text("取消") } }) }
}
