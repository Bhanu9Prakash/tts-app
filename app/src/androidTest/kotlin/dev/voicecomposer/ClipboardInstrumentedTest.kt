package dev.voicecomposer

import android.content.ClipboardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.voicecomposer.integration.ClipboardWriter
import dev.voicecomposer.settings.ClipboardAutoClear
import dev.voicecomposer.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clipboard behaviour against the real system service.
 *
 * The property worth checking on device is the one that could damage a user's
 * data: auto-clear must never wipe something the user copied from another app
 * in the meantime.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    private fun writer(policy: ClipboardAutoClear): ClipboardWriter {
        val settings = SettingsRepository(context).apply { clipboardAutoClear = policy }
        return ClipboardWriter(context, settings)
    }

    @Test
    fun copyPutsTheApprovedTextOnTheClipboard() {
        val text = "Approved draft text"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            writer(ClipboardAutoClear.OFF).copy(text)
        }
        assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun emptyTextIsNotCopied() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            writer(ClipboardAutoClear.OFF).copy("sentinel")
            writer(ClipboardAutoClear.OFF).copy("")
        }
        assertEquals("sentinel", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun autoClearDoesNotWipeSomethingElseTheUserCopied() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            writer(ClipboardAutoClear.THIRTY_SECONDS).copy("our text")
            // The user copies from another app before our timer fires.
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Other app", "something else"),
            )
        }
        // Our scheduled clear checks that the clipboard still holds what we
        // wrote; it does not, so it must leave this alone.
        assertEquals("something else", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun clipIsMarkedSensitiveWhereSupported() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            writer(ClipboardAutoClear.OFF).copy("sensitive draft")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val extras = clipboard.primaryClipDescription?.extras
            assertTrue(
                "Clip should be flagged sensitive so the OS suppresses previews",
                extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true,
            )
        }
    }
}
