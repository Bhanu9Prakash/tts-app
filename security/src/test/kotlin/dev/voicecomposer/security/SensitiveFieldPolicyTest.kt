package dev.voicecomposer.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveFieldPolicyTest {

    @Test
    fun `password input type is blocked`() {
        assertTrue(SensitiveFieldPolicy.isBlocked(FieldSignals(isPassword = true)))
    }

    @Test
    fun `accessibility password flag is blocked`() {
        assertTrue(SensitiveFieldPolicy.isBlocked(FieldSignals(accessibilityPassword = true)))
    }

    @Test
    fun `numeric password - a PIN pad - is blocked`() {
        assertTrue(SensitiveFieldPolicy.isBlocked(FieldSignals(isNumericPassword = true)))
    }

    @Test
    fun `visible password type is blocked`() {
        assertTrue(SensitiveFieldPolicy.isBlocked(FieldSignals(isVisiblePassword = true)))
    }

    @Test
    fun `credential and card autofill hints are blocked`() {
        val hints = listOf(
            "password",
            "newPassword",
            "smsOTPCode",
            "creditCardNumber",
            "creditCardSecurityCode",
            "creditCardExpirationDate",
        )
        for (hint in hints) {
            assertTrue(
                SensitiveFieldPolicy.isBlocked(FieldSignals(autofillHints = setOf(hint))),
                "Autofill hint should block: $hint",
            )
        }
    }

    @Test
    fun `autofill hint matching is case and underscore insensitive`() {
        assertTrue(SensitiveFieldPolicy.isBlocked(FieldSignals(autofillHints = setOf("CREDIT_CARD_NUMBER"))))
        assertTrue(SensitiveFieldPolicy.isBlocked(FieldSignals(autofillHints = setOf("Password"))))
    }

    @Test
    fun `OTP hint text is blocked`() {
        val hints = listOf(
            "Enter OTP",
            "One-time code",
            "One time password",
            "Verification code",
            "Enter the 6-digit security code",
            "2FA code",
            "CVV",
            "Enter your PIN",
        )
        for (hint in hints) {
            assertTrue(
                SensitiveFieldPolicy.isBlocked(FieldSignals(hintText = hint)),
                "Hint text should block: $hint",
            )
        }
    }

    @Test
    fun `ordinary message fields are allowed`() {
        val ordinary = listOf(
            "Message",
            "Type a message",
            "Subject",
            "Search",
            "Write something...",
            "Add a comment",
        )
        for (hint in ordinary) {
            assertFalse(
                SensitiveFieldPolicy.isBlocked(FieldSignals(hintText = hint)),
                "Ordinary field wrongly blocked: $hint",
            )
        }
    }

    @Test
    fun `non-editable fields are blocked`() {
        assertTrue(SensitiveFieldPolicy.isBlocked(FieldSignals(isEditable = false)))
    }

    @Test
    fun `reason codes never contain the field hint text`() {
        val secretHint = "Enter OTP sent to 9876543210"
        val evaluation = SensitiveFieldPolicy.evaluate(FieldSignals(hintText = secretHint))
        assertEquals(FieldDecision.BLOCK, evaluation.decision)
        assertFalse(
            evaluation.reason.contains("9876543210"),
            "Reason code must not carry field content into logs",
        )
        assertEquals("hint_matches_otp_pattern", evaluation.reason)
    }

    @Test
    fun `a plain field with no signals is allowed`() {
        val evaluation = SensitiveFieldPolicy.evaluate(FieldSignals())
        assertEquals(FieldDecision.ALLOW, evaluation.decision)
        assertEquals("no_sensitive_signal", evaluation.reason)
    }

    @Test
    fun `word boundary prevents false blocking on words containing pin`() {
        // "shipping", "spinning" contain "pin" as a substring but not as a word.
        assertFalse(SensitiveFieldPolicy.isBlocked(FieldSignals(hintText = "Shipping address")))
        assertFalse(SensitiveFieldPolicy.isBlocked(FieldSignals(hintText = "Spinning class notes")))
    }
}
