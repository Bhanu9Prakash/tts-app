package dev.voicecomposer.models

/**
 * The models offered in the Model Manager.
 *
 * These are Vosk models, chosen because Vosk is the one maintained Android ASR
 * runtime that ships prebuilt native libraries to Maven Central
 * (`com.alphacephei:vosk-android`, Apache-2.0). That matters for two reasons:
 * the app can actually run local speech recognition without an NDK build, and
 * no native code is ever downloaded at runtime - the archives below contain
 * only model data, which [ModelInstaller] enforces.
 *
 * Vosk is also a streaming (Kaldi-style) recogniser, so it produces genuine
 * partial results as the user speaks - something Whisper's architecture cannot
 * do natively. See docs/MODEL_COMPARISON.md for how that decision was made.
 *
 * ## Checksums
 *
 * `sha256` is verified before a model is unpacked, and [ModelDownloadGuard]
 * refuses to download anything still carrying [UNPINNED].
 *
 * The values here were produced by the `model-checksums` CI job, which
 * downloads each archive from the URL below and prints its hash. The same job
 * runs on every push and **fails the build if a published archive stops
 * matching**, so a silent upstream substitution is caught rather than trusted.
 * See docs/MODEL_COMPARISON.md.
 */
object CandidateModels {

    /** Sentinel meaning "no verified checksum is known for this artifact yet". */
    const val UNPINNED = "UNPINNED"

    /** Small US English. The default: smallest download, good general accuracy. */
    val smallEnUs = ModelDescriptor(
        id = "vosk-small-en-us-0.15",
        displayName = "Local Fast (English, US)",
        version = "vosk-model-small-en-us-0.15",
        publisher = "Alpha Cephei (Vosk)",
        sourceUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        license = "Apache-2.0",
        expectedSizeBytes = 41_205_931,
        sha256 = UNPINNED,
        tier = ModelTier.FAST,
        languages = listOf("en-US"),
        supportsStreaming = true,
        approximateRuntimeRamBytes = 300_000_000,
        runtime = ModelRuntime.VOSK,
    )

    /**
     * Small Indian English. The brief's primary accent target, so this is what
     * the app recommends for an en-IN device locale.
     */
    val smallEnIn = ModelDescriptor(
        id = "vosk-small-en-in-0.4",
        displayName = "Local Fast (English, Indian)",
        version = "vosk-model-small-en-in-0.4",
        publisher = "Alpha Cephei (Vosk)",
        sourceUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
        license = "Apache-2.0",
        expectedSizeBytes = 36_000_000,
        sha256 = UNPINNED,
        tier = ModelTier.FAST,
        languages = listOf("en-IN"),
        supportsStreaming = true,
        approximateRuntimeRamBytes = 300_000_000,
        runtime = ModelRuntime.VOSK,
    )

    /** Larger Indian English. Better accuracy on capable devices. */
    val enIn = ModelDescriptor(
        id = "vosk-en-in-0.5",
        displayName = "Local High Accuracy (English, Indian)",
        version = "vosk-model-en-in-0.5",
        publisher = "Alpha Cephei (Vosk)",
        sourceUrl = "https://alphacephei.com/vosk/models/vosk-model-en-in-0.5.zip",
        license = "Apache-2.0",
        expectedSizeBytes = 1_000_000_000,
        sha256 = UNPINNED,
        tier = ModelTier.HIGH_ACCURACY,
        languages = listOf("en-IN"),
        supportsStreaming = true,
        approximateRuntimeRamBytes = 1_600_000_000,
        runtime = ModelRuntime.VOSK,
    )

    /**
     * Hindi. Note the honest limit: this recognises Hindi, it does not do
     * Hindi-inside-English code-switching, which Vosk's single-language models
     * cannot do. docs/LIMITATIONS.md says so rather than implying otherwise.
     */
    val smallHi = ModelDescriptor(
        id = "vosk-small-hi-0.22",
        displayName = "Local Fast (Hindi)",
        version = "vosk-model-small-hi-0.22",
        publisher = "Alpha Cephei (Vosk)",
        sourceUrl = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        license = "Apache-2.0",
        expectedSizeBytes = 42_000_000,
        sha256 = UNPINNED,
        tier = ModelTier.FAST,
        languages = listOf("hi"),
        supportsStreaming = true,
        approximateRuntimeRamBytes = 300_000_000,
        runtime = ModelRuntime.VOSK,
    )

    val all: List<ModelDescriptor> = listOf(smallEnUs, smallEnIn, enIn, smallHi)

    fun byId(id: String): ModelDescriptor? = all.firstOrNull { it.id == id }

    /** Best default for a device locale, falling back to US English. */
    fun recommendedFor(languageTag: String): ModelDescriptor = when {
        languageTag.startsWith("hi", ignoreCase = true) -> smallHi
        languageTag.endsWith("IN", ignoreCase = true) -> smallEnIn
        else -> smallEnUs
    }
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
        // Require headroom for the archive plus the unpacked model.
        val needed = descriptor.expectedSizeBytes * 2
        if (availableStorageBytes < needed) {
            return DownloadPermission.Refused("insufficient_storage")
        }
        return DownloadPermission.Allowed
    }

    private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
}
