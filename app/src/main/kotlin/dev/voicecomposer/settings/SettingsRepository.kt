package dev.voicecomposer.settings

import android.content.Context
import dev.voicecomposer.commands.CommandParser

enum class TranscriptionBackend { ANDROID_ON_DEVICE, ANDROID_DEFAULT, LOCAL_MODEL, OPENAI }

enum class RefinementBackend { DETERMINISTIC_ONLY, DEVICE_AI, OPENAI }

enum class ClipboardAutoClear(val seconds: Int) {
    OFF(0),
    THIRTY_SECONDS(30),
    ONE_MINUTE(60),
    FIVE_MINUTES(300),
}

/**
 * User settings.
 *
 * ## Defaults
 *
 * Every default here is the private one (product brief section 35). Concretely:
 * no history, no analytics, no crash text, no raw audio retention, preview
 * required, sensitive-app protection on, cloud fallback off. The safe choice is
 * what the user gets without touching anything.
 *
 * Backed by plain SharedPreferences: this file holds no secrets - credentials
 * live in [dev.voicecomposer.security.CredentialStore] - and it is excluded
 * from backup along with the rest.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // -- Speech -----------------------------------------------------------

    var transcriptionBackend: TranscriptionBackend
        get() = enumOr(KEY_TRANSCRIPTION, TranscriptionBackend.ANDROID_ON_DEVICE)
        set(value) = prefs.edit().putString(KEY_TRANSCRIPTION, value.name).apply()

    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE, "en-IN") ?: "en-IN"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    // -- Refinement -------------------------------------------------------

    var refinementBackend: RefinementBackend
        get() = enumOr(KEY_REFINEMENT, RefinementBackend.DETERMINISTIC_ONLY)
        set(value) = prefs.edit().putString(KEY_REFINEMENT, value.name).apply()

    var openAiRefinementModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value).apply()

    // -- Commands ---------------------------------------------------------

    var activationPhrase: String
        get() = prefs.getString(KEY_ACTIVATION, CommandParser.DEFAULT_ACTIVATION_PHRASE)
            ?: CommandParser.DEFAULT_ACTIVATION_PHRASE
        set(value) = prefs.edit().putString(KEY_ACTIVATION, value).apply()

    var aggressiveFillerRemoval: Boolean
        get() = prefs.getBoolean(KEY_AGGRESSIVE_FILLERS, false)
        set(value) = prefs.edit().putBoolean(KEY_AGGRESSIVE_FILLERS, value).apply()

    // -- System integration ----------------------------------------------

    var sensitiveAppProtection: Boolean
        get() = prefs.getBoolean(KEY_SENSITIVE_APPS, true)
        set(value) = prefs.edit().putBoolean(KEY_SENSITIVE_APPS, value).apply()

    var blockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, value).apply()

    var allowedPackages: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, value).apply()

    /** Flow Mode is opt-in and off until the user explicitly enables it. */
    var flowModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOW_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOW_MODE, value).apply()

    var bubbleEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUBBLE, false)
        set(value) = prefs.edit().putBoolean(KEY_BUBBLE, value).apply()

    // -- Privacy ----------------------------------------------------------

    var previewRequired: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_REQUIRED, true)
        set(value) = prefs.edit().putBoolean(KEY_PREVIEW_REQUIRED, value).apply()

    var historyEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTORY, false)
        set(value) = prefs.edit().putBoolean(KEY_HISTORY, value).apply()

    var clipboardAutoClear: ClipboardAutoClear
        get() = enumOr(KEY_CLIPBOARD_CLEAR, ClipboardAutoClear.ONE_MINUTE)
        set(value) = prefs.edit().putString(KEY_CLIPBOARD_CLEAR, value.name).apply()

    var retainRawAudio: Boolean
        get() = prefs.getBoolean(KEY_RETAIN_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_RETAIN_AUDIO, value).apply()

    var analyticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANALYTICS, false)
        set(value) = prefs.edit().putBoolean(KEY_ANALYTICS, value).apply()

    var crashReportingEnabled: Boolean
        get() = prefs.getBoolean(KEY_CRASH_REPORTS, false)
        set(value) = prefs.edit().putBoolean(KEY_CRASH_REPORTS, value).apply()

    var debugLoggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOGGING, value).apply()

    /**
     * Whether a failing local backend may silently fall back to a cloud one.
     *
     * Off, and the brief (section 37) makes this non-negotiable: a local model
     * failing must never be the reason private audio leaves the device. When
     * this is off the app reports the failure instead.
     */
    var allowAutomaticCloudFallback: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_FALLBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_FALLBACK, value).apply()

    var cloudOnWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_WIFI_ONLY, value).apply()

    /** Applies one of the brief's profiles (section 38). */
    fun applyProfile(profile: Profile) {
        when (profile) {
            Profile.MAXIMUM_PRIVACY -> {
                transcriptionBackend = TranscriptionBackend.ANDROID_ON_DEVICE
                refinementBackend = RefinementBackend.DETERMINISTIC_ONLY
                flowModeEnabled = false
                allowAutomaticCloudFallback = false
                previewRequired = true
                historyEnabled = false
                sensitiveAppProtection = true
            }

            Profile.BALANCED -> {
                transcriptionBackend = TranscriptionBackend.ANDROID_ON_DEVICE
                refinementBackend = RefinementBackend.DEVICE_AI
                allowAutomaticCloudFallback = false
                previewRequired = true
                sensitiveAppProtection = true
            }

            Profile.BEST_QUALITY -> {
                transcriptionBackend = TranscriptionBackend.OPENAI
                refinementBackend = RefinementBackend.OPENAI
                previewRequired = true
                sensitiveAppProtection = true
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(key: String, fallback: T): T {
        val raw = prefs.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    companion object {
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        private const val KEY_TRANSCRIPTION = "transcription_backend"
        private const val KEY_LANGUAGE = "language_tag"
        private const val KEY_REFINEMENT = "refinement_backend"
        private const val KEY_OPENAI_MODEL = "openai_refinement_model"
        private const val KEY_ACTIVATION = "activation_phrase"
        private const val KEY_AGGRESSIVE_FILLERS = "aggressive_fillers"
        private const val KEY_SENSITIVE_APPS = "sensitive_app_protection"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_FLOW_MODE = "flow_mode_enabled"
        private const val KEY_BUBBLE = "bubble_enabled"
        private const val KEY_PREVIEW_REQUIRED = "preview_required"
        private const val KEY_HISTORY = "history_enabled"
        private const val KEY_CLIPBOARD_CLEAR = "clipboard_auto_clear"
        private const val KEY_RETAIN_AUDIO = "retain_raw_audio"
        private const val KEY_ANALYTICS = "analytics_enabled"
        private const val KEY_CRASH_REPORTS = "crash_reporting_enabled"
        private const val KEY_DEBUG_LOGGING = "debug_logging_enabled"
        private const val KEY_CLOUD_FALLBACK = "allow_cloud_fallback"
        private const val KEY_CLOUD_WIFI_ONLY = "cloud_wifi_only"
    }
}

enum class Profile { MAXIMUM_PRIVACY, BALANCED, BEST_QUALITY }
