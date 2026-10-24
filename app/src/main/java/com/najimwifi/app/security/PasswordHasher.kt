package com.najimwifi.app.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PasswordDigest(
    val saltBase64: String,
    val hashBase64: String,
    val iterations: Int
)

/** Password-derived verifier; the password itself is never persisted. */
object PasswordHasher {
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun validateNew(password: String) {
        require(password.length in 10..128 &&
            password.any(Char::isLetter) && password.any(Char::isDigit)) {
            "كلمة المرور يجب أن تكون 10 أحرف على الأقل، وبها حرف ورقم"
        }
    }

    fun create(password: String): PasswordDigest {
        validateNew(password)
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(password, salt, ITERATIONS)
        return PasswordDigest(
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(derived, Base64.NO_WRAP),
            ITERATIONS
        ).also { derived.fill(0) }
    }

    fun matches(password: String, verifier: PasswordDigest): Boolean {
        require(verifier.iterations in ITERATIONS..1_000_000) {
            "إعدادات تجزئة كلمة المرور غير صالحة"
        }
        val salt = Base64.decode(verifier.saltBase64, Base64.NO_WRAP)
        val expected = Base64.decode(verifier.hashBase64, Base64.NO_WRAP)
        require(salt.size == SALT_BYTES && expected.size == KEY_BITS / 8) {
            "سجل كلمة المرور تالف"
        }
        val candidate = derive(password, salt, verifier.iterations)
        return try {
            MessageDigest.isEqual(expected, candidate)
        } finally {
            candidate.fill(0)
            expected.fill(0)
            salt.fill(0)
        }
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val characters = password.toCharArray()
        val spec = PBEKeySpec(characters, salt, rounds, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            characters.fill('\u0000')
        }
    }
}