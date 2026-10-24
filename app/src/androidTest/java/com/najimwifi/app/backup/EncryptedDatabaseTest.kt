package com.najimwifi.app.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.security.EncryptedDatabaseSupport
import java.security.SecureRandom
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated test database name, never najim_wifi.db or real owner records. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    @Test fun newlyCreatedSqlcipherRoomFileIsNotPlaintextAndRejectsWrongKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "najim_test_${UUID.randomUUID()}.db"
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrong = ByteArray(32).also(SecureRandom()::nextBytes)
        var db: NajimDatabase? = null
        try {
            EncryptedDatabaseSupport.loadNativeLibrary()
            db = Room.databaseBuilder(context, NajimDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
                .build()
            val opened = requireNotNull(db)
            opened.openHelper.writableDatabase.query("SELECT name FROM sqlite_master LIMIT 1")
                .use { assertTrue(it.moveToFirst()) }
            opened.close()
            db = null
            val bytes = ByteArray(16)
            context.getDatabasePath(name).inputStream().use { input ->
                require(input.read(bytes) == bytes.size)
            }
            assertFalse(bytes.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
            val bad = Room.databaseBuilder(context, NajimDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(wrong.copyOf())).build()
            try {
                assertTrue(runCatching { bad.openHelper.writableDatabase }.isFailure)
            } finally {
                bad.close()
            }
        } finally {
            db?.close()
            context.deleteDatabase(name)
            key.fill(0)
            wrong.fill(0)
        }
    }
}
