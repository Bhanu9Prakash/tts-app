package dev.voicecomposer.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveAppPolicyTest {

    private val policy = SensitiveAppPolicy()

    @Test
    fun `brokerage apps named in the brief are blocked`() {
        assertTrue(policy.isBlocked("com.nextbillion.groww"))
        assertTrue(policy.isBlocked("com.zerodha.kite3"))
    }

    @Test
    fun `payment and UPI apps are blocked`() {
        assertTrue(policy.isBlocked("com.google.android.apps.nbu.paisa.user"))
        assertTrue(policy.isBlocked("com.phonepe.app"))
        assertTrue(policy.isBlocked("net.one97.paytm"))
    }

    @Test
    fun `password managers and authenticators are blocked`() {
        assertTrue(policy.isBlocked("com.x8bit.bitwarden"))
        assertTrue(policy.isBlocked("com.beemdevelopment.aegis"))
        assertTrue(policy.isBlocked("com.google.android.apps.authenticator2"))
    }

    @Test
    fun `banking apps are blocked`() {
        assertTrue(policy.isBlocked("com.snapwork.hdfc"))
        assertTrue(policy.isBlocked("com.chase.sig.android"))
    }

    @Test
    fun `ordinary messaging and productivity apps are allowed`() {
        val allowed = listOf(
            "com.whatsapp",
            "com.google.android.gm",
            "com.anthropic.claude",
            "com.openai.chatgpt",
            "com.google.android.apps.docs.editors.docs",
            "com.Slack",
            "com.android.chrome",
            "com.google.android.keep",
        )
        for (pkg in allowed) {
            assertFalse(policy.isBlocked(pkg), "Ordinary app wrongly blocked: $pkg")
        }
    }

    @Test
    fun `token heuristic matches whole segments only`() {
        assertTrue(policy.isBlocked("com.acme.bank.mobile"), "segment 'bank' should match")
        assertFalse(
            policy.isBlocked("com.example.bankingtips"),
            "'bankingtips' is not the segment 'banking' and must not match",
        )
        assertFalse(policy.isBlocked("com.example.riverbank"))
    }

    @Test
    fun `subpackages of a curated prefix are blocked`() {
        assertTrue(policy.isBlocked("com.phonepe.app.debug"))
    }

    @Test
    fun `a prefix must not match an unrelated longer package`() {
        // "com.axis.mobile" must not cause "com.axismobile.something" to match.
        assertFalse(policy.isBlocked("com.axismobilegames.puzzle"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(policy.isBlocked("COM.PHONEPE.APP"))
    }

    @Test
    fun `user blocklist blocks any app`() {
        val custom = SensitiveAppPolicy(userBlocked = setOf("com.whatsapp"))
        assertTrue(custom.isBlocked("com.whatsapp"))
        assertEquals(SensitiveCategory.USER_BLOCKED, custom.evaluate("com.whatsapp").category)
    }

    @Test
    fun `user allowlist overrides the heuristic but never the user blocklist`() {
        val allowing = SensitiveAppPolicy(userAllowed = setOf("com.acme.bank.mobile"))
        assertFalse(allowing.isBlocked("com.acme.bank.mobile"))

        val both = SensitiveAppPolicy(
            userBlocked = setOf("com.acme.bank.mobile"),
            userAllowed = setOf("com.acme.bank.mobile"),
        )
        assertTrue(
            both.isBlocked("com.acme.bank.mobile"),
            "An explicit block must win over an explicit allow",
        )
    }

    @Test
    fun `disabling protection still honours the user blocklist`() {
        val disabled = SensitiveAppPolicy(userBlocked = setOf("com.mybank.app"), enabled = false)
        assertTrue(
            disabled.isBlocked("com.mybank.app"),
            "A user's explicit block must survive turning the heuristics off",
        )
        assertFalse(disabled.isBlocked("com.phonepe.app"))
    }

    @Test
    fun `protection is on by default`() {
        assertTrue(SensitiveAppPolicy().isBlocked("com.phonepe.app"))
    }
}
