package dev.voicecomposer.security

/** Why an app is treated as sensitive, so the UI can explain itself. */
enum class SensitiveCategory {
    BANKING,
    PAYMENTS_UPI,
    BROKERAGE,
    PASSWORD_MANAGER,
    AUTHENTICATOR,
    USER_BLOCKED,
}

data class AppEvaluation(
    val blocked: Boolean,
    val category: SensitiveCategory?,
    /** Machine-readable reason. Safe to log; contains only a package name. */
    val reason: String,
)

/**
 * Decides whether Voice Composer should stay out of the way in the foreground app.
 *
 * ## Honest limits
 *
 * There is no Android API that reliably answers "is this a banking app". Play
 * Store categories are not available offline, `ApplicationInfo.category` is
 * self-declared by the developer and is usually `CATEGORY_UNDEFINED`, and new
 * apps appear constantly. The brief (section 22) asks us not to claim that
 * every financial app can be detected, and we do not.
 *
 * What this class actually provides:
 *  - a curated denylist of package prefixes for common categories,
 *  - a heuristic on package-name tokens,
 *  - a user-editable list, which is the only mechanism that can be complete.
 *
 * Everything runs locally; no package list is ever transmitted. Matching is on
 * package name only - we never read anything from the other app.
 */
class SensitiveAppPolicy(
    /** User-configured package names that must always be blocked. */
    private val userBlocked: Set<String> = emptySet(),
    /** User-configured packages to allow despite a heuristic match. */
    private val userAllowed: Set<String> = emptySet(),
    private val enabled: Boolean = true,
) {

    fun evaluate(packageName: String): AppEvaluation {
        val pkg = packageName.lowercase()

        if (pkg in userBlocked) {
            return AppEvaluation(true, SensitiveCategory.USER_BLOCKED, "user_blocklist")
        }
        if (!enabled) {
            return AppEvaluation(false, null, "protection_disabled")
        }
        // An explicit user allow overrides the built-in heuristics, but never
        // the user's own blocklist above.
        if (pkg in userAllowed) {
            return AppEvaluation(false, null, "user_allowlist")
        }

        CURATED_PREFIXES.forEach { (prefix, category) ->
            if (pkg == prefix || pkg.startsWith("$prefix.")) {
                return AppEvaluation(true, category, "curated_prefix:$prefix")
            }
        }

        TOKEN_HEURISTICS.forEach { (token, category) ->
            if (pkg.split('.', '_', '-').any { it == token }) {
                return AppEvaluation(true, category, "token_heuristic:$token")
            }
        }

        return AppEvaluation(false, null, "no_match")
    }

    fun isBlocked(packageName: String): Boolean = evaluate(packageName).blocked

    companion object {
        /**
         * Curated package prefixes. Intentionally small and conservative: a
         * wrong entry silently disables the product inside an app the user
         * cares about, so breadth is delegated to the user-editable list and to
         * the token heuristics below.
         */
        private val CURATED_PREFIXES: List<Pair<String, SensitiveCategory>> = listOf(
            // Password managers
            "com.agilebits.onepassword" to SensitiveCategory.PASSWORD_MANAGER,
            "com.onepassword.android" to SensitiveCategory.PASSWORD_MANAGER,
            "com.bitwarden" to SensitiveCategory.PASSWORD_MANAGER,
            "com.x8bit.bitwarden" to SensitiveCategory.PASSWORD_MANAGER,
            "com.lastpass" to SensitiveCategory.PASSWORD_MANAGER,
            "com.keepersecurity" to SensitiveCategory.PASSWORD_MANAGER,
            "com.dashlane" to SensitiveCategory.PASSWORD_MANAGER,
            "org.keepassdroid" to SensitiveCategory.PASSWORD_MANAGER,
            "com.kunzisoft.keepass" to SensitiveCategory.PASSWORD_MANAGER,
            "im.mash.keepass" to SensitiveCategory.PASSWORD_MANAGER,
            "com.zeapo.pwdstore" to SensitiveCategory.PASSWORD_MANAGER,
            "proton.android.pass" to SensitiveCategory.PASSWORD_MANAGER,

            // Authenticators
            "com.google.android.apps.authenticator2" to SensitiveCategory.AUTHENTICATOR,
            "com.azure.authenticator" to SensitiveCategory.AUTHENTICATOR,
            "com.authy.authy" to SensitiveCategory.AUTHENTICATOR,
            "org.shadowice.flocke.andotp" to SensitiveCategory.AUTHENTICATOR,
            "com.beemdevelopment.aegis" to SensitiveCategory.AUTHENTICATOR,
            "com.duosecurity.duomobile" to SensitiveCategory.AUTHENTICATOR,

            // Payments / UPI (India-focused, matching the brief's context)
            "com.google.android.apps.nbu.paisa.user" to SensitiveCategory.PAYMENTS_UPI,
            "com.phonepe.app" to SensitiveCategory.PAYMENTS_UPI,
            "net.one97.paytm" to SensitiveCategory.PAYMENTS_UPI,
            "in.org.npci.upiapp" to SensitiveCategory.PAYMENTS_UPI,
            "in.amazon.mShop.android.shopping" to SensitiveCategory.PAYMENTS_UPI,
            "com.dreamplug.androidapp" to SensitiveCategory.PAYMENTS_UPI,
            "com.paypal.android.p2pmobile" to SensitiveCategory.PAYMENTS_UPI,

            // Brokerage / trading
            "com.nextbillion.groww" to SensitiveCategory.BROKERAGE,
            "com.zerodha.kite3" to SensitiveCategory.BROKERAGE,
            "com.zerodha.coin" to SensitiveCategory.BROKERAGE,
            "com.msf.angelmobile" to SensitiveCategory.BROKERAGE,
            "com.upstox.pro" to SensitiveCategory.BROKERAGE,
            "in.upstox.app" to SensitiveCategory.BROKERAGE,
            "com.fivepaisa.trade" to SensitiveCategory.BROKERAGE,
            "com.robinhood.android" to SensitiveCategory.BROKERAGE,
            "com.interactivebrokers.tws" to SensitiveCategory.BROKERAGE,

            // Banking (a representative sample; completeness is impossible)
            "com.sbi.lotusintouch" to SensitiveCategory.BANKING,
            "com.sbi.SBIFreedomPlus" to SensitiveCategory.BANKING,
            "com.snapwork.hdfc" to SensitiveCategory.BANKING,
            "com.csam.icici.bank.imobile" to SensitiveCategory.BANKING,
            "com.axis.mobile" to SensitiveCategory.BANKING,
            "com.kotak.mobile" to SensitiveCategory.BANKING,
            "com.msf.kbank.mobile" to SensitiveCategory.BANKING,
            "com.bankofbaroda.mconnect" to SensitiveCategory.BANKING,
            "com.chase.sig.android" to SensitiveCategory.BANKING,
            "com.infonow.bofa" to SensitiveCategory.BANKING,
            "com.wf.wellsfargomobile" to SensitiveCategory.BANKING,
            "com.revolut.revolut" to SensitiveCategory.BANKING,
            "com.monzo.android" to SensitiveCategory.BANKING,
        )

        /**
         * Package-name tokens that strongly suggest a financial app. Matched as
         * whole dot/underscore/hyphen-separated segments so that, for example,
         * "com.example.bankingtips" does not match on "banking" but
         * "com.acme.bank.mobile" does.
         */
        private val TOKEN_HEURISTICS: List<Pair<String, SensitiveCategory>> = listOf(
            "bank" to SensitiveCategory.BANKING,
            "banking" to SensitiveCategory.BANKING,
            "netbanking" to SensitiveCategory.BANKING,
            "upi" to SensitiveCategory.PAYMENTS_UPI,
            "wallet" to SensitiveCategory.PAYMENTS_UPI,
            "authenticator" to SensitiveCategory.AUTHENTICATOR,
            "otp" to SensitiveCategory.AUTHENTICATOR,
            "passwordmanager" to SensitiveCategory.PASSWORD_MANAGER,
            "brokerage" to SensitiveCategory.BROKERAGE,
            "trading" to SensitiveCategory.BROKERAGE,
        )
    }
}
