package dev.voicecomposer.flow

/**
 * Safe-flavour counterpart to the enhanced flavour's Flow Mode.
 *
 * There is deliberately no accessibility service and no overlay service in this
 * source set. Code that wants to know whether Flow Mode exists asks
 * [FlowMode.isCompiledIn] rather than catching a ClassNotFoundException, and in
 * this flavour the honest answer is a compile-time constant.
 *
 * The point of the split (product brief section 19) is that the guarantee is
 * structural, not behavioural: a user can run `aapt dump permissions` on the
 * safe APK and see that SYSTEM_ALERT_WINDOW is absent and no
 * BIND_ACCESSIBILITY_SERVICE component is declared. That is verifiable without
 * reading or trusting this source tree.
 */
object FlowMode {
    const val isCompiledIn: Boolean = false
}
