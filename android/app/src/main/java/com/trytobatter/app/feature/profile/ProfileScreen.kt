package com.trytobatter.app.feature.profile

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.trytobatter.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*

@Composable fun ProfileScreen(state: AppSnapshot, onConnection: () -> Unit, onData: () -> Unit, onUpdate: () -> Unit, alarms: AlarmSnapshot = AlarmSnapshot(), onAlarmRefresh: ()->Unit = {}, onPetPreview: () -> Unit = {}, onDesktopPet: () -> Unit = {}) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf("") }
    var showAlarms by remember { mutableStateOf(false) }
    var permissionsExpanded by rememberSaveable {mutableStateOf(true)}
    var dataExpanded by rememberSaveable {mutableStateOf(false)}
    ScenicPage(R.drawable.ttb_v3_profile_header) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.Page, 4.dp, Space.Page, 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { PageHeader("我的", "try to better · ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}") }
        item { LifePanel {
            Text("try to better",style=MaterialTheme.typography.titleLarge)
            Text("个人行动控制中心",style=MaterialTheme.typography.bodyMedium,color=BatterColors.PrimaryDeep)
            Text("本机已保存 ${state.tasks.size}项安排 · ${state.records.size}条执行记录",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
        } }
        item { SectionTitle("设备与同步") }
        item { SettingsGroup { SettingsRow(Icons.Outlined.Computer, "电脑连接", "${if(state.connection.phase == ConnectionPhase.ONLINE) "已连接" else if(state.connection.paired) "连接不可用" else "未连接"} · ${state.pendingSyncCount}项待同步 · ${state.conflictCount}项待确认", onConnection)
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Watch,null,tint=BatterColors.Secondary)
                Column(Modifier.weight(1f)) {Text("华为手环 Band 9",style=MaterialTheme.typography.titleSmall);Text("尚未接入 · 暂无健康数据",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)}
            }
        } }
        item {SettingsGroup {SettingsRow(Icons.Outlined.SystemUpdate,"App 更新","当前 ${context.packageManager.getPackageInfo(context.packageName,0).versionName} · 检查可用更新",onUpdate)}}
        item { SettingsGroup {
            SettingsRow(Icons.Outlined.Pets, "桌面大肥鱼", "离开App也能显示 · 拖动贴边 · 随时关闭", onDesktopPet)
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Outlined.AutoAwesome, "大肥鱼桌宠预览", "透明角色 · 拖动互动 · 本地模拟聊天", onPetPreview)
        } }
        item { FoldHeader("权限与体验","闹钟、摄像头与系统字号 · ${alarms.records.values.count {it.state in setOf(AlarmState.NEEDS_PERMISSION,AlarmState.SCHEDULE_ERROR)}}项提醒需处理",permissionsExpanded,{permissionsExpanded=!permissionsExpanded},Icons.Outlined.Tune,"profile-permissions") }
        if(permissionsExpanded) item { SettingsGroup {
            SettingsRow(Icons.Outlined.NotificationsNone, "通知和闹钟", "${alarms.settings.values.count {it.enabled}}项开启 · ${alarms.records.values.count {it.state==AlarmState.NEEDS_PERMISSION || it.state==AlarmState.SCHEDULE_ERROR}}项需处理") {showAlarms=true}
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Outlined.Videocam, "摄像头权限", if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) "已授权 · 训练准备页检查入镜" else "未授权 · 训练准备页可申请或手工记录") { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Outlined.TextFields, "外观与字号", "跟随系统字号 · 训练界面使用深色布局") { dialog = "当前使用系统中文字体，并随系统字号缩放。日常界面为浅色，训练界面为高对比深色。" }
        } }
        item { FoldHeader("数据与产品","备份恢复、资料与订阅规划",dataExpanded,{dataExpanded=!dataExpanded},Icons.Outlined.FolderOpen,"profile-data") }
        if(dataExpanded) item { SettingsGroup {
            SettingsRow(Icons.Outlined.FolderOpen, "导出、备份与恢复", "JSON 备份 · CSV 记录导出", onData)
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Outlined.Source, "资料与开源来源", "查看动作资料与识别研究来源") { dialog = "动作资料：free-exercise-db、wger。\n姿态底座：MediaPipe。\n计数参考：Google ML Kit。\n反馈框架：Squat-Analysis-Model。\n\n当前动作素材及算法仍待逐项验证，训练可使用手工或明确标记的模拟事件。" }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(Icons.Outlined.Info, "关于与订阅规划", "订阅方案与权益待确定") { dialog = "try to better 是个人日常计划执行工具。\n\n当前版本用于验证原生界面、事项管理与训练流程。订阅权益、价格和支付渠道尚未确定。" }
        } }
    }
    }
    if(showAlarms) AlertDialog(onDismissRequest={showAlarms=false},title={Text("通知和闹钟")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("在事项编辑页或任务行闹钟图标中设置。电脑离线时仍由手机系统管理。")
        AlarmPermissionControls(AlarmMode.RING,onAlarmRefresh)
        Text("普通通知可能延迟。响铃最多2分钟，不更改系统音量或勿扰设置。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick={showAlarms=false}) {Text("完成")}})
    if(dialog.isNotBlank()) AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("说明") }, text = { Text(dialog,Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("知道了") } })
}

@Composable private fun SettingsGroup(content:@Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(Space.Radius),color=MaterialTheme.colorScheme.surface,border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp),content=content)
    }
}

@Composable private fun SettingsRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Surface(modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick=onClick),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.heightIn(min=64.dp).padding(vertical=8.dp),verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(BatterColors.LightBlue),contentAlignment=Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = BatterColors.Primary)
            }
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp),tint = BatterColors.Secondary)
        }
    }
}
