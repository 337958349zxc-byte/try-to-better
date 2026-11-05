package com.trytobatter.app

import androidx.test.platform.app.InstrumentationRegistry
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/** Run prepare on the retained 0.2.1 APK, install 0.2.2 with -r, then run verify. Emulator only. */
class LegacyUpgradeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get()=(context.applicationContext as BatterApplication).repository
    @Test fun prepareOldVersionData()=runBlocking {
        assertEquals("0.2.1",context.packageManager.getPackageInfo(context.packageName,0).versionName)
        repo.load();repo.disconnect();repo.resetDemo(true)
        repo.saveTask(TaskItem(id="upgrade-future",title="升级前未来事项",category=Category.LIFE,scheduledAt=LocalDateTime.now().plusDays(1).toString(),note="保留原事项ID"))
        val history=TaskItem(id="upgrade-history",title="升级前执行事项",category=Category.AI,scheduledAt=LocalDateTime.now().minusDays(1).toString())
        repo.saveTask(history);repo.startTask(history.id);repo.finishTask(history.id,TaskStatus.PARTIAL,"升级前已确认成果")
        assertEquals(2,repo.state.value.tasks.size)
    }
    @Test fun verifyNewVersionKeepsTasksHistoryAndDefaultsAlarmsOff()=runBlocking {
        assertEquals("0.2.2",context.packageManager.getPackageInfo(context.packageName,0).versionName)
        repo.load()
        assertEquals(2,repo.state.value.tasks.size)
        assertEquals("保留原事项ID",repo.state.value.tasks.first {it.id=="upgrade-future"}.note)
        val history=repo.state.value.tasks.first {it.id=="upgrade-history"}
        assertEquals(TaskStatus.PARTIAL,history.status);assertTrue(history.executionSnapshot.isNotBlank())
        assertEquals("升级前已确认成果",repo.state.value.records.single().result)
        for(id in listOf("upgrade-future","upgrade-history")) assertFalse(repo.alarms.value.settings[id]?.enabled==true)
        repo.resetDemo(true)
    }
}
