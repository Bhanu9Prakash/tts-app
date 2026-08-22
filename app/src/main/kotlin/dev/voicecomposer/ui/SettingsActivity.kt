package dev.voicecomposer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.voicecomposer.BuildConfig
import dev.voicecomposer.VoiceComposerApp
import dev.voicecomposer.settings.ClipboardAutoClear
import dev.voicecomposer.settings.RefinementBackend
import dev.voicecomposer.settings.SettingsRepository
import dev.voicecomposer.settings.TranscriptionBackend

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as VoiceComposerApp).settings
        setContent {
            MaterialTheme { SettingsScreen(settings) }
        }
    }
}

@Composable
private fun SettingsScreen(settings: SettingsRepository) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionHeader("Speech")
        var transcription by remember { mutableStateOf(settings.transcriptionBackend) }
        ChoiceRow(
            label = "Android on-device",
            subtitle = "Uses the API that refuses to fall back to a server. " +
                "Unavailable below Android 12.",
            selected = transcription == TranscriptionBackend.ANDROID_ON_DEVICE,
        ) {
            transcription = TranscriptionBackend.ANDROID_ON_DEVICE
            settings.transcriptionBackend = transcription
        }
        ChoiceRow(
            label = "Android default recogniser",
            subtitle = "May run locally or in the cloud depending on your device. " +
                "Voice Composer cannot tell which, and will not claim it is offline.",
            selected = transcription == TranscriptionBackend.ANDROID_DEFAULT,
        ) {
            transcription = TranscriptionBackend.ANDROID_DEFAULT
            settings.transcriptionBackend = transcription
        }

        SectionHeader("Refinement")
        var refinement by remember { mutableStateOf(settings.refinementBackend) }
        ChoiceRow(
            label = "Deterministic only",
            subtitle = "No model. Handles bullets, cleanup, deletions and structure. " +
                "Always available, always offline.",
            selected = refinement == RefinementBackend.DETERMINISTIC_ONLY,
        ) {
            refinement = RefinementBackend.DETERMINISTIC_ONLY
            settings.refinementBackend = refinement
        }
        ChoiceRow(
            label = "OpenAI API (your own key)",
            subtitle = "Billed per token against your API key. This is not covered by a " +
                "ChatGPT Plus or Pro subscription - they are separate products.",
            selected = refinement == RefinementBackend.OPENAI,
        ) {
            refinement = RefinementBackend.OPENAI
            settings.refinementBackend = refinement
        }

        SectionHeader("Commands")
        Text(
            "Activation phrase: \"${settings.activationPhrase}\"",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Say the phrase, then your instruction. Insert, Copy and Cancel always " +
                "need a tap, whatever was heard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("System integration")
        if (BuildConfig.FLOW_MODE_AVAILABLE) {
            var flow by remember { mutableStateOf(settings.flowModeEnabled) }
            ToggleRow(
                label = "Enhanced Flow Mode",
                subtitle = "Requires the Accessibility permission. Android Accessibility " +
                    "Services can technically read much of the on-screen interface of other " +
                    "apps. Voice Composer is built not to, but enabling this genuinely widens " +
                    "what the app is capable of. Safe Mode works without it.",
                checked = flow,
            ) {
                flow = it
                settings.flowModeEnabled = it
            }
        } else {
            Text(
                "This is the safe build. It contains no accessibility service and no overlay " +
                    "permission - verify with: aapt dump permissions <apk>",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        var sensitiveApps by remember { mutableStateOf(settings.sensitiveAppProtection) }
        ToggleRow(
            label = "Sensitive apps protection",
            subtitle = "Stays out of banking, payment, brokerage, password-manager and " +
                "authenticator apps. Detection is best-effort: no Android API reliably " +
                "identifies every financial app, so add your own in the block list.",
            checked = sensitiveApps,
        ) {
            sensitiveApps = it
            settings.sensitiveAppProtection = it
        }

        SectionHeader("Privacy")
        var history by remember { mutableStateOf(settings.historyEnabled) }
        ToggleRow(
            label = "Keep dictation history",
            subtitle = "Off by default. When on, drafts are stored encrypted on this device " +
                "and never uploaded.",
            checked = history,
        ) {
            history = it
            settings.historyEnabled = it
        }

        var cloudFallback by remember { mutableStateOf(settings.allowAutomaticCloudFallback) }
        ToggleRow(
            label = "Allow automatic cloud fallback",
            subtitle = "Off by default. When off, a local model failing produces an error " +
                "rather than quietly sending your audio to a server.",
            checked = cloudFallback,
        ) {
            cloudFallback = it
            settings.allowAutomaticCloudFallback = it
        }

        var clipboard by remember { mutableStateOf(settings.clipboardAutoClear) }
        Text("Clipboard auto-clear", style = MaterialTheme.typography.labelLarge)
        ClipboardAutoClear.entries.forEach { option ->
            ChoiceRow(
                label = when (option) {
                    ClipboardAutoClear.OFF -> "Off"
                    ClipboardAutoClear.THIRTY_SECONDS -> "30 seconds"
                    ClipboardAutoClear.ONE_MINUTE -> "1 minute"
                    ClipboardAutoClear.FIVE_MINUTES -> "5 minutes"
                },
                subtitle = null,
                selected = clipboard == option,
            ) {
                clipboard = option
                settings.clipboardAutoClear = option
            }
        }
        Text(
            "Clearing reduces the window in which another app can read the clipboard. It does " +
                "not make the copy private: the app you paste into necessarily sees the text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ChoiceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
