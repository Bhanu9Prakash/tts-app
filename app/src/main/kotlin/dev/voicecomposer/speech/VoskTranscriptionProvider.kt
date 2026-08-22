package dev.voicecomposer.speech

import dev.voicecomposer.core.ProcessingLocation
import dev.voicecomposer.core.ProviderCapabilities
import dev.voicecomposer.models.ModelDescriptor
import dev.voicecomposer.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Fully local speech recognition, using a Vosk model the user downloaded.
 *
 * This is the Tier 2B provider the product is built around: no network, no
 * subscription, no API key, and - unlike the platform recogniser - we can say
 * where the work happens because we are the ones doing it. That is why this is
 * the only transcription provider that reports
 * [ProcessingLocation.ON_DEVICE] unconditionally.
 *
 * Vosk is a streaming recogniser, so partial results arrive while the user is
 * still speaking. Those partials never leave the app: they render in the
 * scratchpad and nowhere else.
 *
 * Audio is read from [android.media.AudioRecord] straight into the recogniser
 * and discarded. **No audio buffer is ever written to disk**, which is what
 * makes the "raw audio retention: off" default in docs/PRIVACY.md structural
 * rather than a setting we honour.
 *
 * NOTE: implemented and unit-tested where it is testable off-device, but the
 * recognition path itself requires a microphone. See docs/TEST_RESULTS.md.
 */
class VoskTranscriptionProvider(
    private val descriptor: ModelDescriptor,
    private val modelRoot: File,
    private val scope: CoroutineScope,
) : TranscriptionProvider {

    private var model: Model? = null
    private var recorder: android.media.AudioRecord? = null
    private var job: Job? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var stopRequested = false

    override val capabilities: ProviderCapabilities
        get() = ProviderCapabilities(
            id = "vosk-${descriptor.id}",
            displayName = descriptor.displayName,
            // Verified: inference runs in this process, against a file on this
            // device, with no socket involved.
            processingLocation = ProcessingLocation.ON_DEVICE,
            requiresNetwork = false,
            supportsStreaming = true,
            languages = descriptor.languages,
            approximateDownloadBytes = descriptor.expectedSizeBytes,
        )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        modelRoot.isDirectory && modelRoot.list()?.isNotEmpty() == true
    }

    override suspend fun start(onEvent: (TranscriptionEvent) -> Unit) {
        cancelled = false
        stopRequested = false

        val loaded = try {
            withContext(Dispatchers.IO) { model ?: Model(modelRoot.absolutePath).also { model = it } }
        } catch (e: OutOfMemoryError) {
            onEvent(TranscriptionEvent.Failed(TranscriptionError.OutOfMemory))
            return
        } catch (e: Exception) {
            // A model that fails to load after passing checksum verification is
            // most likely incomplete rather than tampered with, but either way
            // we surface it instead of falling back to something less private.
            SafeLog.failure(TAG, "model_load_failed", e, mapOf("model" to descriptor.id))
            onEvent(TranscriptionEvent.Failed(TranscriptionError.ModelCorrupt("load_failed")))
            return
        }

        val record = try {
            createRecorder()
        } catch (e: SecurityException) {
            onEvent(TranscriptionEvent.Failed(TranscriptionError.PermissionDenied))
            return
        } catch (e: Exception) {
            onEvent(TranscriptionEvent.Failed(TranscriptionError.Backend("audio_init_failed")))
            return
        }

        if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
            record.release()
            onEvent(TranscriptionEvent.Failed(TranscriptionError.Backend("audio_uninitialised")))
            return
        }

        recorder = record
        onEvent(TranscriptionEvent.Started)

        job = scope.launch(Dispatchers.IO) {
            Recognizer(loaded, SAMPLE_RATE.toFloat()).use { recognizer ->
                val buffer = ByteArray(BUFFER_BYTES)
                record.startRecording()
                try {
                    while (isActive && !cancelled && !stopRequested) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read <= 0) continue

                        if (recognizer.acceptWaveForm(buffer, read)) {
                            val text = recognizer.result.textOrNull()
                            if (!text.isNullOrBlank()) {
                                // A completed utterance mid-stream: surface it
                                // as a partial so the draft grows as the user
                                // speaks, and keep listening.
                                onEvent(
                                    TranscriptionEvent.Partial(
                                        TranscriptionChunk(text, isFinal = false),
                                    ),
                                )
                            }
                        } else {
                            recognizer.partialResult.partialOrNull()?.let { partial ->
                                if (partial.isNotBlank()) {
                                    onEvent(
                                        TranscriptionEvent.Partial(
                                            TranscriptionChunk(partial, isFinal = false),
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    if (cancelled) {
                        onEvent(TranscriptionEvent.Cancelled)
                    } else {
                        val finalText = recognizer.finalResult.textOrNull()
                        if (finalText.isNullOrBlank()) {
                            onEvent(TranscriptionEvent.Failed(TranscriptionError.NoSpeechDetected))
                        } else {
                            onEvent(
                                TranscriptionEvent.Final(
                                    TranscriptionChunk(finalText, isFinal = true),
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    SafeLog.failure(TAG, "recognition_failed", e)
                    onEvent(TranscriptionEvent.Failed(TranscriptionError.Backend("recognition_failed")))
                } finally {
                    // The audio buffer goes out of scope here and is never
                    // persisted anywhere.
                    releaseRecorder()
                }
            }
        }
    }

    override suspend fun stop() {
        stopRequested = true
    }

    override suspend fun cancel() {
        cancelled = true
        job?.cancel()
        withContext(Dispatchers.IO) { releaseRecorder() }
    }

    /** Frees the model. Called when the provider is swapped out in Settings. */
    fun release() {
        releaseRecorder()
        model?.close()
        model = null
    }

    private fun createRecorder(): android.media.AudioRecord =
        android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            maxOf(BUFFER_BYTES * 2, minBufferBytes()),
        )

    private fun minBufferBytes(): Int =
        android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(BUFFER_BYTES)

    private fun releaseRecorder() {
        recorder?.let { rec ->
            runCatching { if (rec.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
    }

    companion object {
        private const val TAG = "VoskAsr"

        /** Vosk models in the catalogue are trained at 16 kHz. */
        const val SAMPLE_RATE = 16_000

        private const val BUFFER_BYTES = 4096

        /**
         * Vosk returns JSON. Parsing is isolated here so a malformed response
         * degrades to "no text" rather than throwing into the audio loop.
         */
        internal fun String.textOrNull(): String? =
            runCatching { JSONObject(this).optString("text").takeIf { it.isNotBlank() } }.getOrNull()

        internal fun String.partialOrNull(): String? =
            runCatching { JSONObject(this).optString("partial").takeIf { it.isNotBlank() } }.getOrNull()
    }
}
