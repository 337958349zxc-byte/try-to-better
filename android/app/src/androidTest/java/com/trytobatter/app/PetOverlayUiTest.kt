package com.trytobatter.app

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Point
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.trytobatter.app.feature.petoverlay.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Run only on the designated emulator. No MainActivity, task database or sync fixtures. */
class PetOverlayUiTest {
    @get:Rule val ui = AndroidComposeTestRule(
        ActivityScenarioRule<PetOverlayActivity>(Intent(ApplicationProvider.getApplicationContext(), PetOverlayActivity::class.java))
    ) { rule ->
        var current: PetOverlayActivity? = null
        rule.scenario.onActivity { current = it }
        requireNotNull(current)
    }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val dir get() = File(ui.activity.getExternalFilesDir(null),"pet-overlay-v1").apply {mkdirs()}
    private fun shot(name: String) { ui.waitForIdle(); device.waitForIdle(); assertTrue(device.takeScreenshot(File(dir,"$name.png"))) }
    private fun waitRunning() = ui.waitUntil(10000) { PetOverlayRuntime.status.value.running }
    private fun pet() = device.wait(Until.findObject(By.desc("大肥鱼桌宠")),5000) ?: error("Real overlay not found")
    private fun checkHome() {
        val dump=device.executeShellCommand("dumpsys window")
        val focus=dump.lineSequence().first {it.contains("mCurrentFocus=")}
        assertFalse("App must not be the focused desktop: $focus",focus.contains("com.trytobatter.app"))
        assertTrue(dump.contains("Try to Better 大肥鱼"))
        File(dir,"window-home.txt").writeText(dump)
    }

    @Test fun permissionHomeDragChatAndStop() {
        assertFalse("Prepare only this isolated device with overlay permission denied",Settings.canDrawOverlays(ui.activity))
        val config=PetOverlayConfig(ui.activity)
        ui.onNodeWithTag("pet-overlay-size").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(176f) }
        if(config.quiet) ui.onNodeWithTag("pet-overlay-quiet").performScrollTo().performClick()
        if(!config.animations) ui.onNodeWithTag("pet-overlay-animations").performScrollTo().performClick()
        ui.onNodeWithTag("pet-overlay-right").performScrollTo().performClick()
        ui.onNodeWithTag("pet-overlay-toggle").performScrollTo().performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)),5000))
        shot("00-permission")
        device.pressBack()
        ui.waitUntil(5000) { !PetOverlayRuntime.status.value.running }
        assertFalse(Settings.canDrawOverlays(ui.activity))
        ui.onNodeWithTag("pet-overlay-toggle").performClick()
        device.waitForIdle()
        if (!device.hasObject(By.checkable(true))) device.findObject(By.text("try to better"))?.click()
        val allow=device.wait(Until.findObject(By.checkable(true)),5000) ?: error("System overlay permission switch missing")
        allow.click()
        ui.waitUntil(5000) { Settings.canDrawOverlays(ui.activity) }
        repeat(3) { if(device.currentPackageName=="com.android.settings") device.pressBack() }
        waitRunning()
        ui.onNodeWithText("大肥鱼已开启").assertIsDisplayed()
        shot("01-settings")
        ui.onNodeWithTag("pet-overlay-home").performClick()
        Thread.sleep(1200); checkHome(); pet(); shot("02-desktop")
        pet().click(); assertNotNull(device.wait(Until.findObject(By.text("桌宠设置")),5000)); shot("03-bubble")
        device.findObject(By.text("打开聊天")).click()
        ui.onNodeWithTag("pet-chat-input").performTextInput("一起加油")
        ui.onNodeWithTag("pet-chat-send").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("听到啦。今天不用一下做很多，先完成一件小事，我陪着你。").fetchSemanticsNodes().isNotEmpty() }
        shot("04-chat")
        ui.onNodeWithTag("pet-chat-close").performClick()
        Thread.sleep(500); checkHome()
        val before=pet().visibleBounds
        pet().drag(Point(90,before.centerY()-150),600)
        Thread.sleep(300)
        assertTrue(pet().visibleBounds.centerX()<before.centerX()); shot("05-left")
        pet().click(); device.wait(Until.findObject(By.text("桌宠设置")),5000).click()
        ui.onNodeWithTag("pet-overlay-size").performSemanticsAction(SemanticsActions.SetProgress) { it(112f) }
        ui.onNodeWithTag("pet-overlay-quiet").performScrollTo().performClick()
        ui.onNodeWithTag("pet-overlay-home").performScrollTo().performClick()
        Thread.sleep(3000); checkHome(); shot("06-small-sleep")
        pet().click(); device.wait(Until.findObject(By.text("关闭桌宠")),5000).click()
        ui.waitUntil(5000) { !PetOverlayRuntime.status.value.running }
        assertTrue(device.wait(Until.gone(By.desc("大肥鱼桌宠")),5000))
        assertTrue(ui.activity.getSystemService(NotificationManager::class.java).activeNotifications.none {it.notification.channelId=="pet_overlay_v1"})
        shot("07-closed")
    }

    @Test fun availableAreaAndNotificationStop() {
        assertTrue(Settings.canDrawOverlays(ui.activity))
        ui.onNodeWithTag("pet-overlay-toggle").performClick(); waitRunning()
        ui.onNodeWithTag("pet-overlay-home").performClick(); Thread.sleep(500)
        device.executeShellCommand("wm size 1080x2160")
        ui.waitUntil(5000) { device.displayHeight!=2400 }; Thread.sleep(1200)
        val area=pet().visibleBounds
        assertTrue(area.left>=0 && area.top>=0 && area.right<=device.displayWidth && area.bottom<=device.displayHeight)
        shot("08-resized-area")
        device.executeShellCommand("wm size 1080x2400")
        ui.waitUntil(5000) { device.displayWidth<device.displayHeight }; Thread.sleep(600)
        device.openNotification(); Thread.sleep(400); shot("09-notification")
        val manager=ui.activity.getSystemService(NotificationManager::class.java)
        val notification=manager.activeNotifications.single {it.notification.channelId=="pet_overlay_v1"}.notification
        notification.actions.single {it.title.toString()=="关闭桌宠"}.actionIntent.send()
        ui.waitUntil(5000) { !PetOverlayRuntime.status.value.running }
        assertTrue(device.wait(Until.gone(By.desc("大肥鱼桌宠")),5000)); device.pressBack()
        assertTrue(manager.activeNotifications.none {it.notification.channelId=="pet_overlay_v1"})
    }
}
