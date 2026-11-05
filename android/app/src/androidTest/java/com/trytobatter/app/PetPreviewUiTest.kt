package com.trytobatter.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.trytobatter.app.core.designsystem.BatterTheme
import com.trytobatter.app.feature.petpreview.PetPreviewScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Dedicated isolated host: does NOT start MainActivity, initialize a repository or reset any data. */
class PetPreviewUiTest {
    @get:Rule val ui = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun start(animations: Boolean = false) {
        ui.runOnUiThread { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        ui.waitUntil(5000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        ui.setContent { BatterTheme { PetPreviewScreen(onBack = {}, initialAnimations = animations) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("pet-character").fetchSemanticsNodes().isNotEmpty() }
        // IO atlas decode happens off the main thread. Allow it to finish before captures.
        Thread.sleep(1200)
    }
    private fun shot(name: String) {
        ui.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "pet-v1").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(dir, "$name.png")))
    }
    private fun closeSettings() = ui.onNodeWithTag("pet-settings-close").performClick()
    private fun settings() = ui.onNodeWithTag("pet-settings").performClick()

    @Test fun fullInteractionAndScreenshots() {
        start()
        shot("01-desktop")
        val pet = ui.onNodeWithTag("pet-character")
        pet.performClick(); ui.onNodeWithText("打开聊天").assertIsDisplayed(); shot("02-bubble")
        ui.onNodeWithText("今日计划").performClick()
        ui.onNodeWithText("示例数据 · 不读取或写入现有日程").assertIsDisplayed()
        ui.onNodeWithContentDescription("关闭示例").performClick()
        ui.onNodeWithTag("pet-progress").performClick()
        ui.onNodeWithText("示例：3件计划，1件已完成").assertIsDisplayed()
        ui.onNodeWithContentDescription("关闭示例").performClick()
        pet.performClick(); ui.onNodeWithText("暂时隐藏").performClick()
        ui.onNodeWithTag("pet-character").assertDoesNotExist()
        ui.onNodeWithTag("pet-restore").performClick(); pet.assertIsDisplayed()
        val before = pet.getUnclippedBoundsInRoot()
        pet.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(-650f, -280f), 600) }
        val after = pet.getUnclippedBoundsInRoot()
        assertTrue("Drag must dock to opposite edge", after.left < before.left)
        assertTrue("Drag preserves vertical movement", after.top < before.top)
        shot("03-docked-left")
        ui.onNodeWithTag("pet-chat").performClick()
        ui.onNodeWithTag("pet-chat-send").assertIsNotEnabled()
        ui.onNodeWithTag("pet-chat-input").performTextInput("今天的计划")
        ui.onNodeWithTag("pet-chat-send").performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithTag("pet-thinking").fetchSemanticsNodes().isNotEmpty() }
        shot("04-thinking")
        ui.waitUntil(10000) { ui.onAllNodesWithTag("pet-thinking").fetchSemanticsNodes().isEmpty() }
        shot("05-chat")
        ui.onNodeWithTag("pet-chat-close").performClick()
        settings(); shot("06-settings")
        ui.onNodeWithTag("pet-dock-right").performClick()
        ui.onNodeWithTag("pet-size").performSemanticsAction(SemanticsActions.SetProgress) { it(112f) }
        closeSettings(); shot("07-small")
        settings(); ui.onNodeWithTag("pet-display").performClick(); closeSettings()
        pet.assertDoesNotExist(); ui.onNodeWithTag("pet-restore").performClick()
        settings()
        ui.onNodeWithTag("pet-quiet").performScrollTo().performClick()
        ui.onNodeWithTag("pet-test-remind").assertIsNotEnabled()
        closeSettings(); pet.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "SLEEP")); shot("08-quiet")
        settings(); ui.onNodeWithTag("pet-license").performScrollTo().performClick()
        ui.onNodeWithText("来源与许可").assertIsDisplayed()
        ui.onNodeWithContentDescription("关闭许可").performClick(); closeSettings()
        for (name in listOf("IDLE", "WALK", "THINK", "TALK", "HAPPY", "REMIND", "SLEEP", "DRAG")) {
            ui.onNodeWithTag("pet-actions").performClick()
            ui.onNodeWithTag("pet-motion-$name").performClick()
            pet.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name))
        }
        settings()
        ui.onNodeWithTag("pet-sound").performScrollTo().performClick().assertIsOn()
        ui.onNodeWithTag("pet-proactive").performScrollTo().performClick().assertIsOn()
        closeSettings()
        ui.mainClock.advanceTimeBy(31_000)
        ui.waitForIdle()
        ui.onNodeWithTag("pet-bubble").assertIsDisplayed()
        pet.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "REMIND"))
        settings(); ui.onNodeWithTag("pet-quiet").performScrollTo().performClick(); closeSettings()
        ui.mainClock.advanceTimeBy(31_000)
        ui.onNodeWithTag("pet-bubble").assertDoesNotExist()
    }

    @Test fun actualAnimationMovesAndStopsWhenDisabled() {
        start(animations = true)
        ui.onNodeWithTag("pet-actions").performClick()
        ui.onNodeWithTag("pet-motion-WALK").performClick()
        val pet = ui.onNodeWithTag("pet-character")
        val before = pet.getUnclippedBoundsInRoot()
        ui.mainClock.advanceTimeBy(1000)
        val after = pet.getUnclippedBoundsInRoot()
        assertTrue("Real walk moves the pet: $before -> $after", after.left < before.left)
        settings()
        ui.onNodeWithTag("pet-animations").performScrollTo().performClick()
        closeSettings()
        val stopped = pet.getUnclippedBoundsInRoot()
        ui.mainClock.advanceTimeBy(400)
        assertEquals(stopped, pet.getUnclippedBoundsInRoot())
        val root = ui.onNodeWithTag("pet-stage").getUnclippedBoundsInRoot()
        pet.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(1800f, 4000f), 500) }
        val bottom = pet.getUnclippedBoundsInRoot()
        assertTrue(bottom.right <= root.right && bottom.bottom <= root.bottom)
        assertTrue(bottom.left >= root.left && bottom.top >= root.top)
        pet.performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(-1800f, -4000f), 500) }
        val top = pet.getUnclippedBoundsInRoot()
        pet.performClick()
        val speech = ui.onNodeWithTag("pet-bubble").getUnclippedBoundsInRoot()
        assertTrue("Top-docked speech appears below the full pet", speech.top >= top.bottom)
        shot("09-top-dock")
    }
}
