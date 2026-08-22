package dev.voicecomposer.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {

    @Test
    fun `openai style keys are redacted`() {
        val text = "Authorization failed for sk-abcd1234efgh5678ijkl9012mnop"
        val scrubbed = Redaction.redactSecrets(text)
        assertFalse(scrubbed.contains("sk-abcd1234"))
        assertTrue(scrubbed.contains("[REDACTED]"))
    }

    @Test
    fun `bearer tokens are redacted`() {
        val scrubbed = Redaction.redactSecrets("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
        assertFalse(scrubbed.contains("eyJhbGci"))
    }

    @Test
    fun `key value credential pairs are redacted`() {
        for (line in listOf(
            "api_key=supersecretvalue",
            "apiKey: supersecretvalue",
            "token=abc123def456",
            "password: hunter2",
        )) {
            val scrubbed = Redaction.redactSecrets(line)
            assertTrue(scrubbed.contains("[REDACTED]"), "Not redacted: $line")
        }
    }

    @Test
    fun `redact reduces text to a length summary only`() {
        val dictated = "I checked Groww and Zerodha and Zerodha appears better for API access"
        val redacted = Redaction.redact(dictated)
        assertFalse(redacted.contains("Groww"))
        assertFalse(redacted.contains("Zerodha"))
        assertEquals("<${dictated.length} chars>", redacted)
    }

    @Test
    fun `long string fields are reduced rather than logged verbatim`() {
        val longUserText = "word ".repeat(50)
        val scrubbed = Redaction.scrubValue(longUserText)
        assertFalse(scrubbed.contains("word word"), "Long user text must not reach a log field")
        assertTrue(scrubbed.startsWith("<"))
    }

    @Test
    fun `short non-secret values pass through for diagnosability`() {
        assertEquals("model_missing", Redaction.scrubValue("model_missing"))
        assertEquals("42", Redaction.scrubValue(42))
        assertEquals("true", Redaction.scrubValue(true))
    }

    @Test
    fun `unknown object types are reduced to their class name`() {
        assertEquals("ScratchpadLike", Redaction.scrubValue(ScratchpadLike()))
    }

    private class ScratchpadLike

    @Test
    fun `safe log never emits dictated text through the event API`() {
        val captured = mutableListOf<String>()
        SafeLog.sink = { _, _, message -> captured += message }
        try {
            SafeLog.info(
                tag = "Test",
                event = "transcription_complete",
                fields = mapOf(
                    "chars" to 128,
                    "provider" to "whisper-base-en",
                    "durationMs" to 2400,
                ),
            )
            assertEquals(1, captured.size)
            assertTrue(captured[0].startsWith("transcription_complete"))
            assertTrue(captured[0].contains("chars=128"))
        } finally {
            SafeLog.sink = null
        }
    }

    @Test
    fun `failure logging omits the exception message`() {
        val captured = mutableListOf<String>()
        SafeLog.sink = { _, _, message -> captured += message }
        try {
            val boom = IllegalStateException("request body: my private dictated text")
            SafeLog.failure("Test", "refine_failed", boom)
            assertEquals(1, captured.size)
            assertFalse(
                captured[0].contains("private dictated text"),
                "Exception messages routinely carry user content and must not be logged",
            )
            assertTrue(captured[0].contains("IllegalStateException"))
        } finally {
            SafeLog.sink = null
        }
    }

    @Test
    fun `diagnostic samples are suppressed unless verbose diagnostics are enabled`() {
        val captured = mutableListOf<String>()
        SafeLog.sink = { _, _, message -> captured += message }
        try {
            SafeLog.verboseDiagnosticsEnabled = false
            SafeLog.diagnosticSample("Test", "sample", "synthetic text")
            assertTrue(captured.isEmpty(), "Diagnostics must be off by default")
        } finally {
            SafeLog.sink = null
            SafeLog.verboseDiagnosticsEnabled = false
        }
    }
}
