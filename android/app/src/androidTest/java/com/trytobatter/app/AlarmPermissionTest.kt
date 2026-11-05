package com.trytobatter.app

import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.core.alarm.*
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/** Runner sets SCHEDULE_EXACT_ALARM deny/allow between these two explicit test invocations. */
class AlarmPermissionTest {
    private val repo get()=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BatterApplication).repository
    @Test fun deniedPermissionPreservesPreferenceWithoutScheduledClaim()=runBlocking {
        repo.load();repo.disconnect();repo.resetDemo(true)
        val t=TaskItem(id="permission-recovery",title="权限恢复验收",category=Category.LIFE,scheduledAt=LocalDateTime.now().plusHours(2).toString())
        repo.saveTask(t);repo.setTaskAlarm(t.id,TaskAlarmSettings(true))
        assertTrue(repo.alarms.value.settings.getValue(t.id).enabled)
        assertEquals(AlarmState.NEEDS_PERMISSION,repo.alarms.value.records[t.id]?.state)
    }
    @Test fun grantedPermissionRestoresPreviouslySavedPreference()=runBlocking {
        repo.load();repo.refreshAlarms(true)
        assertTrue(repo.alarms.value.settings.getValue("permission-recovery").enabled)
        assertEquals(AlarmState.SCHEDULED,repo.alarms.value.records["permission-recovery"]?.state)
        repo.resetDemo(true)
    }
}
