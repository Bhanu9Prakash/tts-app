package dev.voicecomposer.models

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Why an install was refused or failed. */
sealed interface InstallResult {
    data class Installed(val root: File, val fileCount: Int) : InstallResult
    data class Rejected(val reason: String) : InstallResult

    val isInstalled: Boolean get() = this is Installed
}

/**
 * Unpacks a verified model archive into app-private storage.
 *
 * ## Why this is not just `ZipInputStream` in a loop
 *
 * A model archive is an untrusted blob from the network. The classic attack on
 * naive extraction is *zip slip*: an entry named `../../lib/libc.so` escapes the
 * target directory and overwrites something outside it. Checksum pinning makes
 * that unlikely, but "unlikely" is not the same as "impossible" - a publisher
 * account compromise would give an attacker both the file and the hash we pin
 * against - so extraction refuses to write outside the target regardless.
 *
 * It also refuses entries that would be executable-looking native libraries.
 * The brief (section 8) asks specifically that we not download native code
 * disguised as a model, and a Vosk model legitimately contains only data files.
 *
 * Pure `java.io` / `java.util.zip`, so it is unit-testable off-device.
 */
object ModelInstaller {

    /**
     * File extensions we refuse to extract. A speech model is data; if an
     * archive contains a shared object or a dex file, something is wrong and we
     * stop rather than guess.
     */
    private val FORBIDDEN_EXTENSIONS = setOf("so", "dex", "jar", "apk", "dylib", "dll", "exe", "sh")

    /** Bounds decompression so a zip bomb cannot fill the device. */
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    /**
     * Extracts [open]'s zip content into [targetDir].
     *
     * [targetDir] is created if absent and must be empty or non-existent -
     * extracting over a previous install could leave a mix of two models.
     */
    fun extractZip(targetDir: File, open: () -> InputStream): InstallResult {
        if (targetDir.exists() && targetDir.list()?.isNotEmpty() == true) {
            return InstallResult.Rejected("target_not_empty")
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return InstallResult.Rejected("cannot_create_target")
        }

        val canonicalTarget = targetDir.canonicalFile
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(open().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        return InstallResult.Rejected("too_many_entries")
                    }

                    val outFile = File(canonicalTarget, entry.name)

                    // Zip slip: the resolved path must stay inside the target.
                    val canonicalOut = outFile.canonicalFile
                    if (!canonicalOut.path.startsWith(canonicalTarget.path + File.separator) &&
                        canonicalOut.path != canonicalTarget.path
                    ) {
                        return InstallResult.Rejected("path_traversal")
                    }

                    if (entry.isDirectory) {
                        if (!canonicalOut.exists() && !canonicalOut.mkdirs()) {
                            return InstallResult.Rejected("cannot_create_dir")
                        }
                        zip.closeEntry()
                        continue
                    }

                    val extension = canonicalOut.name.substringAfterLast('.', "").lowercase()
                    if (extension in FORBIDDEN_EXTENSIONS) {
                        return InstallResult.Rejected("forbidden_entry_type_$extension")
                    }

                    canonicalOut.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            return InstallResult.Rejected("cannot_create_dir")
                        }
                    }

                    canonicalOut.outputStream().buffered().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            totalBytes += read
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                return InstallResult.Rejected("archive_too_large")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    // Model files are data. Nothing here is ever executable.
                    canonicalOut.setExecutable(false, false)
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            return InstallResult.Rejected("extract_failed_${e::class.java.simpleName}")
        }

        val fileCount = targetDir.walkTopDown().count { it.isFile }
        if (fileCount == 0) {
            targetDir.deleteRecursively()
            return InstallResult.Rejected("archive_empty")
        }
        return InstallResult.Installed(targetDir, fileCount)
    }

    /**
     * Vosk archives contain a single top-level directory. The recogniser wants
     * that directory, not its parent, so resolve it after extraction.
     */
    fun resolveModelRoot(installDir: File): File {
        val children = installDir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        val singleDir = children.singleOrNull()?.takeIf { it.isDirectory }
        return singleDir ?: installDir
    }
}
