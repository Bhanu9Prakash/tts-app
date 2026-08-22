package dev.voicecomposer.refinement.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterministicTransformsTest {

    @Test
    fun `normalize spaces collapses runs but keeps line structure`() {
        assertEquals(
            "one two three",
            DeterministicTransforms.normalizeSpaces("one   two \t three  "),
        )
        assertEquals(
            "line one\nline two",
            DeterministicTransforms.normalizeSpaces("line   one\nline    two"),
        )
    }

    @Test
    fun `punctuation cleanup fixes dictation artefacts`() {
        assertEquals(
            "Hello, world. How are you?",
            DeterministicTransforms.cleanPunctuation("Hello , world .How are you ?"),
        )
    }

    @Test
    fun `punctuation cleanup collapses repeated terminators`() {
        assertEquals("Really great", DeterministicTransforms.cleanPunctuation("Really great"))
        assertEquals("Wait. Now", DeterministicTransforms.cleanPunctuation("Wait... Now"))
    }

    @Test
    fun `punctuation cleanup does not split decimal numbers`() {
        assertEquals("The RSI is 14.5 today", DeterministicTransforms.cleanPunctuation("The RSI is 14.5 today"))
    }

    @Test
    fun `capitalization fixes sentence starts and the pronoun I`() {
        assertEquals(
            "Hello there. I am fine. How are you?",
            DeterministicTransforms.fixCapitalization("hello there. i am fine. how are you?"),
        )
    }

    @Test
    fun `capitalization leaves acronyms intact`() {
        val input = "The API and the RSI are fine."
        assertEquals(input, DeterministicTransforms.fixCapitalization(input))
    }

    @Test
    fun `conservative filler removal only strips unambiguous fillers`() {
        assertEquals(
            "so I think we should ship",
            DeterministicTransforms.removeFillers("so um I think uh we should ship"),
        )
    }

    @Test
    fun `conservative filler removal preserves meaningful words`() {
        // "like" and "actually" carry meaning; they survive unless the user opts in.
        val input = "I like this and it actually works"
        assertEquals(input, DeterministicTransforms.removeFillers(input))
    }

    @Test
    fun `aggressive filler removal strips discourse fillers when opted in`() {
        val out = DeterministicTransforms.removeFillers(
            "I basically think it actually works",
            aggressive = true,
        )
        assertFalse(out.contains("basically"))
        assertFalse(out.contains("actually"))
    }

    @Test
    fun `cleanup combines tidying without changing meaning`() {
        val out = DeterministicTransforms.cleanup("um so i checked groww and zerodha .they look fine")
        assertEquals("So I checked groww and zerodha. They look fine", out)
    }

    @Test
    fun `delete last word`() {
        assertEquals("one two", DeterministicTransforms.deleteLastWord("one two three"))
        assertEquals("", DeterministicTransforms.deleteLastWord("solo"))
        assertEquals("", DeterministicTransforms.deleteLastWord(""))
        assertEquals("one two", DeterministicTransforms.deleteLastWord("one two three   "))
    }

    @Test
    fun `delete last sentence`() {
        assertEquals(
            "First sentence.",
            DeterministicTransforms.deleteLastSentence("First sentence. Second sentence."),
        )
        assertEquals("", DeterministicTransforms.deleteLastSentence("Only one sentence."))
        assertEquals("", DeterministicTransforms.deleteLastSentence(""))
    }

    @Test
    fun `delete last paragraph`() {
        assertEquals(
            "Para one.",
            DeterministicTransforms.deleteLastParagraph("Para one.\n\nPara two."),
        )
        assertEquals("", DeterministicTransforms.deleteLastParagraph("Single para."))
    }

    @Test
    fun `bulletize splits prose on sentence boundaries`() {
        val out = DeterministicTransforms.toBullets(
            "Historical options data is limited. Automation is restricted.",
        )
        assertEquals("• Historical options data is limited.\n• Automation is restricted.", out)
    }

    @Test
    fun `bulletize is idempotent on already-bulleted text`() {
        val bulleted = "• One\n• Two"
        assertEquals(bulleted, DeterministicTransforms.toBullets(bulleted))
    }

    @Test
    fun `remove bullets handles several marker styles`() {
        assertEquals("One\nTwo", DeterministicTransforms.removeBullets("• One\n• Two"))
        assertEquals("One\nTwo", DeterministicTransforms.removeBullets("- One\n- Two"))
        assertEquals("One\nTwo", DeterministicTransforms.removeBullets("1. One\n2. Two"))
        assertEquals("One\nTwo", DeterministicTransforms.removeBullets("* One\n* Two"))
    }

    @Test
    fun `bulletize then remove bullets round-trips the content`() {
        val original = "First point. Second point."
        val roundTripped = DeterministicTransforms.removeBullets(
            DeterministicTransforms.toBullets(original),
        )
        assertEquals("First point.\nSecond point.", roundTripped)
    }

    @Test
    fun `paragraph splitting leaves short text and existing structure alone`() {
        val short = "One. Two."
        assertEquals(short, DeterministicTransforms.splitParagraphs(short))

        val structured = "Para one.\n\nPara two."
        assertEquals(structured, DeterministicTransforms.splitParagraphs(structured))
    }

    @Test
    fun `paragraph splitting breaks up long unbroken dictation`() {
        val long = (1..10).joinToString(" ") { "Sentence number $it." }
        val out = DeterministicTransforms.splitParagraphs(long, sentencesPerParagraph = 3)
        assertTrue(out.contains("\n\n"))
        // No content may be lost.
        assertEquals(
            long.replace(" ", "").replace("\n", ""),
            out.replace(" ", "").replace("\n", ""),
        )
    }

    @Test
    fun `all transforms handle empty input without throwing`() {
        assertEquals("", DeterministicTransforms.cleanup(""))
        assertEquals("", DeterministicTransforms.toBullets(""))
        assertEquals("", DeterministicTransforms.removeBullets(""))
        assertEquals("", DeterministicTransforms.normalizeSpaces(""))
        assertEquals("", DeterministicTransforms.deleteLastParagraph(""))
    }
}
