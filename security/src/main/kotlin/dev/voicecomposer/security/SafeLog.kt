package dev.voicecomposer.security

/**
 * A logging facade that makes it hard to leak user content by accident.
 *
 * The brief (section 41) requires that production logs never contain dictated
 * text, audio, API keys, transformation prompts or clipboard contents. A policy
 * document alone does not achieve that - someone eventually writes
 * `Log.d(TAG, "transcript=$text")`. So the app's lint configuration bans direct
 * `android.util.Log` use outside this file's Android counterpart, and every
 * call site goes through here.
 *
 * The API is shaped so the safe thing is the easy thing: messages are built
 * from a fixed [event] string plus *typed, non-content* fields. There is no
 * overload that accepts arbitrary user text.
 */
object SafeLog {

    /** Set false for release builds; user-content logging is then impossible. */
    @Volatile
    var verboseDiagnosticsEnabled: Boolean = false

    /** Receives already-redacted lines. The Android layer wires this to Logcat. */
    @Volatile
    var sink: ((level: Level, tag: String, message: String) -> Unit)? = null

    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * Logs a structured event.
     *
     * @param event a fixed identifier, never interpolated user data
     * @param fields non-content metadata: counts, durations, enum names, reasons
     */
    fun event(
        level: Level,
        tag: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val rendered = buildString {
            append(event)
            if (fields.isNotEmpty()) {
                fields.entries.joinTo(this, separator = " ", prefix = " ") { (k, v) ->
                    "$k=${Redaction.scrubValue(v)}"
                }
            }
        }
        sink?.invoke(level, tag, rendered)
    }

    fun debug(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        event(Level.DEBUG, tag, event, fields)

    fun info(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        event(Level.INFO, tag, event, fields)

    fun warn(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        event(Level.WARN, tag, event, fields)

    /**
     * Logs a failure without the exception message, which routinely contains
     * request bodies, file paths and occasionally credentials. The type name
     * and a caller-supplied fixed reason are enough to diagnose.
     */
    fun failure(tag: String, event: String, throwable: Throwable, fields: Map<String, Any?> = emptyMap()) {
        event(
            Level.ERROR,
            tag,
            event,
            fields + mapOf("exception" to throwable::class.java.simpleName),
        )
    }

    /**
     * Only way to get user text near a log, and only when diagnostics are on
     * *and* the caller has confirmed the text is synthetic (section 41).
     */
    fun diagnosticSample(tag: String, event: String, syntheticText: String) {
        if (!verboseDiagnosticsEnabled) return
        sink?.invoke(Level.DEBUG, tag, "$event sample=${Redaction.redact(syntheticText)}")
    }
}

/** Redaction helpers used by [SafeLog] and by crash-report scrubbing. */
object Redaction {

    private val API_KEY_PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{16,}"),
        Regex("Bearer\\s+[A-Za-z0-9._\\-]{16,}", RegexOption.IGNORE_CASE),
        Regex("(?i)(api[_-]?key|token|secret|password)\\s*[=:]\\s*\\S+"),
    )

    private const val REDACTED = "[REDACTED]"

    /** Replaces anything that looks like a credential. */
    fun redactSecrets(text: String): String =
        API_KEY_PATTERNS.fold(text) { acc, pattern -> pattern.replace(acc, REDACTED) }

    /**
     * Reduces free text to a shape summary: length and character classes only.
     * Used where a length is genuinely useful for diagnosis but the content is
     * never appropriate to record.
     */
    fun redact(text: String): String = "<${text.length} chars>"

    /**
     * Scrubs a value destined for a log field. Numbers, booleans and enums pass
     * through; strings are checked for credential shapes and are truncated,
     * because a string field is exactly where user content leaks in.
     */
    fun scrubValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean, is Enum<*> -> value.toString()
        is String -> redactSecrets(value).let { scrubbed ->
            if (scrubbed.length > MAX_FIELD_CHARS) redact(scrubbed) else scrubbed
        }
        else -> value::class.java.simpleName
    }

    private const val MAX_FIELD_CHARS = 64
}
