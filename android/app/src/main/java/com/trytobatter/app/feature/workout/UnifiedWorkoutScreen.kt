@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.trytobatter.app.feature.workout

import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trytobatter.app.core.data.SnapshotCodec
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.delay

@Composable fun UnifiedWorkoutHub(state:AppSnapshot,requestedId:String?,onBack:()->Unit,onDraft:(String)->Unit,
    onRunning:(String,Boolean)->Unit,onSave:(UnifiedWorkoutDraft)->Unit) {
    val draft=remember(state.workoutDraft) {UnifiedWorkoutDraft.decode(state.workoutDraft)?.takeUnless {d ->state.workouts.any {it.id==d.id}}}
    var selected by rememberSaveable {mutableStateOf(requestedId.orEmpty())}
    val task=state.tasks.find {it.id==selected && UnifiedTraining.isStrength(it)}
    if(task!=null && !task.isFinished && (draft==null || draft.taskId==task.id)) {
        key(task.id) {UnifiedWorkoutSession(task,draft,onBack,onDraft,onRunning,onSave,
            canStart=state.tasks.none {it.status==TaskStatus.RUNNING && it.id!=task.id})}
        return
    }
    DetailScaffold(UnifiedTraining.title,onBack) {padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Text("统一七动作模板",style=MaterialTheme.typography.headlineMedium)
                Text("选择本次日程。第二周加量需要明确确认首周恢复良好，日期不会自动改变负荷。")}
            if(requestedId!=null && task==null) item {Text("当前事项不是本周期全身力量训练；步行、恢复请使用事项本身的计时和结果记录。")}
            if(draft!=null) item {AppCard {
                Text("已有未保存训练进度 · D${draft.day}")
                if(state.tasks.any {it.id==draft.taskId && !it.isFinished}) Button(onClick={selected=draft.taskId}) {Text("继续这次训练")}
                else Text("关联事项已结束或不在有效计划中。进度仍保存在本机备份，请核对记录后处理。")
            }}
            val plans=state.tasks.filter(UnifiedTraining::isStrength).sortedBy {it.scheduledAt}
            if(plans.isEmpty()) item {Text("尚未收到周衡V1.4力量日程，请先同步电脑计划。")}
            plans.forEach {t ->item(key=t.id) {AppCard {
                Text("D${UnifiedTraining.day(t)} · ${t.day} ${t.timeLabel}",style=MaterialTheme.typography.titleMedium)
                Text(t.status.label)
                Button(onClick={selected=t.id},enabled=!t.isFinished && (draft==null || draft.taskId==t.id)) {Text("查看动作并执行")}
            }}}
        }
    }
}

@Composable private fun UnifiedWorkoutSession(task:TaskItem,restored:UnifiedWorkoutDraft?,onBack:()->Unit,
    onDraft:(String)->Unit,onRunning:(String,Boolean)->Unit,onSave:(UnifiedWorkoutDraft)->Unit,canStart:Boolean) {
    var session by remember {mutableStateOf(restored ?: UnifiedWorkoutDraft(taskId=task.id,day=UnifiedTraining.day(task)!!,taskSnapshot=SnapshotCodec.task(task).toString()))}
    var confirmRecovery by rememberSaveable {mutableStateOf(false)}
    var confirmSave by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    var cameraIndex by remember {mutableStateOf<Int?>(null)}
    var cameraEnabled by remember {mutableStateOf(false)}
    var cameraFeedback by remember {mutableStateOf("仅提供计数，动作质量未评分")}
    val context=LocalContext.current
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        cameraEnabled=granted;if(!granted) cameraFeedback="相机未授权，可继续手工记录"
    }
    val cameraCounter=remember(cameraIndex) {PoseRepCounter(if(cameraIndex?.let {session.rows[it].exerciseId}=="wh1") "chair" else "wall")}
    LaunchedEffect(session.running,cameraIndex) {cameraCounter.reset()}
    val owner=LocalLifecycleOwner.current
    val latest by rememberUpdatedState(session)
    val persist by rememberUpdatedState(onDraft)
    val runningAction by rememberUpdatedState(onRunning)
    fun pauseAndExit() {cameraEnabled=false;session=session.copy(running=false);if(session.startedAt>0) {onDraft(session.encode());onRunning(task.id,false)};onBack()}
    BackHandler {pauseAndExit()}
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver {_,event ->if(event==Lifecycle.Event.ON_STOP && latest.startedAt>0) {
            cameraEnabled=false;val paused=latest.copy(running=false);session=paused;persist(paused.encode());runningAction(task.id,false)
        }}
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(session.running) {
        if(session.running) {
            var tick=SystemClock.elapsedRealtime()
            while(true) {delay(1000);val now=SystemClock.elapsedRealtime();val added=(now-tick)/1000
                if(added>0) {session=session.copy(seconds=session.seconds+added);tick+=added*1000}}
        }
    }
    LaunchedEffect(session.rows,session.week2,session.startedAt,session.running,session.seconds/5,session.rpe,session.discomfort,session.recovery,session.outcome) {
        if(session.startedAt>0) {delay(250);onDraft(session.encode())}
    }
    DetailScaffold(UnifiedTraining.title,::pauseAndExit) {padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(18.dp,12.dp,18.dp,36.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Text("D${session.day} · ${task.day}",style=MaterialTheme.typography.titleMedium)
                Text("活动计时 ${formatDuration(session.seconds)} · ${if(session.running) "进行中" else "已暂停／待开始"}",style=MaterialTheme.typography.headlineSmall)
                Text("实际次数由你逐组填写；七动作质量未自动评分。未填是未知，计划次数不会成为实际完成量。")}
            item {AppCard {
                Text("本次负荷：${if(session.week2) "第二周（已确认恢复良好）" else "第一周"}",style=MaterialTheme.typography.titleMedium)
                Text("热身5–7分钟，组间休息60–90秒，必要时延长。第一周RPE5–6；第二周恢复良好时6–7。每组保留约2–4次余力，不力竭。")
                if(session.day>=8 && session.startedAt==0L) {
                    Row {Checkbox(confirmRecovery,{confirmRecovery=it;if(!it) session=session.copy(week2=false,rows=UnifiedTraining.rows(false))});Text("我已核对第一周动作标准且次日恢复良好")}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!session.week2,{session=session.copy(week2=false,rows=UnifiedTraining.rows(false))},{Text("维持第一周")})
                        FilterChip(session.week2,{if(confirmRecovery) session=session.copy(week2=true,rows=UnifiedTraining.rows(true))},{Text("采用第二周")},enabled=confirmRecovery)
                    }
                }
                if(session.day>=8) Text("首周动作不能标准完成或次日疲劳明显时维持第一周。开始后本次处方冻结。")
                Button(onClick={
                    if(!canStart) {error="还有其他事项在计时，请先暂停它再开始训练";return@Button}
                    session=session.copy(startedAt=session.startedAt.takeIf {it>0}?:System.currentTimeMillis(),running=!session.running)
                    onDraft(session.encode());onRunning(task.id,session.running)
                },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(session.running) "暂停" else if(session.startedAt>0) "继续" else "开始训练")}
            }}
            UnifiedTraining.moves.forEach {m ->item(key=m.id) {AppCard {
                Text(m.name,style=MaterialTheme.typography.titleLarge)
                Text("计划 ${m.sets(session.week2)} 组 × ${m.target(session.week2)}",style=MaterialTheme.typography.titleMedium)
                Text(m.guide)
                session.rows.forEachIndexed {index,row ->if(row.exerciseId==m.id) {
                    Text("第 ${row.index} 组",style=MaterialTheme.typography.labelLarge)
                    if(m.id in setOf("wh1","wh2")) {
                        TextButton(onClick={
                            cameraIndex=index;cameraFeedback="侧面全身入镜，等待身体关键点"
                            if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) cameraEnabled=true
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        },enabled=session.running) {Text(if(m.id=="wh1") "本组相机辅助计数" else "本组使用墙壁：相机辅助计数")}
                        if(cameraIndex==index) {
                            CameraPanel(cameraEnabled,Modifier.fillMaxWidth().height(210.dp),{cameraFeedback=it;cameraEnabled=false},
                                analyzeEnabled=session.running,onPose={frame ->
                                    val update=cameraCounter.update(frame);cameraFeedback=update.message
                                    if(session.running && update.delta>0) {
                                        val current=session.rows[index]
                                        session=session.copy(rows=session.rows.toMutableList().also {it[index]=current.copy(automatic=(current.automatic+update.delta).coerceAtMost(999))})
                                    }
                                })
                            Text("$cameraFeedback · 自动 ${row.automatic} 次 · 动作质量未评分")
                            Text("相机仅支持侧面椅子动作与墙壁模式；实际准确率待你核对。其他动作手工记录。")
                            TextButton(onClick={session=session.copy(rows=session.rows.toMutableList().also {it[index]=row.copy(left=row.automatic.toString(),done=false)})},enabled=session.startedAt>0) {Text("核对后采用自动次数")}
                            TextButton(onClick={cameraEnabled=false;cameraIndex=null}) {Text("关闭本组相机")}
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(row.left,{v ->session=session.copy(rows=session.rows.toMutableList().also {it[index]=row.copy(left=v.filter(Char::isDigit).take(3),done=false)})},
                            modifier=Modifier.weight(1f),label={Text(if(m.perSide) "左侧实际次数" else "实际次数")},singleLine=true,enabled=session.startedAt>0,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        if(m.perSide) OutlinedTextField(row.right,{v ->session=session.copy(rows=session.rows.toMutableList().also {it[index]=row.copy(right=v.filter(Char::isDigit).take(3),done=false)})},
                            modifier=Modifier.weight(1f),label={Text("右侧实际次数")},singleLine=true,enabled=session.startedAt>0,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                    }
                    Row {Checkbox(row.done,{v ->session=session.copy(rows=session.rows.toMutableList().also {it[index]=row.copy(done=v)})},enabled=session.startedAt>0 && row.countsValid(m.perSide));Text("确认本组已完成")}
                }}
            }}}
            item {AppCard {
                Text("训练结果",style=MaterialTheme.typography.titleLarge)
                OutlinedTextField(session.rpe,{session=session.copy(rpe=it.take(2))},label={Text("RPE 0–10，未知留空")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(session.discomfort,{session=session.copy(discomfort=it)},label={Text("身体不适：无或具体部位与情况")},minLines=2)
                OutlinedTextField(session.recovery,{session=session.copy(recovery=it)},label={Text("恢复情况；次日可在执行记录订正中补充")},minLines=2)
                listOf(TaskStatus.COMPLETED,TaskStatus.PARTIAL,TaskStatus.SKIPPED).forEach {status ->
                    FilterChip(session.outcome==status,{session=session.copy(outcome=status)},{Text(status.label)})
                }
                Text("延期：保留进度返回，在事项编辑中改日期；完成及部分完成均由你核对后保存。")
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                Button(onClick={
                    val paused=session.copy(running=false);session=paused
                    if(paused.startedAt>0) {onDraft(paused.encode());onRunning(task.id,false)}
                    error=paused.validation().orEmpty();if(error.isBlank()) confirmSave=true
                },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=session.startedAt>0) {Text("结束并核对保存")}
            }}
        }
    }
    if(confirmSave) AlertDialog(onDismissRequest={confirmSave=false},title={Text("保存本次真实结果？")},
        text={Text("${session.outcome.label} · ${session.seconds}秒。保存逐组实际次数与本次计划快照，并同步结束关联事项计时。")},
        confirmButton={TextButton(onClick={confirmSave=false;onSave(session.copy(running=false))}) {Text("确认保存")}},
        dismissButton={TextButton(onClick={confirmSave=false}) {Text("返回核对")}})
}
