package dev.voicecomposer.commands

/**
 * Splits a dictated utterance into text-to-keep and an optional command.
 *
 * ## Why this is written the way it is
 *
 * The failure mode that matters is the *false positive*: ordinary speech being
 * silently executed as a command. The brief's worked example is
 * "The command line interface has several commands." Three independent
 * mechanisms guard against that class of mistake:
 *
 *  1. **A reserved two-word phrase.** The trigger defaults to "voice command",
 *     not the bare word "command", so ordinary technical speech about commands
 *     does not contain the trigger at all.
 *  2. **Boundary sensitivity.** A free-form (semantic) instruction is only
 *     honoured when the trigger sits at the start of the utterance or just
 *     after sentence-ending punctuation. "I told him voice command doesn't work"
 *     therefore stays as text, because the trigger is mid-sentence and what
 *     follows is not a known command.
 *  3. **Confirmation for anything that escapes the scratchpad.** INSERT, COPY
 *     and CANCEL never execute from voice alone; they only arm the button.
 *
 * Matching is done on word boundaries over a normalised token stream, so
 * "voice commander" does not trigger, and so trailing punctuation from the ASR
 * ("Voice command.") does not prevent a match.
 *
 * The parser is pure: no Android types, no I/O, no clock. Every rule above is
 * covered by tests in `CommandParserTest`.
 */
class CommandParser(
    /** Configurable per product brief section 24. Matched case-insensitively. */
    private val activationPhrase: String = DEFAULT_ACTIVATION_PHRASE,
) {

    private val activationTokens: List<String> =
        tokenize(activationPhrase).map { it.normalized }.filter { it.isNotEmpty() }

    fun parse(utterance: String): ParseResult {
        if (activationTokens.isEmpty()) return ParseResult(utterance, null)

        val tokens = tokenize(utterance)
        val matchIndex = findLastActivationMatch(tokens)
            ?: return ParseResult(utterance, null)

        val match = tokens[matchIndex]
        val afterPhraseTokenIndex = matchIndex + activationTokens.size

        // Text before the trigger is dictation and is always preserved.
        val literal = utterance.substring(0, match.start).trim().trimEnd(',', ';', ':')

        // Everything after the trigger is the candidate command. Leading
        // punctuation is dropped so that "Voice command. Make this concise"
        // parses the same as "Voice command, make this concise".
        val candidate = if (afterPhraseTokenIndex < tokens.size) {
            utterance.substring(tokens[afterPhraseTokenIndex].start).trim()
        } else {
            ""
        }

        val atBoundary = isAtSegmentBoundary(utterance, match.start)

        // A bare trigger with nothing after it is not actionable. Keep the whole
        // utterance rather than guessing.
        if (candidate.isBlank()) {
            return ParseResult(utterance, null)
        }

        val deterministic = matchDeterministic(candidate)
        if (deterministic != null) {
            val confidence = if (atBoundary) CommandConfidence.HIGH else CommandConfidence.POSSIBLE
            return ParseResult(
                literalText = literal,
                command = DetectedCommand(
                    intent = CommandIntent.Deterministic(deterministic),
                    confidence = confidence,
                    phrase = candidate,
                    requiresConfirmation = requiresConfirmation(deterministic, confidence),
                ),
            )
        }

        // Free-form instruction. Only honoured at a segment boundary; otherwise
        // the safe reading is that the user was simply talking.
        if (!atBoundary) {
            return ParseResult(utterance, null)
        }

        val semantic = classifySemantic(candidate)
        return ParseResult(
            literalText = literal,
            command = DetectedCommand(
                intent = semantic,
                confidence = CommandConfidence.HIGH,
                phrase = candidate,
                // Semantic rewrites only ever change the private draft and are
                // undoable, so they do not need a tap - the preview is the gate.
                requiresConfirmation = false,
            ),
        )
    }

    /**
     * Commands that move text out of the scratchpad, or destroy it, always
     * require an explicit tap. Confidence cannot buy past this.
     */
    private fun requiresConfirmation(
        action: DeterministicAction,
        confidence: CommandConfidence,
    ): Boolean = when (action) {
        DeterministicAction.INSERT,
        DeterministicAction.COPY,
        DeterministicAction.CANCEL,
        -> true

        else -> confidence != CommandConfidence.HIGH
    }

    private fun findLastActivationMatch(tokens: List<Token>): Int? {
        if (tokens.size < activationTokens.size) return null
        // Scan backwards: a command follows the dictation it applies to, so the
        // last occurrence is the operative one.
        for (start in tokens.size - activationTokens.size downTo 0) {
            var matched = true
            for (offset in activationTokens.indices) {
                if (tokens[start + offset].normalized != activationTokens[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
        }
        return null
    }

    /**
     * True when the character run immediately before [index] is the start of
     * the utterance or terminates a sentence.
     */
    private fun isAtSegmentBoundary(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && text[i].isWhitespace()) {
            if (text[i] == '\n') return true
            i--
        }
        if (i < 0) return true
        return text[i] in SENTENCE_TERMINATORS
    }

    private fun matchDeterministic(candidate: String): DeterministicAction? {
        val normalized = normalizePhrase(candidate)
        // Longest patterns first so "delete last sentence" is not shadowed by
        // a shorter "delete last" style pattern.
        return DETERMINISTIC_PATTERNS
            .filter { (pattern, _) -> normalized == pattern || normalized.startsWith("$pattern ") }
            .maxByOrNull { (pattern, _) -> pattern.length }
            ?.second
    }

    private fun classifySemantic(candidate: String): CommandIntent.Semantic {
        val normalized = normalizePhrase(candidate)

        translationTarget(normalized)?.let { language ->
            return CommandIntent.Semantic(SemanticKind.TRANSLATE, candidate, language)
        }

        // Collect every cue that fires, then look at how many *distinct* kinds
        // were hit. One kind means a canned prompt is a faithful reading of the
        // request. Several kinds means the user asked for a combination
        // ("rewrite this professionally, make it concise, and turn the concerns
        // into bullet points") and no single canned prompt is honest about it -
        // so we classify it CUSTOM and pass their own words through untouched.
        val kinds = SEMANTIC_PATTERNS
            .filter { (pattern, _) -> normalized.contains(pattern) }
            .map { (_, kind) -> kind }
            .distinct()

        val kind = if (kinds.size == 1) kinds.single() else SemanticKind.CUSTOM

        return CommandIntent.Semantic(kind, candidate)
    }

    private fun translationTarget(normalized: String): String? {
        val marker = TRANSLATE_MARKERS.firstOrNull { normalized.contains(it) } ?: return null
        val tail = normalized.substringAfter(marker).trim()
        if (tail.isEmpty()) return null
        // Take the first word or two; "german", "brazilian portuguese".
        val words = tail.split(' ').filter { it.isNotBlank() }.take(2)
        return words.joinToString(" ").ifBlank { null }
    }

    // -- tokenisation ------------------------------------------------------

    private data class Token(val normalized: String, val start: Int, val end: Int)

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val raw = text.substring(start, i)
            tokens += Token(normalizeWord(raw), start, i)
        }
        return tokens
    }

    /** Lowercases and strips surrounding punctuation, keeping inner apostrophes. */
    private fun normalizeWord(raw: String): String =
        raw.lowercase().trim { !it.isLetterOrDigit() }

    private fun normalizePhrase(phrase: String): String =
        phrase.split(' ', '\n', '\t')
            .map { normalizeWord(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    companion object {
        const val DEFAULT_ACTIVATION_PHRASE: String = "voice command"

        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '\n', ';')

        private val TRANSLATE_MARKERS = listOf(
            "translate this to",
            "translate this into",
            "translate to",
            "translate into",
        )

        /**
         * Deterministic commands. Matched against the normalised candidate as a
         * whole phrase or as a leading phrase, never as a substring, so that
         * "copy" inside a longer instruction does not trigger a clipboard write.
         */
        private val DETERMINISTIC_PATTERNS: List<Pair<String, DeterministicAction>> = listOf(
            "undo" to DeterministicAction.UNDO,
            "undo that" to DeterministicAction.UNDO,
            "redo" to DeterministicAction.REDO,
            "copy" to DeterministicAction.COPY,
            "copy that" to DeterministicAction.COPY,
            "copy it" to DeterministicAction.COPY,
            "insert" to DeterministicAction.INSERT,
            "insert it" to DeterministicAction.INSERT,
            "insert that" to DeterministicAction.INSERT,
            "cancel" to DeterministicAction.CANCEL,
            "discard" to DeterministicAction.CANCEL,
            "preview" to DeterministicAction.PREVIEW,
            "show preview" to DeterministicAction.PREVIEW,
            "continue" to DeterministicAction.CONTINUE_DICTATION,
            "continue dictation" to DeterministicAction.CONTINUE_DICTATION,
            "keep dictating" to DeterministicAction.CONTINUE_DICTATION,
            "delete last word" to DeterministicAction.DELETE_LAST_WORD,
            "delete the last word" to DeterministicAction.DELETE_LAST_WORD,
            "delete last sentence" to DeterministicAction.DELETE_LAST_SENTENCE,
            "delete the last sentence" to DeterministicAction.DELETE_LAST_SENTENCE,
            "delete last paragraph" to DeterministicAction.DELETE_LAST_PARAGRAPH,
            "delete the last paragraph" to DeterministicAction.DELETE_LAST_PARAGRAPH,
            "new line" to DeterministicAction.NEW_LINE,
            "newline" to DeterministicAction.NEW_LINE,
            "new paragraph" to DeterministicAction.NEW_PARAGRAPH,
            "add bullet" to DeterministicAction.ADD_BULLETS,
            "add bullets" to DeterministicAction.ADD_BULLETS,
            "add bullet points" to DeterministicAction.ADD_BULLETS,
            "turn this into bullet points" to DeterministicAction.ADD_BULLETS,
            "turn this into bullets" to DeterministicAction.ADD_BULLETS,
            "make this bullet points" to DeterministicAction.ADD_BULLETS,
            "remove bullets" to DeterministicAction.REMOVE_BULLETS,
            "remove bullet points" to DeterministicAction.REMOVE_BULLETS,
            "clean this up" to DeterministicAction.CLEANUP,
            "clean up" to DeterministicAction.CLEANUP,
            "tidy this up" to DeterministicAction.CLEANUP,
        )

        /** Substring cues that map a free-form instruction onto a known intent. */
        private val SEMANTIC_PATTERNS: List<Pair<String, SemanticKind>> = listOf(
            "make this concise" to SemanticKind.MAKE_CONCISE,
            "make it concise" to SemanticKind.MAKE_CONCISE,
            "concise" to SemanticKind.MAKE_CONCISE,
            "shorter" to SemanticKind.MAKE_CONCISE,
            "professional" to SemanticKind.MAKE_PROFESSIONAL,
            "formal" to SemanticKind.MAKE_PROFESSIONAL,
            "fix grammar" to SemanticKind.FIX_GRAMMAR,
            "fix the grammar" to SemanticKind.FIX_GRAMMAR,
            "grammar only" to SemanticKind.FIX_GRAMMAR,
            "correct the grammar" to SemanticKind.FIX_GRAMMAR,
            "whatsapp" to SemanticKind.FOR_WHATSAPP,
            "email" to SemanticKind.FOR_EMAIL,
            "ai prompt" to SemanticKind.TO_AI_PROMPT,
            "into a prompt" to SemanticKind.TO_AI_PROMPT,
            "readability" to SemanticKind.IMPROVE_READABILITY,
            "improve readability" to SemanticKind.IMPROVE_READABILITY,
            "preserve my wording" to SemanticKind.IMPROVE_READABILITY,
            "summarize" to SemanticKind.SUMMARIZE,
            "summarise" to SemanticKind.SUMMARIZE,
            "summary" to SemanticKind.SUMMARIZE,
            "simplify" to SemanticKind.SIMPLIFY,
            "simpler" to SemanticKind.SIMPLIFY,
            "persuasive" to SemanticKind.MAKE_PERSUASIVE,
        )
    }
}
