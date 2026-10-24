package com.najimwifi.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stored only in noBackupFilesDir, encrypted with a device-local Keystore key. */
data class LocalCredential(
    val username: String,
    val password: PasswordDigest,
    val policy: LockPolicy,
    val failures: Int = 0,
    val blockedUntilEpochMillis: Long = 0L
)

class CredentialStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "najim_auth_v1.bin"))
    private val keyAlias = "najim_wifi_local_login_v1"
    private val header = byteArrayOf(0x4E, 0x4A, 0x41, 0x31) // NJA1

    @Synchronized
    fun read(): LocalCredential? {
        val encrypted = try {
            file.openRead().use { it.readBytes() }
        } catch (missing: java.io.FileNotFoundException) {
            return null
        }
        require(encrypted.size >= 4 + 12 + 16 &&
            encrypted.copyOfRange(0, 4).contentEquals(header)) {
            "ملف حماية التطبيق غير صالح"
        }
        val secret = existingKey() ?: error(
            "تعذر استرجاع مفتاح حماية التطبيق؛ لا يمكن تجاوز القفل"
        )
        val iv = encrypted.copyOfRange(4, 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(encrypted.copyOfRange(16, encrypted.size))
        return try {
            val data = JSONObject(String(plaintext, Charsets.UTF_8))
            LocalCredential(
                username = data.getString("user"),
                password = PasswordDigest(
                    data.getString("salt"),
                    data.getString("hash"),
                    data.getInt("rounds")
                ),
                policy = LockPolicy.valueOf(data.getString("policy")),
                failures = data.getInt("failures"),
                blockedUntilEpochMillis = data.getLong("blockedUntil")
            )
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun write(record: LocalCredential) {
        require(record.username.isNotBlank())
        require(record.failures >= 0 && record.blockedUntilEpochMillis >= 0)
        val plain = JSONObject().apply {
            put("user", record.username)
            put("salt", record.password.saltBase64)
            put("hash", record.password.hashBase64)
            put("rounds", record.password.iterations)
            put("policy", record.policy.name)
            put("failures", record.failures)
            put("blockedUntil", record.blockedUntilEpochMillis)
        }.toString().toByteArray(Charsets.UTF_8)

        val encrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            ByteBuffer.allocate(header.size + cipher.iv.size + plain.size + 16)
                .put(header)
                .put(cipher.iv)
                .put(cipher.doFinal(plain))
                .array()
        } finally {
            plain.fill(0)
        }

        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        } finally {
            encrypted.fill(0)
        }
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    ).apply {
        init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
    }.generateKey()
}