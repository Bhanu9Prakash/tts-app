package dev.voicecomposer.flow

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.voicecomposer.VoiceComposerApp
import dev.voicecomposer.security.FieldSignals
import dev.voicecomposer.security.SafeLog
import dev.voicecomposer.security.SensitiveAppPolicy
import dev.voicecomposer.security.SensitiveFieldPolicy

/**
 * Flow Mode: notices when an editable field is focused, and inserts approved
 * text into it.
 *
 * ## Scope discipline
 *
 * The brief (section 18) asks for the narrowest possible accessibility
 * implementation. Concretely, in this class:
 *
 *  - We subscribe only to focus and window-state events. Text-change events -
 *    the ones that would make this a key logger - are not subscribed to in
 *    flow_accessibility_config.xml, so they are never delivered.
 *  - We read the focused node's *type flags* only: isPassword, inputType,
 *    autofill hints, and the hint text used for OTP detection. We never read
 *    `node.text`, which is the field's actual contents.
 *  - Nothing derived from an AccessibilityEvent or AccessibilityNodeInfo is
 *    logged, persisted or transmitted. The only things that leave this class
 *    are a boolean (show the bubble or not) and a short machine-readable reason
 *    code from the policy layer.
 *  - There is no node-tree traversal. We look at the event's source node and
 *    stop.
 *
 * ## What this permission could still do
 *
 * Being honest about the ceiling rather than the floor: an accessibility
 * service with `canRetrieveWindowContent` - which is required for
 * ACTION_SET_TEXT, and therefore unavoidable in Flow Mode - *could* read the
 * text of most on-screen views in most apps, including messages and, in
 * badly-built apps, credentials. This implementation does not, and the code
 * above is the whole of what it reads. But the user is granting a capability
 * broader than the use, and docs/PERMISSIONS.md says so in those words. Users
 * who do not want to extend that trust should install the safe flavour, which
 * contains no accessibility service at all.
 */
class FlowAccessibilityService : AccessibilityService() {

    private val app: VoiceComposerApp get() = application as VoiceComposerApp

    private var currentPackage: String? = null
    private var lastDecisionVisible: Boolean? = null

    private val appPolicy: SensitiveAppPolicy
        get() = SensitiveAppPolicy(
            userBlocked = app.settings.blockedPackages,
            userAllowed = app.settings.allowedPackages,
            enabled = app.settings.sensitiveAppProtection,
        )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!app.settings.flowModeEnabled) {
            setBubbleVisible(false, "flow_mode_disabled")
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                currentPackage = event.packageName?.toString()
                // Leaving an app drops any bubble immediately; we re-evaluate
                // when a field in the new app takes focus.
                setBubbleVisible(false, "window_changed")
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            -> evaluateFocus(event)
        }
    }

    private fun evaluateFocus(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: currentPackage
        if (pkg == null) {
            setBubbleVisible(false, "unknown_package")
            return
        }

        // App-level check first: it is cheaper, and in a banking app we do not
        // want to inspect fields at all.
        val appEvaluation = appPolicy.evaluate(pkg)
        if (appEvaluation.blocked) {
            setBubbleVisible(false, appEvaluation.reason)
            return
        }

        val node = event.source
        if (node == null) {
            setBubbleVisible(false, "no_source_node")
            return
        }

        try {
            val evaluation = SensitiveFieldPolicy.evaluate(node.toSignals())
            setBubbleVisible(
                visible = evaluation.decision == dev.voicecomposer.security.FieldDecision.ALLOW,
                reason = evaluation.reason,
            )
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) node.recycle()
        }
    }

    /**
     * Maps a node onto the policy's input.
     *
     * Reads flags and hints only. `node.text` - the field's contents - is
     * deliberately not read here or anywhere else in this class.
     */
    private fun AccessibilityNodeInfo.toSignals(): FieldSignals {
        val inputType = runCatching { this.inputType }.getOrDefault(0)
        return FieldSignals(
            isPassword = inputType.hasVariation(PASSWORD_VARIATIONS),
            isNumericPassword = inputType.hasVariation(setOf(NUMBER_VARIATION_PASSWORD)) &&
                (inputType and TYPE_MASK_CLASS) == TYPE_CLASS_NUMBER,
            isVisiblePassword = inputType.hasVariation(setOf(TEXT_VARIATION_VISIBLE_PASSWORD)),
            // Autofill hints reach an AutofillService, not an
            // AccessibilityService: AccessibilityNodeInfo has no equivalent of
            // View.getAutofillHints(). The signal is genuinely unavailable on
            // this path, so it is left empty rather than approximated. It stays
            // part of FieldSignals because an IME integration can populate it
            // from EditorInfo, and because keeping it in the policy keeps that
            // policy complete and independently testable.
            autofillHints = emptySet(),
            accessibilityPassword = runCatching { isPassword }.getOrDefault(false),
            imeOptionsSuggestOtp = false,
            // The hint is a static label ("Enter OTP"), not user content.
            hintText = runCatching { hintText?.toString() }.getOrNull(),
            isEditable = runCatching { isEditable }.getOrDefault(false),
        )
    }

    private fun Int.hasVariation(variations: Set<Int>): Boolean =
        variations.any { (this and TYPE_MASK_VARIATION) == it }

    private fun setBubbleVisible(visible: Boolean, reason: String) {
        if (lastDecisionVisible == visible) return
        lastDecisionVisible = visible

        // Only the decision and a fixed reason code are logged. No package
        // content, no field content, no event text.
        SafeLog.debug(TAG, "bubble_visibility", mapOf("visible" to visible, "reason" to reason))

        BubbleOverlayService.setVisible(this, visible)
    }

    /**
     * Inserts approved text into the focused field.
     *
     * Re-checks the field policy immediately before writing: focus can move
     * between the user approving the text and this running, and we must not
     * write a draft into a password box because it was safe a second ago.
     */
    fun insertApprovedText(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            val evaluation = SensitiveFieldPolicy.evaluate(node.toSignals())
            if (evaluation.decision != dev.voicecomposer.security.FieldDecision.ALLOW) {
                SafeLog.warn(TAG, "insert_refused", mapOf("reason" to evaluation.reason))
                return false
            }
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) node.recycle()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        BubbleOverlayService.setVisible(this, false)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SafeLog.info(TAG, "flow_service_connected")
    }

    companion object {
        private const val TAG = "FlowA11y"

        @Volatile
        private var instance: FlowAccessibilityService? = null

        fun current(): FlowAccessibilityService? = instance

        // InputType constants, referenced numerically so this file does not
        // depend on android.text.InputType at every call site.
        private const val TYPE_MASK_VARIATION = 0x00000ff0
        private const val TYPE_MASK_CLASS = 0x0000000f
        private const val TYPE_CLASS_NUMBER = 0x00000002
        private const val TEXT_VARIATION_PASSWORD = 0x00000080
        private const val TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
        private const val TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
        private const val NUMBER_VARIATION_PASSWORD = 0x00000010

        private val PASSWORD_VARIATIONS = setOf(
            TEXT_VARIATION_PASSWORD,
            TEXT_VARIATION_WEB_PASSWORD,
        )
    }
}
