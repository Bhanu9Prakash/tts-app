package dev.voicecomposer.speech

import dev.voicecomposer.core.ProviderCapabilities

/** A chunk of recognised speech. */
data class TranscriptionChunk(
    val text: String,
    /** True once this text will not change; false for streaming partials. */
    val isFinal: Boolean,
    /** Recogniser confidence where the backend supplies one. */
    val confidence: Float? = null,
)

sealed interface TranscriptionError {
    data object PermissionDenied : TranscriptionError
    data object NoNetwork : TranscriptionError
    data object ModelUnavailable : TranscriptionError
    data class ModelCorrupt(val reason: String) : TranscriptionError
    data object OutOfMemory : TranscriptionError
    data object NoSpeechDetected : TranscriptionError
    data class Backend(val code: String) : TranscriptionError
}

sealed interface TranscriptionEvent {
    data object Started : TranscriptionEvent
    data class Partial(val chunk: TranscriptionChunk) : TranscriptionEvent
    data class Final(val chunk: TranscriptionChunk) : TranscriptionEvent
    data class Failed(val error: TranscriptionError) : TranscriptionEvent
    data object Cancelled : TranscriptionEvent
}

/**
 * Turns microphone audio into text.
 *
 * Implementations are interchangeable by design (product brief section 3): the
 * Android platform recogniser, a downloaded local model, and a BYOK cloud API
 * all satisfy this interface, and the user picks one independently of the
 * refinement provider.
 *
 * Implementations must report their [capabilities] honestly - in particular
 * `processingLocation`, which the UI renders verbatim. A provider that cannot
 * prove it runs locally must report
 * [dev.voicecomposer.core.ProcessingLocation.UNKNOWN].
 *
 * Declared with a callback rather than a Flow so this module stays free of a
 * coroutines dependency; the Android layer adapts it.
 */
interface TranscriptionProvider {

    val capabilities: ProviderCapabilities

    /** Whether this provider can run right now (model present, key set, ...). */
    suspend fun isAvailable(): Boolean

    /**
     * Begins recognition. Events are delivered until a terminal event
     * ([TranscriptionEvent.Final], [TranscriptionEvent.Failed] or
     * [TranscriptionEvent.Cancelled]).
     */
    suspend fun start(onEvent: (TranscriptionEvent) -> Unit)

    /** Stops recognition and requests a final result. */
    suspend fun stop()

    /** Abandons recognition, discarding any buffered audio and partials. */
    suspend fun cancel()
}
