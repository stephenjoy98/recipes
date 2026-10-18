package com.stephenjoy.recipes.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.stephenjoy.recipes.domain.Recipe
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders recipes to an A4 PDF with the platform [PdfDocument] (no extra libraries, works on
 * every supported Android version). Each recipe starts on a new page; long recipes flow onto
 * further pages line by line.
 */
class PdfRecipeRenderer(private val imageFor: (Recipe) -> File?) {

    private val pageWidth = 595  // A4 at 72 dpi
    private val pageHeight = 842
    private val margin = 48f
    private val contentWidth = (pageWidth - 2 * margin).toInt()

    private val accent = Color.rgb(0x8B, 0x4A, 0x26)
    private val titlePaint = textPaint(24f, Typeface.create(Typeface.SERIF, Typeface.BOLD), Color.rgb(0x22, 0x1A, 0x15))
    private val headingPaint = textPaint(14f, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), accent)
    private val bodyPaint = textPaint(11f, Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL), Color.rgb(0x22, 0x1A, 0x15))
    private val mutedPaint = textPaint(10f, Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL), Color.rgb(0x6B, 0x5B, 0x53))

    fun render(recipes: List<Recipe>): ByteArray {
        val doc = PdfDocument()
        val writer = PageWriter(doc)
        recipes.forEach { recipe ->
            writer.newPage()
            drawRecipe(writer, recipe)
        }
        writer.finish()
        return ByteArrayOutputStream().use { out ->
            doc.writeTo(out)
            doc.close()
            out.toByteArray()
        }
    }

    private fun drawRecipe(w: PageWriter, r: Recipe) {
        w.paragraph(r.title, titlePaint, after = 6f)
        w.paragraph(RecipeFormats.metaLine(r), mutedPaint, after = 4f)
        val tags = listOfNotNull(r.category.takeIf { it.isNotBlank() }) + r.tags.map { "#$it" }
        if (tags.isNotEmpty()) w.paragraph(tags.joinToString("  ·  "), mutedPaint, after = 8f)
        if (r.description.isNotBlank()) w.paragraph(r.description, bodyPaint, after = 10f)

        imageFor(r)?.let { file -> decodeScaled(file)?.let { w.image(it, maxHeight = 220f) } }

        w.heading("Ingredients")
        r.ingredients.forEach { w.paragraph("•  " + RecipeFormats.ingredientLine(it), bodyPaint, after = 3f, indent = 6f) }

        if (r.steps.isNotEmpty()) {
            w.heading("Steps")
            r.steps.forEachIndexed { i, step -> w.paragraph("${i + 1}.  $step", bodyPaint, after = 6f, indent = 6f) }
        }
        val nutrition = RecipeFormats.nutritionLines(r.nutrition)
        if (nutrition.isNotEmpty()) {
            w.heading("Nutrition per serving (estimate)")
            w.paragraph(nutrition.joinToString("   ·   "), bodyPaint, after = 4f)
        }
        if (r.notes.isNotBlank()) {
            w.heading("Notes")
            w.paragraph(r.notes, bodyPaint, after = 4f)
        }
        if (r.sourceUrl.isNotBlank()) {
            w.space(12f)
            w.paragraph("Source: ${r.sourceUrl}" + if (r.author.isNotBlank()) " (${r.author})" else "", mutedPaint)
        }
    }

    private fun decodeScaled(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= contentWidth * 2) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun textPaint(size: Float, typeface: Typeface, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.typeface = typeface
        this.color = color
    }

    /** Lays out content top to bottom and starts new pages as needed. */
    private inner class PageWriter(private val doc: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = margin
        private val bottom = pageHeight - margin

        private val canvas: Canvas get() = page!!.canvas

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            y = margin
        }

        fun finish() {
            page?.let { doc.finishPage(it) }
            page = null
        }

        fun space(dy: Float) {
            y += dy
        }

        fun heading(text: String) {
            // Keep a heading together with at least a few lines of what follows.
            if (y + 60f > bottom) newPage()
            y += 10f
            paragraph(text, headingPaint, after = 4f)
            val line = Paint().apply { color = accent; strokeWidth = 1f; alpha = 90 }
            canvas.drawLine(margin, y, pageWidth - margin, y, line)
            y += 6f
        }

        fun paragraph(text: String, paint: TextPaint, after: Float = 0f, indent: Float = 0f) {
            val width = (contentWidth - indent).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .build()
            var line = 0
            while (line < layout.lineCount) {
                if (y + (layout.getLineBottom(line) - layout.getLineTop(line)) > bottom) newPage()
                // Draw as many lines as fit on this page.
                val first = line
                var last = line
                while (last + 1 < layout.lineCount &&
                    y + (layout.getLineBottom(last + 1) - layout.getLineTop(first)) <= bottom
                ) last++
                val top = layout.getLineTop(first).toFloat()
                val height = layout.getLineBottom(last) - top
                canvas.save()
                canvas.clipRect(margin + indent, y, pageWidth - margin, y + height)
                canvas.translate(margin + indent, y - top)
                layout.draw(canvas)
                canvas.restore()
                y += height
                line = last + 1
            }
            y += after
        }

        fun image(bitmap: Bitmap, maxHeight: Float) {
            val scale = minOf(contentWidth.toFloat() / bitmap.width, maxHeight / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            if (y + h > bottom) newPage()
            canvas.drawBitmap(bitmap, null, RectF(margin, y, margin + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 12f
            bitmap.recycle()
        }
    }
}
