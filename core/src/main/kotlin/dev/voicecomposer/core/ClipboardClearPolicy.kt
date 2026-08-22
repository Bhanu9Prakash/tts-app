package dev.voicecomposer.core

/**
 * Decides whether a scheduled clipboard auto-clear should actually fire.
 *
 * ## Why this is a pure function in `core`
 *
 * The rule it encodes is the one that could *destroy the user's data*: if we
 * clear the clipboard unconditionally after a timeout, we wipe whatever they
 * copied from some other app in the meantime. A clipboard writer that deletes
 * other apps' data is worse than one that never clears at all.
 *
 * That rule deserves to be tested, and it cannot be tested on device: from
 * Android 10 the OS refuses clipboard *reads* to apps without window focus, and
 * a headless CI emulator never grants focus, so an instrumented test cannot
 * observe the clipboard at all. Keeping the decision here - as a pure function
 * over plain values - means it is covered by ordinary unit tests, while the
 * Android class is left with only the mechanical parts.
 */
object ClipboardClearPolicy {

    /**
     * @param ourLabel the label we attach to clips we write
     * @param ourText the text we wrote when the clear was scheduled
     * @param currentLabel the clipboard's current label, or null if unreadable
     * @param currentText the clipboard's current text, or null if unreadable
     */
    fun shouldClear(
        ourLabel: String,
        ourText: String,
        currentLabel: String?,
        currentText: String?,
    ): Boolean {
        // Unreadable clipboard: do nothing. Clearing on a null read would mean
        // clearing precisely when we cannot tell whose data we are destroying.
        if (currentLabel == null || currentText == null) return false

        return currentLabel == ourLabel && currentText == ourText
    }
}
