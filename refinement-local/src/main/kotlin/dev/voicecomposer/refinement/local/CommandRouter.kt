package dev.voicecomposer.refinement.local

import dev.voicecomposer.commands.CommandConfidence
import dev.voicecomposer.commands.CommandIntent
import dev.voicecomposer.commands.DeterministicAction
import dev.voicecomposer.commands.DetectedCommand
import dev.voicecomposer.core.RevisionSource
import dev.voicecomposer.core.Scratchpad

/**
 * What the app should do in response to a parsed command.
 *
 * Note that no branch here performs the action: the router decides, the caller
 * executes. That separation is what makes the "nothing escapes the scratchpad
 * without approval" rule testable.
 */
sealed interface RouterOutcome {
    /** The scratchpad was updated locally. Nothing left the device. */
    data class Applied(val scratchpad: Scratchpad, val action: String) : RouterOutcome

    /** Needs a refinement provider. The caller runs it and shows a preview. */
    data class NeedsRefinement(
        val text: String,
        val instruction: String,
        val kind: String,
        val targetLanguage: String?,
    ) : RouterOutcome

    /**
     * Recognised, but the user must tap to confirm. Used for INSERT / COPY /
     * CANCEL and for anything below HIGH confidence.
     */
    data class AwaitingConfirmation(
        val action: String,
        val prompt: String,
    ) : RouterOutcome

    /** Not a command; the words belong in the draft. */
    data class TreatAsText(val text: String) : RouterOutcome

    data class Rejected(val reason: String) : RouterOutcome
}

/**
 * The hybrid engine from section 26: deterministic where possible, a model only
 * where genuinely required.
 *
 * Pure and synchronous - it returns a decision, never performs I/O - so the
 * routing rules can be exercised exhaustively in unit tests.
 */
class CommandRouter(
    private val aggressiveFillerRemoval: Boolean = false,
) {

    fun route(scratchpad: Scratchpad, command: DetectedCommand): RouterOutcome {
        if (command.confidence == CommandConfidence.NONE) {
            return RouterOutcome.TreatAsText(command.phrase)
        }

        // The confirmation gate is checked before any dispatch, so there is no
        // path on which INSERT/COPY/CANCEL can execute directly from voice.
        if (command.requiresConfirmation) {
            val label = when (val intent = command.intent) {
                is CommandIntent.Deterministic -> intent.action.name
                is CommandIntent.Semantic -> intent.kind.name
            }
            return RouterOutcome.AwaitingConfirmation(
                action = label,
                prompt = confirmationPrompt(label, command.confidence),
            )
        }

        return when (val intent = command.intent) {
            is CommandIntent.Deterministic -> applyDeterministic(scratchpad, intent.action)
            is CommandIntent.Semantic -> RouterOutcome.NeedsRefinement(
                text = scratchpad.current,
                instruction = intent.instruction,
                kind = intent.kind.name,
                targetLanguage = intent.targetLanguage,
            )
        }
    }

    private fun applyDeterministic(
        scratchpad: Scratchpad,
        action: DeterministicAction,
    ): RouterOutcome {
        val name = action.name
        val text = scratchpad.current

        val updated: Scratchpad = when (action) {
            DeterministicAction.UNDO -> scratchpad.undo()
            DeterministicAction.REDO -> scratchpad.redo()

            DeterministicAction.DELETE_LAST_WORD ->
                scratchpad.replace(
                    DeterministicTransforms.deleteLastWord(text),
                    RevisionSource.Deterministic(name),
                )

            DeterministicAction.DELETE_LAST_SENTENCE ->
                scratchpad.replace(
                    DeterministicTransforms.deleteLastSentence(text),
                    RevisionSource.Deterministic(name),
                )

            DeterministicAction.DELETE_LAST_PARAGRAPH ->
                scratchpad.replace(
                    DeterministicTransforms.deleteLastParagraph(text),
                    RevisionSource.Deterministic(name),
                )

            DeterministicAction.NEW_LINE ->
                scratchpad.replace(
                    DeterministicTransforms.appendNewLine(text),
                    RevisionSource.Deterministic(name),
                )

            DeterministicAction.NEW_PARAGRAPH ->
                scratchpad.replace(
                    DeterministicTransforms.appendNewParagraph(text),
                    RevisionSource.Deterministic(name),
                )

            DeterministicAction.ADD_BULLETS ->
                scratchpad.replace(
                    DeterministicTransforms.toBullets(text),
                    RevisionSource.Deterministic(name),
                )

            DeterministicAction.REMOVE_BULLETS ->
                scratchpad.replace(
                    DeterministicTransforms.removeBullets(text),
                    RevisionSource.Deterministic(name),
                )

            DeterministicAction.CLEANUP ->
                scratchpad.replace(
                    DeterministicTransforms.cleanup(text, aggressiveFillerRemoval),
                    RevisionSource.Deterministic(name),
                )

            // These change UI state rather than the draft; the caller handles them.
            DeterministicAction.PREVIEW,
            DeterministicAction.CONTINUE_DICTATION,
            -> scratchpad

            // Unreachable: gated by requiresConfirmation above. Kept explicit so
            // adding a new externalising action fails loudly here rather than
            // silently acquiring a direct path.
            DeterministicAction.INSERT,
            DeterministicAction.COPY,
            DeterministicAction.CANCEL,
            -> return RouterOutcome.Rejected("externalising_action_requires_confirmation")
        }

        return RouterOutcome.Applied(updated, name)
    }

    private fun confirmationPrompt(action: String, confidence: CommandConfidence): String =
        when (confidence) {
            CommandConfidence.POSSIBLE -> "Did you mean: $action?"
            else -> "Tap to confirm: $action"
        }
}
