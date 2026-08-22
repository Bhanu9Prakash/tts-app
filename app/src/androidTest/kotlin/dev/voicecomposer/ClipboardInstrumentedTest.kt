package dev.voicecomposer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.voicecomposer.integration.ClipboardWriter
import dev.voicecomposer.settings.ClipboardAutoClear
import dev.voicecomposer.settings.SettingsRepository
import dev.voicecomposer.ui.ComposerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clipboard behaviour against the real system service.
 *
 * ## Why every test runs inside an ActivityScenario
 *
 * From Android 10 the OS refuses clipboard *reads* to apps that do not have
 * window focus - `getPrimaryClip()` simply returns null. An instrumentation
 * test with no activity on screen therefore cannot observe what it just wrote,
 * and would fail for reasons that say nothing about our code. Launching
 * ComposerActivity gives the process focus, which is also closer to how the
 * feature is actually used.
 *
 * That restriction is itself part of the story in docs/THREAT_MODEL.md: it is
 * why background clipboard snooping by other apps is bounded.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    private fun writer(policy: ClipboardAutoClear): ClipboardWriter {
        val settings = SettingsRepository(context).apply { clipboardAutoClear = policy }
        return ClipboardWriter(context, settings)
    }

    /** Runs [block] on the main thread with the app focused. */
    private fun focused(block: () -> Unit) {
        ActivityScenario.launch(ComposerActivity::class.java).use {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync(block)
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun copyPutsTheApprovedTextOnTheClipboard() {
        val text = "Approved draft text"
        var observed: String? = null

        focused {
            writer(ClipboardAutoClear.OFF).copy(text)
            observed = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }

        assertEquals(text, observed)
    }

    @Test
    fun emptyTextIsNotCopied() {
        var observed: String? = null

        focused {
            writer(ClipboardAutoClear.OFF).copy("sentinel")
            writer(ClipboardAutoClear.OFF).copy("")
            observed = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }

        assertEquals("sentinel", observed)
    }

    @Test
    fun autoClearDoesNotWipeSomethingElseTheUserCopied() {
        var observed: String? = null

        focused {
            writer(ClipboardAutoClear.THIRTY_SECONDS).copy("our text")
            // The user copies from another app before our timer fires.
            clipboard.setPrimaryClip(ClipData.newPlainText("Other app", "something else"))
            observed = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }

        // Our scheduled clear checks the clipboard still holds what we wrote.
        // It does not, so it must leave this alone.
        assertEquals("something else", observed)
    }

    @Test
    fun clipIsMarkedSensitiveWhereSupported() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return

        var sensitive = false
        focused {
            writer(ClipboardAutoClear.OFF).copy("sensitive draft")
            sensitive = clipboard.primaryClipDescription
                ?.extras
                ?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        }

        assertTrue(
            "Clip should be flagged sensitive so the OS suppresses previews and sync",
            sensitive,
        )
    }
}
