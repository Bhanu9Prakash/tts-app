package dev.voicecomposer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScratchpadTest {

    @Test
    fun `starts empty with no history`() {
        val pad = Scratchpad.empty()
        assertEquals("", pad.current)
        assertFalse(pad.canUndo)
        assertFalse(pad.canRedo)
        assertFalse(pad.isModified)
    }

    @Test
    fun `dictation accumulates with single spaces`() {
        val pad = Scratchpad.empty()
            .appendDictation("Hello there")
            .appendDictation("how are you")
        assertEquals("Hello there how are you", pad.current)
    }

    @Test
    fun `dictation does not double-space after existing whitespace`() {
        val pad = Scratchpad.empty()
            .appendDictation("First paragraph.")
            .replace("First paragraph.\n\n", RevisionSource.Deterministic("NEW_PARAGRAPH"))
            .appendDictation("Second paragraph.")
        assertEquals("First paragraph.\n\nSecond paragraph.", pad.current)
    }

    @Test
    fun `blank dictation is ignored and creates no revision`() {
        val pad = Scratchpad.empty().appendDictation("Hello")
        val after = pad.appendDictation("   ")
        assertEquals(pad.current, after.current)
        assertEquals(pad.revisionCount, after.revisionCount)
    }

    @Test
    fun `undo restores the previous revision`() {
        val pad = Scratchpad.empty()
            .appendDictation("Original text")
            .replace("Refined text", RevisionSource.Refinement("local", "make concise"))

        assertEquals("Refined text", pad.current)
        assertTrue(pad.canUndo)

        val undone = pad.undo()
        assertEquals("Original text", undone.current)
        assertTrue(undone.canRedo)
    }

    @Test
    fun `redo reapplies an undone revision`() {
        val pad = Scratchpad.empty()
            .appendDictation("Original text")
            .replace("Refined text", RevisionSource.Refinement("local", "make concise"))
            .undo()
            .redo()
        assertEquals("Refined text", pad.current)
    }

    @Test
    fun `new edit after undo clears the redo stack`() {
        val pad = Scratchpad.empty()
            .appendDictation("One")
            .replace("Two", RevisionSource.ManualEdit)
            .undo()

        assertTrue(pad.canRedo)

        val branched = pad.replace("Three", RevisionSource.ManualEdit)
        assertFalse(branched.canRedo, "A new edit must invalidate redo")
        assertEquals("Three", branched.current)
    }

    @Test
    fun `undo on empty history is a no-op`() {
        val pad = Scratchpad.empty()
        assertEquals(pad.current, pad.undo().current)
    }

    @Test
    fun `redo with nothing to redo is a no-op`() {
        val pad = Scratchpad.empty().appendDictation("Text")
        assertEquals(pad.current, pad.redo().current)
    }

    @Test
    fun `original tracks the first dictated revision for the preview`() {
        val pad = Scratchpad.empty()
            .appendDictation("Raw dictated text")
            .replace("Polished text", RevisionSource.Refinement("local", "polish"))

        assertEquals("Raw dictated text", pad.original)
        assertEquals("Polished text", pad.current)
        assertTrue(pad.isModified)
    }

    @Test
    fun `replacing with identical text creates no revision`() {
        val pad = Scratchpad.empty().appendDictation("Same")
        val after = pad.replace("Same", RevisionSource.ManualEdit)
        assertEquals(pad.revisionCount, after.revisionCount)
    }

    @Test
    fun `history is bounded so long dictation cannot grow without limit`() {
        var pad = Scratchpad.empty(historyLimit = 5)
        repeat(50) { i -> pad = pad.replace("revision $i", RevisionSource.ManualEdit) }
        // present + at most `historyLimit` past entries.
        assertTrue(pad.revisionCount <= 6, "History grew to ${pad.revisionCount}")
        assertEquals("revision 49", pad.current)
    }

    @Test
    fun `clearing discards content and history`() {
        val pad = Scratchpad.empty()
            .appendDictation("Sensitive draft")
            .replace("Refined", RevisionSource.ManualEdit)
            .cleared()

        assertEquals("", pad.current)
        assertFalse(pad.canUndo, "Cancel must not leave the text recoverable via undo")
        assertFalse(pad.canRedo)
    }

    @Test
    fun `scratchpad is immutable - operations return new instances`() {
        val original = Scratchpad.empty().appendDictation("Start")
        original.appendDictation("More")
        original.replace("Other", RevisionSource.ManualEdit)
        original.cleared()
        assertEquals("Start", original.current, "Original instance must be unchanged")
    }
}
