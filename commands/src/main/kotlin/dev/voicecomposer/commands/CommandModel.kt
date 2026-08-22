package dev.voicecomposer.commands

/**
 * How sure we are that the user actually issued a command rather than merely
 * saying words that resemble one.
 *
 * The product brief (section 25) requires that uncertain recognition preserves
 * the words as ordinary text instead of silently executing something.
 */
enum class CommandConfidence {
    /** Reserved phrase at an utterance/sentence boundary AND a recognised command. */
    HIGH,

    /** Recognised, but something about the context is off. Never auto-executes. */
    POSSIBLE,

    /** Not a command. The words stay in the draft verbatim. */
    NONE,
}

/** Commands we can satisfy locally, with no model of any kind. */
enum class DeterministicAction {
    UNDO,
    REDO,
    COPY,
    INSERT,
    CANCEL,
    PREVIEW,
    CONTINUE_DICTATION,
    DELETE_LAST_WORD,
    DELETE_LAST_SENTENCE,
    DELETE_LAST_PARAGRAPH,
    NEW_LINE,
    NEW_PARAGRAPH,
    ADD_BULLETS,
    REMOVE_BULLETS,
    CLEANUP,
}

/** Transformations that genuinely need a language model. */
enum class SemanticKind {
    MAKE_CONCISE,
    MAKE_PROFESSIONAL,
    FIX_GRAMMAR,
    FOR_WHATSAPP,
    FOR_EMAIL,
    TO_AI_PROMPT,
    IMPROVE_READABILITY,
    SUMMARIZE,
    SIMPLIFY,
    MAKE_PERSUASIVE,
    TRANSLATE,
    CUSTOM,
}

sealed interface CommandIntent {
    data class Deterministic(val action: DeterministicAction) : CommandIntent

    data class Semantic(
        val kind: SemanticKind,
        /** The user's own words, passed to the refinement provider. */
        val instruction: String,
        /** Target language for [SemanticKind.TRANSLATE]. */
        val targetLanguage: String? = null,
    ) : CommandIntent
}

/**
 * A command the parser believes it found.
 *
 * [requiresConfirmation] is deliberately not derived solely from confidence:
 * commands that move text out of the private scratchpad and into the outside
 * world always require a tap, even at [CommandConfidence.HIGH] (product brief
 * section 25).
 */
data class DetectedCommand(
    val intent: CommandIntent,
    val confidence: CommandConfidence,
    /** The literal words that were interpreted as the command. */
    val phrase: String,
    val requiresConfirmation: Boolean,
)

/**
 * The outcome of parsing one dictated utterance.
 *
 * [literalText] is what should be appended to the scratchpad. When no command
 * is found it is the entire utterance - that is the safe default the brief
 * asks for.
 */
data class ParseResult(
    val literalText: String,
    val command: DetectedCommand?,
) {
    val hasCommand: Boolean get() = command != null
}
