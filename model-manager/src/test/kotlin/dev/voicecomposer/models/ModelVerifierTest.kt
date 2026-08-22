package dev.voicecomposer.models

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelVerifierTest {

    private val content = "pretend this is a model file".toByteArray()

    private fun descriptorFor(
        bytes: ByteArray,
        sha: String = ModelVerifier.sha256Hex(bytes),
    ) = ModelDescriptor(
        id = "test-model",
        displayName = "Test",
        version = "1",
        publisher = "test",
        sourceUrl = "https://example.invalid/model.bin",
        license = "MIT",
        expectedSizeBytes = bytes.size.toLong(),
        sha256 = sha,
        tier = ModelTier.FAST,
        languages = listOf("en"),
        supportsStreaming = false,
        approximateRuntimeRamBytes = 1,
        runtime = ModelRuntime.VOSK,
    )

    @Test
    fun `a matching file verifies`() {
        assertTrue(ModelVerifier.verify(descriptorFor(content), content).isValid)
    }

    @Test
    fun `a single flipped byte fails verification`() {
        val tampered = content.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val result = ModelVerifier.verify(descriptorFor(content), tampered)
        assertIs<VerificationResult.ChecksumMismatch>(result)
    }

    @Test
    fun `a truncated file fails on size before hashing`() {
        val truncated = content.copyOf(content.size - 5)
        val result = ModelVerifier.verify(descriptorFor(content), truncated)
        val mismatch = assertIs<VerificationResult.SizeMismatch>(result)
        assertEquals(content.size.toLong(), mismatch.expected)
    }

    @Test
    fun `checksum comparison is case insensitive on the expected value`() {
        val upper = ModelVerifier.sha256Hex(content).uppercase()
        assertTrue(ModelVerifier.verify(descriptorFor(content, upper), content).isValid)
    }

    @Test
    fun `streaming verification matches the in-memory result`() {
        val descriptor = descriptorFor(content)
        val result = ModelVerifier.verifyStream(
            descriptor,
            actualSizeBytes = content.size.toLong(),
        ) { ByteArrayInputStream(content) }
        assertTrue(result.isValid)
    }

    @Test
    fun `streaming verification detects tampering`() {
        val descriptor = descriptorFor(content)
        val tampered = content.copyOf().also { it[3] = 0 }
        val result = ModelVerifier.verifyStream(
            descriptor,
            actualSizeBytes = tampered.size.toLong(),
        ) { ByteArrayInputStream(tampered) }
        assertIs<VerificationResult.ChecksumMismatch>(result)
    }

    @Test
    fun `sha256 matches the known vector for the empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ModelVerifier.sha256Hex(ByteArray(0)),
        )
    }
}

class ModelDownloadGuardTest {

    private val pinned = CandidateModels.smallEnUs.copy(
        sha256 = "a".repeat(64),
    )

    @Test
    fun `download is refused without explicit user approval`() {
        val permission = ModelDownloadGuard.check(
            pinned,
            userApproved = false,
            availableStorageBytes = Long.MAX_VALUE,
        )
        val refused = assertIs<DownloadPermission.Refused>(permission)
        assertEquals("user_approval_required", refused.reason)
    }

    @Test
    fun `an approved pinned model on https with space is allowed`() {
        val permission = ModelDownloadGuard.check(
            pinned,
            userApproved = true,
            availableStorageBytes = Long.MAX_VALUE,
        )
        assertTrue(permission.isAllowed)
    }

    @Test
    fun `an unpinned model is refused before any network request is made`() {
        val unpinned = CandidateModels.all.filter { it.sha256 == CandidateModels.UNPINNED }
        for (model in unpinned) {
            val permission = ModelDownloadGuard.check(
                model,
                userApproved = true,
                availableStorageBytes = Long.MAX_VALUE,
            )
            val refused = assertIs<DownloadPermission.Refused>(permission, model.id)
            assertEquals("checksum_not_pinned", refused.reason, model.id)
        }
    }

    @Test
    fun `a malformed checksum is refused`() {
        val bad = pinned.copy(sha256 = "not-a-real-hash")
        val refused = assertIs<DownloadPermission.Refused>(
            ModelDownloadGuard.check(bad, userApproved = true, availableStorageBytes = Long.MAX_VALUE),
        )
        assertEquals("checksum_malformed", refused.reason)
    }

    @Test
    fun `a plaintext http source is refused`() {
        val insecure = pinned.copy(sourceUrl = "http://example.invalid/model.bin")
        val refused = assertIs<DownloadPermission.Refused>(
            ModelDownloadGuard.check(insecure, userApproved = true, availableStorageBytes = Long.MAX_VALUE),
        )
        assertEquals("source_not_https", refused.reason)
    }

    @Test
    fun `insufficient storage is refused with headroom for the unpacked file`() {
        val refused = assertIs<DownloadPermission.Refused>(
            ModelDownloadGuard.check(
                pinned,
                userApproved = true,
                availableStorageBytes = pinned.expectedSizeBytes,
            ),
        )
        assertEquals("insufficient_storage", refused.reason)
    }

    @Test
    fun `catalog entries are internally consistent`() {
        for (model in CandidateModels.all) {
            assertTrue(model.id.isNotBlank(), "id blank")
            assertTrue(model.sourceUrl.startsWith("https://"), "${model.id} must use https")
            assertTrue(model.license.isNotBlank(), "${model.id} must record a license")
            assertTrue(model.publisher.isNotBlank(), "${model.id} must record a publisher")
            assertTrue(model.expectedSizeBytes > 0, "${model.id} must record a size")
            assertEquals(model, CandidateModels.byId(model.id))
        }
    }

    @Test
    fun `every catalog checksum is either unpinned or well-formed`() {
        // A durable invariant: it holds before pinning and after. It catches a
        // truncated, uppercase or otherwise malformed hash being pasted in,
        // which would otherwise only surface as a failed download on a device.
        val hex = Regex("^[a-f0-9]{64}$")
        for (model in CandidateModels.all) {
            val sha = model.sha256
            assertTrue(
                sha == CandidateModels.UNPINNED || hex.matches(sha),
                "${model.id} has a checksum that is neither UNPINNED nor 64 lowercase hex chars: '$sha'",
            )
        }
    }

    @Test
    fun `recommended model follows the device language`() {
        assertEquals(CandidateModels.smallEnIn, CandidateModels.recommendedFor("en-IN"))
        assertEquals(CandidateModels.smallHi, CandidateModels.recommendedFor("hi-IN"))
        assertEquals(CandidateModels.smallEnUs, CandidateModels.recommendedFor("en-US"))
        assertEquals(CandidateModels.smallEnUs, CandidateModels.recommendedFor("de-DE"))
    }

    @Test
    fun `every catalog model uses a runtime that consumes data, not native code`() {
        // ModelInstaller refuses to unpack native libraries; this asserts the
        // catalogue only ever points at runtimes for which that is sufficient.
        for (model in CandidateModels.all) {
            assertEquals(ModelRuntime.VOSK, model.runtime, model.id)
        }
    }
}
