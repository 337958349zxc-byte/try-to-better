@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.trytobatter.app.feature.today

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import com.trytobatter.app.core.alarm.*
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.launch

@Composable fun TaskDetailScreen(task: TaskItem?, nowElapsed: Long, onBack: () -> Unit, onEdit: () -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onOutcome: () -> Unit, onWorkout: () -> Unit, onDelete: (Boolean) -> Unit, onNotify: (String) -> Unit, onRecipes: () -> Unit = {}, onOpenComputer: suspend (String) -> Unit = {}) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(false) }
    var openingComputer by remember(task?.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val learningResources = remember(task?.resourceUrl, task?.note, task?.category) { task?.let(LearningResources::from).orEmpty() }
    val selections = remember(task?.note) { LearningResources.selections(task?.note.orEmpty()) }
    DetailScaffold("事项详情", onBack, actions = { if(task != null && !task.isFinished) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑事项") } }) { padding ->
        if(task == null) EmptyState("事项已不在当前安排中", "执行历史仍可在记录页查看。") else
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { CategoryIcon(task.category); Column { Text(task.category.label, style = MaterialTheme.typography.bodyMedium, color = task.category.color()); Text(task.title, style = MaterialTheme.typography.headlineMedium) } } }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StateBadge(task.status.label); if(task.fixed) StateBadge("固定事项"); if(task.seriesId != null) StateBadge(RepeatRules.label(task.repeatRule)) } }
            if(task.isDemo) item { DemoNote("演示事项 · 执行与修改保存在本机") }
            if(task.category == Category.MEAL) item { Button(onClick=onRecipes,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Icon(Icons.Outlined.Restaurant, null); Spacer(Modifier.width(8.dp)); Text("打开双人菜谱")
            } }
            if(task.executionSnapshot.isNotBlank()) item { AppCard { Text("本次执行快照", style = MaterialTheme.typography.titleMedium); Text(task.executionSnapshot, style = MaterialTheme.typography.bodyMedium); Text("开始时的内容保持不变；下方显示当前计划安排。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) } }
            item { AppCard {
                Text("计划安排", style = MaterialTheme.typography.titleMedium)
                Text("${task.day}  ${task.timeLabel}")
                Text("计划 ${task.durationMinutes} 分钟 / 实际 ${formatDuration(TaskRules.elapsed(task, nowElapsed))}", color = BatterColors.Secondary)
                if(task.executionStartedAt > 0) Text("本次执行使用开始时的步骤快照", style = MaterialTheme.typography.bodySmall, color = BatterColors.Primary)
            } }
            item { AppCard {
                Text("做到哪里算完成", style = MaterialTheme.typography.titleMedium)
                Text(task.criteria.ifBlank { "按实际完成情况记录结果。" })
            } }
            item { AppCard {
                Text("一步一步来", style = MaterialTheme.typography.titleMedium)
                task.steps.lines().filter { it.isNotBlank() }.ifEmpty { listOf("开始执行，结束后记下实际结果。") }.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StateBadge("${i + 1}", BatterColors.Primary); Text(step, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge) }
                }
            } }
            if(task.category in setOf(Category.AI, Category.ENGLISH) && (learningResources.isNotEmpty() || selections.isNotEmpty())) item { AppCard {
                Text("视频与学习资料", style = MaterialTheme.typography.titleMedium)
                if(selections.isNotEmpty()) {
                    Text("本次指定选集", style = MaterialTheme.typography.labelLarge, color = BatterColors.Primary)
                    selections.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
                learningResources.forEach { resource ->
                    HorizontalDivider()
                    Text(resource.label, style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resource.url))) }
                            .onFailure { onNotify("没有找到可打开此资料的应用") }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("在手机打开") }
                    if(!task.isDemo) Button(onClick = { scope.launch {
                        openingComputer = resource.url
                        try { onOpenComputer(resource.url) } finally { openingComputer = null }
                    } }, enabled = openingComputer == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.Computer, null); Spacer(Modifier.width(8.dp)); Text(if(openingComputer == resource.url) "正在发送到电脑…" else "在电脑打开")
                    }
                }
                Text("按指定选集学习，打开视频不代表已观看或掌握；课程计时与完成记录不变。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
            } }
            if(task.category !in setOf(Category.AI, Category.ENGLISH) && task.resourceUrl.isNotBlank() && !(task.category == Category.MEAL && Uri.parse(task.resourceUrl).path?.startsWith("/recipes/") == true)) item { OutlinedButton(onClick = {
                val uri = Uri.parse(task.resourceUrl)
                if(uri.scheme !in listOf("http", "https")) onNotify("资料链接需要以 http 或 https 开头") else
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { onNotify("没有找到可打开此链接的应用") }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(if(task.category == Category.MEAL) "打开备餐参考资料" else "打开外部学习资料") } }
            if(task.note.isNotBlank()) item { AppCard { Text("备注", style = MaterialTheme.typography.titleMedium); Text(task.note) } }
            if(task.category == Category.WORKOUT) item { Button(onClick = onWorkout, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.FitnessCenter, null); Spacer(Modifier.width(8.dp)); Text("进入运动跟练") } }
            if(!task.isFinished) {
                item { Button(onClick = if(task.status == TaskStatus.RUNNING) onPause else onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(when(task.status) { TaskStatus.RUNNING -> "暂停计时"; TaskStatus.PAUSED -> "继续计时"; else -> "开始任务" })
                } }
                item { OutlinedButton(onClick = onOutcome, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("记录完成情况") } }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onEdit) { Text("编辑 / 改期 / 缩短") }
                    if(!task.isProtected) TextButton(onClick = { confirmDelete = true }) { Text("删除事项", color = BatterColors.Error) }
                } }
            }
            item { Text("打开资料或计时到点后，请按实际情况记录结果。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) }
        }
    }
    if(confirmDelete && task != null) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这项安排？") }, text = {
        Column { Text("删除未来安排会产生待同步变更，已有执行历史会保留。")
            if(task.seriesId != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(following, { following = it }); Text("本次及以后的未执行安排") }
        }
    }, confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(following) }) { Text("删除", color = BatterColors.Error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留") } })
}

@Composable fun EditTaskScreen(original: TaskItem?, initialDay: String, allTasks: List<TaskItem>, onBack: () -> Unit,
    alarmSetting: TaskAlarmSettings = TaskAlarmSettings(), alarmRecord: AlarmRecord? = null, onRefreshAlarm: () -> Unit = {},
    onSave: (TaskItem, Boolean, TaskAlarmSettings) -> Unit) {
    val context = LocalContext.current
    val newTaskId = rememberSaveable { newId() }
    var title by rememberSaveable(original?.id) { mutableStateOf(original?.title ?: "") }
    var categoryName by rememberSaveable(original?.id) { mutableStateOf((original?.category ?: Category.LIFE).name) }
    var dayText by rememberSaveable(original?.id) { mutableStateOf(original?.day?.toString() ?: initialDay) }
    var timeText by rememberSaveable(original?.id) { mutableStateOf(original?.start?.toLocalTime()?.format(ClockFormat) ?: LocalTime.now().withSecond(0).withNano(0).format(ClockFormat)) }
    var duration by rememberSaveable(original?.id) { mutableStateOf((original?.durationMinutes ?: 30).toString()) }
    var fixed by rememberSaveable(original?.id) { mutableStateOf(original?.fixed ?: false) }
    var repeat by rememberSaveable(original?.id) { mutableStateOf(original?.repeatRule ?: "") }
    var steps by rememberSaveable(original?.id) { mutableStateOf(original?.steps ?: "") }
    var url by rememberSaveable(original?.id) { mutableStateOf(original?.resourceUrl ?: "") }
    var criteria by rememberSaveable(original?.id) { mutableStateOf(original?.criteria ?: "") }
    var note by rememberSaveable(original?.id) { mutableStateOf(original?.note ?: "") }
    var error by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<TaskItem?>(null) }
    var showOverlap by remember { mutableStateOf(false) }
    var showScope by remember { mutableStateOf(false) }
    var dirtyExit by remember { mutableStateOf(false) }
    var scopeFollowing by remember { mutableStateOf(false) }
    var alarmEnabled by rememberSaveable(original?.id) { mutableStateOf(alarmSetting.enabled) }
    var alarmLead by rememberSaveable(original?.id) { mutableIntStateOf(alarmSetting.leadMinutes) }
    var alarmMode by rememberSaveable(original?.id) { mutableStateOf(alarmSetting.mode.name) }
    val draftAlarm = TaskAlarmSettings(alarmEnabled,alarmLead,AlarmMode.valueOf(alarmMode))
    val initialTime = rememberSaveable { timeText }
    val edited = title != (original?.title ?: "") || duration != (original?.durationMinutes ?: 30).toString() || note != (original?.note ?: "") || timeText != initialTime || dayText != (original?.day?.toString() ?: initialDay) || categoryName != (original?.category ?: Category.LIFE).name || fixed != (original?.fixed ?: false) || repeat != (original?.repeatRule ?: "") || steps != (original?.steps ?: "") || url != (original?.resourceUrl ?: "") || criteria != (original?.criteria ?: "")
    val anyEdited = edited || draftAlarm != alarmSetting
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var optionsExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(anyEdited) { dirtyExit = true }
    var overlapCount by remember { mutableIntStateOf(0) }
    var overlapDetails by remember { mutableStateOf("") }
    var saveFollowing by remember { mutableStateOf(false) }
    val ruleChanged = original != null && repeat != original.repeatRule
    fun submit(following:Boolean) {
        val editedTask=pending ?: return
        runCatching {
            val proposed=RepeatRules.save(allTasks,editedTask,following)
            val before=allTasks.associateBy {it.id}
            val changed=proposed.filter {before[it.id]!=it}
            overlapCount=changed.count {TaskRules.conflicts(it,proposed).isNotEmpty()}
            overlapDetails=changed.filter {TaskRules.conflicts(it,proposed).isNotEmpty()}.take(5).joinToString("\n") {t->
                "${t.day} ${t.timeLabel} · ${TaskRules.conflicts(t,proposed).take(2).joinToString("、") {it.title}}"
            }
            saveFollowing=following
            if(overlapCount>0) showOverlap=true else onSave(editedTask,following,draftAlarm)
        }.onFailure {error=it.message?:"重复安排未保存，请检查设置"}
    }
    fun savePrepared() { if(original?.seriesId != null) {if(ruleChanged) scopeFollowing=true;showScope=true} else submit(false) }
    DetailScaffold(if(original == null) "新增事项" else "编辑事项", { if(anyEdited) dirtyExit = true else onBack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(Space.Page, 8.dp, Space.Page, 24.dp), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
            item { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("事项名称") }, singleLine = true, isError = title.isBlank() && error.isNotBlank()) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c -> FilterChip(selected = categoryName == c.name, onClick = { categoryName = c.name }, label = { Text(c.label) }) }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { val date = LocalDate.parse(dayText); DatePickerDialog(context, { _, y, m, d -> dayText = LocalDate.of(y, m + 1, d).toString() }, date.year, date.monthValue - 1, date.dayOfMonth).show() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(dayText) }
                OutlinedButton(onClick = { val time = LocalTime.parse(timeText); TimePickerDialog(context, { _, h, m -> timeText = LocalTime.of(h, m).format(ClockFormat) }, time.hour, time.minute, true).show() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(timeText) }
            } }
            item { OutlinedTextField(duration, { duration = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("计划时长（分钟）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, supportingText = { Text("缩短计划时长不会抹除已记录的实际用时") }) }
            item { AlarmEditor(draftAlarm,alarmRecord,"${dayText}T${timeText}",{alarmEnabled=it.enabled;alarmLead=it.leadMinutes;alarmMode=it.mode.name},onRefreshAlarm,saved=!edited && draftAlarm==alarmSetting) }
            item { TextButton(onClick={optionsExpanded=!optionsExpanded}) {Text(if(optionsExpanded) "收起重复与其他设置" else "重复与其他设置") } }
            if(optionsExpanded) item { AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("固定时间", style = MaterialTheme.typography.titleSmall); Text("改期时优先保护这段时间", style = MaterialTheme.typography.bodyMedium, color = BatterColors.Secondary) }; Switch(fixed, { fixed = it }) }
                RepeatSettings(repeat,LocalDate.parse(dayText),original?.let {!RepeatRules.editable(it)}==true,original?.seriesId==null || ruleChanged) {repeat=it}
            } }
            item { TextButton(onClick={detailsExpanded=!detailsExpanded}) { Text(if(detailsExpanded) "收起步骤和资料" else "步骤、资料与完成条件") } }
            if(detailsExpanded) {
                item { OutlinedTextField(steps, { steps = it }, Modifier.fillMaxWidth(), label = { Text("执行步骤（每行一步）") }, minLines = 2) }
                item { OutlinedTextField(criteria, { criteria = it }, Modifier.fillMaxWidth(), label = { Text("完成条件") }, minLines = 2) }
                item { OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("外部资料链接（可选）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
            }
            if(optionsExpanded) item { OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("备注（可选）") }, minLines = 2) }
            if(error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            item { Button(onClick = {
                val minutes = duration.toIntOrNull()
                when {
                    title.isBlank() -> error = "请填写事项名称"
                    minutes == null || minutes !in 1..1440 -> error = "计划时长应为 1–1440 分钟"
                    url.isNotBlank() && !url.startsWith("https://") && !url.startsWith("http://") -> error = "资料链接需要以 http:// 或 https:// 开头"
                    else -> {
                        error = ""
                        pending = (original ?: TaskItem(id = newTaskId, title = title.trim(), category = Category.valueOf(categoryName), scheduledAt = LocalDate.parse(dayText).atTime(LocalTime.parse(timeText)).toString()))
                            .copy(title = title.trim(), category = Category.valueOf(categoryName), scheduledAt = LocalDate.parse(dayText).atTime(LocalTime.parse(timeText)).toString(), durationMinutes = minutes, fixed = fixed, repeatRule = repeat, steps = steps, resourceUrl = url, criteria = criteria, note = note)
                        savePrepared()
                    }
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存到本机") } }
        }
    }
    if(showOverlap && pending != null) AlertDialog(onDismissRequest = { showOverlap = false }, title = { Text("安排时间有重叠") }, text = { Text("本次保存的${overlapCount}项安排存在重叠，以下至多显示5项：\n$overlapDetails\n\n你可以调整时间，也可以明确保留重叠；日历会保留冲突标识。") },
        confirmButton = { TextButton(onClick = { showOverlap = false; onSave(pending!!,saveFollowing,draftAlarm) }) { Text("保留重叠并保存") } }, dismissButton = { TextButton(onClick = { showOverlap = false }) { Text("返回调整") } })
    if(showScope && pending != null) AlertDialog(onDismissRequest = { showScope = false }, title = { Text("修改哪些重复安排？") }, text = { Column { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!scopeFollowing, { scopeFollowing = false },enabled=!ruleChanged); Text("仅本次") }; Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(scopeFollowing, { scopeFollowing = true }); Text("本次及以后，保留执行历史") }; if(ruleChanged) Text("重复规则变化将重建后续未执行安排；已开始和已有结果的事项保留。",style=MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { showScope = false; submit(scopeFollowing) }) { Text("确认保存") } }, dismissButton = { TextButton(onClick = { showScope = false }) { Text("取消") } })
    if(dirtyExit) AlertDialog(onDismissRequest = { dirtyExit = false }, title = { Text("还有未保存的修改") }, text = { Text("返回后，这次表单修改将被放弃。") }, confirmButton = { TextButton(onClick = onBack) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { dirtyExit = false }) { Text("继续编辑") } })
}

@Composable fun OutcomeSheet(task: TaskItem?, record: ExecutionRecord? = null, nowElapsed: Long, onDismiss: () -> Unit,
    onSave: (TaskStatus, String, Long) -> Unit) {
    var outcome by remember { mutableStateOf(record?.outcome ?: TaskStatus.COMPLETED) }
    var result by remember { mutableStateOf(record?.result ?: "") }
    var minutes by remember { mutableStateOf(record?.let { "%.1f".format(java.util.Locale.US, it.seconds / 60.0) } ?: "0") }
    var error by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(if(record == null) "记录这次执行" else "订正执行记录", style = MaterialTheme.typography.headlineMedium) }
            item { Text(record?.title ?: task?.title.orEmpty(), style = MaterialTheme.typography.titleSmall) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(TaskStatus.COMPLETED, TaskStatus.PARTIAL, TaskStatus.SKIPPED).forEach { choice -> FilterChip(outcome == choice, { outcome = choice }, { Text(choice.label) }) } } }
            if(record == null && task != null) item { Text("实际计时 ${formatDuration(TaskRules.elapsed(task, nowElapsed))}", color = BatterColors.Secondary) }
            if(record != null) item { OutlinedTextField(minutes, { minutes = it }, Modifier.fillMaxWidth(), label = { Text("实际时长（分钟，可小数）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
            item { OutlinedTextField(result, { result = it }, Modifier.fillMaxWidth(), label = { Text(when(outcome) { TaskStatus.PARTIAL -> "做了哪些，剩余什么？"; TaskStatus.SKIPPED -> "跳过原因（可选）"; else -> "完成成果与备注" }) }, minLines = 3) }
            if(record != null && record.correctedFrom.isNotBlank()) item { Text("此前订正\n${record.correctedFrom}", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) }
            item { Text(if(record == null) "未完成内容不会自动追加到明天。" else "原结果会保留在订正痕迹中。", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary) }
            if(error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            item { Button(onClick = { val secs = if(record == null) 0L else minutes.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 1440 }?.times(60)?.toLong(); if(secs == null) error = "请输入 0–1440 之间的有效分钟数" else onSave(outcome, result, secs) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("保存结果") } }
        }
    }
}
