package dev.voicecomposer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The main composer.
 *
 * Visual language is deliberately plain: a utility, not a branded product. The
 * hierarchy follows the brief's Listening -> Draft -> Transform -> Preview ->
 * Commit ordering, and the commit row sits at the bottom, separated, so that
 * Copy and Insert are never adjacent to anything that fires automatically.
 */
@Composable
fun ComposerScreen(
    state: ComposerUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancelRecording: () -> Unit,
    onEdit: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleDiff: () -> Unit,
    onCopy: () -> Unit,
    onInsert: () -> Unit,
    onCancelAll: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
    insertSupported: Boolean,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Voice Composer", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }

            state.pipeline?.let { PrivacyBanner(it) }

            state.error?.let { message ->
                ErrorCard(message = message, onDismiss = onDismissError)
            }

            MicControl(
                phase = state.phase,
                partial = state.partial,
                onStart = onStart,
                onStop = onStop,
                onCancel = onCancelRecording,
            )

            state.pendingConfirmation?.let { pending ->
                ConfirmationCard(pending = pending)
            }

            if (state.hasContent || state.phase != ComposerPhase.IDLE) {
                DraftSection(
                    state = state,
                    onEdit = onEdit,
                    onToggleDiff = onToggleDiff,
                )

                HistoryRow(
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onUndo = onUndo,
                    onRedo = onRedo,
                )

                HorizontalDivider()

                CommitRow(
                    enabled = state.hasContent,
                    insertSupported = insertSupported,
                    highlightedAction = state.pendingConfirmation?.action,
                    onCopy = onCopy,
                    onInsert = onInsert,
                    onCancelAll = onCancelAll,
                )
            }
        }
    }
}

/**
 * Always visible, never collapsed. The user should not have to go looking to
 * find out whether their voice is leaving the device.
 */
@Composable
private fun PrivacyBanner(pipeline: dev.voicecomposer.core.PipelineDescriptor) {
    val leaves = pipeline.anyStageLeavesDevice
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (leaves) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = if (leaves) "Data leaves this device" else "Everything stays on this device",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = pipeline.render(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MicControl(
    phase: ComposerPhase,
    partial: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (phase) {
            ComposerPhase.LISTENING -> {
                Text("Listening", style = MaterialTheme.typography.titleMedium)
                if (partial.isNotBlank()) {
                    Text(
                        text = partial,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onStop) { Text("Stop") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            ComposerPhase.PROCESSING -> {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text("Refining", style = MaterialTheme.typography.bodyMedium)
            }

            else -> {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    TextButton(
                        onClick = onStart,
                        modifier = Modifier.size(96.dp),
                    ) {
                        Text("Speak", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(
                    "Say \"voice command\" followed by an instruction to transform the draft.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DraftSection(
    state: ComposerUiState,
    onEdit: (String) -> Unit,
    onToggleDiff: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isModified) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Original", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onToggleDiff) {
                    Text(if (state.showDiff) "Hide original" else "Show original")
                }
            }
            if (state.showDiff) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        text = state.original,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = if (state.isModified) "Refined" else "Draft",
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            value = state.current,
            onValueChange = onEdit,
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            label = null,
        )
    }
}

@Composable
private fun HistoryRow(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
        TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
    }
}

/**
 * A voice command that would move text out of the app never executes itself.
 * It renders here, and the corresponding button below is highlighted, but the
 * user still taps.
 */
@Composable
private fun ConfirmationCard(pending: PendingConfirmation) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(pending.prompt, style = MaterialTheme.typography.titleSmall)
            Text(
                "Heard as a voice command. Tap the button below to confirm.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CommitRow(
    enabled: Boolean,
    insertSupported: Boolean,
    highlightedAction: String?,
    onCopy: () -> Unit,
    onInsert: () -> Unit,
    onCancelAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancelAll) { Text("Cancel") }
        Spacer(Modifier.weight(1f))

        if (highlightedAction == "COPY") {
            Button(onClick = onCopy, enabled = enabled) { Text("Copy") }
        } else {
            FilledTonalButton(onClick = onCopy, enabled = enabled) { Text("Copy") }
        }

        if (insertSupported) {
            if (highlightedAction == "INSERT") {
                Button(onClick = onInsert, enabled = enabled) { Text("Insert") }
            } else {
                FilledTonalButton(onClick = onInsert, enabled = enabled) { Text("Insert") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
