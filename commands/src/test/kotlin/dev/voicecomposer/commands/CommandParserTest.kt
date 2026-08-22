package dev.voicecomposer.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandParserTest {

    private val parser = CommandParser()

    // -----------------------------------------------------------------------
    // Section 46: the critical false-activation corpus.
    //
    // Every string here is ordinary speech. None of it may execute a command.
    // -----------------------------------------------------------------------

    private val ordinarySpeechCorpus = listOf(
        "The command line interface has several commands.",
        "Run the command and then check the output.",
        "I gave the command to stop the deployment.",
        "Command Prompt on Windows is different from bash.",
        "There are three commands you need to remember.",
        "He took command of the project last quarter.",
        "The voice recognition system needs more training data.",
        "Her voice command over the room was impressive.",
        "I told him voice command doesn't work on this phone.",
        "We should add a voice commander feature eventually.",
        "The commanding officer approved the request.",
        "Undo is a useful feature in most editors.",
        "Copy the file to the server before you insert the record.",
        "Cancel the meeting and let everyone know.",
        "Please insert the USB drive into the laptop.",
    )

    @Test
    fun `ordinary speech never executes a command`() {
        for (utterance in ordinarySpeechCorpus) {
            val result = parser.parse(utterance)
            assertNull(
                result.command,
                "False activation on ordinary speech: \"$utterance\"",
            )
            assertEquals(
                utterance,
                result.literalText,
                "Text was altered for: \"$utterance\"",
            )
        }
    }

    @Test
    fun `false activation rate over the ordinary speech corpus is zero`() {
        val falsePositives = ordinarySpeechCorpus.count { parser.parse(it).hasCommand }
        assertEquals(0, falsePositives, "Expected no false activations")
    }

    // -----------------------------------------------------------------------
    // Boundary sensitivity.
    // -----------------------------------------------------------------------

    @Test
    fun `trigger mid-sentence with free-form text is treated as speech`() {
        // The trigger appears, but mid-sentence and followed by prose that is
        // not a known command. Safe reading: the user was just talking.
        val result = parser.parse("I told him voice command doesn't work on this phone.")
        assertNull(result.command)
    }

    @Test
    fun `mid-sentence trigger does not fire a semantic rewrite`() {
        // A semantic instruction requires a segment boundary. Mid-sentence, the
        // safe reading is that the user was simply speaking, so the whole
        // utterance is kept verbatim.
        val utterance = "so anyway voice command make this concise"
        val result = parser.parse(utterance)
        assertNull(result.command)
        assertEquals(utterance, result.literalText)
    }

    @Test
    fun `deterministic command mid-sentence is POSSIBLE not HIGH`() {
        val result = parser.parse("I was saying that voice command undo")
        val command = assertNotNull(result.command)
        assertEquals(CommandConfidence.POSSIBLE, command.confidence)
        assertTrue(command.requiresConfirmation, "POSSIBLE commands must require confirmation")
    }

    @Test
    fun `trigger at start of utterance is HIGH confidence`() {
        val result = parser.parse("Voice command, undo")
        val command = assertNotNull(result.command)
        assertEquals(CommandConfidence.HIGH, command.confidence)
        assertEquals(CommandIntent.Deterministic(DeterministicAction.UNDO), command.intent)
    }

    @Test
    fun `trigger after sentence terminator is HIGH confidence`() {
        val result = parser.parse("This is my draft. Voice command, make this concise.")
        val command = assertNotNull(result.command)
        assertEquals(CommandConfidence.HIGH, command.confidence)
        assertEquals("This is my draft.", result.literalText)
    }

    @Test
    fun `word boundary prevents matching inside a longer word`() {
        val result = parser.parse("We should add a voice commander feature eventually.")
        assertNull(result.command)
    }

    // -----------------------------------------------------------------------
    // Section 25: anything leaving the scratchpad requires a tap.
    // -----------------------------------------------------------------------

    @Test
    fun `insert copy and cancel always require confirmation even at HIGH confidence`() {
        val externalising = mapOf(
            "Voice command, insert" to DeterministicAction.INSERT,
            "Voice command, copy" to DeterministicAction.COPY,
            "Voice command, cancel" to DeterministicAction.CANCEL,
        )
        for ((utterance, expected) in externalising) {
            val command = assertNotNull(parser.parse(utterance).command, utterance)
            assertEquals(CommandConfidence.HIGH, command.confidence, utterance)
            assertEquals(CommandIntent.Deterministic(expected), command.intent, utterance)
            assertTrue(
                command.requiresConfirmation,
                "$expected must require a tap even at HIGH confidence",
            )
        }
    }

    @Test
    fun `non-externalising deterministic commands at HIGH confidence do not need a tap`() {
        val command = assertNotNull(parser.parse("Voice command, undo").command)
        assertFalse(command.requiresConfirmation)
    }

    // -----------------------------------------------------------------------
    // Deterministic routing (section 26): these must not reach a model.
    // -----------------------------------------------------------------------

    @Test
    fun `deterministic commands are routed deterministically`() {
        val cases = mapOf(
            "Voice command, undo" to DeterministicAction.UNDO,
            "Voice command, redo" to DeterministicAction.REDO,
            "Voice command, preview" to DeterministicAction.PREVIEW,
            "Voice command, delete last word" to DeterministicAction.DELETE_LAST_WORD,
            "Voice command, delete the last sentence" to DeterministicAction.DELETE_LAST_SENTENCE,
            "Voice command, delete last paragraph" to DeterministicAction.DELETE_LAST_PARAGRAPH,
            "Voice command, new line" to DeterministicAction.NEW_LINE,
            "Voice command, new paragraph" to DeterministicAction.NEW_PARAGRAPH,
            "Voice command, add bullets" to DeterministicAction.ADD_BULLETS,
            "Voice command, remove bullets" to DeterministicAction.REMOVE_BULLETS,
            "Voice command, continue dictation" to DeterministicAction.CONTINUE_DICTATION,
            "Voice command, turn this into bullet points" to DeterministicAction.ADD_BULLETS,
        )
        for ((utterance, expected) in cases) {
            val command = assertNotNull(parser.parse(utterance).command, utterance)
            assertEquals(
                CommandIntent.Deterministic(expected),
                command.intent,
                "Wrong routing for: $utterance",
            )
        }
    }

    @Test
    fun `longest deterministic pattern wins`() {
        val command = assertNotNull(parser.parse("Voice command, delete last sentence").command)
        assertEquals(
            CommandIntent.Deterministic(DeterministicAction.DELETE_LAST_SENTENCE),
            command.intent,
        )
    }

    // -----------------------------------------------------------------------
    // Semantic routing.
    // -----------------------------------------------------------------------

    @Test
    fun `semantic commands are classified by kind`() {
        val cases = mapOf(
            "Voice command, make this concise" to SemanticKind.MAKE_CONCISE,
            "Voice command, make this professional" to SemanticKind.MAKE_PROFESSIONAL,
            "Voice command, fix grammar only" to SemanticKind.FIX_GRAMMAR,
            "Voice command, make it suitable for WhatsApp" to SemanticKind.FOR_WHATSAPP,
            "Voice command, make it suitable for an email" to SemanticKind.FOR_EMAIL,
            "Voice command, turn this into an AI prompt" to SemanticKind.TO_AI_PROMPT,
            "Voice command, summarize this" to SemanticKind.SUMMARIZE,
            "Voice command, make it persuasive" to SemanticKind.MAKE_PERSUASIVE,
        )
        for ((utterance, expectedKind) in cases) {
            val command = assertNotNull(parser.parse(utterance).command, utterance)
            val intent = assertIs<CommandIntent.Semantic>(command.intent, utterance)
            assertEquals(expectedKind, intent.kind, "Wrong semantic kind for: $utterance")
        }
    }

    @Test
    fun `translation extracts the target language`() {
        val command = assertNotNull(parser.parse("Voice command, translate this to German").command)
        val intent = assertIs<CommandIntent.Semantic>(command.intent)
        assertEquals(SemanticKind.TRANSLATE, intent.kind)
        assertEquals("german", intent.targetLanguage)
    }

    @Test
    fun `unrecognised free-form instruction becomes CUSTOM rather than being dropped`() {
        val command = assertNotNull(
            parser.parse("Voice command, rework this so my manager will understand it").command,
        )
        val intent = assertIs<CommandIntent.Semantic>(command.intent)
        assertEquals(SemanticKind.CUSTOM, intent.kind)
        assertTrue(intent.instruction.isNotBlank())
    }

    // -----------------------------------------------------------------------
    // The worked example from the product brief (section 52).
    // -----------------------------------------------------------------------

    @Test
    fun `brief worked example splits dictation from instruction`() {
        val utterance = "I checked Groww and Zerodha and Zerodha appears better for API access. " +
            "Voice command. Rewrite this professionally, make it concise, " +
            "and turn the concerns into bullet points."

        val result = parser.parse(utterance)
        val command = assertNotNull(result.command)

        assertEquals(
            "I checked Groww and Zerodha and Zerodha appears better for API access.",
            result.literalText,
        )
        assertEquals(CommandConfidence.HIGH, command.confidence)
        val intent = assertIs<CommandIntent.Semantic>(command.intent)
        // Compound request: professional AND concise AND bulleted. No single
        // canned prompt covers that, so it is passed through as the user's own
        // instruction rather than being silently narrowed to one of the three.
        assertEquals(SemanticKind.CUSTOM, intent.kind)
        assertTrue(intent.instruction.startsWith("Rewrite this professionally"))
    }

    @Test
    fun `a single semantic cue still resolves to its specific kind`() {
        val command = assertNotNull(parser.parse("Voice command, make this professional").command)
        val intent = assertIs<CommandIntent.Semantic>(command.intent)
        assertEquals(SemanticKind.MAKE_PROFESSIONAL, intent.kind)
    }

    // -----------------------------------------------------------------------
    // Robustness.
    // -----------------------------------------------------------------------

    @Test
    fun `bare trigger with nothing after it is kept as text`() {
        val result = parser.parse("Voice command.")
        assertNull(result.command)
        assertEquals("Voice command.", result.literalText)
    }

    @Test
    fun `last trigger occurrence wins`() {
        val result = parser.parse("Voice command, undo. Voice command, redo")
        val command = assertNotNull(result.command)
        assertEquals(CommandIntent.Deterministic(DeterministicAction.REDO), command.intent)
    }

    @Test
    fun `trigger is case insensitive and tolerates ASR punctuation`() {
        for (variant in listOf("VOICE COMMAND, undo", "voice command; undo", "Voice Command - undo")) {
            assertNotNull(parser.parse(variant).command, variant)
        }
    }

    @Test
    fun `activation phrase is configurable`() {
        val custom = CommandParser(activationPhrase = "hey composer")
        val result = custom.parse("Hey composer, make this concise")
        assertNotNull(result.command)
        // The default phrase must no longer trigger.
        assertNull(custom.parse("Voice command, make this concise").command)
    }

    @Test
    fun `empty and whitespace input is handled`() {
        assertNull(parser.parse("").command)
        assertNull(parser.parse("   ").command)
    }

    @Test
    fun `dictation preceding a newline-separated command is preserved`() {
        val result = parser.parse("Here is my draft\nVoice command, make this concise")
        val command = assertNotNull(result.command)
        assertEquals(CommandConfidence.HIGH, command.confidence)
        assertEquals("Here is my draft", result.literalText)
    }
}
