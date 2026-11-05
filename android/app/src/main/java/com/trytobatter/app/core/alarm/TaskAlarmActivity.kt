package com.trytobatter.app.core.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trytobatter.app.BatterApplication
import com.trytobatter.app.core.designsystem.BatterTheme
import kotlinx.coroutines.launch

class TaskAlarmActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(android.os.Build.VERSION.SDK_INT>=27) {setShowWhenLocked(true);setTurnScreenOn(true)}
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val app=application as BatterApplication
        setContent { BatterTheme {
            val state by app.alarms.state.collectAsState()
            val tasks by app.repository.state.collectAsState()
            val scope=rememberCoroutineScope()
            val records=state.records.values.filter { it.state==AlarmState.RINGING }
            LaunchedEffect(records.size) { if(records.isEmpty()) finish() }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("事项闹钟",style=MaterialTheme.typography.headlineLarge)
                    records.forEach { r -> tasks.tasks.find { it.id==r.taskId }?.let { task ->
                        Text(task.title,style=MaterialTheme.typography.headlineMedium)
                        Text("原定开始时间 ${task.timeLabel}")
                        Button(onClick={scope.launch { app.repository.dismissTaskAlarm(task.id) }},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("关闭闹钟")}
                        if(AlarmRules.canSnooze(task,r,System.currentTimeMillis())) OutlinedButton(onClick={scope.launch {app.repository.snoozeTaskAlarm(task.id,r.generation)}},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("稍后5分钟（${r.snoozeCount}/2）")}
                        OutlinedButton(onClick={app.alarms.openTask(task.id,true,true,r.generation).send();finish()},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("开始事项")}
                    } }
                }
            }
        } }
    }
}
