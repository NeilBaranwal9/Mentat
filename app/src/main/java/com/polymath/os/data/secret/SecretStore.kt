package com.polymath.os.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.polymath.os.di.SecretPrefs
import com.polymath.os.domain.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Rule 5: the Groq key is user-supplied and encrypted at rest. It is never logged and never in BuildConfig. */
interface SecretStore {
    val hasGroqKey: Flow<Boolean>
    suspend fun groqKey(): String?
    suspend fun setGroqKey(key: String)
    suspend fun clearGroqKey()
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore; ciphertext kept in a private
 * DataStore file. Replaces the deprecated EncryptedSharedPreferences.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @SecretPrefs private val store: DataStore<Preferences>,
    private val dispatchers: DispatcherProvider,
) : SecretStore {

    private companion object {
        const val ALIAS = "polymath_groq_key_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        val CIPHERTEXT = stringPreferencesKey("groq_key_ct")
    }

    override val hasGroqKey: Flow<Boolean> = store.data.map { !it[CIPHERTEXT].isNullOrEmpty() }

    override suspend fun groqKey(): String? = withContext(dispatchers.io) {
        val blob = store.data.first()[CIPHERTEXT] ?: return@withContext null
        runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val ct = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun setGroqKey(key: String) = withContext(dispatchers.io) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        store.edit { it[CIPHERTEXT] = blob }
        Unit
    }

    override suspend fun clearGroqKey() = withContext(dispatchers.io) {
        store.edit { it.remove(CIPHERTEXT) }
        Unit
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
