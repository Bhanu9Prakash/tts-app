package dev.voicecomposer.security

/**
 * The signals Android gives us about a focused input field.
 *
 * Deliberately modelled as plain data rather than Android types so the policy
 * that consumes it is unit-testable. The Android layer maps
 * `AccessibilityNodeInfo` / `EditorInfo` onto this and nothing else - in
 * particular it never copies the field's *contents* into this structure.
 */
data class FieldSignals(
    /** From `InputType`: any of the password variants. */
    val isPassword: Boolean = false,
    /** Numeric field flagged as a password (PIN pads). */
    val isNumericPassword: Boolean = false,
    /** `InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`. */
    val isVisiblePassword: Boolean = false,
    /** Autofill hints declared by the app, lowercased. */
    val autofillHints: Set<String> = emptySet(),
    /** `AccessibilityNodeInfo.isPassword`. */
    val accessibilityPassword: Boolean = false,
    /** IME action/flags suggesting a one-time code. */
    val imeOptionsSuggestOtp: Boolean = false,
    /** Content description or hint text, used only for OTP heuristics. */
    val hintText: String? = null,
    val isEditable: Boolean = true,
)

/** What the app is permitted to do for the currently focused field. */
enum class FieldDecision {
    /** Normal operation: bubble may show, insertion is permitted after approval. */
    ALLOW,

    /**
     * The field is or may be sensitive. The bubble hides and automatic
     * insertion is refused. The user can still dictate into the app's own
     * composer and paste manually if they genuinely want to.
     */
    BLOCK,
}

data class FieldEvaluation(
    val decision: FieldDecision,
    /** Machine-readable reason, safe to log. Never contains field content. */
    val reason: String,
)

/**
 * Decides whether Voice Composer may interact with a focused field.
 *
 * ## Honest limits
 *
 * Android does not offer a reliable, universal "this field is sensitive" bit.
 * `isPassword` and the password `InputType` variants are reliable *when the app
 * sets them*, and autofill hints are reliable *when the app declares them* -
 * but an app is free to collect a PIN, an OTP or a card number in a plain
 * `TYPE_CLASS_NUMBER` field with no hints at all, and many do. The heuristics
 * below therefore reduce risk; they cannot eliminate it, and
 * docs/THREAT_MODEL.md states the residual risk rather than claiming coverage.
 *
 * The design consequence is that detection is *not* the only defence. The
 * primary defence is architectural: in Safe Mode the app never reads or writes
 * other apps' fields at all, so field classification is irrelevant there.
 */
object SensitiveFieldPolicy {

    /** Autofill hints that always block. Standard AndroidX constants, lowercased. */
    private val BLOCKING_AUTOFILL_HINTS = setOf(
        "password",
        "newpassword",
        "newusername",
        "smsotpcode",
        "creditcardnumber",
        "creditcardsecuritycode",
        "creditcardexpirationdate",
        "creditcardexpirationday",
        "creditcardexpirationmonth",
        "creditcardexpirationyear",
    )

    /**
     * Hint-text cues for one-time codes. Matched case-insensitively on word
     * boundaries. This is a heuristic and is documented as such.
     */
    private val OTP_HINT_PATTERNS = listOf(
        Regex("\\botp\\b", RegexOption.IGNORE_CASE),
        Regex("\\bone[- ]?time (code|password|pin)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bverification code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsecurity code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bauthentication code\\b", RegexOption.IGNORE_CASE),
        Regex("\\b2fa\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcvv\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcvc\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpin\\b", RegexOption.IGNORE_CASE),
    )

    fun evaluate(signals: FieldSignals): FieldEvaluation {
        if (!signals.isEditable) {
            return FieldEvaluation(FieldDecision.BLOCK, "field_not_editable")
        }
        if (signals.isPassword) {
            return FieldEvaluation(FieldDecision.BLOCK, "input_type_password")
        }
        if (signals.accessibilityPassword) {
            return FieldEvaluation(FieldDecision.BLOCK, "accessibility_password_flag")
        }
        if (signals.isNumericPassword) {
            return FieldEvaluation(FieldDecision.BLOCK, "input_type_numeric_password")
        }
        if (signals.isVisiblePassword) {
            return FieldEvaluation(FieldDecision.BLOCK, "input_type_visible_password")
        }

        val normalizedHints = signals.autofillHints.map { it.lowercase().replace("_", "") }.toSet()
        val blockingHint = normalizedHints.firstOrNull { it in BLOCKING_AUTOFILL_HINTS }
        if (blockingHint != null) {
            return FieldEvaluation(FieldDecision.BLOCK, "autofill_hint_$blockingHint")
        }

        if (signals.imeOptionsSuggestOtp) {
            return FieldEvaluation(FieldDecision.BLOCK, "ime_options_otp")
        }

        val hint = signals.hintText
        if (hint != null && OTP_HINT_PATTERNS.any { it.containsMatchIn(hint) }) {
            // Note: only the fact that a pattern matched is recorded, never the
            // hint text itself.
            return FieldEvaluation(FieldDecision.BLOCK, "hint_matches_otp_pattern")
        }

        return FieldEvaluation(FieldDecision.ALLOW, "no_sensitive_signal")
    }

    fun isBlocked(signals: FieldSignals): Boolean =
        evaluate(signals).decision == FieldDecision.BLOCK
}
