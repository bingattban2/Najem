package com.najimwifi.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Versioned, fully authenticated content within the encrypted archive. */
data class BackupManifest(
    val backupId: String,
    val createdAtMillis: Long,
    val databaseBytes: Long,
    val databaseSha256: String,
    val schemaVersion: Int,
    val roomIdentityHash: String,
    val tableCounts: Map<String, Long>,
    /** V1 backups do not contain the optional portable settings payload. */
    val formatVersion: Int = 1,
    val preferencesBytes: Int = 0,
    val preferencesSha256: String = ""
)

data class BackupPreview(
    val manifest: BackupManifest,
    val totalCustomers: Long,
    val totalFinancialEntries: Long,
    val totalCardMovements: Long,
    val totalSettlements: Long,
    val includesPreferences: Boolean = manifest.formatVersion >= 2
)

/** Strict app-defined format: no arbitrary file paths, executables or credentials. */
object BackupArchiveFormat {
    const val MANIFEST_ENTRY = "manifest.bin"
    const val DATABASE_ENTRY = "database.sqlite"
    const val PREFERENCES_ENTRY = "preferences.bin"
    const val MAX_PREFERENCES_BYTES = 16 * 1024
    const val MAX_DATABASE_BYTES = 512L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 16 * 1024
    const val FORMAT_VERSION = 2
    private val NAMES = listOf(
        "archive_batches", "archive_memberships", "audit_events",
        "card_movements", "central_stock_movements", "central_stock_policies",
        "column_definitions", "customers", "financial_entries",
        "optional_column_cells", "packages", "settlement_groups",
        "settlement_lines"
    )

    val tables: List<String> = NAMES

    fun newManifest(
        createdAtMillis: Long,
        bytes: Long,
        sha256: String,
        version: Int,
        identity: String,
        counts: Map<String, Long>,
        preferencesBytes: Int = 0,
        preferencesSha256: String = ""
    ): BackupManifest = BackupManifest(
        UUID.randomUUID().toString(), createdAtMillis, bytes, sha256,
        version, identity, counts,
        formatVersion = if (preferencesBytes > 0) FORMAT_VERSION else 1,
        preferencesBytes = preferencesBytes,
        preferencesSha256 = preferencesSha256
    ).also(::validate)

    fun validate(manifest: BackupManifest) {
        require(runCatching { UUID.fromString(manifest.backupId) }.isSuccess)
        require(manifest.createdAtMillis > 0L)
        require(manifest.databaseBytes in 1..MAX_DATABASE_BYTES)
        require(manifest.databaseSha256.matches(Regex("[a-f0-9]{64}")))
        require(manifest.schemaVersion > 0 && manifest.roomIdentityHash.isNotBlank())
        require(manifest.roomIdentityHash.length <= 128)
        require(manifest.tableCounts.keys == NAMES.toSet()) {
            "ملف النسخة لا يحتوي على جميع جداول المشروع المعتمدة"
        }
        require(manifest.tableCounts.values.all { it >= 0L })
        require(manifest.formatVersion == 1 || manifest.formatVersion == FORMAT_VERSION) {
            "إصدار وصف النسخة غير معروف"
        }
        if (manifest.formatVersion == 1) {
            require(manifest.preferencesBytes == 0 && manifest.preferencesSha256.isEmpty())
        } else {
            require(manifest.preferencesBytes in 1..MAX_PREFERENCES_BYTES) {
                "حجم إعدادات النسخة غير مقبول"
            }
            require(manifest.preferencesSha256.matches(Regex("[a-f0-9]{64}"))) {
                "بصمة إعدادات النسخة غير صالحة"
            }
        }
    }

    fun writeManifest(out: OutputStream, manifest: BackupManifest) {
        validate(manifest)
        val data = DataOutputStream(out)
        data.writeInt(manifest.formatVersion)
        data.writeUTF(manifest.backupId)
        data.writeLong(manifest.createdAtMillis)
        data.writeLong(manifest.databaseBytes)
        data.writeUTF(manifest.databaseSha256)
        data.writeInt(manifest.schemaVersion)
        data.writeUTF(manifest.roomIdentityHash)
        data.writeInt(NAMES.size)
        for (table in NAMES) {
            data.writeUTF(table)
            data.writeLong(manifest.tableCounts.getValue(table))
        }
        if (manifest.formatVersion >= 2) {
            data.writeInt(manifest.preferencesBytes)
            data.writeUTF(manifest.preferencesSha256)
        }
        data.flush()
    }

    fun readManifest(input: InputStream): BackupManifest {
        val data = DataInputStream(input)
        val format = data.readInt()
        require(format == 1 || format == FORMAT_VERSION) { "إصدار النسخة غير مدعوم" }
        val id = data.readUTF()
        val created = data.readLong()
        val bytes = data.readLong()
        val sha = data.readUTF()
        val version = data.readInt()
        val identity = data.readUTF()
        val count = data.readInt()
        require(count == NAMES.size) { "عدد جداول النسخة غير مطابق" }
        val tables = linkedMapOf<String, Long>()
        repeat(count) {
            val name = data.readUTF()
            require(name in NAMES && name !in tables) { "اسم جدول غير مسموح" }
            tables[name] = data.readLong()
        }
        val preferencesBytes = if (format >= 2) data.readInt() else 0
        val preferencesSha = if (format >= 2) data.readUTF() else ""
        require(data.read() == -1) { "بيانات إضافية غير متوقعة في وصف النسخة" }
        return BackupManifest(
            id, created, bytes, sha, version, identity, tables,
            format, preferencesBytes, preferencesSha
        ).also(::validate)
    }

    fun preview(manifest: BackupManifest): BackupPreview = BackupPreview(
        manifest,
        manifest.tableCounts.getValue("customers"),
        manifest.tableCounts.getValue("financial_entries"),
        manifest.tableCounts.getValue("card_movements"),
        manifest.tableCounts.getValue("settlement_groups")
    )
}
