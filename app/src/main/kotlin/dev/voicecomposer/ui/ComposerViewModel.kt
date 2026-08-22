package dev.voicecomposer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.voicecomposer.commands.CommandParser
import dev.voicecomposer.core.PipelineDescriptor
import dev.voicecomposer.core.RevisionSource
import dev.voicecomposer.core.Scratchpad
import dev.voicecomposer.refinement.RefinementProvider
import dev.voicecomposer.refinement.RefinementRequest
import dev.voicecomposer.refinement.RefinementResult
import dev.voicecomposer.refinement.local.CommandRouter
import dev.voicecomposer.refinement.local.DeterministicRefinementProvider
import dev.voicecomposer.refinement.local.MeaningPreservationCheck
import dev.voicecomposer.refinement.local.RouterOutcome
import dev.voicecomposer.security.SafeLog
import dev.voicecomposer.speech.TranscriptionEvent
import dev.voicecomposer.speech.TranscriptionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the composer is in the speak -> refine -> preview -> commit cycle. */
enum class ComposerPhase { IDLE, LISTENING, PROCESSING, READY }

data class ComposerUiState(
    val phase: ComposerPhase = ComposerPhase.IDLE,
    val original: String = "",
    val current: String = "",
    /** Live partial transcription. Never leaves the app. */
    val partial: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isModified: Boolean = false,
    /** Set when a voice command needs a tap to proceed (section 25). */
    val pendingConfirmation: PendingConfirmation? = null,
    val error: String? = null,
    val pipeline: PipelineDescriptor? = null,
    val showDiff: Boolean = false,
) {
    val hasContent: Boolean get() = current.isNotBlank()
}

data class PendingConfirmation(val action: String, val prompt: String)

/**
 * Drives the composer.
 *
 * The invariant this class exists to hold: the draft lives here, in app memory,
 * and the only methods that can send it anywhere else - [copyToClipboard] and
 * [commitInsert] - are called from tap handlers, never from the transcription
 * or command paths.
 */
class ComposerViewModel(
    private val transcription: TranscriptionProvider,
    private val semanticRefinement: RefinementProvider?,
    private val deterministicRefinement: DeterministicRefinementProvider =
        DeterministicRefinementProvider(),
    private val parser: CommandParser = CommandParser(),
    private val router: CommandRouter = CommandRouter(),
    private val onCopy: (String) -> Unit = {},
    private val onInsert: (String) -> Unit = {},
) : ViewModel() {

    private var scratchpad = Scratchpad.empty()

    private val _state = MutableStateFlow(ComposerUiState())
    val state: StateFlow<ComposerUiState> = _state.asStateFlow()

    init {
        refreshPipeline()
    }

    private fun refreshPipeline() {
        val refinementCaps = (semanticRefinement ?: deterministicRefinement).capabilities
        _state.update {
            it.copy(
                pipeline = PipelineDescriptor(
                    transcription = transcription.capabilities,
                    refinement = refinementCaps,
                ),
            )
        }
    }

    fun startListening() {
        if (_state.value.phase == ComposerPhase.LISTENING) return
        _state.update { it.copy(phase = ComposerPhase.LISTENING, error = null, partial = "") }

        viewModelScope.launch {
            transcription.start { event ->
                when (event) {
                    is TranscriptionEvent.Started -> Unit

                    is TranscriptionEvent.Partial ->
                        _state.update { it.copy(partial = event.chunk.text) }

                    is TranscriptionEvent.Final -> {
                        _state.update { it.copy(partial = "") }
                        handleUtterance(event.chunk.text)
                    }

                    is TranscriptionEvent.Failed -> {
                        SafeLog.warn(
                            TAG,
                            "transcription_failed",
                            mapOf("error" to event.error::class.java.simpleName),
                        )
                        _state.update {
                            it.copy(
                                phase = if (it.hasContent) ComposerPhase.READY else ComposerPhase.IDLE,
                                partial = "",
                                error = event.error.userMessage(),
                            )
                        }
                    }

                    is TranscriptionEvent.Cancelled ->
                        _state.update { it.copy(phase = ComposerPhase.IDLE, partial = "") }
                }
            }
        }
    }

    fun stopListening() {
        viewModelScope.launch { transcription.stop() }
    }

    fun cancelListening() {
        viewModelScope.launch {
            transcription.cancel()
            _state.update { it.copy(phase = ComposerPhase.IDLE, partial = "") }
        }
    }

    /**
     * The heart of the product: a finished utterance is split into text and an
     * optional command, and only ever lands in the private scratchpad.
     */
    private fun handleUtterance(utterance: String) {
        val parsed = parser.parse(utterance)

        if (parsed.literalText.isNotBlank()) {
            scratchpad = scratchpad.appendDictation(parsed.literalText)
        }

        val command = parsed.command
        if (command == null) {
            publish(ComposerPhase.READY)
            return
        }

        when (val outcome = router.route(scratchpad, command)) {
            is RouterOutcome.Applied -> {
                scratchpad = outcome.scratchpad
                SafeLog.info(TAG, "command_applied", mapOf("action" to outcome.action))
                publish(ComposerPhase.READY)
            }

            is RouterOutcome.NeedsRefinement -> runRefinement(outcome)

            is RouterOutcome.AwaitingConfirmation -> {
                // Nothing happens yet. The UI highlights the button; the user taps.
                _state.update {
                    it.copy(pendingConfirmation = PendingConfirmation(outcome.action, outcome.prompt))
                }
                publish(ComposerPhase.READY)
            }

            is RouterOutcome.TreatAsText -> {
                scratchpad = scratchpad.appendDictation(outcome.text)
                publish(ComposerPhase.READY)
            }

            is RouterOutcome.Rejected -> {
                SafeLog.warn(TAG, "command_rejected", mapOf("reason" to outcome.reason))
                publish(ComposerPhase.READY)
            }
        }
    }

    private fun runRefinement(request: RouterOutcome.NeedsRefinement) {
        publish(ComposerPhase.PROCESSING)

        viewModelScope.launch {
            val refinementRequest = RefinementRequest(
                text = request.text,
                instruction = request.instruction,
                kind = request.kind,
                targetLanguage = request.targetLanguage,
            )

            // Deterministic first: never pay a model for a structural edit.
            val provider: RefinementProvider =
                if (deterministicRefinement.supports(refinementRequest)) {
                    deterministicRefinement
                } else {
                    semanticRefinement ?: run {
                        _state.update {
                            it.copy(
                                phase = ComposerPhase.READY,
                                error = "No refinement provider is configured for that. " +
                                    "Choose one in Settings, or edit the draft directly.",
                            )
                        }
                        return@launch
                    }
                }

            when (val result = provider.refine(refinementRequest)) {
                is RefinementResult.Success -> {
                    val checked = MeaningPreservationCheck.evaluate(
                        original = request.text,
                        rewritten = result.text,
                        instructionWasSummarise = request.kind == "SUMMARIZE",
                    )
                    when (checked) {
                        is RefinementResult.Success -> {
                            scratchpad = scratchpad.replace(
                                checked.text,
                                RevisionSource.Refinement(result.providerId, request.kind),
                            )
                            publish(ComposerPhase.READY)
                        }

                        is RefinementResult.Failed -> {
                            SafeLog.warn(
                                TAG,
                                "refinement_output_rejected",
                                mapOf("error" to checked.error::class.java.simpleName),
                            )
                            _state.update {
                                it.copy(
                                    phase = ComposerPhase.READY,
                                    error = "That rewrite looked wrong, so it was discarded. " +
                                        "Your draft is unchanged.",
                                )
                            }
                        }
                    }
                }

                is RefinementResult.Failed -> {
                    _state.update {
                        it.copy(phase = ComposerPhase.READY, error = result.error.userMessage())
                    }
                }
            }
        }
    }

    // -- explicit user actions ---------------------------------------------

    fun editDraft(text: String) {
        scratchpad = scratchpad.replace(text, RevisionSource.ManualEdit)
        publish(_state.value.phase)
    }

    fun undo() {
        scratchpad = scratchpad.undo()
        publish(_state.value.phase)
    }

    fun redo() {
        scratchpad = scratchpad.redo()
        publish(_state.value.phase)
    }

    fun toggleDiff() = _state.update { it.copy(showDiff = !it.showDiff) }

    fun dismissConfirmation() = _state.update { it.copy(pendingConfirmation = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Called only from a tap. */
    fun copyToClipboard() {
        val text = scratchpad.current
        if (text.isBlank()) return
        onCopy(text)
        SafeLog.info(TAG, "copied_to_clipboard", mapOf("chars" to text.length))
        dismissConfirmation()
    }

    /** Called only from a tap. */
    fun commitInsert() {
        val text = scratchpad.current
        if (text.isBlank()) return
        onInsert(text)
        SafeLog.info(TAG, "inserted", mapOf("chars" to text.length))
        dismissConfirmation()
    }

    /** Discards everything, including undo history. */
    fun cancelAll() {
        scratchpad = scratchpad.cleared()
        _state.update {
            ComposerUiState(pipeline = it.pipeline)
        }
    }

    private fun publish(phase: ComposerPhase) {
        _state.update {
            it.copy(
                phase = phase,
                original = scratchpad.original,
                current = scratchpad.current,
                canUndo = scratchpad.canUndo,
                canRedo = scratchpad.canRedo,
                isModified = scratchpad.isModified,
            )
        }
    }

    companion object {
        private const val TAG = "ComposerVM"
    }
}

private fun dev.voicecomposer.speech.TranscriptionError.userMessage(): String = when (this) {
    is dev.voicecomposer.speech.TranscriptionError.PermissionDenied ->
        "Microphone permission is required to dictate."
    is dev.voicecomposer.speech.TranscriptionError.NoNetwork ->
        "The selected speech backend needs a network connection."
    is dev.voicecomposer.speech.TranscriptionError.ModelUnavailable ->
        "The selected speech model is not available. Choose another in Settings."
    is dev.voicecomposer.speech.TranscriptionError.ModelCorrupt ->
        "The speech model failed its integrity check and was not loaded."
    is dev.voicecomposer.speech.TranscriptionError.OutOfMemory ->
        "Not enough memory for this model. Try a smaller one."
    is dev.voicecomposer.speech.TranscriptionError.NoSpeechDetected ->
        "No speech was detected."
    is dev.voicecomposer.speech.TranscriptionError.Backend ->
        "Speech recognition failed."
}

private fun dev.voicecomposer.refinement.RefinementError.userMessage(): String = when (this) {
    is dev.voicecomposer.refinement.RefinementError.Unavailable ->
        "That refinement provider is not available."
    is dev.voicecomposer.refinement.RefinementError.NoNetwork ->
        "No network connection."
    is dev.voicecomposer.refinement.RefinementError.ContextTooLong ->
        "The draft is too long for this model."
    is dev.voicecomposer.refinement.RefinementError.CredentialMissing ->
        "No API key is configured for that provider."
    is dev.voicecomposer.refinement.RefinementError.Backend ->
        "The refinement provider returned an error."
    is dev.voicecomposer.refinement.RefinementError.RejectedOutput ->
        "That rewrite looked wrong, so it was discarded."
}
