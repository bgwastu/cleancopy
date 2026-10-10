package net.wastu.cleancopy

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

class CleanUiTest {
    @get:Rule(order = 0) val foreground = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                if (Build.VERSION.SDK_INT >= 29) automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
                try { base.evaluate() } finally { if (Build.VERSION.SDK_INT >= 29) automation.dropShellPermissionIdentity() }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsRowIsOneAccessibleSwitchAndTogglesFromItsLabel() {
        compose.onNodeWithContentDescription("Settings").performClick()
        val row = compose.onNode(isToggleable() and hasText("Sanitize filename"))
        val wasChecked = FilenameRewriteStore.isEnabled(compose.activity)
        row.performClick()
        compose.runOnIdle { assertEquals(!wasChecked, FilenameRewriteStore.isEnabled(compose.activity)) }
        row.performClick()
        compose.runOnIdle { assertEquals(wasChecked, FilenameRewriteStore.isEnabled(compose.activity)) }
    }

    @Test fun clipboardButtonShowsTheCleanedLinkWithoutRecreation() {
        withClipboard(ClipData.newPlainText("test link", "https://example.com/article?utm_source=mail")) {
            compose.onNodeWithText("Clean current clipboard").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Links cleaned").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("https://example.com/article").assertIsDisplayed()
        }
    }

    @Test fun clipboardButtonShowsCleanedVideoAndItsActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "fixtures/clipboard-video.mp4").apply { parentFile!!.mkdirs() }
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("rotated-with-audio.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            withClipboard(ClipData.newUri(context.contentResolver, "test video", uri)) {
                compose.onNodeWithText("Clean current clipboard").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Clean copies ready").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Copy to clipboard").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Share clean media").performScrollTo().assertIsDisplayed()
            }
        } finally { file.delete() }
    }

    @Test fun emptyClipboardShowsAnErrorAndRetryReadsNewContent() {
        withClipboard(ClipData.newPlainText("empty clipboard", "")) {
            compose.onNodeWithText("Clean current clipboard").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("No readable image, video, or link found in the clipboard").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnUiThread {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("new link", "https://example.com/retry?utm_source=mail"))
            }
            compose.onNodeWithText("Try again").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Links cleaned").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("https://example.com/retry").assertIsDisplayed()
        }
    }

    private fun withClipboard(clip: ClipData, block: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        val previous = compose.runOnIdle { clipboard.primaryClip }
        compose.runOnIdle { clipboard.setPrimaryClip(clip) }
        try { block() } finally {
            compose.runOnUiThread {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<CleanClipboardActivity>().forEach { it.finish() }
            }
            compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
            compose.runOnIdle {
                runCatching {
                    if (previous != null) clipboard.setPrimaryClip(previous)
                    else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    @Test fun resultActionsRemainReachableWithLargeFontInLandscape() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    CleanCopyTheme {
                        CleanResultModal(
                            result = CleanResultData(title = "Clean copies ready", kind = MediaKind.IMAGE, sourceName = "photo.jpg"),
                            onDismiss = {}, onCopyToClipboard = {},
                            mediaItems = listOf(CleanResultMediaItem(Uri.parse("content://unavailable/photo.jpg"), "photo.jpg", MediaKind.IMAGE,
                                listOf("Location metadata removed", "Camera details removed", "Original filename replaced"))),
                            onCopyMedia = { _, _ -> }, onShareMedia = { _, _ -> }, onSaveMedia = { _, _ -> }
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Share clean media").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Share clean media").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save to Downloads").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy to clipboard").performScrollTo().assertIsDisplayed()
    }

    @Test fun recreationKeepsTheSameProcessingResultAndViewModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, CleanClipboardActivity::class.java)
            .putExtra(CleanClipboardActivity.EXTRA_INPUT_TEXT, "https://example.com/?utm_source=mail")
        ActivityScenario.launch<CleanClipboardActivity>(intent).use { scenario ->
            var model: CleaningViewModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[CleaningViewModel::class.java] }
            compose.waitUntil(15_000) { model?.result != null }
            assertEquals("https://example.com/", model!!.result!!.cleanedText)
            scenario.recreate()
            scenario.onActivity {
                assertSame(model, ViewModelProvider(it)[CleaningViewModel::class.java])
                assertEquals("https://example.com/", model!!.result!!.cleanedText)
            }
        }
    }
}
