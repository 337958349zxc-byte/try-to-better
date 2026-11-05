package com.trytobatter.app.feature.petpreview

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.trytobatter.app.core.designsystem.BatterColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** An app-contained, offline prototype. This screen never reads the user's repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PetPreviewScreen(onBack: () -> Unit, initialAnimations: Boolean = true) {
    var display by rememberSaveable { mutableStateOf(true) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var petSize by rememberSaveable { mutableFloatStateOf(176f) }
    var animations by rememberSaveable { mutableStateOf(initialAnimations) }
    var sound by rememberSaveable { mutableStateOf(false) }
    var proactive by rememberSaveable { mutableStateOf(false) }
    var quiet by rememberSaveable { mutableStateOf(false) }
    var xFraction by rememberSaveable { mutableFloatStateOf(1f) }
    var yFraction by rememberSaveable { mutableFloatStateOf(1f) }
    var walkDirection by rememberSaveable { mutableIntStateOf(-1) }
    var motion by rememberSaveable { mutableStateOf(PetMotion.IDLE) }
    var bubble by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf(PetMessage(false, "我在呢。今天想从哪件小事开始？")) }
    val lifeState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifeState.isAtLeast(Lifecycle.State.RESUMED)
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    fun cue() { if (sound && !quiet) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70) }
    fun open(name: String) { bubble = false; panel = name }
    fun remind() { motion = PetMotion.REMIND; bubble = true; cue() }
    LaunchedEffect(proactive, quiet, foreground, display, hidden, panel, bubble) {
        if (proactive && !quiet && foreground && display && !hidden && panel.isEmpty() && !bubble) {
            delay(30_000); remind()
        }
    }
    BackHandler { when { panel.isNotEmpty() -> panel = ""; bubble -> bubble = false; else -> onBack() } }
    val actualMotion = when { dragging -> PetMotion.DRAG; thinking -> PetMotion.THINK; quiet -> PetMotion.SLEEP; else -> motion }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("pet-stage")) {
        val width = maxWidth.value
        val height = maxHeight.value
        val size = petSize.coerceAtMost((width - 24).coerceAtLeast(48f)).coerceAtMost((height - 100).coerceAtLeast(48f))
        val bounds = petBounds(width, height, size)
        val x = bounds.left + xFraction * (bounds.right - bounds.left)
        val y = bounds.top + yFraction * (bounds.bottom - bounds.top)
        val density = LocalDensity.current.density
        fun place(nextX: Float, nextY: Float) {
            xFraction = if (bounds.right > bounds.left) (bounds.x(nextX) - bounds.left) / (bounds.right - bounds.left) else 0f
            yFraction = if (bounds.bottom > bounds.top) (bounds.y(nextY) - bounds.top) / (bounds.bottom - bounds.top) else 0f
        }
        fun dock() { xFraction = if (xFraction < .5f) 0f else 1f }
        LaunchedEffect(actualMotion, animations, foreground, display, hidden, bounds, panel) {
            if (actualMotion == PetMotion.WALK && animations && foreground && display && !hidden && panel.isEmpty()) {
                while (true) {
                    delay(32)
                    val current = bounds.left + xFraction * (bounds.right - bounds.left)
                    val next = current + walkDirection * .8f
                    if (next <= bounds.left || next >= bounds.right) walkDirection *= -1
                    place(next, bounds.top + yFraction * (bounds.bottom - bounds.top))
                }
            }
        }
        PetWallpaper()
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Column(Modifier.weight(1f).padding(start = 3.dp)) {
                    Text("大肥鱼", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = BatterColors.Text)
                    Text("TRY TO BETTER", fontSize = 10.sp, letterSpacing = 1.5.sp, color = BatterColors.Secondary)
                }
                Surface(shape = CircleShape, color = Color.White.copy(alpha = .75f)) {
                    Text("页面内模拟", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, color = BatterColors.PrimaryDeep)
                }
                IconButton(onClick = { open("settings") }, modifier = Modifier.testTag("pet-settings")) { Icon(Icons.Outlined.Tune, "桌宠设置", tint = BatterColors.Text) }
            }
            Spacer(Modifier.height(54.dp))
            Text("09:41", fontSize = 67.sp, fontWeight = FontWeight.Light, letterSpacing = (-3).sp, color = Color(0xFF344D78))
            Text("11月5日  星期四  ·  模拟桌面", fontSize = 13.sp, color = Color(0xFF52688B))
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(5.dp).background(BatterColors.Primary, CircleShape))
                Text(if (quiet) "安静陪伴中" else "留一点时间，给今天的自己", fontSize = 12.sp, color = Color(0xFF52688B))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 19.dp), horizontalArrangement = Arrangement.SpaceAround) {
                PetShortcut("聊天", Icons.Outlined.ChatBubbleOutline, "pet-chat") { open("chat") }
                PetShortcut("计划", Icons.Outlined.CalendarToday, "pet-plan") { open("plan") }
                PetShortcut("执行", Icons.Outlined.Insights, "pet-progress") { open("progress") }
                PetShortcut("动作", Icons.Outlined.AutoAwesome, "pet-actions") { open("actions") }
            }
            Text("点击互动 · 拖动停靠屏幕边缘", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp), fontSize = 11.sp, color = Color(0xFF596D8C))
        }
        if (display && !hidden) {
            Box(Modifier.offset { IntOffset((x * density).roundToInt(), (y * density).roundToInt()) }
                .size(size.dp).testTag("pet-character")
                .semantics {
                    contentDescription = "大肥鱼，${actualMotion.label}，可点击或拖动"
                    stateDescription = actualMotion.name
                    role = Role.Button
                    onClick("与大肥鱼互动") { bubble = !bubble; motion = PetMotion.TALK; true }
                    customActions = listOf(
                        CustomAccessibilityAction("停靠左侧") { xFraction = 0f; true },
                        CustomAccessibilityAction("停靠右侧") { xFraction = 1f; true })
                }
                .pointerInput(bounds) {
                    detectDragGestures(onDragStart = { dragging = true; bubble = false },
                        onDragEnd = { dragging = false; dock(); motion = PetMotion.IDLE },
                        onDragCancel = { dragging = false; dock(); motion = PetMotion.IDLE }) { change, delta ->
                        change.consume()
                        val nowX = bounds.left + xFraction * (bounds.right - bounds.left)
                        val nowY = bounds.top + yFraction * (bounds.bottom - bounds.top)
                        place(nowX + delta.x / density, nowY + delta.y / density)
                    }
                }.pointerInput(quiet) {
                    detectTapGestures(onTap = { bubble = !bubble; motion = PetMotion.TALK; cue() })
                }) {
                PetArtwork(actualMotion, animations && foreground && !hidden, Modifier.fillMaxSize(),
                    facingLeft = actualMotion == PetMotion.WALK && walkDirection < 0,
                    onCycleEnd = { if (motion == PetMotion.REMIND) motion = PetMotion.IDLE })
            }
            if (bubble) {
                val bubbleWidth = 272f.coerceAtMost(width - 32f)
                val bubbleX = (x + size / 2 - bubbleWidth / 2).coerceIn(16f, (width - bubbleWidth - 16f).coerceAtLeast(16f))
                val belowPet = y < 287f
                val bubbleY = if (belowPet) (y + size + 8f).coerceAtMost(height - 324f).coerceAtLeast(76f) else y - 211f
                PetSpeechBubble(actualMotion.phrase, onChat = { open("chat") }, onPlan = { open("plan") },
                    onProgress = { open("progress") }, onHide = { hidden = true; bubble = false }, tailOnTop = belowPet,
                    modifier = Modifier.offset { IntOffset((bubbleX * density).roundToInt(), (bubbleY * density).roundToInt()) }.width(bubbleWidth.dp))
            }
        } else FilledTonalButton(onClick = { display = true; hidden = false; motion = PetMotion.IDLE },
            modifier = Modifier.align(Alignment.Center).testTag("pet-restore")) {
            Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("叫她回来")
        }
        when (panel) {
            "chat" -> PetChatDialog(messages, thinking, onThinking = { thinking = it; motion = if (it) PetMotion.THINK else PetMotion.TALK }, onReply = { cue() }, onClose = { panel = "" })
            "settings" -> PetSettingsSheet(display, petSize, xFraction, animations, sound, proactive, quiet,
                onDisplay = { display = it; hidden = false; bubble = false }, onSize = { petSize = it },
                onPosition = { xFraction = it }, onAnimations = { animations = it }, onSound = { sound = it },
                onProactive = { proactive = it }, onQuiet = { quiet = it; bubble = false },
                onRemind = { panel = ""; hidden = false; display = true; remind() },
                onLicense = { panel = "license" }, onClose = { panel = "" })
            "actions" -> ModalBottomSheet(onDismissRequest = { panel = "" }, containerColor = Color.White) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                    Text("她的每一种心情", style = MaterialTheme.typography.titleLarge)
                    Text("点选一种动作，回到桌面预览", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
                    Spacer(Modifier.height(16.dp))
                    PetMotion.entries.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { state -> FilterChip(selected = motion == state, onClick = {
                                motion = state; quiet = false; display = true; hidden = false; panel = ""
                            }, label = { Text(state.label, fontSize = 12.sp) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("pet-motion-${state.name}")) }
                        }
                    }
                }
            }
            "plan", "progress" -> PetExampleSheet(panel == "plan", onClose = { panel = "" })
            "license" -> PetLicenseDialog { panel = "settings" }
        }
    }
}

@Composable private fun PetWallpaper() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFE3EEFF), Color(0xFFF1F2FF), Color(0xFFDBE9FF))))
        drawCircle(Color.White.copy(alpha = .45f), radius = size.width * .6f, center = Offset(size.width * .97f, size.height * .2f))
        val wave = Path().apply {
            moveTo(0f, size.height * .61f)
            cubicTo(size.width * .34f, size.height * .43f, size.width * .55f, size.height * .86f, size.width, size.height * .63f)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(wave, Brush.verticalGradient(listOf(Color(0xFFC7DBFF).copy(alpha = .2f), Color(0xFFC2DAFF).copy(alpha = .7f))))
        val lower = Path().apply {
            moveTo(0f, size.height * .82f)
            cubicTo(size.width * .4f, size.height * .67f, size.width * .7f, size.height * .98f, size.width, size.height * .78f)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(lower, Color.White.copy(alpha = .3f))
    }
}

@Composable private fun PetShortcut(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, Modifier.size(52.dp).testTag(tag), shape = RoundedCornerShape(18.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = .83f), contentColor = Color(0xFF3C61A4))) {
            Icon(icon, label, Modifier.size(24.dp))
        }
        Text(label, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color(0xFF3E5275))
    }
}

@Composable private fun PetSpeechBubble(phrase: String, onChat: () -> Unit, onPlan: () -> Unit, onProgress: () -> Unit, onHide: () -> Unit, tailOnTop: Boolean, modifier: Modifier) {
    Column(modifier.testTag("pet-bubble")) {
        if (tailOnTop) SpeechTail(true, Modifier.align(Alignment.CenterHorizontally))
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 8.dp) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PetAvatar(Modifier.size(34.dp)); Column {
                        Text("大肥鱼", fontSize = 12.sp, color = BatterColors.PrimaryDeep, fontWeight = FontWeight.SemiBold)
                        Text(phrase, fontSize = 13.sp, lineHeight = 19.sp, color = BatterColors.Text)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BubbleButton("打开聊天", true, Modifier.weight(1f), onChat)
                    BubbleButton("今日计划", false, Modifier.weight(1f), onPlan)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BubbleButton("执行情况", false, Modifier.weight(1f), onProgress)
                    BubbleButton("暂时隐藏", false, Modifier.weight(1f), onHide)
                }
            }
        }
        if (!tailOnTop) SpeechTail(false, Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable private fun SpeechTail(up: Boolean, modifier: Modifier) {
    Canvas(modifier.size(18.dp, 10.dp)) {
        val base = if (up) size.height else 0f
        drawPath(Path().apply { moveTo(0f, base); lineTo(size.width, base); lineTo(size.width / 2, size.height - base); close() }, Color.White)
    }
}

@Composable private fun BubbleButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (primary) BatterColors.Primary else BatterColors.LightBlue, contentColor = if (primary) Color.White else BatterColors.PrimaryDeep)) {
        Text(label, fontSize = 12.sp)
    }
}
