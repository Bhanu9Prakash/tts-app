package dev.voicecomposer.models

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelInstallerTest {

    private val tempRoot: File = createTempDir()

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "vc-installer-${System.nanoTime()}").apply { mkdirs() }

    @AfterTest
    fun cleanup() {
        tempRoot.deleteRecursively()
    }

    /** Builds an in-memory zip from entry name -> content. */
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

    private fun install(target: File, bytes: ByteArray) =
        ModelInstaller.extractZip(target) { ByteArrayInputStream(bytes) }

    @Test
    fun `a normal model archive extracts`() {
        val target = File(tempRoot, "ok")
        val result = install(
            target,
            zipOf(
                "vosk-model-small-en-us-0.15/README" to "hello",
                "vosk-model-small-en-us-0.15/am/final.mdl" to "weights",
                "vosk-model-small-en-us-0.15/conf/model.conf" to "config",
            ),
        )
        val installed = assertIs<InstallResult.Installed>(result)
        assertEquals(3, installed.fileCount)
        assertTrue(File(target, "vosk-model-small-en-us-0.15/am/final.mdl").isFile)
    }

    // -----------------------------------------------------------------------
    // Zip slip. The reason this class exists rather than a plain extract loop.
    // -----------------------------------------------------------------------

    @Test
    fun `an entry escaping the target directory is refused`() {
        val target = File(tempRoot, "slip")
        val result = install(target, zipOf("../escaped.txt" to "owned"))

        val rejected = assertIs<InstallResult.Rejected>(result)
        assertEquals("path_traversal", rejected.reason)
        assertFalse(File(tempRoot, "escaped.txt").exists(), "File escaped the target directory")
    }

    @Test
    fun `a deeply nested traversal is refused`() {
        val target = File(tempRoot, "slip2")
        val result = install(target, zipOf("model/../../../../tmp/escaped.txt" to "owned"))
        assertIs<InstallResult.Rejected>(result)
    }

    @Test
    fun `an absolute path entry cannot escape the target`() {
        // java.io.File(parent, "/abs/path") joins rather than replacing, so an
        // absolute entry name lands *inside* the target. That is already safe,
        // so this asserts containment rather than rejection - the property that
        // actually matters is that nothing is written outside the target.
        val target = File(tempRoot, "slip3")
        install(target, zipOf("/tmp/vc-absolute-escape.txt" to "owned"))

        assertFalse(
            File("/tmp/vc-absolute-escape.txt").exists(),
            "An absolute entry name must not write outside the target directory",
        )
        assertTrue(File(target, "tmp/vc-absolute-escape.txt").isFile)
    }

    // -----------------------------------------------------------------------
    // Section 8: never unpack native code disguised as a model.
    // -----------------------------------------------------------------------

    @Test
    fun `native libraries and executables are refused`() {
        val forbidden = listOf(
            "model/libevil.so",
            "model/classes.dex",
            "model/payload.apk",
            "model/tool.jar",
            "model/run.sh",
            "model/thing.dll",
        )
        for (name in forbidden) {
            val target = File(tempRoot, "forbidden-${name.hashCode()}")
            val result = install(target, zipOf(name to "binary"))
            val rejected = assertIs<InstallResult.Rejected>(result, name)
            assertTrue(
                rejected.reason.startsWith("forbidden_entry_type_"),
                "Expected a forbidden-type rejection for $name, got ${rejected.reason}",
            )
        }
    }

    @Test
    fun `ordinary model data files are allowed`() {
        val target = File(tempRoot, "allowed")
        val result = install(
            target,
            zipOf(
                "m/final.mdl" to "x",
                "m/words.txt" to "x",
                "m/graph.fst" to "x",
                "m/mfcc.conf" to "x",
            ),
        )
        assertIs<InstallResult.Installed>(result)
    }

    // -----------------------------------------------------------------------
    // Robustness.
    // -----------------------------------------------------------------------

    @Test
    fun `an empty archive is refused rather than installed`() {
        val target = File(tempRoot, "empty")
        val result = install(target, zipOf())
        val rejected = assertIs<InstallResult.Rejected>(result)
        assertEquals("archive_empty", rejected.reason)
    }

    @Test
    fun `corrupt archive data is refused and leaves nothing behind`() {
        val target = File(tempRoot, "corrupt")
        val result = install(target, "this is not a zip file at all".toByteArray())
        assertIs<InstallResult.Rejected>(result)
        assertFalse(target.exists(), "A failed extract must not leave a partial install")
    }

    @Test
    fun `extracting over a non-empty directory is refused`() {
        val target = File(tempRoot, "occupied").apply {
            mkdirs()
            File(this, "leftover.txt").writeText("previous install")
        }
        val result = install(target, zipOf("m/final.mdl" to "x"))
        val rejected = assertIs<InstallResult.Rejected>(result)
        assertEquals("target_not_empty", rejected.reason)
    }

    @Test
    fun `extracted files are not executable`() {
        val target = File(tempRoot, "perms")
        install(target, zipOf("m/final.mdl" to "x"))
        assertFalse(File(target, "m/final.mdl").canExecute())
    }

    @Test
    fun `resolveModelRoot descends into the single top-level directory`() {
        val target = File(tempRoot, "root")
        install(target, zipOf("vosk-model-small-en-us-0.15/am/final.mdl" to "x"))
        assertEquals(
            "vosk-model-small-en-us-0.15",
            ModelInstaller.resolveModelRoot(target).name,
        )
    }

    @Test
    fun `resolveModelRoot stays put when the archive is flat`() {
        val target = File(tempRoot, "flat")
        install(target, zipOf("final.mdl" to "x", "words.txt" to "y"))
        assertEquals(target.name, ModelInstaller.resolveModelRoot(target).name)
    }
}
