package dev.voicecomposer.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardClearPolicyTest {

    private val label = "Voice Composer"
    private val text = "Approved draft text"

    private fun shouldClear(currentLabel: String?, currentText: String?) =
        ClipboardClearPolicy.shouldClear(label, text, currentLabel, currentText)

    @Test
    fun `clears when the clipboard still holds exactly what we wrote`() {
        assertTrue(shouldClear(label, text))
    }

    @Test
    fun `does not clear when the user copied something else in the meantime`() {
        // The rule that matters: never destroy another app's clipboard data.
        assertFalse(shouldClear("Other app", "something else"))
    }

    @Test
    fun `does not clear when the text changed but the label happens to match`() {
        assertFalse(shouldClear(label, "a different draft"))
    }

    @Test
    fun `does not clear when another app used the same text under its own label`() {
        assertFalse(shouldClear("Other app", text))
    }

    @Test
    fun `does not clear when the clipboard cannot be read`() {
        // Android refuses clipboard reads to unfocused apps, so null is a real
        // and common case. Clearing then would mean clearing exactly when we
        // cannot tell whose data we would be destroying.
        assertFalse(shouldClear(null, null))
        assertFalse(shouldClear(label, null))
        assertFalse(shouldClear(null, text))
    }

    @Test
    fun `empty text we wrote is still matched exactly`() {
        assertTrue(ClipboardClearPolicy.shouldClear(label, "", label, ""))
        assertFalse(ClipboardClearPolicy.shouldClear(label, "", label, "x"))
    }
}
