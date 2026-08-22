package dev.voicecomposer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.voicecomposer.models.CandidateModels
import dev.voicecomposer.models.ModelDescriptor
import dev.voicecomposer.models.ModelDownloadState

/**
 * The Model Manager (product brief section 7).
 *
 * Three rules are visible in the UI rather than buried in the code:
 *
 *  - **Nothing downloads silently.** Every model has an explicit Download
 *    button, and the size is stated before the tap, not after.
 *  - **An unverified model cannot be downloaded at all.** Models whose checksum
 *    is not pinned render as unavailable with the reason shown, because
 *    `ModelDownloadGuard` will refuse them anyway.
 *  - **Installed models can be deleted**, and the space they use is stated.
 */
@Composable
fun ModelManagerScreen(
    models: List<ModelDescriptor>,
    installedIds: Set<String>,
    activeId: String?,
    downloadStates: Map<String, ModelDownloadState>,
    onDownload: (ModelDescriptor) -> Unit,
    onDelete: (ModelDescriptor) -> Unit,
    onSelect: (ModelDescriptor) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Speech models", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }

        Text(
            "Downloaded models run entirely on this device. Nothing you say is sent " +
                "anywhere while a local model is selected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        for (model in models) {
            ModelCard(
                model = model,
                installed = model.id in installedIds,
                active = model.id == activeId,
                state = downloadStates[model.id] ?: ModelDownloadState.Idle,
                onDownload = { onDownload(model) },
                onDelete = { onDelete(model) },
                onSelect = { onSelect(model) },
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelDescriptor,
    installed: Boolean,
    active: Boolean,
    state: ModelDownloadState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val pinned = model.sha256 != CandidateModels.UNPINNED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (active) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.titleMedium)

            Text(
                buildString {
                    append("${model.approximateDownloadMb} MB download")
                    append("  ·  ~${model.approximateRuntimeRamBytes / (1024 * 1024)} MB RAM")
                    append("  ·  ${model.languages.joinToString(", ")}")
                    if (model.supportsStreaming) append("  ·  live partial results")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "${model.publisher}  ·  ${model.license}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                !pinned -> Text(
                    "Unavailable in this build: no verified checksum is pinned for this " +
                        "model, so it will not be downloaded. See docs/MODEL_COMPARISON.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                state is ModelDownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Downloading ${state.bytesRead / (1024 * 1024)} MB of " +
                            "${state.totalBytes / (1024 * 1024)} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                state is ModelDownloadState.Verifying ->
                    Text("Verifying checksum…", style = MaterialTheme.typography.bodySmall)

                state is ModelDownloadState.Installing ->
                    Text("Installing…", style = MaterialTheme.typography.bodySmall)

                state is ModelDownloadState.Failed -> Text(
                    "Failed: ${state.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val busy = state is ModelDownloadState.Downloading ||
                    state is ModelDownloadState.Verifying ||
                    state is ModelDownloadState.Installing

                if (installed) {
                    if (active) {
                        Text("In use", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Button(onClick = onSelect) { Text("Use this model") }
                    }
                    TextButton(onClick = onDelete, enabled = !busy) { Text("Delete") }
                } else {
                    Button(onClick = onDownload, enabled = pinned && !busy) {
                        Text("Download ${model.approximateDownloadMb} MB")
                    }
                }
            }
        }
    }
}
