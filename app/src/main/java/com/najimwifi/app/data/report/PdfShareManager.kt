package com.najimwifi.app.data.report

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.najimwifi.app.domain.report.ReportDocument
import com.najimwifi.app.data.settings.SettingsStore
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Own provider subclass avoids platform-specific instantiation issues. */
class NajimPdfProvider : FileProvider()

/** Creates private cached PDFs, then grants access to one selected URI. */
class PdfShareManager(private val context: Context) {
    private val directory: File get() = File(context.cacheDir, "reports").apply {
        check(exists() || mkdirs()) { "تعذر إنشاء مجلد التقارير" }
    }

    private fun checked(file: File): File {
        require(file.canonicalFile.parentFile == directory.canonicalFile &&
            file.extension.equals("pdf", ignoreCase = true)) {
            "يمكن مشاركة تقارير PDF الصادرة من التطبيق فقط"
        }
        require(file.isFile) { "ملف التقرير غير موجود" }
        return file
    }

    suspend fun createReport(report: ReportDocument): File = withContext(Dispatchers.IO) {
        val file = File.createTempFile("najim_report_", ".pdf", directory)
        try {
            val brand = SettingsStore(context).settings.first().businessHeader.ifBlank { "إدارة مبيعات الشبكة" }
            FileOutputStream(file).use { ArabicPdfRenderer(context).render(report, it, brand) }
            checked(file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }


    /** Same private PDF lifecycle and Save/Share/Print support as customer reports. */
    suspend fun createOperationLogReport(
        rows: List<OperationLogPdfRow>,
        query: String,
        fromDate: java.time.LocalDate?,
        toDate: java.time.LocalDate?
    ): File = withContext(Dispatchers.IO) {
        val file = File.createTempFile("najim_operations_", ".pdf", directory)
        try {
            val brand = SettingsStore(context).settings.first().businessHeader.ifBlank { "إدارة مبيعات الشبكة" }
            FileOutputStream(file).use { output ->
                OperationLogPdfRenderer(context).render(rows, query, fromDate, toDate, output, brand)
            }
            checked(file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun share(file: File) {
        val safe = checked(file)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", safe
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, safe.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "مشاركة تقرير PDF").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Launch with Activity Result API; send result Uri to saveToUri(). */
    fun saveAsIntent(suggestedName: String): Intent {
        val safeName = suggestedName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            .trim('.').take(90).ifEmpty { "najim_report" }
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, if (safeName.endsWith(".pdf", true)) safeName else "$safeName.pdf")
        }
    }

    suspend fun saveToUri(file: File, destination: Uri) = withContext(Dispatchers.IO) {
        FileInputStream(checked(file)).use { source ->
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: error("تعذر فتح وجهة حفظ PDF")
            output.use { source.copyTo(it) }
        }
    }

    /** The system print UI handles printers and PDF destinations. */
    fun print(activity: Activity, file: File) {
        val safe = checked(file)
        val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(safe.name, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal,
                callback: LayoutResultCallback, extras: Bundle?
            ) {
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                } else {
                    callback.onLayoutFinished(
                        PrintDocumentInfo.Builder(safe.name)
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                            .build(), true
                    )
                }
            }
            override fun onWrite(
                pages: Array<out PageRange>, destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal, callback: WriteResultCallback
            ) {
                Thread {
                    try {
                        if (cancellationSignal.isCanceled) {
                            callback.onWriteCancelled()
                        } else {
                            FileInputStream(safe).use { input ->
                                FileOutputStream(destination.fileDescriptor).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                        }
                    } catch (error: Exception) {
                        callback.onWriteFailed(error.message ?: "خطأ الطباعة")
                    } finally {
                        destination.close()
                    }
                }.start()
            }
        }, PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build())
    }
}