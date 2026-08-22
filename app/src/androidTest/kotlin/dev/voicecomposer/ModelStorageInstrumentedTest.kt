package dev.voicecomposer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.voicecomposer.models.CandidateModels
import dev.voicecomposer.models.InstallResult
import dev.voicecomposer.models.ModelInstaller
import dev.voicecomposer.models.ModelRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Model storage against a real Android filesystem.
 *
 * The unit tests cover the extraction logic on a JVM temp directory; this
 * checks the parts that only exist on device: app-private `filesDir` paths,
 * the install/delete lifecycle, and that the zip-slip defence still holds
 * against Android's filesystem semantics rather than the desktop JVM's.
 */
@RunWith(AndroidJUnit4::class)
class ModelStorageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = ModelRepository(context)
    private val descriptor = CandidateModels.smallEnUs

    @After
    fun tearDown() {
        repository.delete(descriptor)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun modelsLiveInAppPrivateStorage() {
        val dir = repository.installDirFor(descriptor)
        assertTrue(
            "Models must live under filesDir, not external storage: $dir",
            dir.absolutePath.startsWith(context.filesDir.absolutePath),
        )
    }

    @Test
    fun installAndDeleteRoundTrip() {
        val installDir = repository.installDirFor(descriptor)
        installDir.deleteRecursively()

        val result = ModelInstaller.extractZip(installDir) {
            ByteArrayInputStream(
                zipOf(
                    "vosk-model-small-en-us-0.15/README" to "hello",
                    "vosk-model-small-en-us-0.15/am/final.mdl" to "weights",
                ),
            )
        }
        assertTrue("Install failed: $result", result is InstallResult.Installed)
        assertTrue(repository.isInstalled(descriptor))

        val root = repository.installedRoot(descriptor)
        assertEquals("vosk-model-small-en-us-0.15", root?.name)
        assertTrue(File(root, "am/final.mdl").isFile)

        assertTrue(repository.delete(descriptor))
        assertFalse(repository.isInstalled(descriptor))
    }

    @Test
    fun zipSlipIsRefusedOnDevice() {
        val installDir = repository.installDirFor(descriptor)
        installDir.deleteRecursively()

        val result = ModelInstaller.extractZip(installDir) {
            ByteArrayInputStream(zipOf("../../escaped.txt" to "owned"))
        }

        assertTrue("Expected rejection, got $result", result is InstallResult.Rejected)
        assertFalse(
            "A zip entry escaped into filesDir",
            File(context.filesDir, "escaped.txt").exists(),
        )
    }

    @Test
    fun nativeLibrariesAreRefusedOnDevice() {
        val installDir = repository.installDirFor(descriptor)
        installDir.deleteRecursively()

        val result = ModelInstaller.extractZip(installDir) {
            ByteArrayInputStream(zipOf("model/libevil.so" to "ELF"))
        }
        assertTrue(result is InstallResult.Rejected)
    }

    @Test
    fun anUnpinnedModelIsNotDownloadedEvenWhenApproved() = kotlinx.coroutines.runBlocking {
        // Every model in this build is UNPINNED, so this asserts the guard
        // actually stops the request rather than merely intending to.
        val state = repository.download(descriptor, userApproved = true) { }
        assertTrue(
            "Expected the download to be refused, got $state",
            state is dev.voicecomposer.models.ModelDownloadState.Failed,
        )
        assertFalse(repository.isInstalled(descriptor))
    }
}
