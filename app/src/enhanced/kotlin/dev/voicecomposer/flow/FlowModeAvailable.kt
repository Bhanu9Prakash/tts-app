package dev.voicecomposer.flow

/**
 * Enhanced-flavour counterpart to the safe flavour's [FlowMode] stub.
 *
 * Flow Mode being compiled in is not the same as it being active: the user must
 * additionally enable it in Settings, grant the accessibility permission, and
 * grant the overlay permission. All three are off by default.
 */
object FlowMode {
    const val isCompiledIn: Boolean = true
}
