package com.trytobatter.app.feature.petpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trytobatter.app.core.designsystem.BatterColors
import kotlinx.coroutines.delay

internal data class PetMessage(val fromUser: Boolean, val text: String)

@Composable internal fun PetChatDialog(
    messages: MutableList<PetMessage>, thinking: Boolean, onThinking: (Boolean) -> Unit,
    onReply: () -> Unit, onClose: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val latestThinking by rememberUpdatedState(onThinking)
    DisposableEffect(Unit) { onDispose { latestThinking(false) } }
    LaunchedEffect(pending) {
        val text = pending ?: return@LaunchedEffect
        onThinking(true)
        delay(1200)
        val reply = when {
            text.contains("计划") -> "可以先挑一件最容易开始的事。下方的今日计划是演示，实际安排仍在 App 的“今天”里。"
            text.contains("累") || text.contains("休息") -> "那就先歇一会儿。我安静待着，等你准备好了再继续。"
            else -> "听到啦。今天不用一下做很多，先完成一件小事，我陪着你。"
        }
        messages.add(PetMessage(false, reply)); pending = null; onThinking(false); onReply()
    }
    LaunchedEffect(messages.size, thinking) { listState.animateScrollToItem(messages.size + if (thinking) 1 else 0) }
    fun close() { onThinking(false); onClose() }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth(.92f).heightIn(max = 480.dp).fillMaxHeight(.92f).testTag("pet-chat-window"),
            shape = RoundedCornerShape(28.dp), color = Color.White, shadowElevation = 12.dp) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).background(BatterColors.LightBlue, CircleShape), contentAlignment = Alignment.Center) { PetAvatar(Modifier.size(42.dp)) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("大肥鱼", style = MaterialTheme.typography.titleMedium)
                        Text("离线演示 · AI 未连接", fontSize = 11.sp, color = BatterColors.Secondary)
                    }
                    IconButton(onClick = ::close, Modifier.testTag("pet-chat-close")) { Icon(Icons.Outlined.Close, "关闭聊天") }
                }
                HorizontalDivider(color = BatterColors.Border)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF7F9FE)), state = listState,
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text("本地模拟对话，不会发送到网络", Modifier.fillMaxWidth(), fontSize = 10.sp, color = BatterColors.Secondary) }
                    items(messages.toList()) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                            if (!message.fromUser) { PetAvatar(Modifier.size(27.dp)); Spacer(Modifier.width(6.dp)) }
                            Surface(Modifier.widthIn(max = 246.dp), shape = RoundedCornerShape(16.dp, 16.dp, if (message.fromUser) 4.dp else 16.dp, if (message.fromUser) 16.dp else 4.dp),
                                color = if (message.fromUser) BatterColors.Primary else Color.White) {
                                Text(message.text, Modifier.padding(12.dp), fontSize = 13.sp, lineHeight = 21.sp,
                                    color = if (message.fromUser) Color.White else BatterColors.Text)
                            }
                        }
                    }
                    if (thinking) item { Row(Modifier.testTag("pet-thinking"), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("大肥鱼正在想……", fontSize = 12.sp, color = BatterColors.Secondary)
                    } }
                }
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, { input = it.take(500) }, Modifier.weight(1f).testTag("pet-chat-input"),
                        placeholder = { Text("说点什么…", fontSize = 13.sp) }, label = { Text("消息", fontSize = 11.sp) }, maxLines = 3,
                        shape = RoundedCornerShape(18.dp))
                    FilledIconButton(onClick = { val text = input.trim(); if (text.isNotEmpty() && pending == null) { messages.add(PetMessage(true, text)); pending = text; input = "" } },
                        enabled = input.isNotBlank() && pending == null, modifier = Modifier.size(48.dp).testTag("pet-chat-send"), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "发送")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PetSettingsSheet(
    display: Boolean, size: Float, xFraction: Float, animations: Boolean, sound: Boolean, proactive: Boolean, quiet: Boolean,
    onDisplay: (Boolean) -> Unit, onSize: (Float) -> Unit, onPosition: (Float) -> Unit,
    onAnimations: (Boolean) -> Unit, onSound: (Boolean) -> Unit, onProactive: (Boolean) -> Unit, onQuiet: (Boolean) -> Unit,
    onRemind: () -> Unit, onLicense: () -> Unit, onClose: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("pet-settings-content")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("陪伴，刚刚好", style = MaterialTheme.typography.titleLarge)
                    Text("桌宠设置 · 仅本次预览", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
                }
                IconButton(onClick = onClose, Modifier.testTag("pet-settings-close")) { Icon(Icons.Outlined.Close, "关闭设置") }
            }
            PetSwitch("显示大肥鱼", "只在此模拟桌面内显示", display, "pet-display", onDisplay)
            Text("角色大小  ${(size / 176f * 100).toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Slider(size, onSize, valueRange = 112f..208f, steps = 5, modifier = Modifier.testTag("pet-size"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("小巧", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
                Text("清晰", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("停靠位置", Modifier.weight(1f), fontSize = 14.sp)
                FilterChip(selected = xFraction < .5f, onClick = { onPosition(0f) }, label = { Text("左侧") }, modifier = Modifier.heightIn(min = 48.dp).testTag("pet-dock-left"))
                FilterChip(selected = xFraction >= .5f, onClick = { onPosition(1f) }, label = { Text("右侧") }, modifier = Modifier.heightIn(min = 48.dp).testTag("pet-dock-right"))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = BatterColors.Border)
            PetSwitch("角色动画", "关闭后保留完整静态姿态", animations, "pet-animations", onAnimations)
            PetSwitch("互动声音", "轻提示音，默认关闭", sound, "pet-sound", onSound)
            PetSwitch("主动提醒", "仅前台预览内每30秒演示", proactive, "pet-proactive", onProactive)
            PetSwitch("安静模式", "休息姿态，暂停声音与主动提醒", quiet, "pet-quiet", onQuiet)
            TextButton(onClick = onRemind, enabled = !quiet, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pet-test-remind")) { Text("立即体验一次提醒") }
            Surface(shape = RoundedCornerShape(16.dp), color = BatterColors.LightBlue) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(20.dp), tint = BatterColors.PrimaryDeep)
                    Column { Text("AI 未连接", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = BatterColors.PrimaryDeep)
                        Text("本地模拟数据 · 未接入 DeepSeek API", fontSize = 11.sp, color = BatterColors.Secondary) }
                }
            }
            TextButton(onClick = onLicense, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pet-license")) { Text("素材来源与完整许可", fontSize = 13.sp) }
            Text("角色素材：YunYueSama / codex-deepseek-pet", fontSize = 10.sp, color = BatterColors.Secondary)
        }
    }
}

@Composable private fun PetSwitch(title: String, note: String, checked: Boolean, tag: String, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) { Text(title, fontSize = 14.sp); Text(note, fontSize = 11.sp, color = BatterColors.Secondary, lineHeight = 17.sp) }
        Switch(checked, onChecked, Modifier.testTag(tag))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PetExampleSheet(plan: Boolean, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (plan) "今日计划" else "执行情况", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClose) { Icon(Icons.Outlined.Close, "关闭示例") }
            }
            Text("示例数据 · 不读取或写入现有日程", fontSize = 12.sp, color = BatterColors.Secondary)
            Spacer(Modifier.height(20.dp))
            if (plan) listOf("09:00" to "阅读 20 分钟", "15:00" to "出门走一走", "21:00" to "写下今天的小进步").forEach { (time, title) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(time, color = BatterColors.PrimaryDeep, fontSize = 14.sp); Text(title, fontSize = 14.sp)
                }
            } else {
                Text("每一小步，都有被看见", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { 1f / 3f }, modifier = Modifier.fillMaxWidth().height(6.dp), color = BatterColors.Primary)
                Text("示例：3件计划，1件已完成", Modifier.padding(top = 12.dp), fontSize = 14.sp)
                Text("阅读已完成 · 散步和回顾尚未开始", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = BatterColors.Secondary)
            }
            Spacer(Modifier.height(16.dp))
            Text("真实安排与执行记录请回到 App 的“今天”和“记录”查看。", fontSize = 12.sp, color = BatterColors.Secondary)
        }
    }
}

@Composable internal fun PetLicenseDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val text = remember { listOf("NOTICE.txt", "LICENSE", "ASSET_LICENSE.md").joinToString("\n\n") { name ->
        context.assets.open("petpreview/$name").bufferedReader().use { it.readText() }
    } }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.fillMaxHeight(.8f).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("来源与许可", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClose) { Icon(Icons.Outlined.Close, "关闭许可") }
                }
                Text(text, Modifier.weight(1f).verticalScroll(rememberScrollState()), fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
    }
}
