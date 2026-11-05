package com.trytobatter.app.feature.profile

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.data.*
import com.trytobatter.app.core.designsystem.*

@Composable fun UpdateScreen(info: UpdateInfo, address: String, onBack: () -> Unit, onCheck: () -> Unit, onDownload: () -> Unit, installIntent: () -> Intent, onNotify: (String) -> Unit) {
    val context = LocalContext.current
    val busy = info.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)
    var continueInstall by rememberSaveable { mutableStateOf(false) }
    fun openInstaller() {
        runCatching {
            if(!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                onNotify("允许安装更新后返回，App 会继续打开系统确认")
            } else { continueInstall = false; context.startActivity(installIntent()) }
        }.onFailure { continueInstall = false; onNotify(it.message ?: "无法打开系统安装页面") }
    }
    LaunchedEffect(Unit) { if(info.phase == UpdatePhase.IDLE) onCheck() }
    LaunchedEffect(info.phase) { if(info.phase == UpdatePhase.READY && continueInstall) openInstaller() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if(continueInstall && info.phase == UpdatePhase.READY && context.packageManager.canRequestPackageInstalls()) openInstaller()
    }
    DetailScaffold("App 更新", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { AppCard { Icon(Icons.Outlined.SystemUpdate, null, tint = BatterColors.Primary); Text("让工具保持好用", style = MaterialTheme.typography.headlineMedium); Text("当前版本 ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}"); Text("从已配置电脑检查版本，沿用局域网安装包下载通道。", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary) } }
            item { Text("电脑地址：$address", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary) }
            if(info.message.isNotBlank()) item { Notice(info.message, warning = info.phase == UpdatePhase.ERROR) }
            info.release?.let { release -> item { AppCard { Text("发布版本 ${release.versionName}", style = MaterialTheme.typography.titleMedium); Text("安装包 %.1f MB".format(release.size / 1024.0 / 1024.0)); if(release.notes.isNotBlank()) Text(release.notes) } } }
            if(info.phase == UpdatePhase.DOWNLOADING) item { LinearProgressIndicator(progress = { info.progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("已下载 ${info.progress}%") }
            item { Button(onClick = onCheck, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if(info.phase == UpdatePhase.CHECKING) "正在检查…" else "检查 App 更新") } }
            if(info.phase == UpdatePhase.AVAILABLE || (info.phase == UpdatePhase.ERROR && info.release != null)) item { Button(onClick = { continueInstall = true; onDownload() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("下载并更新") } }
            if(info.phase == UpdatePhase.READY) item { Button(onClick = { continueInstall = true; openInstaller() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("继续安装更新") } }
            item { Text("下载后检查文件大小、完整性、版本和签名。系统会再次确认安装，完成前仍使用当前版本。", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary) }
        }
    }
}
