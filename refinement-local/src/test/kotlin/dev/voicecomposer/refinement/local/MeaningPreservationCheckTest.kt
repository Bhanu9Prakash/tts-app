package dev.voicecomposer.refinement.local

import dev.voicecomposer.refinement.RefinementError
import dev.voicecomposer.refinement.RefinementResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MeaningPreservationCheckTest {

    private val original =
        "I checked Groww and Zerodha and honestly Zerodha seems better in terms of APIs " +
            "but historical options data is something I still need to investigate further."

    private fun check(rewritten: String, summarise: Boolean = false) =
        MeaningPreservationCheck.evaluate(original, rewritten, summarise)

    @Test
    fun `a reasonable rewrite is accepted`() {
        val result = check(
            "After reviewing Groww and Zerodha, Zerodha appears stronger for API access. " +
                "The remaining concern is the availability of historical options data.",
        )
        assertIs<RefinementResult.Success>(result)
    }

    @Test
    fun `empty output is rejected`() {
        val result = check("")
        val failed = assertIs<RefinementResult.Failed>(result)
        assertEquals("empty_output", (failed.error as RefinementError.RejectedOutput).reason)
    }

    @Test
    fun `a collapsed rewrite is rejected rather than shown as a draft`() {
        val failed = assertIs<RefinementResult.Failed>(check("Zerodha."))
        assertEquals("output_too_short", (failed.error as RefinementError.RejectedOutput).reason)
    }

    @Test
    fun `aggressive shortening is allowed when the user asked to summarise`() {
        val result = check("Zerodha is better for APIs.", summarise = true)
        assertIs<RefinementResult.Success>(result)
    }

    @Test
    fun `runaway expansion is rejected`() {
        val failed = assertIs<RefinementResult.Failed>(check("word ".repeat(200)))
        assertEquals("output_too_long", (failed.error as RefinementError.RejectedOutput).reason)
    }

    @Test
    fun `leaked assistant preamble is rejected`() {
        val preambles = listOf(
            "Sure, here is the rewritten version: Zerodha appears stronger for API access overall today.",
            "Here's the professional version you asked for: Zerodha appears stronger for API access.",
            "Certainly! Zerodha appears stronger for API access than Groww does for most traders.",
            "Output: Zerodha appears stronger for API access than Groww does for most traders.",
        )
        for (text in preambles) {
            val failed = assertIs<RefinementResult.Failed>(check(text), text)
            assertEquals(
                "prompt_leaked",
                (failed.error as RefinementError.RejectedOutput).reason,
                text,
            )
        }
    }

    @Test
    fun `short originals skip the ratio check so brief drafts still refine`() {
        val result = MeaningPreservationCheck.evaluate("hey there", "Hello.", false)
        assertIs<RefinementResult.Success>(result)
    }

    @Test
    fun `accepted output is trimmed`() {
        val result = check(
            "  After reviewing both brokers, Zerodha appears stronger for API access today.  ",
        )
        val success = assertIs<RefinementResult.Success>(result)
        assertTrue(!success.text.startsWith(" ") && !success.text.endsWith(" "))
    }
}
