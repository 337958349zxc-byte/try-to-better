@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.trytobatter.app.feature.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Checkpoints restore paused. A restored preview never silently reopens the camera. */
data class WorkoutDraft(
    val id: String = newId(), val stage: String = "overview", val exercise: Int = 0, val set: Int = 1,
    val reps: Int = 0, val auto: Int = 0, val seconds: Long = 0, val sets: List<WorkoutSet> = emptyList(),
    val simulated: Boolean = false,
) {
    fun encode(): String = JSONObject().apply {
        put("id", id); put("stage", stage); put("exercise", exercise); put("set", set); put("reps", reps)
        put("auto", auto); put("seconds", seconds); put("simulated", simulated)
        put("sets", JSONArray().apply { sets.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("exercise", s.exercise); put("setIndex", s.setIndex); put("autoReps", s.autoReps)
            put("confirmedReps", s.confirmedReps); put("quality", s.quality); put("note", s.note)
        }) } })
    }.toString()
    companion object {
        fun decode(raw: String): WorkoutDraft = runCatching {
            val j = JSONObject(raw); val array = j.getJSONArray("sets")
            WorkoutDraft(j.getString("id"), j.getString("stage"), j.getInt("exercise"), j.getInt("set"), j.getInt("reps"), j.getInt("auto"), j.getLong("seconds"),
                (0 until array.length()).map { i -> val x = array.getJSONObject(i); WorkoutSet(x.getString("id"), x.getString("exercise"), x.getInt("setIndex"), x.getInt("autoReps"), x.getInt("confirmedReps"), x.getString("quality"), x.optString("note")) }, j.optBoolean("simulated"))
                .also { require(it.exercise in Exercises.indices && it.set in 1..Exercises[it.exercise].sets && it.reps in 0..999 && it.seconds >= 0 && it.stage in listOf("overview", "demo", "prepare", "countdown", "training", "paused", "rest", "result")) }
        }.getOrElse { WorkoutDraft() }
    }
}

@Composable fun WorkoutScreen(initialDraft: String, onBack: () -> Unit, onDraft: (String) -> Unit,
    onSave: (WorkoutRecord) -> Unit, onNotify: (String) -> Unit, onExitDraft: (String) -> Unit) {
    val restored = remember { WorkoutDraft.decode(initialDraft) }
    var stage by rememberSaveable { mutableStateOf(if(restored.stage in listOf("training", "countdown")) "paused" else restored.stage) }
    val sessionId = rememberSaveable { restored.id }
    var exerciseIndex by rememberSaveable { mutableIntStateOf(restored.exercise) }
    var setIndex by rememberSaveable { mutableIntStateOf(restored.set) }
    var reps by rememberSaveable { mutableIntStateOf(restored.reps) }
    var auto by rememberSaveable { mutableIntStateOf(restored.auto) }
    var seconds by rememberSaveable { mutableLongStateOf(restored.seconds) }
    var sets by remember { mutableStateOf(restored.sets) }
    val simulated = rememberSaveable { restored.simulated }
    var camera by remember { mutableStateOf(false) }
    var lost by rememberSaveable { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(3) }
    var rest by rememberSaveable { mutableIntStateOf(45) }
    var showExit by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("开启相机后显示实时计数状态") }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exercise = Exercises[exerciseIndex]
    val counter = remember(sessionId,exerciseIndex,setIndex) {PoseRepCounter(exercise.id)}
    fun acceptPose(frame:PoseFrame) {
        val update=counter.update(frame)
        lost=!update.tracked
        feedback=update.message
        if(stage=="training" && update.delta>0) {auto=(auto+update.delta).coerceAtMost(999);reps=(reps+update.delta).coerceAtMost(999)}
        if(stage!="training") counter.reset()
    }
    LaunchedEffect(stage,camera,exerciseIndex,setIndex) {
        counter.reset();lost=camera && stage=="training"
        feedback=when {stage=="paused" -> "识别已暂停，继续本组后恢复计数";!camera -> "相机未开启 · 可手工记录";else -> "等待识别当前动作关节…"}
    }
    fun cameraFailure(message:String) {cameraError=message;camera=false;lost=false;counter.reset()}
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        camera = granted
        if(!granted) cameraError = "相机权限未开启，可继续手工记录。也可到系统设置中开启。"
    }
    fun openCamera() {
        cameraError=""
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) camera=true
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    fun prepareTraining() {stage="prepare";openCamera()}
    fun nextGroup() {
        if(setIndex < exercise.sets) setIndex++ else if(exerciseIndex < Exercises.lastIndex) {exerciseIndex++;setIndex=1} else return
        prepareTraining()
    }
    fun draft() = WorkoutDraft(sessionId, stage, exerciseIndex, setIndex, reps, auto, seconds, sets, simulated).encode()
    val latestDraft by rememberUpdatedState(newValue = { draft() })
    val persistDraft by rememberUpdatedState(onDraft)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) {
            if(stage == "training" || stage == "countdown") stage = "paused"
            camera = false; persistDraft(latestDraft())
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Debounced checkpoints; only active time ticks. Rest never becomes completion by itself.
    LaunchedEffect(stage, exerciseIndex, setIndex, reps, auto, sets) { delay(350); if(stage != "overview") onDraft(draft()) }
    LaunchedEffect(stage, if(stage=="training") lost else false) {
        if(stage == "training" && !lost) {
            var tick = SystemClock.elapsedRealtime()
            while(true) { delay(1000); val now = SystemClock.elapsedRealtime(); seconds += (now - tick) / 1000; tick = now
                if(seconds % 5L == 0L) onDraft(draft())
            }
        }
        if(stage == "countdown") { countdown = 3; while(countdown > 0) { delay(1000); countdown-- }; stage = "training" }
        if(stage == "rest") while(rest > 0) { delay(1000); rest-- }
    }
    fun leave() { if(stage == "overview") onBack() else showExit = true }
    BackHandler { leave() }
    fun finishSet() {
        sets = sets + WorkoutSet(exercise = exercise.name, setIndex = setIndex, autoReps = auto, confirmedReps = reps,
            note = if(simulated) "会话含历史模拟计数，已由用户核对" else if(auto > 0) "本机姿态自动计数，已由用户核对" else "手工记录")
        auto = 0; reps = 0; lost = false; rest = 45; feedback = "本组已结束"
        stage = if(exerciseIndex == Exercises.lastIndex && setIndex == exercise.sets) "result" else "rest"
    }
    BatterTheme(training = true) {
        DetailScaffold("历史训练进度", ::leave) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(stage in listOf("prepare","countdown","training","paused")) Box(Modifier.fillMaxWidth().height(240.dp).testTag("workout-camera")) {
                    CameraPanel(camera,Modifier.fillMaxSize(),::cameraFailure,analyzeEnabled=stage!="paused",onPose=::acceptPose)
                    if(!camera) Button(onClick=::openCamera,modifier=Modifier.align(Alignment.Center).heightIn(min=52.dp)) {Text("开启相机预览与计数")}
                }
                if(stage in listOf("prepare","countdown","training","paused")) Text(feedback,
                    modifier=Modifier.fillMaxWidth().padding(horizontal=Space.Page,vertical=8.dp),style=MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(Space.Page, 6.dp, Space.Page, 24.dp), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
                if(cameraError.isNotBlank()) item {Notice(cameraError,warning=true)}
                when(stage) {
                    "overview" -> {
                        item { Text("稳稳完成每一组", style = MaterialTheme.typography.headlineLarge); Text("2 个动作 · 4 组 · 组间休息 45 秒", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { AppCard { Text("今天的动作", style = MaterialTheme.typography.titleMedium); Exercises.forEach { Text("${it.name}    ${it.reps} 次 × ${it.sets} 组") } } }
                        item { AppCard { Icon(Icons.Outlined.FitnessCenter, null); Text("选择稳固支撑，按舒适幅度完成。感觉不适时暂停。", style = MaterialTheme.typography.bodyLarge) } }
                        item { Notice("本机姿态计数支持椅子坐站与靠墙俯卧撑，可按实际完成订正次数。") }
                        item { WorkoutButton("开始跟练", ::prepareTraining) }
                        item { TextButton(onClick={stage="demo"}) {Text("先看动作步骤")} }
                    }
                    "demo" -> {
                        item { Text(exercise.name, style = MaterialTheme.typography.headlineLarge) }
                        item { CameraPanel(false, Modifier.fillMaxWidth().height(230.dp), { cameraError = it }) }
                        item { AppCard { Text("动作步骤", style = MaterialTheme.typography.titleMedium); exercise.instructions.forEachIndexed { i, s -> Text("${i + 1}. $s") }; Text("示范视频待接入，当前仅提供步骤与入镜示意。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        item { WorkoutButton("摆放手机", ::prepareTraining) }
                    }
                    "prepare" -> {
                        item { Text("先把手机摆稳", style = MaterialTheme.typography.headlineMedium); Text("${exercise.name} · 第 $setIndex / ${exercise.sets} 组"); Text(exercise.angle) }
                        item { AppCard { Text("入镜检查", style = MaterialTheme.typography.titleMedium); Text("全身与动作支撑点可见\n拍摄方向与动作要求一致\n光线充足，手机保持稳定"); Text("识别需要侧面全身入镜，绿色连线表示已检测到的身体关键点。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
                        item { WorkoutButton("我已准备好，3 秒后开始", { stage = "countdown" }) }
                        item { TextButton(onClick = { stage = "demo" }) { Text("再看动作步骤") } }
                    }
                    "countdown" -> {
                        item { Text(exercise.name, style = MaterialTheme.typography.headlineMedium) }
                        item { Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { Text("$countdown", fontSize = 96.sp, fontWeight = FontWeight.Bold) } }
                        item { WorkoutButton("取消倒计时", { stage = "prepare" }) }
                    }
                    "training", "paused" -> {
                        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(exercise.name, style = MaterialTheme.typography.headlineMedium); Text("第 $setIndex / ${exercise.sets} 组 · 目标 ${exercise.reps} 次") }; StateBadge(if(stage == "paused") "已暂停" else if(camera && lost) "等待入镜" else if(camera) "自动计数" else "手工计数") } }
                        item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Text("$reps", fontSize = 80.sp, lineHeight = 88.sp, fontWeight = FontWeight.Bold); Text("本组确认次数   /   累计活动 ${formatDuration(seconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = { reps = (reps - 1).coerceAtLeast(0) }, Modifier.weight(1f).heightIn(min = 52.dp)) { Text("手工 −1") }; Button(onClick = { reps = (reps + 1).coerceAtMost(999) }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = stage == "training") { Text("手工 +1") } } }
                        item { Text("自动识别次数 $auto · 最终以确认次数保存",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { WorkoutButton(if(stage == "paused") "继续本组" else "暂停本组", { stage = if(stage == "paused") "training" else "paused" }) }
                        item { OutlinedButton(onClick = ::finishSet, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("结束本组并核对") } }
                    }
                    "rest" -> {
                        item { Text("这一组，完成了", style = MaterialTheme.typography.headlineLarge); Text("休息后再继续，按体感调整。") }
                        item { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text(formatDuration(rest.toLong()), fontSize = 64.sp, fontWeight = FontWeight.Bold) } }
                        item { AppCard { Text("刚才一组确认 ${sets.lastOrNull()?.confirmedReps ?: 0} 次"); Text("动作质量未评分", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        item { WorkoutButton("准备下一组", ::nextGroup) }
                        item { TextButton(onClick = { stage = "result" }) { Text("提前结束，按实际记录") } }
                    }
                    "result" -> {
                        item { Text("按实际情况留下记录", style = MaterialTheme.typography.headlineMedium); Text("活动用时 ${formatDuration(seconds)} · 已记录 ${sets.size} 组") }
                        if(exerciseIndex < Exercises.lastIndex || setIndex < exercise.sets) item {
                            WorkoutButton("继续跟练", ::nextGroup)
                            Text("保留已记录组次，进入下一组相机预览。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        sets.forEachIndexed { index, s -> item(key = s.id) { AppCard { Text("${s.exercise} · 第 ${s.setIndex} 组", style = MaterialTheme.typography.titleMedium); Text("自动 ${s.autoReps} 次 / 确认 ${s.confirmedReps} 次"); Text(if(s.quality=="无法判断") "动作质量未评分" else "动作质量：${s.quality}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("订正次数", Modifier.weight(1f)); IconButton(onClick = { sets = sets.toMutableList().also { it[index] = s.copy(confirmedReps = (s.confirmedReps - 1).coerceAtLeast(0)) } }) { Icon(Icons.Outlined.Remove, "减少${s.exercise}第${s.setIndex}组") }; Text("${s.confirmedReps}"); IconButton(onClick = { sets = sets.toMutableList().also { it[index] = s.copy(confirmedReps = (s.confirmedReps + 1).coerceAtMost(999)) } }) { Icon(Icons.Outlined.Add, "增加${s.exercise}第${s.setIndex}组") } }
                        } } }
                        item { WorkoutButton("保存训练记录", { onSave(WorkoutRecord(id = sessionId, sets = sets, seconds = seconds, simulated = simulated, note = if(simulated) "会话含历史模拟事件" else "本机姿态计数与手工订正，动作质量未自动评定")) }, enabled = sets.isNotEmpty()) }
                        item { DemoNote("保存训练组次后，日程事项仍需按实际完成情况单独确认。") }
                    }
                }
            }
            }
        }
        if(showExit) AlertDialog(onDismissRequest = { showExit = false }, title = { Text("暂时离开跟练？") }, text = { Text("组次和次数会保存在本机。再次进入时计时保持暂停，摄像头需重新开启。") }, confirmButton = { TextButton(onClick = { if(stage == "training" || stage == "countdown") stage = "paused"; onExitDraft(draft()) }) { Text("保留进度并返回") } }, dismissButton = { TextButton(onClick = { showExit = false }) { Text("继续跟练") } })
    }
}

@Composable private fun WorkoutButton(text: String, action: () -> Unit, enabled: Boolean = true) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text) }
}
