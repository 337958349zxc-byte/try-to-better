package com.trytobatter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import com.trytobatter.app.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Dedicated emulator fixtures; verifies a real ACTION_VIEW handoff to the LAN kitchen. */
class RecipeUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun mealEntrancesOpenCurrentKitchenWithoutChangingTask() {
        ui.runOnUiThread {ui.activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        val repo=(ui.activity.application as BatterApplication).repository
        val task=TaskItem(id="recipe-ui",title="午餐备餐入口验收",category=Category.MEAL,scheduledAt=LocalDate.now().atTime(11,0).toString(),resourceUrl="http://192.168.0.88:8081/recipes/?meal=lunch#recipe")
        runBlocking {repo.load();repo.disconnect();repo.resetDemo(true);repo.saveTask(task)}
        assertEquals("http://192.168.0.23:8081/recipes/?meal=lunch#recipe",repo.recipeUrl(task.resourceUrl))
        ui.onNodeWithText("计划",useUnmergedTree=true).performClick()
        ui.onNodeWithText("打开双人菜谱").assertExists()
        ui.onNodeWithText("饮食备餐").performClick()
        ui.onNodeWithText("打开双人菜谱").assertIsDisplayed()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.takeScreenshot(File(ui.activity.getExternalFilesDir(null),"recipe-category.png"))
        ui.onNodeWithText(task.title).performClick()
        ui.onNodeWithText("打开双人菜谱").assertIsDisplayed()
        device.takeScreenshot(File(ui.activity.getExternalFilesDir(null),"recipe-task.png"))
        val before=repo.state.value.tasks.single()
        ui.onNodeWithText("打开双人菜谱").performClick()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.chromium")),8000))
        // Chromium may ask for confirmation for the user's existing HTTP LAN server.
        device.wait(Until.findObject(By.text("继续访问网站")),1500)?.click()
        assertTrue(device.wait(Until.hasObject(By.clazz("android.webkit.WebView")),10000))
        assertTrue(UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("姜葱土豆焖鸡丁＋蒜蓉包菜"))
        device.waitForIdle()
        device.takeScreenshot(File(ui.activity.getExternalFilesDir(null),"recipe-browser.png"))
        assertEquals(before,repo.state.value.tasks.single())
    }
}
