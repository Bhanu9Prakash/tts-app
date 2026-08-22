package dev.voicecomposer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.voicecomposer.VoiceComposerApp
import dev.voicecomposer.integration.ClipboardWriter

/**
 * The Safe Mode entry point: a normal activity the user opens deliberately.
 *
 * Nothing here needs the overlay or accessibility permission. Output is by
 * explicit Copy, which is the safest mechanism in the brief's ranking
 * (section 30).
 */
class ComposerActivity : ComponentActivity() {

    private val app: VoiceComposerApp get() = application as VoiceComposerApp

    private val viewModel: ComposerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ComposerViewModel(
                    transcription = app.providers.transcriptionProvider(),
                    semanticRefinement = app.providers.semanticRefinementProvider(),
                    parser = app.providers.commandParser(),
                    onCopy = { text -> clipboard.copy(text) },
                ) as T
        }
    }

    private val clipboard by lazy { ClipboardWriter(this, app.settings) }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ComposerScreen(
                    state = state,
                    onStart = ::requestMicThenStart,
                    onStop = viewModel::stopListening,
                    onCancelRecording = viewModel::cancelListening,
                    onEdit = viewModel::editDraft,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onToggleDiff = viewModel::toggleDiff,
                    onCopy = viewModel::copyToClipboard,
                    onInsert = viewModel::commitInsert,
                    onCancelAll = viewModel::cancelAll,
                    onDismissError = viewModel::dismissError,
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    // Safe Mode has no way to type into another app; Copy is
                    // the commit action. ProcessTextActivity is the exception
                    // and supplies its own screen.
                    insertSupported = false,
                )
            }
        }
    }

    private fun requestMicThenStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Do not keep the microphone open when the user leaves.
        if (!isChangingConfigurations) viewModel.cancelListening()
    }
}
