package com.trytobatter.app

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import com.trytobatter.app.core.alarm.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*
import com.trytobatter.app.feature.today.*
import com.trytobatter.app.feature.calendar.CalendarScreen
import com.trytobatter.app.feature.plans.*
import com.trytobatter.app.feature.records.RecordsScreen
import com.trytobatter.app.feature.records.PeriodReviewScreen
import com.trytobatter.app.feature.profile.*
import com.trytobatter.app.feature.petpreview.PetPreviewScreen
import com.trytobatter.app.feature.sync.ConnectionScreen
import com.trytobatter.app.feature.workout.WorkoutScreen
import com.trytobatter.app.feature.workout.WorkoutDraft
import com.trytobatter.app.feature.workout.UnifiedWorkoutHub
import kotlinx.coroutines.delay
import java.time.LocalDate

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(Tab("today", "今天", Icons.Outlined.Today), Tab("calendar", "日历", Icons.Outlined.CalendarMonth),
    Tab("plans", "计划", Icons.Outlined.Assignment), Tab("records", "记录", Icons.Outlined.BarChart), Tab("profile", "我的", Icons.Outlined.PersonOutline))

@Composable fun BatterApp(vm: BatterViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    val alarms by vm.repository.alarms.collectAsStateWithLifecycle()
    var alarmTaskId by remember { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current as? MainActivity
    val context = LocalContext.current
    val alarmRequest = activity?.alarmRequest?.collectAsStateWithLifecycle()?.value
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "today"
    val isTraining = route.startsWith("workout")
    var elapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var pendingStart by remember { mutableStateOf<TaskItem?>(null) }
    var outcomeTaskId by remember { mutableStateOf<String?>(null) }
    var correctionId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val snackbarSpace = with(LocalDensity.current) { snackbarHeight.toDp() }
    val view = LocalView.current
    DisposableEffect(isTraining, view) {
        val window = (view.context as? Activity)?.window
        window?.let { WindowCompat.getInsetsController(it, view).apply { isAppearanceLightStatusBars = !isTraining; isAppearanceLightNavigationBars = !isTraining } }
        onDispose { }
    }
    LaunchedEffect(Unit) { while(true) { elapsed = SystemClock.elapsedRealtime(); delay(1000) } }
    LaunchedEffect(vm) { vm.messages.collect { msg ->
        val result = snackbar.showSnackbar(msg.text, actionLabel = if(msg.undoable) "撤销" else null, duration = SnackbarDuration.Short)
        if(result == SnackbarResult.ActionPerformed) vm.perform("已撤销上次事项修改") { undo() }
    } }
    fun back() { nav.popBackStack() }
    fun start(task: TaskItem) {
        if(state.tasks.any { it.status == TaskStatus.RUNNING && it.id != task.id }) pendingStart = task
        else vm.perform("计时已开始，进度保存在本机") { startTask(task.id) }
    }
    fun task(id: String) { nav.navigate("task/$id") }
    fun toggleAlarm(id: String) { vm.perform {
        toggleTaskAlarm(id)
        val record = this.alarms.value.records[id]
        vm.notify(when(record?.state) {
            AlarmState.DISABLED -> "关联闹钟已关闭"
            AlarmState.SCHEDULED -> "已安排提前10分钟响铃"
            AlarmState.MISSED -> "本次提醒时间已过，请调整事项时间或闹钟设置"
            AlarmState.NEEDS_PERMISSION -> "闹钟设置已保存，需要系统授权"
            else -> record?.error?.ifBlank { record.state.label } ?: "闹钟设置已保存"
        })
        if(record?.state in setOf(AlarmState.MISSED,AlarmState.NEEDS_PERMISSION,AlarmState.SCHEDULE_ERROR)) alarmTaskId = id
    } }
    fun edit(id: String = "new", day: String = LocalDate.now().toString()) { nav.navigate("edit/$id?day=$day") }
    fun recipes(resourceUrl: String = "") {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(vm.repository.recipeUrl(resourceUrl)))) }
            .onFailure { vm.notify("无法打开菜谱，请检查电脑地址和浏览器") }
    }
    LaunchedEffect(state.loaded,alarmRequest) {
        if(state.loaded && alarmRequest!=null) {
            val (id,begin,dismiss,generation)=alarmRequest
            if(dismiss || begin) vm.repository.dismissTaskAlarm(id,generation)
            state.tasks.find {it.id==id}?.let { t -> task(id); if(begin && !t.isFinished && t.status!=TaskStatus.RUNNING) start(t) }
                ?: vm.notify("事项已不在当前安排中")
            // Clearing this LaunchedEffect key earlier would cancel its own suspend work.
            if(activity?.alarmRequest?.value==alarmRequest) activity.alarmRequest.value=null
        }
    }
    BatterTheme(training = isTraining) {
        Scaffold(contentWindowInsets = WindowInsets.systemBars,
            snackbarHost = { SnackbarHost(snackbar, Modifier.onSizeChanged { snackbarHeight = it.height }) },
            bottomBar = { if(route in tabs.map { it.route }) NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { tab -> NavigationBarItem(selected = route == tab.route, onClick = { nav.navigate(tab.route) {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true
                } }, icon = { Icon(tab.icon, null,Modifier.size(23.dp)) }, label = { Text(tab.label) },
                    colors=NavigationBarItemDefaults.colors(selectedIconColor=BatterColors.Primary,selectedTextColor=BatterColors.Primary,
                        indicatorColor=BatterColors.LightBlue,unselectedIconColor=BatterColors.Secondary,unselectedTextColor=BatterColors.Secondary)) }
            } }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(bottom = snackbarSpace)) {
                if(!state.loaded) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(loadError == null) { CircularProgressIndicator(); Text("正在读取本机安排") }
                    else { Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error); Button(onClick = vm::load) { Text("重试") } }
                } else NavHost(navController = nav, startDestination = "today") {
                    composable("today") { TodayScreen(state, elapsed, ::task, ::start,
                        { vm.perform("计时已暂停") { pauseTask(it) } }, { outcomeTaskId = it.id }, { edit(it) }, { edit() },
                        { nav.navigate("connection") }, { nav.navigate("updates") },
                        if(updates.phase in setOf(com.trytobatter.app.core.data.UpdatePhase.AVAILABLE, com.trytobatter.app.core.data.UpdatePhase.READY)) updates.release?.versionName else null,
                        { nav.navigate("update") },alarms,::toggleAlarm,timelineControl={TimelineClockControl(vm.repository,vm::notify)},onCalendar={nav.navigate("calendar")}) }
                    composable("calendar") { CalendarScreen(state, ::task, { edit(day = it) },alarms,::toggleAlarm) }
                    composable("plans") { PlansScreen(state, { nav.navigate("category/${it.name}") }, { nav.navigate("updates") }, { nav.navigate("connection") }, { nav.navigate("workout") }, { recipes() },::task) }
                    composable("category/{name}") { e -> CategoryPlanScreen(Category.valueOf(e.arguments?.getString("name") ?: "LIFE"), state, ::back, ::task, { edit() }, { recipes() }) }
                    composable("records") { RecordsScreen(state, { correctionId = it }, { nav.navigate("data") }, { nav.navigate("review") }) }
                    composable("review") { PeriodReviewScreen(state, ::back, { correctionId = it }, vm::notify) }
                    composable("profile") { ProfileScreen(state, { nav.navigate("connection") }, { nav.navigate("data") }, { nav.navigate("update") },alarms,{vm.perform {refreshAlarms()}}, onPetPreview = { nav.navigate("petpreview") }, onDesktopPet = { context.startActivity(android.content.Intent(context, com.trytobatter.app.feature.petoverlay.PetOverlayActivity::class.java)) }) }
                    composable("petpreview") { PetPreviewScreen(::back) }
                    composable("task/{id}") { e -> val id = e.arguments?.getString("id").orEmpty(); val t = state.tasks.find { it.id == id }
                        TaskDetailScreen(t, elapsed, ::back, { edit(id) }, { t?.let(::start) }, { vm.perform("计时已暂停") { pauseTask(id) } },
                            { outcomeTaskId = id }, { nav.navigate("workout?taskId=$id") }, { following -> vm.perform("事项已删除，变更保存在本机", true, ::back) { deleteTask(id, following) } }, vm::notify, { recipes(t?.resourceUrl.orEmpty()) },
                            onOpenComputer = { url -> try { vm.notify(vm.repository.openOnComputer(id, url)) } catch(e: kotlinx.coroutines.CancellationException) { throw e } catch(e: Exception) { vm.notify(e.message ?: "电脑打开请求未完成") } })
                    }
                    composable("edit/{id}?day={day}", arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("day") { defaultValue = LocalDate.now().toString() })) { e ->
                        val id = e.arguments?.getString("id"); val t = state.tasks.find { it.id == id }
                        EditTaskScreen(t, e.arguments?.getString("day") ?: LocalDate.now().toString(), state.tasks, ::back,
                            alarms.settings[id] ?: TaskAlarmSettings(),alarms.records[id],{vm.perform {refreshAlarms()}}) { updated, following, setting ->
                            vm.perform("事项已保存到本机", true, ::back) { saveTask(updated, following); setTaskAlarm(updated.id,setting,following || t?.seriesId==null && updated.repeatRule.isNotBlank()) }
                        }
                    }
                    composable("connection") { ConnectionScreen(state, ::back,
                        { address, code -> vm.perform("配对信息已保存，将自动同步") { pair(address, code) } }, { vm.perform("手机已断开配对，记录仍保留") { disconnect() } },
                        { vm.perform("同步完成，已收到电脑回执") { syncNow() } }, { nav.navigate("updates") }, { nav.navigate("update") },
                        { vm.perform { autoConnect(true) } }) }
                    composable("updates") { ConflictScreen(state, ::back, { id, keep -> vm.perform("选择已保存到本机") { resolveConflict(id, keep) } }, { nav.navigate("connection") },
                        { key, keep -> vm.perform(if(keep) "手机内容已保留，等待电脑回执" else "已采用电脑内容") { resolveSyncConflict(key, keep) } }) }
                    composable("update") { UpdateScreen(updates, state.connection.address, ::back,
                        { vm.perform { checkUpdate() } }, { vm.perform { downloadUpdate() } }, { vm.repository.updateInstallIntent() }, vm::notify) }
                    composable("data") { DataScreen(state, ::back, { vm.perform("已恢复所选备份") { restore(it) } }, { vm.perform("本机演示数据已重置") { resetDemo(it) } }, vm::notify) }
                    composable("workout?taskId={taskId}",arguments=listOf(navArgument("taskId") {defaultValue=""})) { e ->
                        UnifiedWorkoutHub(state,e.arguments?.getString("taskId")?.takeIf {it.isNotBlank()},::back,
                            {vm.perform {saveWorkoutDraft(it)}},
                            {id,running ->vm.perform {if(running) startTask(id) else pauseTask(id)}},
                            {draft ->vm.perform("训练组次与事项结果已保存到本机",onDone=::back) {saveUnifiedWorkout(draft.encode())}})
                    }
                }
            }
        }
        pendingStart?.let { pending -> AlertDialog(onDismissRequest = { pendingStart = null }, title = { Text("切换当前计时事项？") },
            text = { Text("${state.tasks.find { it.status == TaskStatus.RUNNING }?.title.orEmpty()} 正在计时。切换后先暂停它，再开始 ${pending.title}。") },
            confirmButton = { TextButton(onClick = { pendingStart = null; vm.perform("已暂停上一项并开始新事项") { startTask(pending.id) } }) { Text("暂停并切换") } },
            dismissButton = { TextButton(onClick = { pendingStart = null }) { Text("继续当前事项") } }) }
        state.tasks.find {it.id==alarmTaskId}?.let { t -> TaskAlarmSheet(t,alarms,{alarmTaskId=null},
            {setting,following->vm.perform {setTaskAlarm(t.id,setting,following)}},{vm.perform {refreshAlarms(true)}}) }
        val record = state.records.find { it.id == correctionId }
        val target = state.tasks.find { it.id == outcomeTaskId }
        if(target != null || record != null) OutcomeSheet(target, record, elapsed, { outcomeTaskId = null; correctionId = null }) { outcome, result, seconds ->
            vm.perform("执行结果已保存在本机", onDone = { outcomeTaskId = null; correctionId = null }) {
                if(record != null) correctRecord(record.id, outcome, seconds, result) else if(target != null) finishTask(target.id, outcome, result)
            }
        }
    }
}
