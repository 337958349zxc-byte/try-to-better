package com.trytobatter.app.core.alarm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlarmAdd
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.trytobatter.app.core.data.AppRepository
import com.trytobatter.app.core.designsystem.Space
import com.trytobatter.app.core.designsystem.Notice
import com.trytobatter.app.core.designsystem.BatterColors
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable fun TimelineClockControl(repository: AppRepository, onNotify: (String) -> Unit) {
    val batchVm: TimelineClockBatchViewModel = viewModel(viewModelStoreOwner=LocalActivity.current as ComponentActivity)
    val progress by batchVm.state.collectAsStateWithLifecycle()
    val delivery by repository.clockDiagnosticDelivery.collectAsStateWithLifecycle()
    val tasks by repository.state.collectAsStateWithLifecycle()
    val ledger by repository.timelineClock.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val resumed=lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val today=LocalDate.now().toString()
    val app=repository.systemClockApp()
    val targets=TimelineClockRules.targets(tasks.tasks,app?.packageName.orEmpty())
    val previous=ledger.entries.values.filter { it.day==today }
    var issue by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val busy=progress.busy
    val pending=if(app==null) emptyList() else TimelineClockRules.plan(tasks.tasks,ledger,app.packageName,true)
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {batchVm.returned(it.resultCode)}
    DisposableEffect(lifecycle,batchVm) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_PAUSE) batchVm.leftApp()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(progress,resumed) {batchVm.pump(resumed) {launcher.launch(it)}}
    // External clock maintenance is performed only while Today is visible and the app is resumed.
    val taskVersion=tasks.tasks.joinToString { "${it.id}:${it.revision}:${it.status}:${it.scheduledAt}" }
    LaunchedEffect(taskVersion,resumed,busy) {
        if(ledger.enabledDay==today && app!=null && resumed && !busy && pending.isNotEmpty() && issue.isBlank() && progress.issue.isBlank()) batchVm.begin(true)
    }
    val error=issue.ifBlank {progress.issue}.ifBlank {ledger.error}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Surface(shape=RoundedCornerShape(Space.Radius),color=MaterialTheme.colorScheme.surface,border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant)) {
    Column(Modifier.padding(horizontal=12.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.AlarmAdd,null,tint=BatterColors.Primary)
            Column(Modifier.weight(1f)) {
                Text("时间轴一键提醒",style=MaterialTheme.typography.titleSmall)
                Text(if(busy) "正在处理 ${progress.finished}/${progress.total} 项" else "${targets.size}项可提醒 · 提前10分钟",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
            }
            TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp).semantics {stateDescription=if(expanded) "已展开" else "已收起"}) {
                Text(if(expanded) "收起" else "管理")
                Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))
            }
        }
        if(expanded || busy) {
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Text("通过${app?.label ?: "系统时钟"}处理，实际创建与关闭需到时钟核对。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
        Button(onClick={batchVm.begin(true)},enabled=app!=null && !busy && (targets.isNotEmpty() || pending.isNotEmpty()),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Icon(Icons.Outlined.AlarmAdd,null);Spacer(Modifier.width(8.dp))
            Text(if(busy) "正在${if(progress.closing) "取消" else "设置"} ${progress.finished}/${progress.total}…" else if(ledger.enabledDay==today && pending.isNotEmpty()) "更新全部系统闹钟" else "一键设置全部闹钟")
        }
        if(app?.packageName.isNullOrBlank()) Text("如弹出应用选择，请选择手机自带时钟。",style=MaterialTheme.typography.bodySmall)
        if(previous.isNotEmpty()) {
            Text("${previous.count {it.stage==ClockRequestStage.REQUESTED}} 项请求已返回 · 创建和取消结果请到系统闹钟核对",style=MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(previous.isNotEmpty() || busy) TextButton(onClick={batchVm.begin(false)},enabled=!progress.closing || !busy) {Text("全部关闭")}
            TextButton(onClick={runCatching {context.startActivity(repository.showSystemClockIntent())}.onFailure {issue=SystemClockBridge.failureText(it as? Exception ?: Exception(it))}}) {Text("查看系统闹钟")}
            TextButton(onClick={
                sending=true
                scope.launch {
                    try {onNotify(repository.sendClockDiagnostics())}
                    catch(e:kotlinx.coroutines.CancellationException) {throw e}
                    catch(e:Exception) {issue=e.message ?: "诊断发送未完成"}
                    finally {sending=false}
                }
            },enabled=!sending) {Text(if(sending) "正在发送诊断…" else "发送时钟诊断到电脑")}
        }
        if(delivery.isNotBlank()) Text(delivery,style=MaterialTheme.typography.bodySmall)
        if(busy) Text("若系统时钟显示确认页，请确认后返回，继续处理剩余 ${progress.queue.size+(if(progress.flight!=null) 1 else 0)} 项。",style=MaterialTheme.typography.bodySmall)
        Text("仅处理今天尚未开始且提醒时间未过的事项；改期、删除后回到今天页继续更新。",style=MaterialTheme.typography.bodySmall)
        }
    }
    }
    if(error.isNotBlank()) Notice(error,warning=true)
    }
}
