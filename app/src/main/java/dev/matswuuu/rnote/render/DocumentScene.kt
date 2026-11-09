package dev.matswuuu.rnote.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import dev.matswuuu.rnote.format.Layout
import dev.matswuuu.rnote.format.RnoteFile
import dev.matswuuu.rnote.render.StrokeComposer.toRectF
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * A document with all strokes prepared for drawing.
 *
 * The prepared paths and paints are shared between draw calls, so drawing is serialized.
 */
class DocumentScene private constructor(
    val file: RnoteFile,
    private val strokes: List<PreparedStroke>,
    /** Number of strokes that could not be prepared for drawing. */
    val failedStrokes: Int,
) {
    val document = file.document
    val documentBounds: RectF = document.bounds.toRectF()

    /** Page rectangles, only for layouts that are split into pages. */
    val pages: List<RectF> = when (document.layout) {
        Layout.FIXED_SIZE, Layout.CONTINUOUS_VERTICAL -> document.pageBounds().map { it.toRectF() }
        else -> emptyList()
    }

    /** All page rectangles for drawing borders, for every layout. */
    private val borderPages: List<RectF> =
        if (document.format.showBorders) document.pageBounds().map { it.toRectF() } else emptyList()

    /** Union of the bounds of all strokes, or `null` if the document is empty. */
    val contentBounds: RectF? = strokes.fold(null as RectF?) { acc, s ->
        acc?.apply { union(s.bounds) } ?: RectF(s.bounds)
    }

    val strokeCount get() = strokes.size

    private val backgroundPainter = BackgroundPainter(document.background)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = document.format.borderColor.toArgb()
    }

    private val highlighterPaint = Paint().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) blendMode = BlendMode.MULTIPLY
    }

    private val tmpRect = RectF()

    /**
     * Draws the given [region] of the document. The canvas must already be transformed so that
     * document coordinates map to the desired output; [scale] is the resulting pixels per document unit.
     */
    @Synchronized
    fun draw(canvas: Canvas, region: RectF, scale: Float) {
        val clip = tmpRect
        if (!clip.setIntersect(region, documentBounds)) return

        val save = canvas.save()
        canvas.clipRect(clip)
        backgroundPainter.draw(canvas, clip, scale)

        if (borderPages.isNotEmpty()) {
            borderPaint.strokeWidth = 1f / scale
            for (page in borderPages) {
                if (RectF.intersects(page, clip)) canvas.drawRect(page, borderPaint)
            }
        }

        for (stroke in strokes) {
            if (!RectF.intersects(stroke.bounds, region)) continue
            if (stroke.highlighter) {
                val layer = canvas.saveLayer(stroke.bounds, highlighterPaint)
                for (op in stroke.ops) op.draw(canvas)
                canvas.restoreToCount(layer)
            } else {
                for (op in stroke.ops) op.draw(canvas)
            }
        }
        canvas.restoreToCount(save)
    }

    /** The region shown in thumbnails: the first page, or the content. */
    fun thumbnailRegion(): RectF {
        pages.firstOrNull()?.let { return RectF(it) }
        val content = contentBounds ?: return RectF(documentBounds)
        return RectF(content).apply {
            inset(-32f, -32f)
            if (!intersect(documentBounds)) set(documentBounds)
        }
    }

    fun renderThumbnail(maxSize: Int): Bitmap? {
        val region = thumbnailRegion()
        val longest = max(region.width(), region.height())
        if (longest <= 0f) return null
        val scale = maxSize / longest
        val bitmap = Bitmap.createBitmap(
            (region.width() * scale).toInt().coerceAtLeast(1),
            (region.height() * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(document.background.color.toArgb())
        canvas.scale(scale, scale)
        canvas.translate(-region.left, -region.top)
        draw(canvas, region, scale)
        return bitmap
    }

    companion object {
        suspend fun create(file: RnoteFile): DocumentScene {
            val prepared = ArrayList<PreparedStroke>(file.strokes.size)
            var failed = 0
            for ((i, entry) in file.strokes.withIndex()) {
                if (i % 64 == 0) coroutineContext.ensureActive()
                StrokeComposer.tryPrepare(entry)
                    .onSuccess { if (it != null) prepared.add(it) }
                    .onFailure { failed++ }
            }
            return DocumentScene(file, prepared, failed + file.skippedStrokes)
        }
    }
}
