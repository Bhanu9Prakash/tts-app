package dev.voicecomposer.refinement.local

import dev.voicecomposer.core.ProcessingLocation
import dev.voicecomposer.core.ProviderCapabilities
import dev.voicecomposer.refinement.RefinementError
import dev.voicecomposer.refinement.RefinementProvider
import dev.voicecomposer.refinement.RefinementRequest
import dev.voicecomposer.refinement.RefinementResult

/**
 * The always-available refinement floor.
 *
 * Handles the structural and tidying transformations that need no model, so the
 * product still does something useful with no LLM installed, no API key and no
 * network - which is the graceful-degradation requirement in section 48.
 *
 * It deliberately declines anything semantic rather than approximating it: a
 * regex pretending to "make this professional" would produce confident
 * nonsense, and the router falls through to a real model or tells the user it
 * cannot comply.
 */
class DeterministicRefinementProvider(
    private val aggressiveFillerRemoval: Boolean = false,
) : RefinementProvider {

    override val capabilities = ProviderCapabilities(
        id = PROVIDER_ID,
        displayName = "Deterministic (on-device)",
        // Verified: this class performs pure string manipulation in-process.
        processingLocation = ProcessingLocation.ON_DEVICE,
        requiresNetwork = false,
        supportsStreaming = false,
    )

    override suspend fun isAvailable(): Boolean = true

    override fun supports(request: RefinementRequest): Boolean =
        request.kind in SUPPORTED_KINDS

    override suspend fun refine(request: RefinementRequest): RefinementResult {
        if (!supports(request)) {
            return RefinementResult.Failed(RefinementError.Unavailable)
        }
        val output = when (request.kind) {
            "ADD_BULLETS" -> DeterministicTransforms.toBullets(request.text)
            "REMOVE_BULLETS" -> DeterministicTransforms.removeBullets(request.text)
            "CLEANUP" -> DeterministicTransforms.cleanup(request.text, aggressiveFillerRemoval)
            "NEW_LINE" -> DeterministicTransforms.appendNewLine(request.text)
            "NEW_PARAGRAPH" -> DeterministicTransforms.appendNewParagraph(request.text)
            "DELETE_LAST_WORD" -> DeterministicTransforms.deleteLastWord(request.text)
            "DELETE_LAST_SENTENCE" -> DeterministicTransforms.deleteLastSentence(request.text)
            "DELETE_LAST_PARAGRAPH" -> DeterministicTransforms.deleteLastParagraph(request.text)
            else -> return RefinementResult.Failed(RefinementError.Unavailable)
        }
        return RefinementResult.Success(output, PROVIDER_ID)
    }

    companion object {
        const val PROVIDER_ID = "deterministic-local"

        private val SUPPORTED_KINDS = setOf(
            "ADD_BULLETS",
            "REMOVE_BULLETS",
            "CLEANUP",
            "NEW_LINE",
            "NEW_PARAGRAPH",
            "DELETE_LAST_WORD",
            "DELETE_LAST_SENTENCE",
            "DELETE_LAST_PARAGRAPH",
        )
    }
}

/**
 * A sanity check applied to every *semantic* rewrite before it is shown.
 *
 * Small on-device models fail in characteristic ways: they answer the
 * instruction instead of applying it, they truncate, or they return the prompt.
 * None of that is catchable by the model itself, so the router checks the shape
 * of the output and refuses obviously broken rewrites rather than presenting
 * them as a finished draft. The user still sees the original, and the preview
 * gate means a bad rewrite is never committed silently.
 */
object MeaningPreservationCheck {

    /**
     * @param instructionWasSummarise summarising legitimately shortens text, so
     *        the length floor is relaxed for it
     */
    fun evaluate(
        original: String,
        rewritten: String,
        instructionWasSummarise: Boolean,
    ): RefinementResult {
        val trimmed = rewritten.trim()

        if (trimmed.isEmpty()) {
            return RefinementResult.Failed(RefinementError.RejectedOutput("empty_output"))
        }

        val originalWords = original.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val rewrittenWords = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }

        // Only meaningful on text long enough for a ratio to mean something.
        if (originalWords >= MIN_WORDS_FOR_RATIO_CHECK) {
            val floor = if (instructionWasSummarise) SUMMARY_FLOOR else REWRITE_FLOOR
            if (rewrittenWords < originalWords * floor) {
                return RefinementResult.Failed(RefinementError.RejectedOutput("output_too_short"))
            }
            if (rewrittenWords > originalWords * EXPANSION_CEILING) {
                return RefinementResult.Failed(RefinementError.RejectedOutput("output_too_long"))
            }
        }

        // A model echoing its own instructions back is a common small-model failure.
        if (LEAKED_PROMPT_MARKERS.any { trimmed.startsWith(it, ignoreCase = true) }) {
            return RefinementResult.Failed(RefinementError.RejectedOutput("prompt_leaked"))
        }

        return RefinementResult.Success(trimmed, "checked")
    }

    private const val MIN_WORDS_FOR_RATIO_CHECK = 12
    private const val REWRITE_FLOOR = 0.35
    private const val SUMMARY_FLOOR = 0.10
    private const val EXPANSION_CEILING = 3.0

    private val LEAKED_PROMPT_MARKERS = listOf(
        "sure,",
        "sure!",
        "here is the",
        "here's the",
        "certainly",
        "i have rewritten",
        "rewritten text:",
        "as an ai",
        "output:",
    )
}
