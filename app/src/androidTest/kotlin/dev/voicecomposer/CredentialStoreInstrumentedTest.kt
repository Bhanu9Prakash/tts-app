package dev.voicecomposer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.voicecomposer.security.CredentialStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Android Keystore.
 *
 * This is the class most worth running on a device: the crypto path cannot be
 * unit-tested off-device at all, and a mistake in it would be silent - the app
 * would appear to store a key and simply fail to retrieve it later, or worse,
 * store something recoverable.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: CredentialStore

    @Before
    fun setUp() {
        store = CredentialStore(context)
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun storesAndRetrievesACredential() {
        val secret = "sk-test-value-that-should-round-trip-1234567890"
        assertTrue(store.put(CredentialStore.ALIAS_OPENAI, secret))
        assertTrue(store.has(CredentialStore.ALIAS_OPENAI))
        assertEquals(secret, store.get(CredentialStore.ALIAS_OPENAI))
    }

    @Test
    fun missingCredentialReturnsNull() {
        assertNull(store.get(CredentialStore.ALIAS_OPENAI))
        assertFalse(store.has(CredentialStore.ALIAS_OPENAI))
    }

    @Test
    fun storedFormIsNotThePlaintext() {
        val secret = "sk-plaintext-must-not-be-stored-verbatim"
        store.put(CredentialStore.ALIAS_OPENAI, secret)

        // Read the backing file directly: what lands on disk must be ciphertext.
        val prefs = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE)
        val stored = prefs.all.values.joinToString(" ") { it.toString() }

        assertFalse(
            "The plaintext credential was found in the preferences file",
            stored.contains(secret),
        )
        assertTrue("Expected some stored ciphertext", stored.isNotEmpty())
    }

    @Test
    fun eachEncryptionUsesAFreshIv() {
        // Randomised encryption is required; a fixed IV with AES-GCM is a
        // catastrophic failure, so assert two writes of the same value differ.
        val secret = "same-value-twice"
        val prefs = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE)

        store.put(CredentialStore.ALIAS_OPENAI, secret)
        val firstCiphertext = prefs.getString("data_${CredentialStore.ALIAS_OPENAI}", null)
        val firstIv = prefs.getString("iv_${CredentialStore.ALIAS_OPENAI}", null)

        store.put(CredentialStore.ALIAS_OPENAI, secret)
        val secondCiphertext = prefs.getString("data_${CredentialStore.ALIAS_OPENAI}", null)
        val secondIv = prefs.getString("iv_${CredentialStore.ALIAS_OPENAI}", null)

        assertNotNull(firstIv)
        assertNotEquals("IV was reused across encryptions", firstIv, secondIv)
        assertNotEquals(firstCiphertext, secondCiphertext)
        assertEquals(secret, store.get(CredentialStore.ALIAS_OPENAI))
    }

    @Test
    fun removingTheLastCredentialMakesOldCiphertextUndecryptable() {
        store.put(CredentialStore.ALIAS_OPENAI, "sk-will-be-deleted")

        val prefs = context.getSharedPreferences("credentials", android.content.Context.MODE_PRIVATE)
        val ciphertext = prefs.getString("data_${CredentialStore.ALIAS_OPENAI}", null)
        val iv = prefs.getString("iv_${CredentialStore.ALIAS_OPENAI}", null)
        assertNotNull(ciphertext)

        // Deleting the last credential also deletes the Keystore key, so even a
        // surviving copy of the ciphertext - in a backup or forensic image -
        // can no longer be decrypted.
        store.remove(CredentialStore.ALIAS_OPENAI)

        prefs.edit().putString("data_${CredentialStore.ALIAS_OPENAI}", ciphertext)
            .putString("iv_${CredentialStore.ALIAS_OPENAI}", iv).commit()

        assertNull(
            "Ciphertext was still decryptable after the key was destroyed",
            store.get(CredentialStore.ALIAS_OPENAI),
        )
    }

    @Test
    fun clearAllRemovesEverything() {
        store.put(CredentialStore.ALIAS_OPENAI, "sk-value")
        store.clearAll()
        assertFalse(store.has(CredentialStore.ALIAS_OPENAI))
        assertNull(store.get(CredentialStore.ALIAS_OPENAI))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
    private fun assertNotNull(message: String, value: Any?) = assertTrue(message, value != null)
}
