package dev.matswuuu.rnote.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.caverock.androidsvg.SVG
import dev.matswuuu.rnote.format.Affine
import dev.matswuuu.rnote.format.LineCap
import dev.matswuuu.rnote.format.LineStyle
import dev.matswuuu.rnote.format.PenPath
import dev.matswuuu.rnote.format.Rectangle
import dev.matswuuu.rnote.format.RnoteColor
import dev.matswuuu.rnote.format.RoughStyle
import dev.matswuuu.rnote.format.Segment
import dev.matswuuu.rnote.format.Shape
import dev.matswuuu.rnote.format.SmoothStyle
import dev.matswuuu.rnote.format.Stroke
import dev.matswuuu.rnote.format.StrokeEntry
import dev.matswuuu.rnote.format.StrokeLayer
import dev.matswuuu.rnote.format.Style
import dev.matswuuu.rnote.format.TexturedStyle
import dev.matswuuu.rnote.format.Vec2
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Converts the strokes of an Rnote document into Android drawing operations,
 * following the composition logic of `rnote-compose`.
 */
object StrokeComposer {
    private const val TAG = "StrokeComposer"

    /** Upper bound for decoded bitmap image sizes, to keep memory usage in check. */
    private const val MAX_BITMAP_PIXELS = 16_000_000

    /**
     * Returns `null` for strokes that don't draw anything.
     * @throws Exception if the stroke can't be prepared, e.g. because of invalid image data.
     */
    fun prepare(entry: StrokeEntry): PreparedStroke? {
        val ops = ArrayList<DrawOp>()
        val bounds = RectF()
        when (val stroke = entry.stroke) {
            is Stroke.Brush -> composeBrush(stroke, ops, bounds)
            is Stroke.ShapeStroke -> composeShape(stroke.shape, stroke.style, ops, bounds)
            is Stroke.Text -> TextComposer.compose(stroke, ops, bounds)
            is Stroke.VectorImage -> composeVectorImage(stroke, ops, bounds)
            is Stroke.BitmapImage -> composeBitmapImage(stroke, ops, bounds)
        }
        if (ops.isEmpty()) return null
        return PreparedStroke(ops, bounds, entry.layer == StrokeLayer.Highlighter)
    }

    fun tryPrepare(entry: StrokeEntry): Result<PreparedStroke?> = try {
        Result.success(prepare(entry))
    } catch (e: Exception) {
        Log.w(TAG, "Failed to prepare ${entry.stroke::class.simpleName}", e)
        Result.failure(e)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory while preparing ${entry.stroke::class.simpleName}", e)
        Result.failure(e)
    }

    // -------------------------------------------------------------------------------------------
    // Paint helpers
    // -------------------------------------------------------------------------------------------

    internal fun fillPaint(color: RnoteColor) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color.toArgb()
    }

    private fun strokePaint(
        color: RnoteColor,
        width: Double,
        cap: Paint.Cap = Paint.Cap.BUTT,
        join: Paint.Join = Paint.Join.MITER,
        dash: FloatArray? = null,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color.toArgb()
        strokeWidth = width.toFloat()
        strokeCap = cap
        strokeJoin = join
        strokeMiter = 10f
        if (dash != null && dash.size >= 2 && dash.sum() > 0f) pathEffect = DashPathEffect(dash, 0f)
    }

    /** Port of `SmoothOptions::compute_piet_stroke_style()`. */
    private fun smoothDash(style: SmoothStyle): FloatArray? {
        val base = when (style.lineStyle) {
            LineStyle.SOLID -> return null
            LineStyle.DOTTED -> doubleArrayOf(0.0, 0.0)
            LineStyle.DASHED_NARROW -> doubleArrayOf(1.0, 0.618)
            LineStyle.DASHED_EQUIDISTANT -> doubleArrayOf(1.0, 1.0)
            LineStyle.DASHED_WIDE -> doubleArrayOf(1.0, 1.618)
        }
        val w = style.strokeWidth
        val dotted = style.lineStyle == LineStyle.DOTTED
        for (i in base.indices) {
            when (style.lineCap) {
                LineCap.STRAIGHT -> base[i] *= w * E
                LineCap.ROUNDED -> {
                    if (!dotted) base[i] *= w * E
                    if (i % 2 == 1) base[i] += 2.0 * w
                }
            }
        }
        // zero length dashes are needed for dots, but Skia needs strictly positive intervals
        return FloatArray(base.size) { max(base[it], 0.001).toFloat() }
    }

    private fun smoothStrokePaint(style: SmoothStyle, color: RnoteColor, join: Paint.Join = Paint.Join.MITER, cap: Paint.Cap? = null) =
        strokePaint(
            color, style.strokeWidth,
            cap = cap ?: if (style.lineCap == LineCap.ROUNDED) Paint.Cap.ROUND else Paint.Cap.BUTT,
            join = join,
            dash = smoothDash(style),
        )

    private fun addBounds(bounds: RectF, path: Path, outset: Float) {
        val r = RectF()
        path.computeBounds(r, true)
        r.inset(-outset, -outset)
        if (bounds.isEmpty) bounds.set(r) else bounds.union(r)
    }

    private fun addBounds(bounds: RectF, r: RectF) {
        if (bounds.isEmpty) bounds.set(r) else bounds.union(r)
    }

    internal fun Affine.toMatrix(): Matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                m[0].toFloat(), m[2].toFloat(), m[4].toFloat(),
                m[1].toFloat(), m[3].toFloat(), m[5].toFloat(),
                0f, 0f, 1f,
            )
        )
    }

    // -------------------------------------------------------------------------------------------
    // Brush strokes
    // -------------------------------------------------------------------------------------------

    private fun composeBrush(stroke: Stroke.Brush, ops: MutableList<DrawOp>, bounds: RectF) {
        when (val style = stroke.style) {
            is SmoothStyle -> composeSmoothPenPath(stroke.path, style, ops, bounds)
            is TexturedStyle -> TexturedComposer.composePenPath(stroke.path, style, ops, bounds)
            // Rnote does not support rough pen paths; draw them smooth instead of failing.
            is RoughStyle -> composeSmoothPenPath(
                stroke.path,
                SmoothStyle(strokeWidth = style.strokeWidth, strokeColor = style.strokeColor),
                ops, bounds,
            )
        }
    }

    private fun composeSmoothPenPath(path: PenPath, style: SmoothStyle, ops: MutableList<DrawOp>, bounds: RectF) {
        val color = style.strokeColor ?: return
        val full = Path()
        var singlePos = true
        var prev = path.start
        val lines = ArrayList<Pair<Vec2, Vec2>>()

        for (seg in path.segments) {
            if (seg.end.pos == path.start.pos) continue
            singlePos = false

            val ws = style.pressureCurve.apply(style.strokeWidth, prev.pressure)
            val we = style.pressureCurve.apply(style.strokeWidth, seg.end.pressure)
            lines.clear()
            when (seg) {
                is Segment.LineTo -> lines.add(prev.pos to seg.end.pos)
                is Segment.QuadBezTo -> approxBezier(prev.pos, seg.cp, seg.cp, seg.end.pos, quadratic = true, lines)
                is Segment.CubBezTo -> approxBezier(prev.pos, seg.cp1, seg.cp2, seg.end.pos, quadratic = false, lines)
            }
            composeLinesVariableWidth(full, lines, ws, we)
            prev = seg.end
        }

        if (!full.isEmpty) {
            ops.add(DrawOp.PathOp(full, fillPaint(color)))
            addBounds(bounds, full, 1f)
        }
        if (singlePos) {
            val r = (style.pressureCurve.apply(style.strokeWidth, path.start.pressure) * 0.5).toFloat()
            val p = path.start.pos
            ops.add(DrawOp.CircleOp(p.x.toFloat(), p.y.toFloat(), r, fillPaint(color)))
            addBounds(bounds, RectF(p.x.toFloat() - r - 1, p.y.toFloat() - r - 1, p.x.toFloat() + r + 1, p.y.toFloat() + r + 1))
        }
    }

    private fun noSubsegmentsForSegmentLen(len: Double): Int =
        if (len < 15.0 * 5) ceil(len / 15.0).toInt().coerceAtLeast(1) else 5

    private fun bezierPoint(p0: Vec2, c1: Vec2, c2: Vec2, p3: Vec2, quadratic: Boolean, t: Double): Vec2 {
        val mt = 1 - t
        return if (quadratic) {
            Vec2(
                mt * mt * p0.x + 2 * mt * t * c1.x + t * t * p3.x,
                mt * mt * p0.y + 2 * mt * t * c1.y + t * t * p3.y,
            )
        } else {
            Vec2(
                mt * mt * mt * p0.x + 3 * mt * mt * t * c1.x + 3 * mt * t * t * c2.x + t * t * t * p3.x,
                mt * mt * mt * p0.y + 3 * mt * mt * t * c1.y + 3 * mt * t * t * c2.y + t * t * t * p3.y,
            )
        }
    }

    private fun approxBezier(p0: Vec2, c1: Vec2, c2: Vec2, p3: Vec2, quadratic: Boolean, out: MutableList<Pair<Vec2, Vec2>>) {
        var perimeter = 0.0
        var last = p0
        for (i in 1..16) {
            val p = bezierPoint(p0, c1, c2, p3, quadratic, i / 16.0)
            perimeter += (p - last).length()
            last = p
        }
        val n = noSubsegmentsForSegmentLen(perimeter).coerceAtLeast(2)
        var start = p0
        for (i in 1..n) {
            val end = bezierPoint(p0, c1, c2, p3, quadratic, i.toDouble() / n)
            out.add(start to end)
            start = end
        }
    }

    /** Port of `compose_lines_variable_width()` from rnote-compose. */
    private fun composeLinesVariableWidth(path: Path, linesIn: List<Pair<Vec2, Vec2>>, startWidth: Double, endWidth: Double) {
        val lines = linesIn.filter { (it.second - it.first).length() > 0.0 }
        val n = lines.size
        if (n == 0) return

        val pos = ArrayList<Vec2>(n * 2)
        val neg = ArrayList<Vec2>(n * 2)
        lines.forEachIndexed { i, (s, e) ->
            val lsw = startWidth + (endWidth - startWidth) * (i.toDouble() / n)
            val lew = startWidth + (endWidth - startWidth) * ((i + 1).toDouble() / n)
            val orth = (e - s).orthUnit()
            pos.add(s + orth * (lsw * 0.5))
            neg.add(s - orth * (lsw * 0.5))
            pos.add(e + orth * (lew * 0.5))
            neg.add(e - orth * (lew * 0.5))
        }

        val startDir = (lines.first().second - lines.first().first).normalizeOrZero()
        val endDir = (lines.last().second - lines.last().first).normalizeOrZero()
        val startPos = pos.first()
        val endPos = pos.last()
        val startNeg = neg.first()
        val endNeg = neg.last()

        if (startWidth > 0.0 && startPos != startNeg) {
            path.moveTo(startNeg.x.toFloat(), startNeg.y.toFloat())
            val a = startNeg - startDir * (startWidth * (2.0 / 3.0))
            val b = startPos - startDir * (startWidth * (2.0 / 3.0))
            path.cubicTo(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), startPos.x.toFloat(), startPos.y.toFloat())
        } else {
            path.moveTo(startPos.x.toFloat(), startPos.y.toFloat())
        }
        for (p in pos) path.lineTo(p.x.toFloat(), p.y.toFloat())
        if (endWidth > 0.0 && endPos != endNeg) {
            val a = endPos + endDir * (endWidth * (2.0 / 3.0))
            val b = endNeg + endDir * (endWidth * (2.0 / 3.0))
            path.cubicTo(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), endNeg.x.toFloat(), endNeg.y.toFloat())
        } else {
            path.lineTo(endNeg.x.toFloat(), endNeg.y.toFloat())
        }
        for (i in neg.indices.reversed()) path.lineTo(neg[i].x.toFloat(), neg[i].y.toFloat())
        path.close()
    }

    // -------------------------------------------------------------------------------------------
    // Shapes
    // -------------------------------------------------------------------------------------------

    private const val ARROW_TIP_LENGTH = 10.0
    private const val ARROW_TIP_ANGLE = (13.0 / 16.0) * PI

    private fun arrowTipPoints(arrow: Shape.Arrow, strokeWidth: Double?): Pair<Vec2, Vec2> {
        val d = arrow.tip - arrow.start
        val dir = if (d.length() == 0.0) Vec2(1.0, 0.0) else d * (1.0 / d.length())
        val len = ARROW_TIP_LENGTH * (1.0 + 0.18 * (strokeWidth ?: 0.0))
        val v = dir * len
        return (v.rotate(ARROW_TIP_ANGLE) + arrow.tip) to (v.rotate(-ARROW_TIP_ANGLE) + arrow.tip)
    }

    private fun Path.moveTo(p: Vec2) = moveTo(p.x.toFloat(), p.y.toFloat())
    private fun Path.lineTo(p: Vec2) = lineTo(p.x.toFloat(), p.y.toFloat())

    private fun rectanglePath(r: Rectangle): Path = Path().apply {
        val c = r.corners()
        moveTo(c[0]); lineTo(c[1]); lineTo(c[2]); lineTo(c[3]); close()
    }

    private fun composeShape(shape: Shape, style: Style, ops: MutableList<DrawOp>, bounds: RectF) {
        when (style) {
            is SmoothStyle -> composeSmoothShape(shape, style, ops, bounds)
            is RoughStyle -> composeRoughShape(shape, style, ops, bounds)
            // Rnote only supports the textured style for pen paths
            is TexturedStyle -> composeSmoothShape(
                shape, SmoothStyle(strokeWidth = style.strokeWidth, strokeColor = style.strokeColor), ops, bounds
            )
        }
    }

    private fun composeSmoothShape(shape: Shape, style: SmoothStyle, ops: MutableList<DrawOp>, bounds: RectF) {
        val w = style.strokeWidth.toFloat()
        val outset = w * 2f + 1f

        fun fillAndStroke(path: Path, fill: Boolean = true, join: Paint.Join = Paint.Join.MITER, cap: Paint.Cap? = null) {
            if (fill) style.fillColor?.let { ops.add(DrawOp.PathOp(path, fillPaint(it))) }
            style.strokeColor?.let { ops.add(DrawOp.PathOp(path, smoothStrokePaint(style, it, join, cap))) }
            addBounds(bounds, path, outset)
        }

        fun dot(p: Vec2) {
            val color = style.strokeColor ?: return
            ops.add(DrawOp.CircleOp(p.x.toFloat(), p.y.toFloat(), w, fillPaint(color)))
            addBounds(bounds, RectF(p.x.toFloat() - w, p.y.toFloat() - w, p.x.toFloat() + w, p.y.toFloat() + w))
        }

        when (shape) {
            is Shape.Line -> fillAndStroke(Path().apply { moveTo(shape.start); lineTo(shape.end) }, fill = false)
            is Shape.Arrow -> {
                val (l, r) = arrowTipPoints(shape, style.strokeWidth)
                fillAndStroke(
                    Path().apply {
                        moveTo(shape.start); lineTo(shape.tip)
                        moveTo(l); lineTo(shape.tip); lineTo(r)
                    },
                    fill = false,
                )
            }

            is Shape.Rect -> fillAndStroke(rectanglePath(shape.rectangle))
            is Shape.Ellipse -> fillAndStroke(Path().apply {
                addOval(
                    RectF(-shape.radii.x.toFloat(), -shape.radii.y.toFloat(), shape.radii.x.toFloat(), shape.radii.y.toFloat()),
                    Path.Direction.CW,
                )
                transform(shape.affine.toMatrix())
            })

            is Shape.QuadBez -> fillAndStroke(Path().apply {
                moveTo(shape.start)
                quadTo(shape.cp.x.toFloat(), shape.cp.y.toFloat(), shape.end.x.toFloat(), shape.end.y.toFloat())
            })

            is Shape.CubBez -> fillAndStroke(Path().apply {
                moveTo(shape.start)
                cubicTo(
                    shape.cp1.x.toFloat(), shape.cp1.y.toFloat(),
                    shape.cp2.x.toFloat(), shape.cp2.y.toFloat(),
                    shape.end.x.toFloat(), shape.end.y.toFloat(),
                )
            })

            is Shape.Polyline -> {
                if (shape.path.isEmpty() || shape.path.all { it == shape.start }) {
                    dot(shape.start)
                } else {
                    val p = Path().apply { moveTo(shape.start); shape.path.forEach { lineTo(it) } }
                    fillAndStroke(p, fill = false, join = Paint.Join.BEVEL, cap = Paint.Cap.BUTT)
                }
            }

            is Shape.Polygon -> {
                if (shape.path.isEmpty() || shape.path.all { it == shape.start }) {
                    dot(shape.start)
                } else {
                    val p = Path().apply { moveTo(shape.start); shape.path.forEach { lineTo(it) }; close() }
                    if (style.strokeColor != null) {
                        fillAndStroke(p, join = Paint.Join.BEVEL, cap = Paint.Cap.BUTT)
                    }
                }
            }
        }
    }

    private fun composeRoughShape(shape: Shape, style: RoughStyle, ops: MutableList<DrawOp>, bounds: RectF) {
        // roughr's default stroke color is black, which Rnote does not override when no color is set.
        val strokeColor = style.strokeColor ?: RnoteColor.BLACK
        val fillColor = style.fillColor?.takeIf { !it.isTransparent }
        val hasFill = fillColor != null
        fun gen() = RoughGenerator(style.strokeWidth, Math.toDegrees(style.hachureAngle), style.fillStyle, style.seed)
        fun p(v: Vec2) = RoughGenerator.P(v.x, v.y)

        var transform: Matrix? = null
        var evenOddFill = false
        val sets = ArrayList<RoughGenerator.OpSet>()
        when (shape) {
            is Shape.Line -> sets += gen().line(shape.start.x, shape.start.y, shape.end.x, shape.end.y)
            is Shape.Arrow -> {
                sets += gen().line(shape.start.x, shape.start.y, shape.tip.x, shape.tip.y)
                val (l, r) = arrowTipPoints(shape, style.strokeWidth)
                sets += gen().linearPath(listOf(p(l), p(shape.tip), p(r)), close = false)
            }

            is Shape.Rect -> {
                val h = shape.rectangle.halfExtents
                sets += gen().rectangle(-h.x, -h.y, h.x * 2, h.y * 2, hasFill, stroke = true)
                transform = shape.rectangle.affine.toMatrix()
            }

            is Shape.Ellipse -> {
                sets += gen().ellipse(0.0, 0.0, shape.radii.x * 2, shape.radii.y * 2, hasFill, stroke = true)
                transform = shape.affine.toMatrix()
            }

            is Shape.QuadBez -> {
                sets += gen().bezierQuadratic(p(shape.start), p(shape.cp), p(shape.end), hasFill, stroke = true)
                evenOddFill = true
            }

            is Shape.CubBez -> {
                sets += gen().bezierCubic(p(shape.start), p(shape.cp1), p(shape.cp2), p(shape.end), hasFill, stroke = true)
                evenOddFill = true
            }

            is Shape.Polyline -> sets += gen().linearPath(listOf(shape.start).plus(shape.path).map(::p), close = false)
            is Shape.Polygon -> {
                sets += gen().polygon(listOf(shape.start).plus(shape.path).map(::p), hasFill, stroke = true)
                evenOddFill = true
            }
        }

        val w = style.strokeWidth
        for (set in sets) {
            transform?.let { set.path.transform(it) }
            val paint = when (set.type) {
                RoughGenerator.OpSetType.PATH -> strokePaint(strokeColor, w)
                RoughGenerator.OpSetType.FILL_PATH -> {
                    if (evenOddFill) set.path.fillType = Path.FillType.EVEN_ODD
                    fillPaint(fillColor ?: continue)
                }

                RoughGenerator.OpSetType.FILL_SKETCH -> strokePaint(fillColor ?: continue, w / 2.0)
            }
            ops.add(DrawOp.PathOp(set.path, paint))
            addBounds(bounds, set.path, (w * 2 + 2).toFloat())
        }
    }

    // -------------------------------------------------------------------------------------------
    // Images
    // -------------------------------------------------------------------------------------------

    private fun composeVectorImage(image: Stroke.VectorImage, ops: MutableList<DrawOp>, bounds: RectF) {
        val svg = SVG.getFromString(image.svgData)
        var iw = image.intrinsicSize.x.toFloat()
        var ih = image.intrinsicSize.y.toFloat()
        if (iw <= 0f || ih <= 0f) {
            iw = svg.documentWidth.takeIf { it > 0 } ?: (image.rectangle.halfExtents.x * 2).toFloat()
            ih = svg.documentHeight.takeIf { it > 0 } ?: (image.rectangle.halfExtents.y * 2).toFloat()
        }
        if (svg.documentViewBox == null) svg.setDocumentViewBox(0f, 0f, iw, ih)
        svg.setDocumentWidth(iw)
        svg.setDocumentHeight(ih)
        val picture = svg.renderToPicture(ceil(iw).toInt().coerceAtLeast(1), ceil(ih).toInt().coerceAtLeast(1))

        val h = image.rectangle.halfExtents
        val matrix = image.rectangle.affine.toMatrix().apply {
            preTranslate(-h.x.toFloat(), -h.y.toFloat())
            preScale((h.x * 2 / iw).toFloat(), (h.y * 2 / ih).toFloat())
        }
        ops.add(DrawOp.PictureOp(picture, matrix))
        addBounds(bounds, image.rectangle.bounds().toRectF())
    }

    private fun composeBitmapImage(image: Stroke.BitmapImage, ops: MutableList<DrawOp>, bounds: RectF) {
        val bitmap = decodeBitmap(image) ?: return
        val h = image.rectangle.halfExtents
        val dst = RectF(-h.x.toFloat(), -h.y.toFloat(), h.x.toFloat(), h.y.toFloat())
        ops.add(DrawOp.BitmapOp(bitmap, image.rectangle.affine.toMatrix(), dst))
        addBounds(bounds, image.rectangle.bounds().toRectF())
    }

    private fun decodeBitmap(image: Stroke.BitmapImage): Bitmap? {
        val w = image.pixelWidth
        val h = image.pixelHeight
        val bitmap = if (w > 0 && h > 0 && image.data.size.toLong() == w.toLong() * h * 4) {
            val step = ceil(sqrt(w.toDouble() * h / MAX_BITMAP_PIXELS)).toInt().coerceAtLeast(1)
            rgbaPremultipliedToBitmap(image.data, w, h, step)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image.data, 0, image.data.size, bounds)
            var sample = 1
            while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_BITMAP_PIXELS) sample *= 2
            BitmapFactory.decodeByteArray(
                image.data, 0, image.data.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
        return bitmap
    }

    /**
     * Converts premultiplied RGBA8 pixel data to a bitmap. The pixels are converted explicitly, because
     * the native memory layout of [Bitmap.Config.ARGB_8888] is platform dependent.
     */
    private fun rgbaPremultipliedToBitmap(data: ByteArray, w: Int, h: Int, step: Int): Bitmap {
        val outW = (w / step).coerceAtLeast(1)
        val outH = (h / step).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val row = IntArray(outW)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val i = ((y * step).toLong() * w + x * step).toInt() * 4
                val a = data[i + 3].toInt() and 0xff
                var r = data[i].toInt() and 0xff
                var g = data[i + 1].toInt() and 0xff
                var b = data[i + 2].toInt() and 0xff
                if (a in 1..254) {
                    // setPixels() expects unpremultiplied colors
                    r = min(255, (r * 255 + a / 2) / a)
                    g = min(255, (g * 255 + a / 2) / a)
                    b = min(255, (b * 255 + a / 2) / a)
                }
                row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(row, 0, outW, 0, y, outW, 1)
        }
        return bitmap
    }

    internal fun dev.matswuuu.rnote.format.Bounds.toRectF() =
        RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
}
