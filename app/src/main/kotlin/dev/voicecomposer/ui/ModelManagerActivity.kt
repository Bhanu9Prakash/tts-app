package dev.voicecomposer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.voicecomposer.VoiceComposerApp
import dev.voicecomposer.models.CandidateModels
import dev.voicecomposer.models.ModelDescriptor
import dev.voicecomposer.models.ModelDownloadState
import dev.voicecomposer.settings.TranscriptionBackend
import kotlinx.coroutines.launch

/**
 * Hosts the Model Manager.
 *
 * Downloads run in `lifecycleScope`, so leaving the screen cancels them - a
 * partially downloaded archive is deleted by [dev.voicecomposer.models.ModelRepository]
 * rather than left to be resumed, because a half-file that later passes no
 * checksum is worse than starting again.
 */
class ModelManagerActivity : ComponentActivity() {

    private val app: VoiceComposerApp get() = application as VoiceComposerApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val states = remember { mutableStateMapOf<String, ModelDownloadState>() }
                var installed by remember {
                    mutableStateOf(app.models.installedModels().map { it.id }.toSet())
                }
                var active by remember { mutableStateOf(activeModelId()) }

                ModelManagerScreen(
                    models = CandidateModels.all,
                    installedIds = installed,
                    activeId = active,
                    downloadStates = states,
                    onDownload = { model ->
                        lifecycleScope.launch {
                            app.models.download(
                                descriptor = model,
                                // The tap *is* the approval. There is no setting
                                // that can pre-approve downloads.
                                userApproved = true,
                                onState = { states[model.id] = it },
                            )
                            installed = app.models.installedModels().map { it.id }.toSet()
                            if (model.id in installed && active == null) {
                                selectModel(model)
                                active = model.id
                            }
                        }
                    },
                    onDelete = { model ->
                        app.models.delete(model)
                        installed = app.models.installedModels().map { it.id }.toSet()
                        states.remove(model.id)
                        if (active == model.id) {
                            app.settings.localModelId = null
                            active = null
                        }
                    },
                    onSelect = { model ->
                        selectModel(model)
                        active = model.id
                    },
                    onBack = { finish() },
                )
            }
        }
    }

    /**
     * Selecting a model also switches the transcription backend to it. Without
     * that, downloading a model would appear to do nothing, which is a worse
     * kind of surprise than an explicit switch.
     */
    private fun selectModel(model: ModelDescriptor) {
        app.settings.localModelId = model.id
        app.settings.transcriptionBackend = TranscriptionBackend.LOCAL_MODEL
    }

    private fun activeModelId(): String? =
        if (app.settings.transcriptionBackend == TranscriptionBackend.LOCAL_MODEL) {
            app.settings.localModelId
        } else {
            null
        }
}
