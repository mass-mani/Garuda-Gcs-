package com.garuda.livestream.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android Keystore backed store for RTSP and publisher credentials. */
class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("garuda_secure_v1", Context.MODE_PRIVATE)
    private val alias = "garuda_live_stream_v1"

    fun put(name: String, value: String) {
        if (value.isEmpty()) {
            preferences.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stored = cipher.iv + encrypted
        preferences.edit().putString(name, "v2:" + Base64.encodeToString(stored, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        return runCatching {
            val versioned = encoded.startsWith("v2:")
            val stored = Base64.decode(if (versioned) encoded.substring(3) else encoded, Base64.NO_WRAP)
            require(stored.size >= 12 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(0, 12)))
            if (versioned) cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(stored.copyOfRange(12, stored.size)), Charsets.UTF_8).also {
                if (!versioned) put(name, it)
            }
        }.getOrNull()
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    companion object {
        // AppPrefs is created by both the activity and service. Key creation must be atomic across instances.
        private val KEY_LOCK = Any()
    }
}
