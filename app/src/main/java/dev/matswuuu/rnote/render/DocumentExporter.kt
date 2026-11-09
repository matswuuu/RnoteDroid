package dev.matswuuu.rnote.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Exports a [DocumentScene] to PDF or a raster image.
 * All functions are blocking and should be called on a background thread.
 */
object DocumentExporter {
    private const val PT_PER_INCH = 72.0
    private const val DEFAULT_DPI = 96.0
    private const val MAX_PDF_POINTS = 14400.0 // 200 inches, PdfDocument's practical limit
    private const val MAX_IMAGE_PIXELS = 32_000_000L
    private const val CONTENT_MARGIN = 24f

    data class Progress(val current: Int, val total: Int)

    /** The regions to export, in document coordinates, and their size in PDF points. */
    private fun pdfPages(scene: DocumentScene): List<Pair<RectF, RectF>> {
        val dpi = scene.document.format.dpi.takeIf { it > 0 } ?: DEFAULT_DPI
        val scale = (PT_PER_INCH / dpi).toFloat()
        val pages = scene.pages.ifEmpty { listOf(exportRegion(scene)) }
        return pages.map { page ->
            val w = (page.width() * scale).coerceIn(1f, MAX_PDF_POINTS.toFloat())
            val h = (page.height() * scale).coerceIn(1f, MAX_PDF_POINTS.toFloat())
            RectF(page) to RectF(0f, 0f, w, h)
        }
    }

    /** The area that contains all content, with a margin. */
    private fun exportRegion(scene: DocumentScene): RectF {
        val content = scene.contentBounds
        val region = if (content != null) {
            RectF(content).apply { union(scene.documentBounds) }
        } else {
            RectF(scene.documentBounds)
        }
        region.inset(-CONTENT_MARGIN, -CONTENT_MARGIN)
        if (region.isEmpty) return RectF(scene.documentBounds)
        return region
    }

    suspend fun writePdf(
        scene: DocumentScene,
        out: OutputStream,
        onProgress: (Progress) -> Unit = {},
    ) {
        val pages = pdfPages(scene)
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, (region, pageSize) ->
                coroutineContext.ensureActive()
                onProgress(Progress(index + 1, pages.size))
                val info = PdfDocument.PageInfo.Builder(
                    ceil(pageSize.width().toDouble()).toInt().coerceAtLeast(1),
                    ceil(pageSize.height().toDouble()).toInt().coerceAtLeast(1),
                    index + 1,
                ).create()
                val page = document.startPage(info)
                val canvas = page.canvas
                val scaleX = pageSize.width() / region.width()
                val scaleY = pageSize.height() / region.height()
                canvas.scale(scaleX, scaleY)
                canvas.translate(-region.left, -region.top)
                scene.draw(canvas, region, min(scaleX, scaleY))
                document.finishPage(page)
            }
            coroutineContext.ensureActive()
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /**
     * Renders the whole document as a bitmap. The image is scaled down if it would be too large.
     * @param scaleHint pixels per document unit; `null` uses the format DPI.
     */
    suspend fun renderBitmap(scene: DocumentScene, scaleHint: Float? = null): Bitmap {
        coroutineContext.ensureActive()
        val region = if (scene.pages.isNotEmpty()) {
            RectF(scene.documentBounds)
        } else {
            exportRegion(scene)
        }
        val hint = scaleHint ?: ((scene.document.format.dpi.takeIf { it > 0 } ?: DEFAULT_DPI) / 72.0).toFloat()
        val pixels = region.width().toDouble() * region.height() * hint * hint
        val scale = if (pixels > MAX_IMAGE_PIXELS) {
            (kotlin.math.sqrt(MAX_IMAGE_PIXELS / (region.width().toDouble() * region.height()))).toFloat()
        } else {
            hint
        }.coerceAtLeast(0.05f)

        val bitmap = Bitmap.createBitmap(
            ceil(region.width() * scale.toDouble()).toInt().coerceAtLeast(1),
            ceil(region.height() * scale.toDouble()).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        coroutineContext.ensureActive()
        val canvas = Canvas(bitmap)
        // a neutral background outside the document
        canvas.drawColor(scene.document.background.color.toArgb())
        canvas.scale(scale, scale)
        canvas.translate(-region.left, -region.top)
        scene.draw(canvas, region, scale)
        return bitmap
    }

    suspend fun writePng(scene: DocumentScene, out: OutputStream, onProgress: (Progress) -> Unit = {}) {
        onProgress(Progress(0, 1))
        val bitmap = renderBitmap(scene)
        try {
            coroutineContext.ensureActive()
            onProgress(Progress(1, 1))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bitmap.recycle()
        }
    }
}
