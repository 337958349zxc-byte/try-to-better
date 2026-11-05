package com.trytobatter.app

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.trytobatter.app.core.data.BackgroundSyncWorker

data class AlarmTaskRequest(val taskId: String, val start: Boolean, val dismissAlarm: Boolean, val generation: String?)

class MainActivity : ComponentActivity() {
    val alarmRequest = kotlinx.coroutines.flow.MutableStateFlow<AlarmTaskRequest?>(null)
    private fun readAlarmIntent(intent: android.content.Intent?) { intent?.getStringExtra("alarmTaskId")?.let {
        alarmRequest.value = AlarmTaskRequest(it,intent.getBooleanExtra("alarmStartTask",false),intent.getBooleanExtra("alarmDismiss",false),intent.getStringExtra("alarmGeneration"))
    } }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); readAlarmIntent(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readAlarmIntent(intent)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent { BatterApp() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            val repository = (application as BatterApplication).repository
            if(repository.state.value.loaded) runCatching { repository.refreshAlarms() }
            while(true) {
                if(repository.state.value.loaded) {
                    runCatching { repository.autoConnect() }
                    if(repository.state.value.connection.paired) runCatching { repository.syncNow() }
                    if(repository.state.value.connection.phase in setOf(com.trytobatter.app.core.model.ConnectionPhase.ONLINE, com.trytobatter.app.core.model.ConnectionPhase.WAITING_APPROVAL) && repository.updates.value.checkedAt + 6 * 60 * 60 * 1000L < System.currentTimeMillis()) runCatching { repository.checkUpdate() }
                }
                delay(2500)
            }
        } }
    }
    override fun onStop() { super.onStop(); if((application as BatterApplication).repository.state.value.connection.paired) BackgroundSyncWorker.once(this) }
}
