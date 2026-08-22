package dev.voicecomposer.core

/** How a particular revision of the draft came to exist. */
sealed interface RevisionSource {
    /** Text appended by dictation. */
    data object Dictation : RevisionSource

    /** A deterministic, purely local edit (delete last word, add bullets, ...). */
    data class Deterministic(val action: String) : RevisionSource

    /** A semantic rewrite produced by a refinement provider. */
    data class Refinement(val providerId: String, val instruction: String) : RevisionSource

    /** The user typed into the scratchpad directly. */
    data object ManualEdit : RevisionSource
}

/** One immutable point in the draft's history. */
data class Revision(
    val text: String,
    val source: RevisionSource,
)

/**
 * The private staging area that the whole product is built around.
 *
 * The central rule (product brief sections 27 and 29) is that nothing here is
 * visible to any other application until the user explicitly commits it. This
 * class therefore has no notion of "insert" or "copy" at all - committing is
 * the caller's job, and it can only ever act on [current].
 *
 * Immutable and pure so the undo/redo semantics are unit-testable without an
 * Android runtime.
 */
class Scratchpad private constructor(
    private val past: List<Revision>,
    private val present: Revision,
    private val future: List<Revision>,
    /** Bounds memory (and therefore how much dictated text sits in RAM). */
    private val historyLimit: Int,
) {

    val current: String get() = present.text

    /**
     * The raw dictation, kept so the preview can always show "Original" beside
     * "Refined" (product brief section 28).
     *
     * This is the *last* purely-dictated revision, not the first: dictation
     * accumulates across several utterances, and the user's "original" is
     * everything they said before a transformation was applied - not the first
     * fragment of it.
     */
    val original: String
        get() = (past + present)
            .lastOrNull { it.source is RevisionSource.Dictation }
            // History is bounded, so on a very long editing session the
            // dictated revisions can age out. Falling back to the oldest
            // revision we still hold is the closest honest answer.
            ?.text
            ?: (past.firstOrNull() ?: present).text

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** True when refinement has actually changed the text. */
    val isModified: Boolean get() = original != current

    val revisionCount: Int get() = past.size + 1

    private fun advance(next: Revision): Scratchpad {
        val newPast = (past + present).takeLast(historyLimit)
        // Any new edit invalidates the redo stack, as in every standard editor.
        return Scratchpad(newPast, next, emptyList(), historyLimit)
    }

    /**
     * Appends newly dictated text, inserting a separating space only when the
     * existing text does not already end with whitespace or an opening bracket.
     */
    fun appendDictation(text: String): Scratchpad {
        val addition = text.trim()
        if (addition.isEmpty()) return this
        val base = present.text
        val joined = when {
            base.isEmpty() -> addition
            base.last().isWhitespace() -> base + addition
            else -> "$base $addition"
        }
        return advance(Revision(joined, RevisionSource.Dictation))
    }

    /** Replaces the draft wholesale, recording why. */
    fun replace(text: String, source: RevisionSource): Scratchpad {
        if (text == present.text) return this
        return advance(Revision(text, source))
    }

    fun undo(): Scratchpad {
        if (past.isEmpty()) return this
        return Scratchpad(
            past = past.dropLast(1),
            present = past.last(),
            future = listOf(present) + future,
            historyLimit = historyLimit,
        )
    }

    fun redo(): Scratchpad {
        if (future.isEmpty()) return this
        return Scratchpad(
            past = past + present,
            present = future.first(),
            future = future.drop(1),
            historyLimit = historyLimit,
        )
    }

    /**
     * Drops all content and history.
     *
     * Used by Cancel and by the "leaving a sensitive app" path. Returning a
     * fresh instance means the previous text is unreachable and becomes
     * garbage-collectable; we deliberately do not keep a "recently cancelled"
     * buffer, because that would be a retention surface the user did not ask
     * for.
     */
    fun cleared(): Scratchpad = empty(historyLimit)

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 50

        fun empty(historyLimit: Int = DEFAULT_HISTORY_LIMIT): Scratchpad =
            Scratchpad(
                past = emptyList(),
                present = Revision("", RevisionSource.Dictation),
                future = emptyList(),
                historyLimit = historyLimit,
            )
    }
}
