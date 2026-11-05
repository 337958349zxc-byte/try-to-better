package com.trytobatter.app.feature.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import com.trytobatter.app.feature.records.recordTime

@Composable fun ConnectionScreen(state: AppSnapshot, onBack: () -> Unit, onPair: (String, String) -> Unit,
    onDisconnect: () -> Unit, onSync: () -> Unit, onConflicts: () -> Unit, onUpdate: () -> Unit, onAuto: () -> Unit = {}) {
    var address by rememberSaveable { mutableStateOf(state.connection.address) }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    val info = state.connection
    DetailScaffold("电脑连接与同步", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Computer, null, Modifier.size(36.dp), tint = BatterColors.Primary)
                    Column { Text(if(info.paired) info.serverName else "让电脑与手机一起工作", style = MaterialTheme.typography.titleMedium); Text("计划更新与执行记录，在局域网中互通", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary) }
                }
                StatusLine("连接状态", when(info.phase) { ConnectionPhase.UNPAIRED -> "尚未连接"; ConnectionPhase.SEARCHING -> "正在自动寻找电脑"; ConnectionPhase.WAITING_APPROVAL -> "等待电脑允许连接"; ConnectionPhase.CONNECTING -> "正在连接电脑"; ConnectionPhase.ONLINE -> "已连接"; ConnectionPhase.OFFLINE -> "暂时离线，将自动重试"; ConnectionPhase.AUTH_ERROR -> "需重新确认连接" })
                StatusLine("本机修改", if(state.pendingSyncCount > 0) "${state.pendingSyncCount} 项等待电脑确认" else if(state.lastRealSyncAt > 0) "已收到电脑回执" else "保存在手机")
                StatusLine("最近同步", if(state.lastRealSyncAt > 0) recordTime(state.lastRealSyncAt) else "尚未完成真实同步")
                if(info.paired) Text(info.address, style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
                if(info.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            } }
            if(info.error.isNotBlank()) item { Notice(info.error, warning = true) }
            if(state.conflictCount > 0) item { Notice("${state.conflictCount} 项内容需要比较确认，手机内容已保留", "查看", true, onConflicts) }
            if(!info.paired) {
                item { AppCard { Text(if(info.phase == ConnectionPhase.WAITING_APPROVAL) "在电脑上点一次允许" else "手机自动找电脑", style = MaterialTheme.typography.titleMedium); Text(if(info.phase == ConnectionPhase.WAITING_APPROVAL) "已找到 ${info.serverName}。在电脑连接页面允许这台手机，之后就会自动同步。" else "让电脑与手机处于同一局域网。找到电脑后，首次在电脑点允许即可，以后不用重复操作。", style = MaterialTheme.typography.bodyMedium); if(info.requestLabel.isNotBlank()) StateBadge("连接请求 ${info.requestLabel}", BatterColors.Primary); Text("电脑连接页：http://127.0.0.1:8081", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) } }
                item { Button(onClick = onAuto, enabled = info.phase !in setOf(ConnectionPhase.SEARCHING, ConnectionPhase.WAITING_APPROVAL), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(when(info.phase) { ConnectionPhase.SEARCHING -> "正在寻找…"; ConnectionPhase.WAITING_APPROVAL -> "等待电脑允许连接"; else -> "自动连接电脑" }) } }
                if(info.phase == ConnectionPhase.WAITING_APPROVAL) item { TextButton(onClick = onDisconnect) { Text("稍后再连接") } }
                item { TextButton(onClick = { manual = !manual }) { Text(if(manual) "收起手动连接" else "手动连接（备用）") } }
                if(manual) {
                item { OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("电脑局域网地址") }, placeholder = { Text("例：192.168.0.23:8081") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true) }
                item { OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("电脑显示的 6 位配对码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
                if(error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
                item { Button(onClick = { if(address.isBlank() || code.length != 6) error = "请填写电脑地址和 6 位配对码" else { error = ""; onPair(address, code) } }, enabled = !info.syncing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if(info.syncing) "正在配对…" else "配对并连接电脑") } }
                }
            } else {
                if(info.phase == ConnectionPhase.AUTH_ERROR) item { Button(onClick = onAuto, modifier = Modifier.fillMaxWidth()) { Text("重新确认连接") } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onSync, enabled = !info.syncing, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(if(info.syncing) "同步中…" else "立即同步") }
                    OutlinedButton(onClick = { confirmDisconnect = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("断开配对") }
                } }
            }
            item { AppCard { Text("打开时及时同步，断线也能继续", style = MaterialTheme.typography.titleMedium); Text("App 在前台时约每 2.5 秒同步；切回 App 会重新连接。后台由 Android 调度，可能延迟。", style = MaterialTheme.typography.bodyMedium); Text("只有收到对应回执，待发送修改才会移除。运动视频和摄像头画面不通过这个通道传输。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) } }
            item { OutlinedButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text("检查 App 更新") } }
            item { TextButton(onClick = onConflicts, modifier = Modifier.fillMaxWidth()) { Text("查看计划更新与冲突") } }
        }
    }
    if(confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text("断开这台电脑？") }, text = { Text("手机中的事项和执行记录会保留。再次连接时需要电脑上的新配对码。") }, confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("断开配对") } }, dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("保持连接") } })
}
@Composable private fun StatusLine(title: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary); Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) } }
