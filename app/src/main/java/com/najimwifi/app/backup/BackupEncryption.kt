package com.najimwifi.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable AES-256-GCM format with PBKDF2-HMAC-SHA256 recovery phrase.
 * Authenticated 64 KiB frames allow bounded-memory large-database streaming.
 * Counter-authenticated terminal frame rejects truncated encrypted archives.
 */
object BackupEncryption {
    private val MAGIC = "NJWBAK01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val FRAME_BYTES = 64 * 1024
    private const val GCM_TAG_BYTES = 16

    fun encrypt(
        destination: OutputStream,
        phrase: CharArray,
        writePlaintext: (OutputStream) -> Unit
    ) {
        require(phrase.size in 12..256) { "عبارة الاسترداد يجب ألا تقل عن 12 حرفًا" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val key = derive(phrase, salt, ITERATIONS)
        try {
            val header = ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + NONCE_PREFIX_BYTES)
                .put(MAGIC).putInt(ITERATIONS).put(salt).put(prefix).array()
            val out = DataOutputStream(destination)
            out.write(header)
            val encrypted = FrameWriter(out, key, header, prefix)
            try {
                writePlaintext(encrypted)
                encrypted.finish()
            } finally {
                encrypted.discard()
            }
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }

    fun <T> decrypt(
        source: InputStream,
        phrase: CharArray,
        consumePlaintext: (InputStream) -> T
    ): T {
        require(phrase.isNotEmpty()) { "عبارة الاسترداد مطلوبة" }
        val input = DataInputStream(source)
        val header = ByteArray(MAGIC.size + 4 + SALT_BYTES + NONCE_PREFIX_BYTES)
        input.readFully(header)
        require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "صيغة النسخة الاحتياطية غير معروفة"
        }
        val rounds = ByteBuffer.wrap(header, MAGIC.size, 4).int
        require(rounds in 250_000..1_500_000) { "معلمات حماية النسخة غير مدعومة" }
        val salt = header.copyOfRange(MAGIC.size + 4, MAGIC.size + 4 + SALT_BYTES)
        val prefix = header.copyOfRange(header.size - NONCE_PREFIX_BYTES, header.size)
        val key = derive(phrase, salt, rounds)
        try {
            val decrypted = FrameReader(input, key, header, prefix)
            val result = consumePlaintext(decrypted)
            // ZipInputStream stops at central directory. Read remaining ZIP bytes
            // through the authenticated stream and require the final GCM frame.
            val sink = ByteArray(8192)
            while (decrypted.read(sink) != -1) { /* authenticated ZIP directory */ }
            return result
        } finally {
            key.fill(0)
            salt.fill(0)
        }
    }

    private fun derive(phrase: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(phrase, salt, rounds, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun frameCipher(
        mode: Int, key: ByteArray, header: ByteArray,
        prefix: ByteArray, index: Int, length: Int
    ): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(length).array())
        }
    }

    private class FrameWriter(
        private val out: DataOutputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        private val prefix: ByteArray
    ) : OutputStream() {
        private val buffer = ByteArray(FRAME_BYTES)
        private var size = 0
        private var index = 0
        private var ended = false

        override fun write(b: Int) {
            check(!ended)
            buffer[size++] = b.toByte()
            if (size == FRAME_BYTES) flushFrame()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!ended)
            require(off >= 0 && len >= 0 && off <= b.size - len)
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val n = minOf(FRAME_BYTES - size, remaining)
                System.arraycopy(b, offset, buffer, size, n)
                size += n
                offset += n
                remaining -= n
                if (size == FRAME_BYTES) flushFrame()
            }
        }

        private fun flushFrame() {
            if (size == 0) return
            check(index != Int.MAX_VALUE) { "النسخة أكبر من الحد المدعوم" }
            val cipher = frameCipher(Cipher.ENCRYPT_MODE, key, header, prefix, index++, size)
            out.writeInt(size)
            out.write(cipher.doFinal(buffer, 0, size))
            buffer.fill(0)
            size = 0
        }

        fun finish() {
            if (ended) return
            flushFrame()
            val tag = frameCipher(Cipher.ENCRYPT_MODE, key, header, prefix, index, 0)
                .doFinal(ByteArray(0))
            out.writeInt(0)
            out.write(tag)
            out.flush()
            ended = true
        }

        fun discard() { buffer.fill(0) }
        override fun flush() { if (!ended) flushFrame() }
        override fun close() { finish() }
    }

    private class FrameReader(
        private val input: DataInputStream,
        private val key: ByteArray,
        private val header: ByteArray,
        private val prefix: ByteArray
    ) : InputStream() {
        private var buffer = ByteArray(0)
        private var position = 0
        private var index = 0
        private var ended = false

        private fun refill(): Boolean {
            if (ended) return false
            val size = input.readInt() // EOF means missing authentication terminator
            require(size in 0..FRAME_BYTES) { "طول إطار مشفر غير صالح" }
            val content = ByteArray(size + GCM_TAG_BYTES)
            input.readFully(content)
            val decoded = frameCipher(Cipher.DECRYPT_MODE, key, header, prefix, index++, size)
                .doFinal(content)
            if (size == 0) {
                require(decoded.isEmpty() && input.read() == -1) {
                    "معلومات إضافية أو ملف مشفر غير مكتمل"
                }
                ended = true
                return false
            }
            require(decoded.size == size)
            buffer.fill(0)
            buffer = decoded
            position = 0
            return true
        }

        override fun read(): Int {
            if (position == buffer.size && !refill()) return -1
            return buffer[position++].toInt() and 255
        }

        override fun read(out: ByteArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off <= out.size - len)
            if (len == 0) return 0
            if (position == buffer.size && !refill()) return -1
            val n = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, out, off, n)
            position += n
            return n
        }
    }
}
