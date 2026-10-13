package com.example.aiphone

import android.content.SharedPreferences
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API キー、個人情報、通話用件などの保存データを Android Keystore の AES-GCM 鍵で暗号化する。
 * 保存形式: "v1:<IVのBase64>:<暗号文のBase64>"。
 * 旧版の平文は読み取り時に検出し、暗号化できた場合だけ移行する。新規保存で平文にフォールバックしない。
 */
object SecretStore {
    // Deliberately keep this legacy alias stable; changing it would make existing encrypted data unreadable.
    private const val ALIAS = "aidenwa_secret_v1"
    private const val PREFIX = "v1:"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    sealed class ReadResult {
        data class Success(
            val value: String,
            /** 旧版の平文として読み取った値。 */
            val wasLegacyPlaintext: Boolean = false,
            /** 旧版データの暗号化移行に失敗した場合 true。データは上書きしていない。 */
            val migrationFailed: Boolean = false,
        ) : ReadResult()

        /** 理由は機械判定用の短いコードのみ。保存値や暗号文を含めない。 */
        data class Failure(val reason: String) : ReadResult()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** 暗号化した文字列。失敗時は null を返し、呼び出し元で平文保存しないこと。 */
    fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    /** 保存値を復号する。旧版の平文は移行対象として明示的に返し、破損した暗号文とは区別する。 */
    fun decodeStored(stored: String): ReadResult {
        if (stored.isEmpty()) return ReadResult.Success("")
        if (!stored.startsWith(PREFIX)) {
            return ReadResult.Success(stored, wasLegacyPlaintext = true)
        }
        return try {
            val parts = stored.removePrefix(PREFIX).split(":")
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return ReadResult.Failure("malformed_encrypted_value")
            }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            ReadResult.Success(String(c.doFinal(ct), Charsets.UTF_8))
        } catch (_: Exception) {
            ReadResult.Failure("decryption_failed")
        }
    }

    /** 保存されている Gemini API キーを読み、旧版の平文は暗号化できた場合だけ移行する。 */
    fun readApiKey(prefs: SharedPreferences): ReadResult {
        val stored = prefs.getString(AiCallService.KEY_API, "").orEmpty()
        return when (val result = decodeStored(stored)) {
            is ReadResult.Failure -> result
            is ReadResult.Success -> {
                if (!result.wasLegacyPlaintext || result.value.isEmpty()) return result
                val encrypted = encrypt(result.value)
                    ?: return result.copy(migrationFailed = true)
                prefs.edit { putString(AiCallService.KEY_API, encrypted) }
                ReadResult.Success(result.value)
            }
        }
    }

    /** API キーを保存。暗号化に失敗した場合は既存値を変更せず false を返す。 */
    fun putApiKey(prefs: SharedPreferences, apiKey: String): Boolean {
        if (apiKey.isEmpty()) {
            prefs.edit { remove(AiCallService.KEY_API) }
            return true
        }
        val encrypted = encrypt(apiKey) ?: return false
        prefs.edit { putString(AiCallService.KEY_API, encrypted) }
        return true
    }

    /** 確認済みキーの記録用。キーそのものは保存せず、SHA-256 のハッシュだけを残す。 */
    fun fingerprint(apiKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

}
