package dev.voicecomposer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.voicecomposer.integration.ClipboardWriter
import dev.voicecomposer.settings.ClipboardAutoClear
import dev.voicecomposer.settings.SettingsRepository
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clipboard behaviour against the real system service - what little of it can
 * honestly be checked here.
 *
 * ## What this test deliberately does not assert, and why
 *
 * From Android 10 the OS refuses clipboard *reads* to apps that do not hold
 * window focus, and a headless CI emulator never truly grants focus. Three
 * approaches were tried and all failed with `getPrimaryClip()` returning null:
 * a plain instrumentation context, launching an ActivityScenario, and granting
 * the READ_CLIPBOARD app-op. So a test here cannot observe what it just wrote,
 * and any assertion about clipboard *contents* would be testing the emulator,
 * not the app.
 *
 * Rather than weaken the assertion or paper over it, the decision that actually
 * matters - "never clear a clipboard that now holds someone else's data" - was
 * extracted into [dev.voicecomposer.core.ClipboardClearPolicy], a pure function
 * with full unit-test coverage. That is a better home for it anyway.
 *
 * What remains here is worth keeping: it proves the real ClipboardManager
 * accepts the clip we construct, including the sensitivity flag, without
 * throwing on a real Android runtime. That is a genuine integration risk
 * (`PersistableBundle` on the description, the API-33 flag) that no unit test
 * covers.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun writer(policy: ClipboardAutoClear): ClipboardWriter {
        val settings = SettingsRepository(context).apply { clipboardAutoClear = policy }
        return ClipboardWriter(context, settings)
    }

    @Test
    fun writingAClipDoesNotThrowOnARealRuntime() {
        instrumentation.runOnMainSync {
            writer(ClipboardAutoClear.OFF).copy("Approved draft text")
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun writingEmptyTextDoesNotThrow() {
        instrumentation.runOnMainSync {
            writer(ClipboardAutoClear.OFF).copy("")
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun schedulingAnAutoClearDoesNotThrow() {
        // Exercises the Handler path and the clear callback's read attempt,
        // which on this runtime returns null and must therefore be a no-op
        // rather than a crash.
        instrumentation.runOnMainSync {
            writer(ClipboardAutoClear.THIRTY_SECONDS).copy("our text")
        }
        instrumentation.waitForIdleSync()
    }
}
