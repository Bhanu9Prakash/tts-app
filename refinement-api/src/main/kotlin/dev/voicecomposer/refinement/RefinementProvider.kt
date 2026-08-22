package dev.voicecomposer.refinement

import dev.voicecomposer.core.ProviderCapabilities

/** A semantic rewrite request. */
data class RefinementRequest(
    /** The current draft. */
    val text: String,
    /** The user's instruction, in their own words. */
    val instruction: String,
    /** Canonical intent, where one was recognised; null for free-form. */
    val kind: String? = null,
    val targetLanguage: String? = null,
)

sealed interface RefinementResult {
    data class Success(
        val text: String,
        val providerId: String,
    ) : RefinementResult

    data class Failed(val error: RefinementError) : RefinementResult
}

sealed interface RefinementError {
    data object Unavailable : RefinementError
    data object NoNetwork : RefinementError
    data object ContextTooLong : RefinementError
    data object CredentialMissing : RefinementError
    data class Backend(val code: String) : RefinementError
    /**
     * The provider produced output we refused to show. See
     * `MeaningPreservationCheck` - a rewrite that collapses a long draft to a
     * few words is more likely a failure than a good summary.
     */
    data class RejectedOutput(val reason: String) : RefinementError
}

/**
 * Rewrites text according to an instruction.
 *
 * The deterministic implementation satisfies this interface too, so the command
 * router can treat "make bullets" and "make this professional" uniformly even
 * though only one of them needs a model.
 */
interface RefinementProvider {

    val capabilities: ProviderCapabilities

    suspend fun isAvailable(): Boolean

    /** Whether this provider can handle the given request at all. */
    fun supports(request: RefinementRequest): Boolean

    suspend fun refine(request: RefinementRequest): RefinementResult
}
