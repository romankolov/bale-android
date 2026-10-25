package ai.bale.client.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.bale.client.domain.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SavedSession(val account: Account, val token: String)

/** All account/token metadata is encrypted. Keys never leave Android Keystore. */
internal class SessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "bale.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun load(): SavedSession? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString("encrypted", null) ?: return@withContext null
        try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            cipher.updateAAD(alias.toByteArray())
            val json = JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
            SavedSession(Account(json.getInt("id"), json.getString("name")), json.getString("token"))
        } catch (_: Exception) {
            // Key invalidation or corrupted ciphertext requires a fresh login, not plaintext fallback.
            check(preferences.edit().remove("encrypted").commit())
            null
        }
    }
    suspend fun save(session: SavedSession) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(alias.toByteArray())
        val json = JSONObject().put("id", session.account.id).put("name", session.account.name).put("token", session.token)
        val packed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted", Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
    }
    suspend fun clear() = withContext(Dispatchers.IO) { check(preferences.edit().remove("encrypted").commit()) }
    suspend fun deviceId(): String = withContext(Dispatchers.IO) {
        preferences.getString("device", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("device", it).commit())
        }
    }
}
