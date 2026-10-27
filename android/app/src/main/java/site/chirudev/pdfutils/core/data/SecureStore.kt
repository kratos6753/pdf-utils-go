package site.chirudev.pdfutils.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the refresh token (encrypted) and the per-install device id. */
interface SecureStore {
    suspend fun deviceId(): String
    suspend fun refreshToken(): String?
    suspend fun setRefreshToken(token: String?)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pdfutils")

@Singleton
class KeystoreSecureStore @Inject constructor(@ApplicationContext private val context: Context) : SecureStore {
    private val deviceKey = stringPreferencesKey("device_id")
    private val refreshKey = stringPreferencesKey("refresh_token")

    @Volatile private var cachedDeviceId: String? = null

    override suspend fun deviceId(): String {
        cachedDeviceId?.let { return it }
        var id: String? = null
        context.dataStore.edit { prefs ->
            id = prefs[deviceKey] ?: UUID.randomUUID().toString().replace("-", "").also { prefs[deviceKey] = it }
        }
        return id!!.also { cachedDeviceId = it }
    }

    override suspend fun refreshToken(): String? {
        val stored = context.dataStore.data.first()[refreshKey] ?: return null
        // A key lost after a restore or OS upgrade just means signing in again.
        return runCatching { decrypt(stored) }.getOrNull()
    }

    override suspend fun setRefreshToken(token: String?) {
        context.dataStore.edit { prefs ->
            if (token == null) prefs.remove(refreshKey) else prefs[refreshKey] = encrypt(token)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val out = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pdfutils_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
