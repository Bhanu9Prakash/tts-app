package dev.voicecomposer.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.voicecomposer.core.ProcessingLocation
import dev.voicecomposer.core.ProviderCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transcription via the Android platform recogniser.
 *
 * ## The honesty problem this class exists to handle
 *
 * `SpeechRecognizer.createSpeechRecognizer` gives no guarantee about where
 * recognition happens. Depending on the OEM, the installed recogniser and the
 * user's downloaded language packs, the same call may run on-device or ship
 * audio to a server, and there is no supported API that reports which. The
 * product brief (section 6) forbids calling that "offline".
 *
 * So this provider reports [ProcessingLocation.UNKNOWN] unless it was created
 * through [createOnDevice], which uses
 * `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+). That factory
 * fails rather than silently falling back to a network backend, which is
 * exactly the property that lets us claim [ProcessingLocation.ON_DEVICE]
 * honestly.
 *
 * NOTE: implemented but not device-tested - see docs/TEST_RESULTS.md. The
 * behaviour of the platform recogniser varies by device and could not be
 * exercised in the build environment used to produce this source tree.
 */
class AndroidSpeechProvider(
    private val context: Context,
    private val preferOnDevice: Boolean,
    private val languageTag: String = "en-IN",
) : TranscriptionProvider {

    private var recognizer: SpeechRecognizer? = null
    private var cancelled = false

    /** True when we are using the API that contractually forbids a cloud fallback. */
    private val onDeviceGuaranteed: Boolean
        get() = preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override val capabilities: ProviderCapabilities
        get() = ProviderCapabilities(
            id = if (onDeviceGuaranteed) ID_ON_DEVICE else ID_DEFAULT,
            displayName = if (onDeviceGuaranteed) {
                "Android on-device speech"
            } else {
                "Android speech recognition"
            },
            processingLocation = if (onDeviceGuaranteed) {
                ProcessingLocation.ON_DEVICE
            } else {
                // The load-bearing line of this class.
                ProcessingLocation.UNKNOWN
            },
            requiresNetwork = !onDeviceGuaranteed,
            supportsStreaming = true,
            languages = listOf(languageTag),
        )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Main) {
        when {
            // isOnDeviceRecognitionAvailable only exists from API 33. Between
            // 31 and 32 the on-device factory exists but there is no way to ask
            // in advance, so we report available and let start() surface a
            // failure - which is still honest, because that factory fails
            // rather than silently using a network backend.
            onDeviceGuaranteed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

            onDeviceGuaranteed -> true

            else -> SpeechRecognizer.isRecognitionAvailable(context)
        }
    }

    override suspend fun start(onEvent: (TranscriptionEvent) -> Unit) {
        withContext(Dispatchers.Main) {
            cancelled = false
            val created = try {
                if (onDeviceGuaranteed) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Exception) {
                onEvent(TranscriptionEvent.Failed(TranscriptionError.ModelUnavailable))
                return@withContext
            }

            recognizer = created
            created.setRecognitionListener(listener(onEvent))

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Ask for punctuation where the recogniser supports it. Ignored
                // where it does not; the deterministic cleanup pass is the
                // fallback.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                }
                if (onDeviceGuaranteed) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            onEvent(TranscriptionEvent.Started)
            created.startListening(intent)
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) { recognizer?.stopListening() }
    }

    override suspend fun cancel() {
        withContext(Dispatchers.Main) {
            cancelled = true
            recognizer?.cancel()
            release()
        }
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun listener(emit: (TranscriptionEvent) -> Unit) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (cancelled) return
            val text = partialResults.firstResult() ?: return
            // Partials stay inside the app. They are shown in the scratchpad
            // only; nothing is sent to any other app at this stage.
            emit(TranscriptionEvent.Partial(TranscriptionChunk(text, isFinal = false)))
        }

        override fun onResults(results: Bundle?) {
            if (cancelled) {
                emit(TranscriptionEvent.Cancelled)
                release()
                return
            }
            val text = results.firstResult()
            if (text.isNullOrBlank()) {
                emit(TranscriptionEvent.Failed(TranscriptionError.NoSpeechDetected))
            } else {
                emit(TranscriptionEvent.Final(TranscriptionChunk(text, isFinal = true)))
            }
            release()
        }

        override fun onError(error: Int) {
            if (cancelled) {
                emit(TranscriptionEvent.Cancelled)
                release()
                return
            }
            emit(TranscriptionEvent.Failed(error.toTranscriptionError()))
            release()
        }
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun Int.toTranscriptionError(): TranscriptionError = when (this) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> TranscriptionError.PermissionDenied
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> TranscriptionError.NoNetwork

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> TranscriptionError.NoSpeechDetected

        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        -> TranscriptionError.ModelUnavailable

        // The numeric code is safe to log: it carries no user content.
        else -> TranscriptionError.Backend("speech_recognizer_$this")
    }

    companion object {
        const val ID_DEFAULT = "android-speech"
        const val ID_ON_DEVICE = "android-speech-on-device"

        /**
         * The only constructor that may report [ProcessingLocation.ON_DEVICE].
         * Returns null below API 31, where the guaranteeing API does not exist.
         */
        fun createOnDevice(context: Context, languageTag: String = "en-IN"): AndroidSpeechProvider? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AndroidSpeechProvider(context, preferOnDevice = true, languageTag = languageTag)
            } else {
                null
            }

        fun createDefault(context: Context, languageTag: String = "en-IN"): AndroidSpeechProvider =
            AndroidSpeechProvider(context, preferOnDevice = false, languageTag = languageTag)
    }
}
