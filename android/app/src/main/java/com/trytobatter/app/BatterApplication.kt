package com.trytobatter.app

import android.app.Application
import com.trytobatter.app.core.data.LocalDemoRepository

class BatterApplication : Application() {
    val alarmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val alarms by lazy { com.trytobatter.app.core.alarm.TaskAlarmCoordinator(this) }
    val systemClock by lazy { com.trytobatter.app.core.alarm.SystemClockBridge(this) }
    val repository by lazy { LocalDemoRepository(this) }
}
