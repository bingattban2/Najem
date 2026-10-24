package com.najimwifi.app.data.report

import android.database.Cursor
import androidx.room.withTransaction
import com.najimwifi.app.data.local.NajimDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One consistent read-only snapshot of the existing accounting tables. */
data class ReportSource(
    val customers: List<ReportDbRow>,
    val movements: List<ReportDbRow>,
    val groups: List<ReportDbRow>,
    val lines: List<ReportDbRow>,
    val financial: List<ReportDbRow>,
    val archiveMemberships: List<ReportDbRow>
)

/** Flat values retain Room's exact @Embedded prefixes: customer_, package_. */
data class ReportDbRow(private val fields: Map<String, Any?>) {
    fun text(name: String): String? = fields[name]?.toString()

    fun requiredText(name: String): String = text(name)
        ?.takeIf { it.isNotBlank() }
        ?: error("حقل مطلوب غير موجود في بيانات التقرير: $name")

    fun number(name: String): Long? = when (val value = fields[name]) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is String -> value.toLongOrNull()
        else -> error("قيمة غير صحيحة للحقل $name")
    }

    fun requiredNumber(name: String): Long = number(name)
        ?: error("حقل عددي مطلوب غير موجود: $name")
}

/**
 * Fixed SQL only: no user input or optional presentation columns are
 * interpolated into identifiers. Read the full history to compute opening
 * debt and end-of-period consignment as of any requested calendar date.
 */
class ReportQueries(private val database: NajimDatabase) {
    suspend fun snapshot(): ReportSource = withContext(Dispatchers.IO) {
        database.withTransaction {
            val db = database.openHelper.readableDatabase

            fun read(table: String): List<ReportDbRow> =
                db.query("SELECT * FROM $table").use { cursor ->
                    cursor.toReportRows()
                }

            ReportSource(
                customers = read("customers"),
                movements = read("card_movements"),
                groups = read("settlement_groups"),
                lines = read("settlement_lines"),
                financial = read("financial_entries"),
                archiveMemberships = read("archive_memberships")
            )
        }
    }

    private fun Cursor.toReportRows(): List<ReportDbRow> = buildList {
        while (moveToNext()) {
            val fields = LinkedHashMap<String, Any?>()
            for (i in 0 until columnCount) {
                fields[getColumnName(i)] = when (getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                    Cursor.FIELD_TYPE_STRING -> getString(i)
                    Cursor.FIELD_TYPE_BLOB -> error("حقل ثنائي غير مدعوم في التقرير")
                    Cursor.FIELD_TYPE_FLOAT -> error(
                        "قيمة عائمة في السجل؛ لا يمكن حساب مبلغ تقريبي"
                    )
                    else -> error("نوع حقل غير مدعوم")
                }
            }
            add(ReportDbRow(fields))
        }
    }
}