package dev.voicecomposer.refinement.local

/**
 * Pure text transformations that need no model of any kind.
 *
 * The product brief (section 26) is explicit that not every command should be
 * routed through an LLM. Everything here runs in microseconds, offline, with no
 * hallucination risk and no data leaving the process - which is why the command
 * router prefers these whenever one applies.
 */
object DeterministicTransforms {

    /** Filler words removed by [removeFillers]. Conservative on purpose. */
    private val FILLER_WORDS = setOf(
        "um", "uh", "erm", "hmm", "mmm", "eh",
        "like", "basically", "actually", "literally",
        "honestly", "sort", "kinda", "kind",
    )

    /**
     * Only these are removed unconditionally. The rest of [FILLER_WORDS] carry
     * real meaning often enough ("I like it", "sort the list") that removing
     * them would change what the user said, so they are left alone unless the
     * user opts in.
     */
    private val ALWAYS_SAFE_FILLERS = setOf("um", "uh", "erm", "hmm", "mmm", "eh")

    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    /** Collapses runs of whitespace and trims, without touching line breaks. */
    fun normalizeSpaces(text: String): String =
        text.lines()
            .joinToString("\n") { line -> line.replace(Regex("[ \\t]+"), " ").trim() }
            .trim()

    /**
     * Fixes spacing around punctuation that dictation commonly gets wrong:
     * a space before a comma, a missing space after one, doubled terminators.
     */
    fun cleanPunctuation(text: String): String =
        text
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(Regex("([,;:])(?=[^\\s\\d])"), "$1 ")
            .replace(Regex("([.!?])(?=[A-Za-z])"), "$1 ")
            .replace(Regex("([.!?]){2,}"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Capitalises the first letter of each sentence and the pronoun "I". */
    fun fixCapitalization(text: String): String {
        if (text.isBlank()) return text
        val builder = StringBuilder(text)
        var capitalizeNext = true
        for (i in builder.indices) {
            val c = builder[i]
            when {
                capitalizeNext && c.isLetter() -> {
                    builder[i] = c.uppercaseChar()
                    capitalizeNext = false
                }
                c in ".!?\n" -> capitalizeNext = true
            }
        }
        // Standalone "i" -> "I".
        return Regex("\\bi\\b").replace(builder.toString()) { "I" }
    }

    /**
     * Removes filler words.
     *
     * [aggressive] additionally removes discourse fillers that can be
     * meaningful in context, so it is off by default and surfaced as a setting.
     */
    fun removeFillers(text: String, aggressive: Boolean = false): String {
        val targets = if (aggressive) FILLER_WORDS else ALWAYS_SAFE_FILLERS
        val cleaned = text.split(" ").filter { word ->
            val bare = word.lowercase().trim { !it.isLetterOrDigit() }
            bare !in targets
        }.joinToString(" ")
        return normalizeSpaces(cleaned)
    }

    /**
     * The "clean this up" command: safe, meaning-preserving tidying only.
     * Deliberately does not attempt grammar correction, which needs a model.
     */
    fun cleanup(text: String, aggressiveFillers: Boolean = false): String {
        if (text.isBlank()) return text
        var out = removeFillers(text, aggressiveFillers)
        out = cleanPunctuation(out)
        out = fixCapitalization(out)
        return out.trim()
    }

    fun deleteLastWord(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return ""
        val idx = trimmed.indexOfLast { it.isWhitespace() }
        return if (idx < 0) "" else trimmed.substring(0, idx).trimEnd()
    }

    fun deleteLastSentence(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return ""
        // Skip the terminator of the final sentence, then find the one before it.
        val withoutFinalTerminator = trimmed.trimEnd('.', '!', '?', ' ')
        val idx = withoutFinalTerminator.indexOfLast { it in ".!?" }
        return if (idx < 0) "" else trimmed.substring(0, idx + 1).trimEnd()
    }

    fun deleteLastParagraph(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return ""
        val idx = trimmed.lastIndexOf("\n\n")
        return if (idx < 0) "" else trimmed.substring(0, idx).trimEnd()
    }

    fun appendNewLine(text: String): String = "${text.trimEnd()}\n"

    fun appendNewParagraph(text: String): String = "${text.trimEnd()}\n\n"

    /**
     * Turns prose into a bullet list by splitting on sentence boundaries.
     *
     * This is the deterministic answer to "turn this into bullet points": it is
     * structural rather than semantic, so it needs no model and cannot invent
     * content. Lines that are already bullets are left as they are.
     */
    fun toBullets(text: String, marker: String = "• "): String {
        if (text.isBlank()) return text
        if (isBulleted(text)) return text

        return text.lines()
            .filter { it.isNotBlank() }
            .flatMap { line -> SENTENCE_END.split(line) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n") { sentence -> marker + sentence }
    }

    fun removeBullets(text: String): String =
        text.lines()
            .joinToString("\n") { line ->
                line.trimStart().removePrefix("• ")
                    .let { Regex("^[-*•]\\s+").replace(it, "") }
                    .let { Regex("^\\d+[.)]\\s+").replace(it, "") }
            }
            .trim()

    private fun isBulleted(text: String): Boolean {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        return lines.all { Regex("^\\s*([-*•]|\\d+[.)])\\s+").containsMatchIn(it) }
    }

    /**
     * Splits a long unbroken dictation into paragraphs every [sentencesPerParagraph]
     * sentences, for readability. Text that already has paragraph breaks is left
     * alone so we never fight the user's own structure.
     */
    fun splitParagraphs(text: String, sentencesPerParagraph: Int = 4): String {
        if (text.contains("\n\n")) return text
        val sentences = SENTENCE_END.split(text).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size <= sentencesPerParagraph) return text
        return sentences.chunked(sentencesPerParagraph)
            .joinToString("\n\n") { chunk -> chunk.joinToString(" ") }
    }
}
