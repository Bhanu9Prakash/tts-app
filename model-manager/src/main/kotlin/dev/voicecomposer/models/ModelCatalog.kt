package dev.voicecomposer.models

import java.security.MessageDigest

/** Performance bracket shown in the Model Manager. */
enum class ModelTier { FAST, BALANCED, HIGH_ACCURACY }

/**
 * Everything we record about a downloadable model.
 *
 * The brief (section 8) requires this metadata to exist for every model and the
 * checksum to be verified before loading. All of it is baked into the app at
 * build time - the catalog is never fetched from the network, because a
 * remotely-supplied catalog would let an attacker who controls that endpoint
 * substitute both the URL and the checksum it is checked against, which would
 * make the checksum meaningless.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val publisher: String,
    /** Canonical download URL. Pinned at build time. */
    val sourceUrl: String,
    val license: String,
    val expectedSizeBytes: Long,
    /** Lowercase hex SHA-256 of the downloaded artifact. */
    val sha256: String,
    val tier: ModelTier,
    val languages: List<String>,
    val supportsStreaming: Boolean,
    /** Approximate peak RAM while loaded, for the device-suitability warning. */
    val approximateRuntimeRamBytes: Long,
    val runtime: ModelRuntime,
) {
    val approximateDownloadMb: Long get() = expectedSizeBytes / (1024 * 1024)
}

/**
 * Which inference engine loads the file.
 *
 * Recorded per model so the app refuses to hand a file to the wrong runtime,
 * and so a "model" file can never be loaded as a native library (section 8).
 */
enum class ModelRuntime { WHISPER_CPP, SHERPA_ONNX }

/** Why a candidate model file was rejected. */
sealed interface VerificationResult {
    data object Valid : VerificationResult
    data class SizeMismatch(val expected: Long, val actual: Long) : VerificationResult
    data class ChecksumMismatch(val expected: String, val actual: String) : VerificationResult
    data object Missing : VerificationResult

    val isValid: Boolean get() = this is Valid
}

/**
 * Verifies downloaded model files.
 *
 * Kept free of Android and of `java.io.File` in its core so the logic is
 * testable; the Android layer supplies bytes via the streaming overload.
 */
object ModelVerifier {

    /**
     * Verifies a model already held in memory. Used by tests and small files.
     */
    fun verify(descriptor: ModelDescriptor, content: ByteArray): VerificationResult {
        if (content.size.toLong() != descriptor.expectedSizeBytes) {
            return VerificationResult.SizeMismatch(descriptor.expectedSizeBytes, content.size.toLong())
        }
        val actual = sha256Hex(content)
        if (!constantTimeEquals(actual, descriptor.sha256.lowercase())) {
            return VerificationResult.ChecksumMismatch(descriptor.sha256.lowercase(), actual)
        }
        return VerificationResult.Valid
    }

    /**
     * Verifies a stream without holding the whole model in memory - models run
     * to gigabytes, so the in-memory overload is unusable on device.
     *
     * @param openStream opens a fresh stream over the candidate file
     */
    fun verifyStream(
        descriptor: ModelDescriptor,
        actualSizeBytes: Long,
        openStream: () -> java.io.InputStream,
    ): VerificationResult {
        if (actualSizeBytes != descriptor.expectedSizeBytes) {
            return VerificationResult.SizeMismatch(descriptor.expectedSizeBytes, actualSizeBytes)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        openStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().toHex()
        if (!constantTimeEquals(actual, descriptor.sha256.lowercase())) {
            return VerificationResult.ChecksumMismatch(descriptor.sha256.lowercase(), actual)
        }
        return VerificationResult.Valid
    }

    fun sha256Hex(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Length-independent comparison. Not strictly required for a public
     * checksum, but it costs nothing and keeps the habit consistent with the
     * credential-comparison code paths.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
