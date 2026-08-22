package dev.voicecomposer.models

/**
 * The models offered in the Model Manager.
 *
 * ## Why the checksums are not filled in here
 *
 * A checksum is only worth anything if it was computed from an artifact the
 * publisher actually published. This source tree was produced in an environment
 * with no access to the model hosts, so the honest options were to ship
 * checksums that had never been verified, or to ship none and make the app
 * refuse to download until they are pinned. We chose the latter: a
 * plausible-looking but unverified hash is worse than an absent one, because it
 * looks like a guarantee.
 *
 * `tools/pin-models.sh` downloads each artifact from its canonical URL, prints
 * the SHA-256, and rewrites this file. [ModelDownloadGuard] refuses any model
 * still carrying [UNPINNED], so the shipped app cannot silently download an
 * unverified blob.
 *
 * Sizes and RAM figures below are the publishers' own published figures, not
 * measurements taken by this project. See docs/MODEL_COMPARISON.md, which
 * distinguishes the two.
 */
object CandidateModels {

    /** Sentinel meaning "no verified checksum is known for this artifact yet". */
    const val UNPINNED = "UNPINNED"

    val whisperTinyEn = ModelDescriptor(
        id = "whisper-tiny-en-q5_1",
        displayName = "Local Fast (Whisper tiny.en)",
        version = "ggml-tiny.en-q5_1",
        publisher = "ggml-org / whisper.cpp",
        sourceUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin",
        license = "MIT (whisper.cpp), MIT (OpenAI Whisper weights)",
        expectedSizeBytes = 32_000_000,
        sha256 = UNPINNED,
        tier = ModelTier.FAST,
        languages = listOf("en"),
        supportsStreaming = false,
        approximateRuntimeRamBytes = 400_000_000,
        runtime = ModelRuntime.WHISPER_CPP,
    )

    val whisperBaseEn = ModelDescriptor(
        id = "whisper-base-en-q5_1",
        displayName = "Local Balanced (Whisper base.en)",
        version = "ggml-base.en-q5_1",
        publisher = "ggml-org / whisper.cpp",
        sourceUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
        license = "MIT (whisper.cpp), MIT (OpenAI Whisper weights)",
        expectedSizeBytes = 60_000_000,
        sha256 = UNPINNED,
        tier = ModelTier.BALANCED,
        languages = listOf("en"),
        supportsStreaming = false,
        approximateRuntimeRamBytes = 500_000_000,
        runtime = ModelRuntime.WHISPER_CPP,
    )

    val whisperSmall = ModelDescriptor(
        id = "whisper-small-q5_1",
        displayName = "Local High Accuracy (Whisper small, multilingual)",
        version = "ggml-small-q5_1",
        publisher = "ggml-org / whisper.cpp",
        sourceUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        license = "MIT (whisper.cpp), MIT (OpenAI Whisper weights)",
        expectedSizeBytes = 190_000_000,
        sha256 = UNPINNED,
        tier = ModelTier.HIGH_ACCURACY,
        // Multilingual: relevant for the Hindi/Telugu code-switching the brief asks about.
        languages = listOf("en", "hi", "te", "multilingual"),
        supportsStreaming = false,
        approximateRuntimeRamBytes = 1_000_000_000,
        runtime = ModelRuntime.WHISPER_CPP,
    )

    /**
     * Streaming alternative. Whisper is not a streaming architecture, so
     * partial results during a long dictation need a transducer model.
     */
    val sherpaStreamingEn = ModelDescriptor(
        id = "sherpa-zipformer-streaming-en",
        displayName = "Local Streaming (Zipformer transducer, English)",
        version = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
        publisher = "k2-fsa / sherpa-onnx",
        sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        license = "Apache-2.0",
        expectedSizeBytes = 350_000_000,
        sha256 = UNPINNED,
        tier = ModelTier.BALANCED,
        languages = listOf("en"),
        supportsStreaming = true,
        approximateRuntimeRamBytes = 400_000_000,
        runtime = ModelRuntime.SHERPA_ONNX,
    )

    val all: List<ModelDescriptor> = listOf(
        whisperTinyEn,
        whisperBaseEn,
        whisperSmall,
        sherpaStreamingEn,
    )

    fun byId(id: String): ModelDescriptor? = all.firstOrNull { it.id == id }
}

/** Why a download was refused before any network request was made. */
sealed interface DownloadPermission {
    data object Allowed : DownloadPermission
    data class Refused(val reason: String) : DownloadPermission

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * The gate every model download passes through.
 *
 * Enforces three of the brief's requirements in one place: no silent downloads
 * (section 7), verified integrity (section 8), and no unverified blob ever
 * being fetched.
 */
object ModelDownloadGuard {

    fun check(
        descriptor: ModelDescriptor,
        userApproved: Boolean,
        availableStorageBytes: Long,
    ): DownloadPermission {
        if (descriptor.sha256 == CandidateModels.UNPINNED || descriptor.sha256.isBlank()) {
            return DownloadPermission.Refused("checksum_not_pinned")
        }
        if (!SHA256_HEX.matches(descriptor.sha256)) {
            return DownloadPermission.Refused("checksum_malformed")
        }
        if (!descriptor.sourceUrl.startsWith("https://")) {
            return DownloadPermission.Refused("source_not_https")
        }
        // Explicit approval per download, never a blanket setting.
        if (!userApproved) {
            return DownloadPermission.Refused("user_approval_required")
        }
        // Require headroom for the download plus the unpacked file.
        val needed = descriptor.expectedSizeBytes * 2
        if (availableStorageBytes < needed) {
            return DownloadPermission.Refused("insufficient_storage")
        }
        return DownloadPermission.Allowed
    }

    private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
}
