package dev.voicecomposer.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores BYOK API credentials.
 *
 * ## What this does and does not achieve
 *
 * The key material is generated inside the Android Keystore and never leaves
 * it: this class holds only ciphertext, and decryption is performed by the
 * Keystore on request. On devices with a TEE or StrongBox the key is
 * hardware-backed, so the raw key cannot be exported even with root.
 *
 * What that does *not* do, and the product brief (section 11) requires us to
 * say plainly: it does not make the API key unextractable. An attacker with
 * code execution as this app - a rooted device with a hostile app, or a
 * compromised device - can simply ask the Keystore to decrypt, exactly as we
 * do. Hardware backing protects the *key material*, not the *plaintext the app
 * is entitled to obtain*. A remotely usable, long-lived API credential on a
 * user device is extractable by a sufficiently privileged attacker, and no
 * Android API changes that.
 *
 * The mitigations that actually reduce this risk are architectural and are
 * documented in docs/THREAT_MODEL.md: BYOK is optional and off by default, the
 * app works fully without it, users are advised to scope keys and rotate them,
 * and a user-controlled relay is offered as an alternative that keeps the key
 * off the device entirely.
 *
 * NOTE: implemented but not device-tested; Keystore behaviour cannot be
 * exercised without a device or emulator. See docs/TEST_RESULTS.md.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Stores [value] under [alias], replacing any previous value.
     * Returns false if the platform refused to provide a key.
     */
    fun put(alias: String, value: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(ivKey(alias), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(dataKey(alias), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
        true
    } catch (e: Exception) {
        // Deliberately does not log the exception message: it can echo the
        // value that failed to encrypt.
        SafeLog.failure(TAG, "credential_store_failed", e, mapOf("alias" to alias))
        false
    }

    fun get(alias: String): String? = try {
        val iv = prefs.getString(ivKey(alias), null)
        val data = prefs.getString(dataKey(alias), null)
        if (iv == null || data == null) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
                )
            }
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        SafeLog.failure(TAG, "credential_read_failed", e, mapOf("alias" to alias))
        null
    }

    fun has(alias: String): Boolean = prefs.contains(dataKey(alias))

    /**
     * Removes a credential. Also removes the Keystore key when no credentials
     * remain, so "delete my API key" genuinely destroys the ability to decrypt
     * any copy of the ciphertext that survives in a backup or forensic image.
     */
    fun remove(alias: String) {
        prefs.edit().remove(ivKey(alias)).remove(dataKey(alias)).apply()
        if (prefs.all.keys.none { it.startsWith(DATA_PREFIX) }) {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
        }
    }

    /** Wipes every stored credential and the key that protects them. */
    fun clearAll() {
        prefs.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /** True when the key is held in a TEE or StrongBox, for display in Settings. */
    fun isHardwareBacked(): Boolean = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (key == null) {
            false
        } else {
            val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java)
                as android.security.keystore.KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel != android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun ivKey(alias: String) = "$IV_PREFIX$alias"
    private fun dataKey(alias: String) = "$DATA_PREFIX$alias"

    companion object {
        private const val TAG = "CredentialStore"

        /**
         * Named "credentials" so the backup exclusion rules in
         * res/xml/backup_rules.xml can target this file precisely.
         */
        private const val PREFS_NAME = "credentials"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "voice_composer_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_PREFIX = "iv_"
        private const val DATA_PREFIX = "data_"

        const val ALIAS_OPENAI = "openai_api_key"
    }
}
