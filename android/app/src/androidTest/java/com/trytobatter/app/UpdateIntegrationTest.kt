package com.trytobatter.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.trytobatter.app.core.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateIntegrationTest {
    @Test fun downloadSignedUpdateVerifyProviderAndReachSystemConfirmation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = LocalDemoRepository(context)
        val client = LanClient(context)
        repo.load(); if(repo.state.value.connection.paired) repo.disconnect(); repo.resetDemo(true)
        repo.pair("http://192.168.0.23:18081", client.request("http://127.0.0.1:18081", "/api/desktop").getString("pairCode"))
        val device = UiDevice.getInstance(instrumentation)
        try {
            repo.checkUpdate(); assertEquals(UpdatePhase.AVAILABLE, repo.updates.value.phase)
            repo.downloadUpdate(); assertEquals(UpdatePhase.READY, repo.updates.value.phase)
            val intent = repo.updateInstallIntent()
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val size = context.contentResolver.openAssetFileDescriptor(intent.data!!, "r")!!.use { it.length }
            assertEquals(repo.updates.value.release!!.size, size)
            val archive = File(context.cacheDir, "updates/verified-update.apk")
            assertTrue(runCatching { AppUpdateManager.verifyArchive(context, archive, repo.updates.value.release!!.copy(versionCode = 999)) }.isFailure)
            if(!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
                val switches = device.findObjects(By.clazz("android.widget.Switch"))
                assertEquals("Expected this app's single install-source permission switch", 1, switches.size)
                if(!switches.single().isChecked) switches.single().click()
                device.pressBack()
                assertTrue(context.packageManager.canRequestPackageInstalls())
            }
            context.startActivity(intent)
            assertTrue(device.wait(Until.hasObject(By.textContains("try to batter")), 5000))
            assertTrue("System installer did not open: ${device.currentPackageName}", device.currentPackageName.contains("packageinstaller"))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "18-system-update-confirmation.png"))
            device.pressBack() // Verification stops at Android's confirmation; installation is not claimed.
        } finally { repo.disconnect(); repo.resetDemo() }
        Unit
    }
}
