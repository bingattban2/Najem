package com.najimwifi.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class MigrationRescueCipherTest {
    @Test fun encryptionRoundTripAndForgeryResistance() {
        val dir = java.nio.file.Files.createTempDirectory("najim_cipher_test_").toFile()
        val source = File(dir, "original.sqlite")
        val encrypted = File(dir, "rescue.aesgcm")
        val recovered = File(dir, "recovered.sqlite")
        val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        try {
            for (size in listOf(1, 1024, 256 * 1024, 256 * 1024 + 73)) {
                encrypted.delete()
                source.writeBytes(ByteArray(size).also(SecureRandom()::nextBytes))
                MigrationRescueCipher.encrypt(source, encrypted, key)
                MigrationRescueCipher.decrypt(encrypted, recovered, key)
                assertArrayEquals(source.readBytes(), recovered.readBytes())
                val original = encrypted.readBytes()
                val changed = original.clone().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
                encrypted.writeBytes(changed)
                assertThrows(Exception::class.java) {
                    MigrationRescueCipher.decrypt(encrypted, recovered, key)
                }
                encrypted.writeBytes(original.copyOf(original.size - 8))
                assertThrows(Exception::class.java) {
                    MigrationRescueCipher.decrypt(encrypted, recovered, key)
                }
                encrypted.writeBytes(original + byteArrayOf(1))
                assertThrows(Exception::class.java) {
                    MigrationRescueCipher.decrypt(encrypted, recovered, key)
                }
                encrypted.writeBytes(original)
                assertThrows(Exception::class.java) {
                    MigrationRescueCipher.decrypt(encrypted, recovered,
                        SecretKeySpec(ByteArray(32), "AES"))
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
