package com.trytobatter.app.feature.petoverlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.feature.petpreview.*

/** Permission/settings Activity only; opening it never initializes the business repository. */
class PetOverlayActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var refresh by mutableIntStateOf(0)
    private var waitingPermission = false
    private var showChat by mutableStateOf(false)
    private var returnHomeAfterChat = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        resolveOverlayPermission()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startPet() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingPermission = savedInstanceState?.getBoolean("waitingPetPermission") ?: false
        enableEdgeToEdge()
        readIntent(intent)
        setContent { BatterTheme {
            val status by PetOverlayRuntime.status.collectAsStateWithLifecycle()
            var license by remember { mutableStateOf(false) }
            DesktopPetSettings(allowed, status, refresh, onBack = ::finish, onEnable = ::enable,
                onStop = { stopService(Intent(this, PetOverlayService::class.java)) }, onHome = ::home,
                onLicense = { license = true })
            if (license) PetLicenseDialog { license = false }
            if (showChat) {
                val messages = remember { mutableStateListOf(PetMessage(false, "我在呢，今天想聊点什么？")) }
                var thinking by remember { mutableStateOf(false) }
                PetChatDialog(messages, thinking, onThinking = { thinking = it }, onReply = {}, onClose = {
                    showChat = false
                    if (returnHomeAfterChat) { returnHomeAfterChat = false; moveTaskToBack(true) }
                })
            }
        } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    private fun readIntent(intent: Intent?) { showChat = intent?.getBooleanExtra("chat", false) == true; returnHomeAfterChat = showChat }
    override fun onResume() {
        super.onResume(); allowed = Settings.canDrawOverlays(this); refresh++
        if (waitingPermission && allowed) window.decorView.post { resolveOverlayPermission() }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("waitingPetPermission", waitingPermission); super.onSaveInstanceState(outState) }
    private fun enable() {
        allowed = Settings.canDrawOverlays(this)
        if (!allowed) {
            waitingPermission = true
            try { permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) }
            catch (_: android.content.ActivityNotFoundException) { waitingPermission = false; PetOverlayRuntime.set(false, "此系统未提供授权入口，请在系统设置中开启悬浮窗权限") }
        } else requestNotificationsAndStart()
    }
    private fun resolveOverlayPermission() {
        if (!waitingPermission) return
        waitingPermission = false; allowed = Settings.canDrawOverlays(this)
        if (allowed) requestNotificationsAndStart() else PetOverlayRuntime.set(false, "尚未授权，允许后才能显示桌宠")
    }
    private fun requestNotificationsAndStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startPet()
    }
    private fun startPet() {
        if (!Settings.canDrawOverlays(this)) { PetOverlayRuntime.set(false, "请先开启悬浮窗权限"); return }
        try {
            PetOverlayRuntime.set(false, "正在开启")
            ContextCompat.startForegroundService(this, Intent(this, PetOverlayService::class.java))
        } catch (_: RuntimeException) { PetOverlayRuntime.set(false, "暂时无法开启，请保持App在前台后重试") }
    }
    private fun home() { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable private fun DesktopPetSettings(
    allowed: Boolean, status: OverlayStatus, refresh: Int, onBack: () -> Unit, onEnable: () -> Unit,
    onStop: () -> Unit, onHome: () -> Unit, onLicense: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = remember { PetOverlayConfig(context.applicationContext) }
    var size by remember(refresh) { mutableFloatStateOf(config.size.toFloat()) }
    var animations by remember(refresh) { mutableStateOf(config.animations) }
    var quiet by remember(refresh) { mutableStateOf(config.quiet) }
    var left by remember(refresh) { mutableStateOf(config.left) }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("桌面大肥鱼", style = MaterialTheme.typography.titleLarge)
            }
            Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PetAvatar(Modifier.size(64.dp))
                Column { Text("离开 App，也在身边", style = MaterialTheme.typography.titleMedium)
                    Text("真实悬浮桌宠 · 可拖动 · 可随时关闭", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) }
            }
            Surface(color = BatterColors.LightBlue, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (status.running) "大肥鱼已开启" else "让她来到手机桌面", style = MaterialTheme.typography.titleMedium)
                    Text(status.message, style = MaterialTheme.typography.bodyMedium, color = BatterColors.PrimaryDeep)
                    if (!allowed) Text("首次开启需要允许“显示在其他应用上层”，返回后即可显示。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
                    Button(onClick = if (status.running) onStop else onEnable, enabled = status.message != "正在开启",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pet-overlay-toggle")) { Text(if (status.running) "关闭桌宠" else "开启桌宠") }
                    OutlinedButton(onHome, enabled = status.running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pet-overlay-home")) { Text("回到桌面看看") }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("陪伴，刚刚好", style = MaterialTheme.typography.titleMedium)
            Text("拖动角色，松手自动靠边；点击角色打开气泡。", Modifier.padding(top = 6.dp, bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
            Text("角色大小  ${(size / 176f * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(size, { size = it; config.size = it.toInt(); PetOverlayService.update(context) }, valueRange = 112f..208f, steps = 5, modifier = Modifier.testTag("pet-overlay-size"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("停靠位置", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                FilterChip(left, { left = true; config.left = true; PetOverlayService.update(context) }, label = { Text("左侧") }, modifier = Modifier.heightIn(min = 48.dp).testTag("pet-overlay-left"))
                FilterChip(!left, { left = false; config.left = false; PetOverlayService.update(context) }, label = { Text("右侧") }, modifier = Modifier.heightIn(min = 48.dp).testTag("pet-overlay-right"))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("角色动画", Modifier.weight(1f)); Switch(animations, { animations = it; config.animations = it; PetOverlayService.update(context) }, Modifier.testTag("pet-overlay-animations"))
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("安静陪伴"); Text("睡觉姿态，不发声音", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) }
                Switch(quiet, { quiet = it; config.quiet = it; PetOverlayService.update(context) }, Modifier.testTag("pet-overlay-quiet"))
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("聊天暂为离线演示。锁屏时桌宠隐藏，解锁后恢复；重启或被系统结束后，需要重新开启。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
            TextButton(onLicense, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("素材来源与完整许可") }
        }
    }
}
