package dev.voicecomposer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import dev.voicecomposer.commands.CommandParser
import dev.voicecomposer.refinement.OpenAiRefinementProvider
import dev.voicecomposer.refinement.RefinementProvider
import dev.voicecomposer.security.CredentialStore
import dev.voicecomposer.security.SafeLog
import dev.voicecomposer.settings.RefinementBackend
import dev.voicecomposer.settings.SettingsRepository
import dev.voicecomposer.settings.TranscriptionBackend
import dev.voicecomposer.speech.AndroidSpeechProvider
import dev.voicecomposer.speech.TranscriptionProvider

class VoiceComposerApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    lateinit var credentials: CredentialStore
        private set

    lateinit var providers: ProviderRegistry
        private set

    override fun onCreate() {
        super.onCreate()

        settings = SettingsRepository(this)
        credentials = CredentialStore(this)
        providers = ProviderRegistry(this, settings, credentials)

        installLogSink()
        createNotificationChannel()
    }

    /**
     * Routes [SafeLog] to Logcat in debug builds only.
     *
     * In release the sink stays null, so the structured events are produced and
     * discarded. Combined with SafeLog having no API that accepts free user
     * text, that is the enforcement behind "production logs contain no dictated
     * text" (product brief section 41) rather than a promise.
     */
    private fun installLogSink() {
        if (!BuildConfig.DEBUG) {
            SafeLog.sink = null
            SafeLog.verboseDiagnosticsEnabled = false
            return
        }
        SafeLog.sink = { level, tag, message ->
            when (level) {
                SafeLog.Level.DEBUG -> Log.d(tag, message)
                SafeLog.Level.INFO -> Log.i(tag, message)
                SafeLog.Level.WARN -> Log.w(tag, message)
                SafeLog.Level.ERROR -> Log.e(tag, message)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_RECORDING,
            getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_recording_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
    }
}

/**
 * Builds providers from the user's current selection.
 *
 * Deliberately constructs a fresh provider per request rather than caching:
 * changing a backend in Settings must take effect on the next dictation, and a
 * cached cloud provider outliving a switch to local would be exactly the
 * "silently switched to cloud" failure the brief forbids (section 36).
 */
class ProviderRegistry(
    private val app: Application,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
) {

    fun commandParser(): CommandParser =
        CommandParser(activationPhrase = settings.activationPhrase)

    fun transcriptionProvider(): TranscriptionProvider =
        when (settings.transcriptionBackend) {
            TranscriptionBackend.ANDROID_ON_DEVICE ->
                AndroidSpeechProvider.createOnDevice(app, settings.languageTag)
                    // Below API 31 there is no on-device guarantee. We fall back
                    // to the default recogniser, which correctly reports
                    // ProcessingLocation.UNKNOWN rather than pretending.
                    ?: AndroidSpeechProvider.createDefault(app, settings.languageTag)

            TranscriptionBackend.ANDROID_DEFAULT ->
                AndroidSpeechProvider.createDefault(app, settings.languageTag)

            TranscriptionBackend.LOCAL_MODEL ->
                // The local ASR runtime is not bundled in this build; see
                // docs/LIMITATIONS.md. Falling back keeps the app usable rather
                // than presenting a dead option.
                AndroidSpeechProvider.createOnDevice(app, settings.languageTag)
                    ?: AndroidSpeechProvider.createDefault(app, settings.languageTag)

            TranscriptionBackend.OPENAI ->
                AndroidSpeechProvider.createDefault(app, settings.languageTag)
        }

    /**
     * The semantic provider, or null when the user has chosen deterministic-only.
     * Null is a supported state: the app still runs every structural command.
     */
    fun semanticRefinementProvider(): RefinementProvider? =
        when (settings.refinementBackend) {
            RefinementBackend.DETERMINISTIC_ONLY -> null

            RefinementBackend.OPENAI ->
                if (credentials.has(CredentialStore.ALIAS_OPENAI)) {
                    OpenAiRefinementProvider(credentials, settings.openAiRefinementModel)
                } else {
                    null
                }

            RefinementBackend.DEVICE_AI ->
                // Gemini Nano / ML Kit GenAI is not wired in this build; see
                // docs/LIMITATIONS.md for why and what it would take.
                null
        }
}
