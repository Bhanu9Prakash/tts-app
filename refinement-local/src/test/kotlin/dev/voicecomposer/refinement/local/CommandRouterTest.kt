package dev.voicecomposer.refinement.local

import dev.voicecomposer.commands.CommandParser
import dev.voicecomposer.core.RevisionSource
import dev.voicecomposer.core.Scratchpad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests over parser + router: the path a real utterance takes,
 * short of the providers themselves.
 */
class CommandRouterTest {

    private val parser = CommandParser()
    private val router = CommandRouter()

    private fun route(draft: String, utterance: String): RouterOutcome {
        val pad = Scratchpad.empty().appendDictation(draft)
        val command = assertNotNull(parser.parse(utterance).command, "No command parsed: $utterance")
        return router.route(pad, command)
    }

    // -----------------------------------------------------------------------
    // Section 26: deterministic commands must never reach a model.
    // -----------------------------------------------------------------------

    @Test
    fun `bulletize is handled locally and never requests refinement`() {
        val outcome = route(
            "Historical options data is limited. Automation is restricted.",
            "Voice command, turn this into bullet points",
        )
        val applied = assertIs<RouterOutcome.Applied>(outcome)
        assertEquals("ADD_BULLETS", applied.action)
        assertEquals(
            "• Historical options data is limited.\n• Automation is restricted.",
            applied.scratchpad.current,
        )
    }

    @Test
    fun `delete last word is handled locally`() {
        val outcome = route("one two three", "Voice command, delete last word")
        val applied = assertIs<RouterOutcome.Applied>(outcome)
        assertEquals("one two", applied.scratchpad.current)
    }

    @Test
    fun `undo is handled locally through the scratchpad`() {
        val pad = Scratchpad.empty()
            .appendDictation("original")
            .replace("refined", RevisionSource.Refinement("x", "y"))
        val command = assertNotNull(parser.parse("Voice command, undo").command)

        val applied = assertIs<RouterOutcome.Applied>(router.route(pad, command))
        assertEquals("original", applied.scratchpad.current)
    }

    // -----------------------------------------------------------------------
    // Section 26: semantic commands are routed to a provider, with the user's
    // own instruction preserved.
    // -----------------------------------------------------------------------

    @Test
    fun `semantic command requests refinement carrying the current draft`() {
        val outcome = route(
            "yeah so I checked Groww and Zerodha",
            "Voice command, make this professional",
        )
        val needs = assertIs<RouterOutcome.NeedsRefinement>(outcome)
        assertEquals("MAKE_PROFESSIONAL", needs.kind)
        assertEquals("yeah so I checked Groww and Zerodha", needs.text)
        assertTrue(needs.instruction.contains("professional"))
    }

    @Test
    fun `translation carries the target language through to the provider`() {
        val outcome = route("Hello there", "Voice command, translate this to German")
        val needs = assertIs<RouterOutcome.NeedsRefinement>(outcome)
        assertEquals("TRANSLATE", needs.kind)
        assertEquals("german", needs.targetLanguage)
    }

    // -----------------------------------------------------------------------
    // Section 25/29: nothing escapes the scratchpad without a tap.
    // -----------------------------------------------------------------------

    @Test
    fun `insert never applies directly and only arms a confirmation`() {
        val outcome = route("my draft", "Voice command, insert")
        val awaiting = assertIs<RouterOutcome.AwaitingConfirmation>(outcome)
        assertEquals("INSERT", awaiting.action)
    }

    @Test
    fun `copy never applies directly`() {
        val awaiting = assertIs<RouterOutcome.AwaitingConfirmation>(
            route("my draft", "Voice command, copy"),
        )
        assertEquals("COPY", awaiting.action)
    }

    @Test
    fun `cancel never applies directly`() {
        val awaiting = assertIs<RouterOutcome.AwaitingConfirmation>(
            route("my draft", "Voice command, cancel"),
        )
        assertEquals("CANCEL", awaiting.action)
    }

    @Test
    fun `no externalising command can ever reach Applied`() {
        // The strong form of the rule: sweep every phrasing we accept for the
        // three externalising actions and assert none of them mutates state.
        val phrasings = listOf(
            "Voice command, insert",
            "Voice command, insert it",
            "Voice command, insert that",
            "Voice command, copy",
            "Voice command, copy that",
            "Voice command, copy it",
            "Voice command, cancel",
            "Voice command, discard",
        )
        for (utterance in phrasings) {
            val outcome = route("draft text", utterance)
            assertIs<RouterOutcome.AwaitingConfirmation>(
                outcome,
                "Externalising command must await confirmation: $utterance",
            )
        }
    }

    @Test
    fun `a POSSIBLE confidence command awaits confirmation rather than applying`() {
        // Mid-sentence trigger with a deterministic command -> POSSIBLE.
        val outcome = route("some draft", "I was saying that voice command undo")
        val awaiting = assertIs<RouterOutcome.AwaitingConfirmation>(outcome)
        assertEquals("UNDO", awaiting.action)
        assertTrue(awaiting.prompt.startsWith("Did you mean"))
    }

    // -----------------------------------------------------------------------
    // Preview / continue are UI-level and must not mutate the draft.
    // -----------------------------------------------------------------------

    @Test
    fun `preview and continue dictation leave the draft untouched`() {
        for (utterance in listOf("Voice command, preview", "Voice command, continue dictation")) {
            val applied = assertIs<RouterOutcome.Applied>(route("draft text", utterance), utterance)
            assertEquals("draft text", applied.scratchpad.current, utterance)
        }
    }
}
