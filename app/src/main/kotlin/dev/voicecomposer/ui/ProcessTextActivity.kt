package dev.voicecomposer.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.voicecomposer.VoiceComposerApp
import dev.voicecomposer.integration.ClipboardWriter
import dev.voicecomposer.security.SafeLog

/**
 * Direct text replacement in another app, with no Accessibility permission.
 *
 * This is the documented `ACTION_PROCESS_TEXT` contract: the user selects text
 * in any app, picks Voice Composer from the selection toolbar, and we return
 * replacement text via [Activity.setResult]. The host app performs the edit
 * itself, so we never touch its UI and hold no special privilege.
 *
 * Its limits are real and are stated in docs/FEASIBILITY.md: it requires the
 * user to select text first, the host app must send a non-readonly request for
 * the replacement to be applied, and not every app surfaces the menu item. It
 * is not a general "type into any field" mechanism - that is what Flow Mode's
 * accessibility service is for, and why Flow Mode exists as a separate,
 * opt-in flavour.
 *
 * ## Handling untrusted input
 *
 * This activity is exported, so any app can invoke it with arbitrary extras.
 * The incoming text is treated strictly as content: it is placed in the
 * scratchpad and shown to the user, never parsed for commands, never executed,
 * and bounded in length.
 */
class ProcessTextActivity : ComponentActivity() {

    private val app: VoiceComposerApp get() = application as VoiceComposerApp

    private var readOnly = true

    private val viewModel: ComposerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ComposerViewModel(
                    transcription = app.providers.transcriptionProvider(),
                    semanticRefinement = app.providers.semanticRefinementProvider(),
                    parser = app.providers.commandParser(),
                    onCopy = { text -> ClipboardWriter(this@ProcessTextActivity, app.settings).copy(text) },
                    onInsert = ::returnResult,
                ) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        seedFromIntent()

        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ComposerScreen(
                    state = state,
                    onStart = viewModel::startListening,
                    onStop = viewModel::stopListening,
                    onCancelRecording = viewModel::cancelListening,
                    onEdit = viewModel::editDraft,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onToggleDiff = viewModel::toggleDiff,
                    onCopy = viewModel::copyToClipboard,
                    onInsert = viewModel::commitInsert,
                    onCancelAll = { finish() },
                    onDismissError = viewModel::dismissError,
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    // A read-only request cannot accept replacement text, so we
                    // do not offer a button that would silently do nothing.
                    insertSupported = !readOnly,
                )
            }
        }
    }

    private fun seedFromIntent() {
        val incoming = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (incoming.isNullOrBlank()) return

        if (incoming.length > MAX_INCOMING_CHARS) {
            SafeLog.warn(
                TAG,
                "process_text_rejected_oversize",
                mapOf("chars" to incoming.length),
            )
            return
        }

        // Seeded as a manual edit, never routed through the command parser: an
        // external app must not be able to make us execute anything by putting
        // the activation phrase in the text it sends.
        viewModel.editDraft(incoming)
    }

    private fun returnResult(text: String) {
        if (readOnly) {
            finish()
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text),
        )
        SafeLog.info(TAG, "process_text_returned", mapOf("chars" to text.length))
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.cancelListening()
    }

    companion object {
        private const val TAG = "ProcessText"

        /** Bounds memory and rejects absurd input from a hostile caller. */
        private const val MAX_INCOMING_CHARS = 100_000
    }
}
