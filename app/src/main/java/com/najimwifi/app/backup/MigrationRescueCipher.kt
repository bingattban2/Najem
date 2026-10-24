package com.najimwifi.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Rescue-only bounded-memory AES-GCM format. Every ordered chunk is
 * authenticated; the final zero-length authenticated record is mandatory.
 * Does not export plaintext outside the caller-chosen temporary destination.
 */
internal object MigrationRescueCipher {
    private val MAGIC = byteArrayOf(0x4e, 0x4a, 0x52, 0x33) // NJR3
    private const val CHUNK = 256 * 1024
    private const val MAX_BYTES = 1024L * 1024L * 1024L

    fun encrypt(plain: File, encrypted: File, key: SecretKey) {
        require(plain.isFile && plain.length() in 1..MAX_BYTES)
        require(!encrypted.exists()) { "نسخة إنقاذ مشفرة موجودة بالفعل" }
        FileInputStream(plain).use { input ->
            DataOutputStream(FileOutputStream(encrypted)).use { output ->
                output.write(MAGIC)
                val buffer = ByteArray(CHUNK)
                var counter = 0L
                var total = 0L
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    if (length == 0) continue
                    total += length
                    require(total <= MAX_BYTES && counter < 0xffffffffL)
                    output.writeInt(length)
                    val (iv, bytes) = encryptChunk(buffer, length, counter, key)
                    output.write(iv)
                    output.write(bytes)
                    counter++
                }
                require(counter < 0xffffffffL)
                output.writeInt(0)
                val (iv, terminator) = encryptChunk(ByteArray(0), 0, counter, key)
                output.write(iv)
                output.write(terminator)
                output.flush()
            }
        }
    }

    /** Throws for bad tags, missing terminator, truncation or appended data. */
    fun decrypt(encrypted: File, plain: File, key: SecretKey) {
        require(encrypted.isFile && encrypted.length() > 36L)
        FileInputStream(encrypted).use { stream ->
            DataInputStream(stream).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "صيغة إنقاذ غير معروفة" }
                FileOutputStream(plain).use { output ->
                    var counter = 0L
                    var total = 0L
                    while (true) {
                        require(counter < 0xffffffffL) { "عدد قطع الإنقاذ غير صالح" }
                        val size = input.readInt()
                        require(size in 0..CHUNK) { "حجم قطعة الإنقاذ غير صالح" }
                        total += size
                        require(total <= MAX_BYTES) { "الإنقاذ تجاوز الحجم الأقصى" }
                        val iv = ByteArray(12)
                        input.readFully(iv)
                        val ciphertext = ByteArray(size + 16)
                        input.readFully(ciphertext)
                        val plaintext = decryptChunk(ciphertext, size, iv, counter, key)
                        require(plaintext.size == size)
                        if (size == 0) {
                            require(input.read() == -1) { "بيانات إضافية بعد نهاية الإنقاذ" }
                            break
                        }
                        output.write(plaintext)
                        counter++
                    }
                    output.fd.sync()
                }
            }
        }
    }

    private fun aad(counter: Long, length: Int): ByteArray = MAGIC + byteArrayOf(
        (counter ushr 24).toByte(), (counter ushr 16).toByte(),
        (counter ushr 8).toByte(), counter.toByte(),
        (length ushr 24).toByte(), (length ushr 16).toByte(),
        (length ushr 8).toByte(), length.toByte()
    )

    private fun encryptChunk(
        bytes: ByteArray, length: Int, counter: Long, key: SecretKey
    ): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeystore defaults to randomizedEncryptionRequired=true.
        // Let the provider generate a distinct authenticated 96-bit IV.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(counter, length))
        return cipher.iv to cipher.doFinal(bytes, 0, length)
    }

    private fun decryptChunk(
        ciphertext: ByteArray, length: Int, iv: ByteArray,
        counter: Long, key: SecretKey
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(counter, length))
        return cipher.doFinal(ciphertext)
    }
}
