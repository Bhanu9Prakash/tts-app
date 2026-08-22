package dev.voicecomposer.core

/**
 * Where a provider's work actually happens.
 *
 * This type exists to keep the product honest. Section 6 of the product brief
 * is explicit that the UI must never claim "offline" unless that has actually
 * been verified, and Android's [android.speech.SpeechRecognizer] is the exact
 * trap it is warning about: the platform recognizer may be served locally or by
 * a cloud backend depending on OEM, device and installed language packs, and
 * an app cannot in general observe which.
 *
 * So we refuse to model this as a boolean. [UNKNOWN] is a first-class value and
 * the UI renders it as "not verified", never as "local".
 */
enum class ProcessingLocation {
    /**
     * Verified on-device. Only for providers where we control the inference
     * ourselves - a bundled/downloaded model executed in our own process - or
     * where the platform API contractually guarantees it (for example
     * `createOnDeviceSpeechRecognizer`, which fails rather than falling back to
     * a network backend).
     */
    ON_DEVICE,

    /**
     * Audio or text demonstrably leaves the device. Any BYOK cloud provider.
     */
    OFF_DEVICE,

    /**
     * Cannot be determined from a supported API. The default platform
     * [android.speech.SpeechRecognizer] lands here. Treated as
     * "may leave the device" for every privacy decision.
     */
    UNKNOWN;

    /**
     * Whether the user must be warned that data may leave the device.
     *
     * Note that [UNKNOWN] is deliberately grouped with [OFF_DEVICE]: an
     * unverified claim is treated as the unsafe case, never the safe one.
     */
    val mayLeaveDevice: Boolean
        get() = this != ON_DEVICE

    /** Short label for the pipeline display. Never says "offline" for [UNKNOWN]. */
    val label: String
        get() = when (this) {
            ON_DEVICE -> "Processed locally"
            OFF_DEVICE -> "Leaves this device"
            UNKNOWN -> "Not verified - may leave this device"
        }
}

/** What a provider is able to do, so the UI can describe it without guessing. */
data class ProviderCapabilities(
    val id: String,
    val displayName: String,
    val processingLocation: ProcessingLocation,
    val requiresNetwork: Boolean,
    val supportsStreaming: Boolean = false,
    val languages: List<String> = emptyList(),
    /** Populated for downloadable models; null for platform/cloud providers. */
    val approximateDownloadBytes: Long? = null,
)

/**
 * The end-to-end description of the currently selected pipeline, used to render
 * the "CURRENT PIPELINE" panel (product brief section 12).
 */
data class PipelineDescriptor(
    val transcription: ProviderCapabilities,
    val refinement: ProviderCapabilities,
) {
    /** True when any stage may send user data off the device. */
    val anyStageLeavesDevice: Boolean
        get() = transcription.processingLocation.mayLeaveDevice ||
            refinement.processingLocation.mayLeaveDevice

    /**
     * Renders the pipeline as the plain-text panel shown in Settings.
     * Kept in core (not the UI layer) so it is unit-testable.
     */
    fun render(): String = buildString {
        appendLine("CURRENT PIPELINE")
        appendLine()
        appendLine("Voice")
        appendLine("  |")
        appendLine("${transcription.displayName}")
        appendLine("  ${transcription.processingLocation.label}")
        appendLine("  |")
        appendLine("Transcript")
        appendLine("  |")
        appendLine("${refinement.displayName}")
        appendLine("  ${refinement.processingLocation.label}")
        appendLine("  |")
        append("Preview")
    }
}
