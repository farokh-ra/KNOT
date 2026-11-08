package dev.twoperson.messenger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.twoperson.messenger.crypto.Crypto
import dev.twoperson.messenger.crypto.X25519
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest.
 *
 * The long-term X25519 identity key and each contact's invitation secret are wrapped with an
 * AES-256 key that lives in the Android Keystore and is not exportable. The wrapped blobs are
 * kept in private SharedPreferences. Nothing secret is ever logged.
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(): ByteArray {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            decodeBase64(existing)?.let { if (it.size == DEVICE_ID_BYTES) return it }
        }
        val generated = Crypto.randomBytes(DEVICE_ID_BYTES)
        prefs.edit().putString(KEY_DEVICE_ID, encodeBase64(generated)).apply()
        return generated
    }

    fun identityPublicKey(): ByteArray {
        val existing = prefs.getString(KEY_IDENTITY_PUBLIC, null)
        if (existing != null) {
            decodeBase64(existing)?.let { if (it.size == 32) return it }
        }
        identityPrivateKey()
        return decodeBase64(prefs.getString(KEY_IDENTITY_PUBLIC, null) ?: "") ?: ByteArray(32)
    }

    fun identityPrivateKey(): ByteArray {
        val wrapped = prefs.getString(KEY_IDENTITY_PRIVATE, null)
        if (wrapped != null) {
            unwrap(wrapped)?.let { if (it.size == 32) return it }
        }
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicKey(privateKey)
        prefs.edit()
            .putString(KEY_IDENTITY_PRIVATE, wrap(privateKey))
            .putString(KEY_IDENTITY_PUBLIC, encodeBase64(publicKey))
            .apply()
        return privateKey
    }

    /** Invitation secret for one locally stored contact. */
    fun inviteSecret(contactId: String): ByteArray? {
        val wrapped = prefs.getString(inviteKey(contactId), null) ?: return null
        return unwrap(wrapped)?.takeIf { it.size == INVITE_BYTES }
    }

    fun saveInviteSecret(contactId: String, secret: ByteArray?) {
        if (secret == null) {
            prefs.edit().remove(inviteKey(contactId)).apply()
        } else {
            prefs.edit().putString(inviteKey(contactId), wrap(secret)).apply()
        }
    }

    /** Version 1 stored exactly one invitation under a fixed key. */
    fun legacyInviteSecret(): ByteArray? {
        val wrapped = prefs.getString(KEY_LEGACY_INVITE, null) ?: return null
        return unwrap(wrapped)?.takeIf { it.size == INVITE_BYTES }
    }

    /** Re-keys the old single invitation onto the migrated contact and removes the old value. */
    fun migrateLegacyInvite(contactId: String) {
        val wrapped = prefs.getString(KEY_LEGACY_INVITE, null) ?: return
        prefs.edit()
            .putString(inviteKey(contactId), wrapped)
            .remove(KEY_LEGACY_INVITE)
            .apply()
    }

    fun clearLegacyInvite() {
        prefs.edit().remove(KEY_LEGACY_INVITE).apply()
    }

    private fun inviteKey(contactId: String): String = "invite_$contactId"

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return encodeBase64(iv + ciphertext)
    }

    private fun unwrap(encoded: String): ByteArray? {
        val raw = decodeBase64(encoded) ?: return null
        if (raw.size < IV_BYTES + 16) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES))
            )
            cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size))
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBase64(text: String): ByteArray? = try {
        Base64.decode(text, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        private const val PREFS = "secure_store"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_WRAP_ALIAS = "tpmsg_wrap_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_IDENTITY_PRIVATE = "identity_private"
        private const val KEY_IDENTITY_PUBLIC = "identity_public"
        private const val KEY_LEGACY_INVITE = "invite_secret"
        private const val DEVICE_ID_BYTES = 16
        private const val INVITE_BYTES = 32
        private const val IV_BYTES = 12
    }
}
