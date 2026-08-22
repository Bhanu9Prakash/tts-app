package dev.voicecomposer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessingTest {

    @Test
    fun `unknown processing location is treated as potentially leaving the device`() {
        // This is the whole point of the type: an unverified claim must never
        // be optimistically reported as local.
        assertTrue(ProcessingLocation.UNKNOWN.mayLeaveDevice)
        assertTrue(ProcessingLocation.OFF_DEVICE.mayLeaveDevice)
        assertFalse(ProcessingLocation.ON_DEVICE.mayLeaveDevice)
    }

    @Test
    fun `no label claims offline unless verified`() {
        assertFalse(ProcessingLocation.UNKNOWN.label.contains("locally", ignoreCase = true))
        assertFalse(ProcessingLocation.OFF_DEVICE.label.contains("locally", ignoreCase = true))
        assertTrue(ProcessingLocation.ON_DEVICE.label.contains("locally", ignoreCase = true))
    }

    private fun caps(id: String, location: ProcessingLocation) = ProviderCapabilities(
        id = id,
        displayName = id,
        processingLocation = location,
        requiresNetwork = location == ProcessingLocation.OFF_DEVICE,
    )

    @Test
    fun `fully local pipeline reports nothing leaving the device`() {
        val pipeline = PipelineDescriptor(
            transcription = caps("Local ASR", ProcessingLocation.ON_DEVICE),
            refinement = caps("Deterministic", ProcessingLocation.ON_DEVICE),
        )
        assertFalse(pipeline.anyStageLeavesDevice)
        assertTrue(pipeline.render().contains("Processed locally"))
    }

    @Test
    fun `a single cloud stage marks the whole pipeline as leaving the device`() {
        val pipeline = PipelineDescriptor(
            transcription = caps("OpenAI Transcription", ProcessingLocation.OFF_DEVICE),
            refinement = caps("Deterministic", ProcessingLocation.ON_DEVICE),
        )
        assertTrue(pipeline.anyStageLeavesDevice)
        assertTrue(pipeline.render().contains("Leaves this device"))
    }

    @Test
    fun `an unverified stage also marks the pipeline as leaving the device`() {
        val pipeline = PipelineDescriptor(
            transcription = caps("Android SpeechRecognizer", ProcessingLocation.UNKNOWN),
            refinement = caps("Deterministic", ProcessingLocation.ON_DEVICE),
        )
        assertTrue(
            pipeline.anyStageLeavesDevice,
            "An unverified transcription backend must not be reported as private",
        )
    }

    @Test
    fun `rendered pipeline names both providers and both locations`() {
        val pipeline = PipelineDescriptor(
            transcription = caps("Whisper base.en", ProcessingLocation.ON_DEVICE),
            refinement = caps("OpenAI", ProcessingLocation.OFF_DEVICE),
        )
        val rendered = pipeline.render()
        assertTrue(rendered.contains("Whisper base.en"))
        assertTrue(rendered.contains("OpenAI"))
        assertTrue(rendered.contains("Processed locally"))
        assertTrue(rendered.contains("Leaves this device"))
        assertTrue(rendered.startsWith("CURRENT PIPELINE"))
    }

    @Test
    fun `capabilities carry download size only where meaningful`() {
        val platform = caps("Android", ProcessingLocation.UNKNOWN)
        assertEquals(null, platform.approximateDownloadBytes)
    }
}
