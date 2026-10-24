package com.najimwifi.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.security.EncryptedDatabaseSupport
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An isolated SQLCipher export fixture; never opens the owner's najim_wifi.db. */
@RunWith(AndroidJUnit4::class)
class LegacyEncryptionPreparationTest {
    @Test
    fun plaintextToEncryptedCandidateCopiesRowsAndDoesNotChangeOriginal() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(app.cacheDir, "migration_test_${UUID.randomUUID()}").apply {
            require(mkdirs())
        }
        val original = File(dir, "test_plain.sqlite")
        val target = File(dir, "test_cipher.sqlite")
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrong = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            SQLiteDatabase.openOrCreateDatabase(original, null).use { sql ->
                sql.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY, content TEXT NOT NULL)")
                sql.execSQL("INSERT INTO items(id,content) VALUES (1,'نجم واي فاي')")
                sql.version = 1
            }
            EncryptedDatabaseSupport.loadNativeLibrary()
            CipherDatabase.openDatabase(
                target.absolutePath, key, null,
                CipherDatabase.OPEN_READWRITE or CipherDatabase.CREATE_IF_NECESSARY, null
            ).use { cipher ->
                cipher.execSQL("ATTACH DATABASE '${original.absolutePath.replace("'", "''")}' AS plain KEY ''")
                try {
                    cipher.rawQuery("SELECT sqlcipher_export('main','plain')", null).use {
                        assertTrue(it.moveToFirst())
                    }
                    cipher.execSQL("PRAGMA main.user_version=1")
                } finally {
                    cipher.execSQL("DETACH DATABASE plain")
                }
            }
            SQLiteDatabase.openDatabase(original.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT content FROM items WHERE id=1", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("نجم واي فاي", rows.getString(0))
                }
            }
            val header = ByteArray(16)
            target.inputStream().use { assertEquals(16, it.read(header)) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
            CipherDatabase.openDatabase(
                target.absolutePath, key, null, CipherDatabase.OPEN_READONLY, null
            ).use {
                it.rawQuery("SELECT content FROM items WHERE id=1", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals("نجم واي فاي", rows.getString(0))
                }
            }
            val rejected = runCatching {
                CipherDatabase.openDatabase(
                    target.absolutePath, wrong, null, CipherDatabase.OPEN_READONLY, null
                ).use { it.rawQuery("SELECT COUNT(*) FROM items", null).use { rows ->
                    require(rows.moveToFirst())
                } }
            }.isFailure
            assertTrue("Wrong key must not read SQLCipher", rejected)
        } finally {
            key.fill(0)
            wrong.fill(0)
            dir.listFiles()?.forEach(File::delete)
            dir.delete()
        }
    }
}
