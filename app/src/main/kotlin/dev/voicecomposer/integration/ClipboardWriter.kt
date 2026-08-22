package dev.voicecomposer.integration

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import dev.voicecomposer.core.ClipboardClearPolicy
import dev.voicecomposer.security.SafeLog
import dev.voicecomposer.settings.ClipboardAutoClear
import dev.voicecomposer.settings.SettingsRepository

/**
 * Writes approved text to the clipboard, and clears it again.
 *
 * ## What the clipboard does and does not protect
 *
 * The clipboard is a system-wide channel. On Android 10+ the OS stops
 * background apps reading it, and on 13+ the system shows a copy confirmation,
 * but the foreground app the user pastes into necessarily sees the text, and
 * an IME with focus can observe it. Auto-clear reduces the window; it does not
 * make the copy private. docs/THREAT_MODEL.md states the residual risk.
 *
 * This class only ever *writes*. It never reads the clipboard and never keeps a
 * history - the brief (section 31) forbids both, and not having a read path is
 * a stronger guarantee than a promise not to use one.
 */
class ClipboardWriter(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    private val clipboard: ClipboardManager
        get() = context.getSystemService(ClipboardManager::class.java)

    fun copy(text: String) {
        if (text.isEmpty()) return

        val clip = ClipData.newPlainText(LABEL, text).apply {
            // Ask the OS not to include this in clipboard previews or to sync
            // it to other devices. Honoured from API 33; harmless before.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }

        clipboard.setPrimaryClip(clip)
        SafeLog.info(TAG, "clipboard_write", mapOf("chars" to text.length))

        scheduleClear(text)
    }

    private fun scheduleClear(written: String) {
        val policy = settings.clipboardAutoClear
        if (policy == ClipboardAutoClear.OFF) return

        Handler(Looper.getMainLooper()).postDelayed(
            { clearIfStillOurs(written) },
            policy.seconds * 1000L,
        )
    }

    /**
     * Clears only if the clipboard still holds what we put there.
     *
     * Without this check we would wipe whatever the user copied in the
     * meantime, from some other app - a clipboard writer that deletes other
     * apps' data is itself a bug.
     *
     * The decision lives in [ClipboardClearPolicy] so it can be unit-tested:
     * Android refuses clipboard reads to unfocused apps, which makes this
     * untestable on device. See that class for the reasoning.
     */
    private fun clearIfStillOurs(written: String) {
        val current = runCatching { clipboard.primaryClip }.getOrNull()
        val currentLabel = current?.description?.label?.toString()
        val currentText = current?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()

        val stillOurs = ClipboardClearPolicy.shouldClear(
            ourLabel = LABEL,
            ourText = written,
            currentLabel = currentLabel,
            currentText = currentText,
        )

        if (!stillOurs) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, ""))
        }
        SafeLog.info(TAG, "clipboard_auto_cleared")
    }

    companion object {
        private const val TAG = "Clipboard"
        private const val LABEL = "Voice Composer"
    }
}
