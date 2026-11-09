package dev.matswuuu.rnote.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.matswuuu.rnote.format.Background
import dev.matswuuu.rnote.format.PatternStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Draws the document background color and pattern. Patterns are aligned to the document origin,
 * like Rnote's SVG patterns with `patternUnits="userSpaceOnUse"`.
 */
class BackgroundPainter(private val background: Background) {
    private companion object {
        const val LINE_WIDTH = 0.5f
        const val DOTS_WIDTH = 1.5f
        const val HEXAGON_HEIGHT = 2.0f
        val SQRT_THREE = sqrt(3.0)

        /** Below this on-screen pattern spacing (in px), the pattern is not drawn. */
        const val MIN_SCREEN_SPACING = 3.0f

        /** Upper bound of pattern cells drawn per call. */
        const val MAX_CELLS = 250_000
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = background.color.toArgb()
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH
        color = background.patternColor.toArgb()
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = background.patternColor.toArgb()
    }

    /**
     * @param region the region to draw, in document coordinates (already clipped to the document)
     * @param scale screen pixels per document unit, used to skip patterns that would be too dense
     */
    fun draw(canvas: Canvas, region: RectF, scale: Float) {
        canvas.drawRect(region, fillPaint)

        val sx = background.patternSize.x.toFloat()
        val sy = background.patternSize.y.toFloat()
        if (sx <= 0f || sy <= 0f) return

        when (background.pattern) {
            PatternStyle.NONE -> {}
            PatternStyle.LINES -> {
                if (sy * scale < MIN_SCREEN_SPACING) return
                val off = LINE_WIDTH * 0.5f
                forEachIndex(region.top - off - LINE_WIDTH, region.bottom, sy) { j ->
                    val y = j * sy + off
                    canvas.drawLine(region.left, y, region.right, y, linePaint)
                }
            }

            PatternStyle.GRID -> {
                if (minOf(sx, sy) * scale < MIN_SCREEN_SPACING) return
                if (cellCount(region, sx, sy) > MAX_CELLS) return
                val off = LINE_WIDTH * 0.5f
                forEachIndex(region.top - LINE_WIDTH, region.bottom, sy) { j ->
                    val y = j * sy + off
                    canvas.drawLine(region.left, y, region.right, y, linePaint)
                }
                forEachIndex(region.left - LINE_WIDTH, region.right, sx) { i ->
                    val x = i * sx + off
                    canvas.drawLine(x, region.top, x, region.bottom, linePaint)
                }
            }

            PatternStyle.DOTS -> {
                if (minOf(sx, sy) * scale < MIN_SCREEN_SPACING) return
                if (cellCount(region, sx, sy) > MAX_CELLS) return
                val r = DOTS_WIDTH / 3f
                val rect = RectF()
                forEachIndex(region.top - DOTS_WIDTH, region.bottom, sy) { j ->
                    val y = j * sy
                    forEachIndex(region.left - DOTS_WIDTH, region.right, sx) { i ->
                        val x = i * sx
                        rect.set(x, y, x + DOTS_WIDTH, y + DOTS_WIDTH)
                        canvas.drawRoundRect(rect, r, r, dotPaint)
                    }
                }
            }

            PatternStyle.ISOMETRIC_GRID -> {
                val spacing = sy
                val w = (spacing * SQRT_THREE).toFloat()
                if (spacing * scale < MIN_SCREEN_SPACING) return
                if (cellCount(region, w, spacing) > MAX_CELLS) return
                val off = LINE_WIDTH * 0.5f
                val path = Path()
                forEachIndex(region.top - spacing, region.bottom, spacing) { j ->
                    val y = j * spacing
                    forEachIndex(region.left - w, region.right, w) { i ->
                        val x = i * w + off
                        path.moveTo(x, y); path.lineTo(x + w, y + spacing)
                        path.moveTo(x, y + spacing); path.lineTo(x + w, y)
                        path.moveTo(x + w * 0.5f, y); path.lineTo(x + w * 0.5f, y + spacing)
                        path.moveTo(x, y); path.lineTo(x, y + spacing)
                    }
                }
                canvas.save()
                canvas.clipRect(region)
                canvas.drawPath(path, linePaint)
                canvas.restore()
            }

            PatternStyle.ISOMETRIC_DOTS -> {
                val spacing = sy
                val w = (spacing * SQRT_THREE).toFloat()
                if (spacing * scale < MIN_SCREEN_SPACING) return
                if (cellCount(region, w, spacing) > MAX_CELLS) return
                val h = HEXAGON_HEIGHT
                val q = (SQRT_THREE / 4.0).toFloat() * h
                val half = (SQRT_THREE / 2.0).toFloat() * h
                val path = Path()
                fun hexagon(x: Float, y: Float) {
                    path.moveTo(x + q, y + h)
                    path.lineTo(x + half, y + 0.75f * h)
                    path.lineTo(x + half, y + 0.25f * h)
                    path.lineTo(x + q, y)
                    path.lineTo(x, y + 0.25f * h)
                    path.lineTo(x, y + 0.75f * h)
                    path.close()
                }
                forEachIndex(region.top - spacing, region.bottom, spacing) { j ->
                    val y = j * spacing
                    forEachIndex(region.left - w, region.right, w) { i ->
                        val x = i * w
                        hexagon(x, y)
                        hexagon(x + w * 0.5f, y + spacing * 0.5f)
                    }
                }
                canvas.save()
                canvas.clipRect(region)
                canvas.drawPath(path, dotPaint)
                canvas.restore()
            }
        }
    }

    private fun cellCount(region: RectF, sx: Float, sy: Float): Double =
        ceil(region.width() / sx + 2.0) * ceil(region.height() / sy + 2.0)

    private inline fun forEachIndex(from: Float, to: Float, step: Float, block: (Float) -> Unit) {
        val first = floor(from.toDouble() / step).toLong()
        val last = ceil(to.toDouble() / step).toLong()
        if (last - first > MAX_CELLS) return
        for (i in first..last) block(i.toFloat())
    }
}
