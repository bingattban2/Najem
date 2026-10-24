package com.najimwifi.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.najimwifi.app.data.settings.NajimSettings
import com.najimwifi.app.data.settings.PortableSettingsCodec
import com.najimwifi.app.domain.service.DebtAlertOptions
import java.security.MessageDigest
import org.junit.Test
import org.junit.runner.RunWith

/** Portable archive framing tests; no user's database is opened or overwritten. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val phrase = "اختبار عبارة استرداد طويلة 2026 #987".toCharArray()

    private fun encrypted(payload: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        BackupEncryption.encrypt(out, phrase.copyOf()) { it.write(payload) }
        out.toByteArray()
    }

    @Test fun multiFrameArchiveRestoresEveryByte() {
        val payload = ByteArray(165_731) { (it * 19).toByte() }
        val result = BackupEncryption.decrypt(
            ByteArrayInputStream(encrypted(payload)), phrase.copyOf()
        ) { it.readBytes() }
        assertArrayEquals(payload, result)
    }

    @Test fun wrongPhraseAndCorruptedCiphertextCannotBeRead() {
        val source = encrypted(ByteArray(70_000) { (it % 127).toByte() })
        assertTrue(runCatching {
            BackupEncryption.decrypt(
                ByteArrayInputStream(source), "خاطئة عبارة استرداد مختلفة 100".toCharArray()
            ) { it.readBytes() }
        }.isFailure)
        val tampered = source.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() }
        assertTrue(runCatching {
            BackupEncryption.decrypt(ByteArrayInputStream(tampered), phrase.copyOf()) {
                it.readBytes()
            }
        }.isFailure)
        assertTrue(runCatching {
            BackupEncryption.decrypt(
                ByteArrayInputStream(source.copyOf(source.size - 1)), phrase.copyOf()
            ) { it.readBytes() }
        }.isFailure)
    }


    @Test fun portablePreferencesRoundTripKeepsArabicAndAlertSettings() {
        val settings = NajimSettings(
            businessHeader = "نجم واي فاي - حضرموت",
            alerts = DebtAlertOptions(
                debtEnabled = true,
                minimumDebtMinor = 13_000L,
                settlementDelayEnabled = true,
                overdueAfterDays = 14,
                systemNotificationsEnabled = true
            )
        )
        val encoded = PortableSettingsCodec.encode(settings)
        assertEquals(settings, PortableSettingsCodec.decode(encoded))
        assertTrue(runCatching {
            PortableSettingsCodec.decode(encoded + byteArrayOf(1))
        }.isFailure)
        assertTrue(runCatching {
            PortableSettingsCodec.decode(byteArrayOf(1, 2, 3))
        }.isFailure)
    }

    @Test fun currentManifestVerifiesSettingsDigestAndLegacyFormatRemainsReadable() {
        val settings = PortableSettingsCodec.encode(NajimSettings("شركة النجم"))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(settings).joinToString("") { "%02x".format(it) }
        val counts = BackupArchiveFormat.tables.associateWith { 2L }
        val new = BackupArchiveFormat.newManifest(
            createdAtMillis = 1_800_000_000_000L,
            bytes = 4096L,
            sha256 = "b".repeat(64),
            version = 1,
            identity = "room-test",
            counts = counts,
            preferencesBytes = settings.size,
            preferencesSha256 = digest
        )
        assertTrue(BackupArchiveFormat.preview(new).includesPreferences)
        val out = ByteArrayOutputStream()
        BackupArchiveFormat.writeManifest(out, new)
        assertEquals(new, BackupArchiveFormat.readManifest(
            ByteArrayInputStream(out.toByteArray())
        ))
        val old = BackupArchiveFormat.newManifest(
            1_800_000_000_000L, 4096L, "b".repeat(64), 1, "room-test", counts
        )
        val legacy = ByteArrayOutputStream()
        BackupArchiveFormat.writeManifest(legacy, old)
        val restored = BackupArchiveFormat.readManifest(
            ByteArrayInputStream(legacy.toByteArray())
        )
        assertEquals(1, restored.formatVersion)
        assertTrue(!BackupArchiveFormat.preview(restored).includesPreferences)
    }

    @Test fun manifestRoundTripPreservesAllThirteenTables() {
        val counts = BackupArchiveFormat.tables.associateWith { 3L }
        val manifest = BackupArchiveFormat.newManifest(
            createdAtMillis = 1_800_000_000_000L,
            bytes = 4096L,
            sha256 = "a".repeat(64),
            version = 1,
            identity = "fixture-room-identity",
            counts = counts
        )
        val buffer = ByteArrayOutputStream()
        BackupArchiveFormat.writeManifest(buffer, manifest)
        val restored = BackupArchiveFormat.readManifest(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals(manifest, restored)
        assertEquals(BackupArchiveFormat.tables.toSet(), restored.tableCounts.keys)
    }
}
