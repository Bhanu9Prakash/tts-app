package dev.voicecomposer.models

import android.content.Context
import dev.voicecomposer.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Progress and outcome of a model download, for the Model Manager UI. */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : ModelDownloadState {
        val fraction: Float get() = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else 0f
    }
    data object Verifying : ModelDownloadState
    data object Installing : ModelDownloadState
    data class Installed(val root: File) : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}

/**
 * Downloads, verifies and installs models into app-private storage.
 *
 * The order of operations is the whole point, and it is deliberately strict:
 *
 *   1. [ModelDownloadGuard] runs **before any network request**, so an unpinned
 *      or non-HTTPS model is never fetched at all.
 *   2. The archive is downloaded to a temporary file.
 *   3. The SHA-256 is verified by streaming the file from disk.
 *   4. **Only then** is it unpacked, by [ModelInstaller], which independently
 *      refuses path traversal and native code.
 *   5. The archive is deleted.
 *
 * A failure at any step deletes everything and leaves no partial install, so a
 * half-written model can never be loaded.
 *
 * Models live in `filesDir/models`, which is app-private and excluded from
 * backup (see res/xml/backup_rules.xml).
 */
class ModelRepository(private val context: Context) {

    private val modelsDir: File get() = File(context.filesDir, "models")

    fun installDirFor(descriptor: ModelDescriptor): File = File(modelsDir, descriptor.id)

    /** The directory to hand the recogniser, or null when not installed. */
    fun installedRoot(descriptor: ModelDescriptor): File? {
        val dir = installDirFor(descriptor)
        if (!dir.isDirectory) return null
        val root = ModelInstaller.resolveModelRoot(dir)
        return if (root.list()?.isNotEmpty() == true) root else null
    }

    fun isInstalled(descriptor: ModelDescriptor): Boolean = installedRoot(descriptor) != null

    fun installedModels(): List<ModelDescriptor> = CandidateModels.all.filter { isInstalled(it) }

    /** Frees the space a model occupies. Offered in Settings (brief section 8). */
    fun delete(descriptor: ModelDescriptor): Boolean {
        val deleted = installDirFor(descriptor).deleteRecursively()
        SafeLog.info(TAG, "model_deleted", mapOf("model" to descriptor.id, "ok" to deleted))
        return deleted
    }

    fun availableStorageBytes(): Long = context.filesDir.usableSpace

    /**
     * Downloads and installs [descriptor].
     *
     * @param userApproved must be true; the guard refuses otherwise, which is
     *        how "do not silently download models" is enforced rather than
     *        promised.
     */
    suspend fun download(
        descriptor: ModelDescriptor,
        userApproved: Boolean,
        onState: (ModelDownloadState) -> Unit,
    ): ModelDownloadState = withContext(Dispatchers.IO) {

        val permission = ModelDownloadGuard.check(
            descriptor,
            userApproved = userApproved,
            availableStorageBytes = availableStorageBytes(),
        )
        if (permission is DownloadPermission.Refused) {
            SafeLog.warn(TAG, "download_refused", mapOf("model" to descriptor.id, "reason" to permission.reason))
            return@withContext ModelDownloadState.Failed(permission.reason).also(onState)
        }

        modelsDir.mkdirs()
        val installDir = installDirFor(descriptor)
        installDir.deleteRecursively()

        val archive = File(modelsDir, "${descriptor.id}.download")
        archive.delete()

        try {
            // -- 1. download ------------------------------------------------
            val connection = (URL(descriptor.sourceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                connection.disconnect()
                return@withContext fail(archive, installDir, "http_$status", descriptor, onState)
            }

            val declared = connection.contentLengthLong.takeIf { it > 0 } ?: descriptor.expectedSizeBytes
            var read = 0L
            onState(ModelDownloadState.Downloading(0, declared))

            connection.inputStream.use { input ->
                archive.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastReported = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        read += n

                        // Refuse a response that overruns what we expect, so a
                        // hostile or broken endpoint cannot fill the disk.
                        if (read > descriptor.expectedSizeBytes * OVERRUN_ALLOWANCE) {
                            return@withContext fail(archive, installDir, "response_too_large", descriptor, onState)
                        }
                        if (read - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = read
                            onState(ModelDownloadState.Downloading(read, declared))
                        }
                    }
                }
            }
            connection.disconnect()

            // -- 2. verify --------------------------------------------------
            onState(ModelDownloadState.Verifying)
            val verification = ModelVerifier.verifyStream(
                descriptor,
                actualSizeBytes = archive.length(),
            ) { archive.inputStream() }

            if (!verification.isValid) {
                val reason = when (verification) {
                    is VerificationResult.ChecksumMismatch -> "checksum_mismatch"
                    is VerificationResult.SizeMismatch -> "size_mismatch"
                    else -> "verification_failed"
                }
                // Deliberately loud: this is the case that means the artifact
                // under a URL we trust is not the artifact we pinned.
                SafeLog.warn(TAG, "model_verification_failed", mapOf("model" to descriptor.id, "reason" to reason))
                return@withContext fail(archive, installDir, reason, descriptor, onState)
            }

            // -- 3. install -------------------------------------------------
            onState(ModelDownloadState.Installing)
            when (val install = ModelInstaller.extractZip(installDir) { archive.inputStream() }) {
                is InstallResult.Rejected ->
                    return@withContext fail(archive, installDir, install.reason, descriptor, onState)

                is InstallResult.Installed -> {
                    archive.delete()
                    val root = ModelInstaller.resolveModelRoot(installDir)
                    SafeLog.info(
                        TAG,
                        "model_installed",
                        mapOf("model" to descriptor.id, "files" to install.fileCount),
                    )
                    ModelDownloadState.Installed(root).also(onState)
                }
            }
        } catch (e: java.net.UnknownHostException) {
            fail(archive, installDir, "no_network", descriptor, onState)
        } catch (e: Exception) {
            SafeLog.failure(TAG, "download_failed", e, mapOf("model" to descriptor.id))
            fail(archive, installDir, "download_failed", descriptor, onState)
        }
    }

    /** Leaves nothing behind, so a partial model can never be loaded. */
    private fun fail(
        archive: File,
        installDir: File,
        reason: String,
        descriptor: ModelDescriptor,
        onState: (ModelDownloadState) -> Unit,
    ): ModelDownloadState {
        archive.delete()
        installDir.deleteRecursively()
        return ModelDownloadState.Failed(reason).also(onState)
    }

    companion object {
        private const val TAG = "ModelRepo"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val PROGRESS_STEP_BYTES = 512 * 1024L

        /** Tolerance over the declared size before we abort a download. */
        private const val OVERRUN_ALLOWANCE = 2
    }
}
