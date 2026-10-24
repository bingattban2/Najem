package com.najimwifi.app.data.report

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/** Android-native RTL shaping; A4 coordinates are PDF points. */
class PdfPageLayout(
    context: Context,
    private val document: PdfDocument,
    private val title: String,
    private val subheading: String,
    private val brand: String = "إدارة مبيعات الشبكة"
) {
    companion object {
        const val WIDTH = 595
        const val HEIGHT = 842
        const val MARGIN = 30f
        const val CONTENT_WIDTH = WIDTH - 2f * MARGIN
        private const val FOOTER_TOP = 804f
    }

    private val normalTypeface = Typeface.createFromAsset(
        context.applicationContext.assets, "fonts/Cairo-Regular.ttf"
    )
    private val boldTypeface = Typeface.createFromAsset(
        context.applicationContext.assets, "fonts/Cairo-Bold.ttf"
    )
    private var page: PdfDocument.Page? = null
    private var number = 0
    var cursor = 0f
        private set

    init {
        check(paint(12f).hasGlyph("ع")) {
            "خط أندرويد لا يدعم العربية؛ يلزم توفير خط عربي مرخص"
        }
    }

    val pageNumber: Int get() = number
    val canvas get() = checkNotNull(page) { "صفحة PDF غير مفتوحة" }.canvas

    private fun paint(size: Float, bold: Boolean = false) = TextPaint(
        Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
    ).apply {
        color = Color.BLACK
        textSize = size
        typeface = if (bold) boldTypeface else normalTypeface
    }

    private fun layout(text: String, width: Float, size: Float, bold: Boolean): StaticLayout {
        require(width > 0f)
        val safe = if (text.isEmpty()) " " else text
        val textPaint = paint(size, bold)
        return StaticLayout.Builder.obtain(
            safe, 0, safe.length, textPaint, width.toInt().coerceAtLeast(1)
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build()
    }

    fun measure(text: String, width: Float, size: Float = 9f, bold: Boolean = false): Float =
        layout(text, width, size, bold).height.toFloat()

    fun text(
        value: String,
        left: Float,
        top: Float,
        width: Float,
        size: Float = 9f,
        bold: Boolean = false
    ): Float {
        val item = layout(value, width, size, bold)
        canvas.save()
        canvas.translate(left, top)
        item.draw(canvas)
        canvas.restore()
        return item.height.toFloat()
    }

    fun line(y: Float) {
        canvas.drawLine(
            MARGIN, y, WIDTH - MARGIN, y,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.LTGRAY
                strokeWidth = 0.65f
            }
        )
    }

    fun openPage() {
        finishCurrent()
        number++
        page = document.startPage(
            PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, number).create()
        )
        canvas.drawColor(Color.WHITE)
        text(brand, MARGIN, 26f, CONTENT_WIDTH, 16f, true)
        text(title, MARGIN, 53f, CONTENT_WIDTH, 12f, true)
        text(subheading, MARGIN, 75f, CONTENT_WIDTH, 9f)
        line(105f)
        cursor = 115f
    }

    fun ensureSpace(height: Float): Boolean {
        require(height > 0f && height <= FOOTER_TOP - 115f) {
            "محتوى طويل جدًا لصفحة A4"
        }
        if (page == null) openPage()
        if (cursor + height <= FOOTER_TOP) return false
        openPage()
        return true
    }

    fun advance(height: Float) {
        cursor += height
    }

    fun paragraph(value: String, size: Float = 10f, bold: Boolean = false) {
        // Reflow long customer lists and notes across as many pages as needed.
        // One oversized StaticLayout may otherwise exceed the entire A4 page.
        val source = if (value.isEmpty()) " " else value
        val shaped = layout(source, CONTENT_WIDTH, size, bold)
        for (lineIndex in 0 until shaped.lineCount) {
            val start = shaped.getLineStart(lineIndex)
            val end = shaped.getLineEnd(lineIndex)
            val segment = source.substring(start, end).trimEnd('\n', '\r')
                .ifEmpty { " " }
            val lineHeight = measure(segment, CONTENT_WIDTH, size, bold) + 4f
            ensureSpace(lineHeight)
            text(segment, MARGIN, cursor, CONTENT_WIDTH, size, bold)
            advance(lineHeight)
        }
        // Skip spacing if it would create an empty trailing page.
        if (cursor + 8f <= FOOTER_TOP) advance(8f)
    }

    private fun finishCurrent() {
        val old = page ?: return
        text("صفحة $number", MARGIN, 811f, CONTENT_WIDTH, 9f)
        document.finishPage(old)
        page = null
    }

    fun finish() {
        if (page == null && number == 0) openPage()
        finishCurrent()
    }
}
