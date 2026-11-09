package dev.matswuuu.rnote.render

import android.graphics.Path
import dev.matswuuu.rnote.format.FillStyle
import java.util.SplittableRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A Kotlin port of the parts of `roughr` (a Rust port of Rough.js) that Rnote uses for its
 * "rough" shape style. The random number generator differs from the original, so the exact
 * jitter is not identical, but the look is.
 */
class RoughGenerator(
    private val strokeWidth: Double,
    /** Hachure angle in degrees. */
    private val hachureAngle: Double,
    private val fillStyle: FillStyle?,
    seed: Long?,
) {
    enum class OpSetType { PATH, FILL_PATH, FILL_SKETCH }

    class OpSet(val type: OpSetType, val path: Path)

    data class P(val x: Double, val y: Double)

    private class LineSeg(val start: P, val end: P) {
        fun length(): Double = sqrt((end.x - start.x).let { it * it } + (end.y - start.y).let { it * it })
    }

    private val maxRandomnessOffset = 2.0
    private val roughness = 1.0
    private val bowing = 2.0
    private val curveFitting = 0.95
    private val curveTightness = 0.0
    private val curveStepCount = 9.0
    private val hachureGap = -1.0
    private val fillWeight = -1.0

    private companion object {
        /** Limits for loops whose iteration count depends on the shape size, against absurd input. */
        const val MAX_STEPS = 10_000.0
        const val MAX_ITEMS_PER_LINE = 2_000
    }

    private val random = SplittableRandom(seed ?: 345L)

    private fun rand(): Double = random.nextDouble()

    private fun offset(min: Double, max: Double, gain: Double = 1.0): Double =
        roughness * gain * (rand() * (max - min) + min)

    private fun offsetOpt(x: Double, gain: Double = 1.0): Double = offset(-x, x, gain)

    // -------------------------------------------------------------------------------------------
    // Public shape generators. Each returns the op sets in drawing order.
    // -------------------------------------------------------------------------------------------

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, stroke: Boolean = true): List<OpSet> =
        if (stroke) listOf(OpSet(OpSetType.PATH, Path().also { doubleLine(it, x1, y1, x2, y2) })) else emptyList()

    fun linearPath(points: List<P>, close: Boolean, stroke: Boolean = true): List<OpSet> =
        if (stroke) listOf(OpSet(OpSetType.PATH, linearPathOps(points, close))) else emptyList()

    fun polygon(points: List<P>, fill: Boolean, stroke: Boolean): List<OpSet> {
        val outline = linearPathOps(points, true)
        val sets = ArrayList<OpSet>()
        if (fill) sets.add(fillPolygon(points))
        if (stroke) sets.add(OpSet(OpSetType.PATH, outline))
        return sets
    }

    fun rectangle(x: Double, y: Double, w: Double, h: Double, fill: Boolean, stroke: Boolean): List<OpSet> {
        val points = listOf(P(x, y), P(x + w, y), P(x + w, y + h), P(x, y + h))
        val outline = linearPathOps(points, true)
        val sets = ArrayList<OpSet>()
        if (fill) sets.add(fillPolygon(points))
        if (stroke) sets.add(OpSet(OpSetType.PATH, outline))
        return sets
    }

    fun ellipse(cx: Double, cy: Double, width: Double, height: Double, fill: Boolean, stroke: Boolean): List<OpSet> {
        val params = generateEllipseParams(width, height)
        val outline = Path()
        val estimated = ellipseWithParams(outline, cx, cy, params)
        val sets = ArrayList<OpSet>()
        if (fill) {
            if (fillStyle == FillStyle.SOLID) {
                val shape = Path()
                ellipseWithParams(shape, cx, cy, params)
                sets.add(OpSet(OpSetType.FILL_PATH, shape))
            } else {
                sets.add(OpSet(OpSetType.FILL_SKETCH, patternFill(estimated)))
            }
        }
        if (stroke) sets.add(OpSet(OpSetType.PATH, outline))
        return sets
    }

    fun bezierQuadratic(start: P, cp: P, end: P, fill: Boolean, stroke: Boolean): List<OpSet> {
        val cp1 = P(start.x + 2.0 / 3.0 * (cp.x - start.x), start.y + 2.0 / 3.0 * (cp.y - start.y))
        val cp2 = P(end.x + 2.0 / 3.0 * (cp.x - end.x), end.y + 2.0 / 3.0 * (cp.y - end.y))
        return bezierCubic(start, cp1, cp2, end, fill, stroke)
    }

    fun bezierCubic(start: P, cp1: P, cp2: P, end: P, fill: Boolean, stroke: Boolean): List<OpSet> {
        val outline = Path()
        bezierTo(outline, cp1.x, cp1.y, cp2.x, cp2.y, end.x, end.y, start)
        val sets = ArrayList<OpSet>()
        if (fill) {
            val poly = (0..20).map { i ->
                val t = i / 20.0
                val mt = 1 - t
                P(
                    mt * mt * mt * start.x + 3 * mt * mt * t * cp1.x + 3 * mt * t * t * cp2.x + t * t * t * end.x,
                    mt * mt * mt * start.y + 3 * mt * mt * t * cp1.y + 3 * mt * t * t * cp2.y + t * t * t * end.y,
                )
            }
            sets.add(fillPolygon(poly))
        }
        if (stroke) sets.add(OpSet(OpSetType.PATH, outline))
        return sets
    }

    // -------------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------------

    private fun fillPolygon(points: List<P>): OpSet =
        if (fillStyle == FillStyle.SOLID) solidFillPolygon(points) else OpSet(OpSetType.FILL_SKETCH, patternFill(points))

    private fun linearPathOps(points: List<P>, close: Boolean): Path {
        val path = Path()
        val len = points.size
        if (len > 2) {
            for (i in 0 until len - 1) {
                doubleLine(path, points[i].x, points[i].y, points[i + 1].x, points[i + 1].y)
            }
            if (close) doubleLine(path, points[len - 1].x, points[len - 1].y, points[0].x, points[0].y)
        } else if (len == 2) {
            doubleLine(path, points[0].x, points[0].y, points[1].x, points[1].y)
        }
        return path
    }

    private fun solidFillPolygon(points: List<P>): OpSet {
        val path = Path()
        if (points.size > 2) {
            points.forEachIndexed { i, p ->
                val x = (p.x + offsetOpt(maxRandomnessOffset)).toFloat()
                val y = (p.y + offsetOpt(maxRandomnessOffset)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        return OpSet(OpSetType.FILL_PATH, path)
    }

    private fun doubleLine(path: Path, x1: Double, y1: Double, x2: Double, y2: Double) {
        singleLine(path, x1, y1, x2, y2, overlay = false)
        singleLine(path, x1, y1, x2, y2, overlay = true)
    }

    private fun singleLine(path: Path, x1: Double, y1: Double, x2: Double, y2: Double, overlay: Boolean) {
        val lengthSq = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
        val length = sqrt(lengthSq)
        val gain = when {
            length < 200.0 -> 1.0
            length > 500.0 -> 0.4
            else -> -0.0016668 * length + 1.233334
        }
        var off = maxRandomnessOffset
        if (off * off * 100.0 > lengthSq) off = length / 10.0
        val halfOffset = off / 2.0
        val divergePoint = 0.2 + rand() * 0.2
        var midDispX = bowing * maxRandomnessOffset * (y2 - y1) / 200.0
        var midDispY = bowing * maxRandomnessOffset * (x1 - x2) / 200.0
        midDispX = offsetOpt(midDispX, gain)
        midDispY = offsetOpt(midDispY, gain)
        val o = if (overlay) halfOffset else off

        path.moveTo((x1 + offsetOpt(o, gain)).toFloat(), (y1 + offsetOpt(o, gain)).toFloat())
        val c1x = midDispX + x1 + (x2 - x1) * divergePoint + offsetOpt(o, gain)
        val c1y = midDispY + y1 + (y2 - y1) * divergePoint + offsetOpt(o, gain)
        val c2x = midDispX + x1 + 2.0 * (x2 - x1) * divergePoint + offsetOpt(o, gain)
        val c2y = midDispY + y1 + 2.0 * (y2 - y1) * divergePoint + offsetOpt(o, gain)
        val ex = x2 + offsetOpt(o, gain)
        val ey = y2 + offsetOpt(o, gain)
        path.cubicTo(c1x.toFloat(), c1y.toFloat(), c2x.toFloat(), c2y.toFloat(), ex.toFloat(), ey.toFloat())
    }

    private fun curve(path: Path, points: List<P>) {
        val len = points.size
        if (len > 3) {
            val s = 1.0 - curveTightness
            path.moveTo(points[1].x.toFloat(), points[1].y.toFloat())
            var i = 1
            while (i + 2 < len) {
                val p = points[i]
                val b1x = p.x + (s * points[i + 1].x - s * points[i - 1].x) / 6.0
                val b1y = p.y + (s * points[i + 1].y - s * points[i - 1].y) / 6.0
                val b2x = points[i + 1].x + (s * points[i].x - s * points[i + 2].x) / 6.0
                val b2y = points[i + 1].y + (s * points[i].y - s * points[i + 2].y) / 6.0
                path.cubicTo(
                    b1x.toFloat(), b1y.toFloat(), b2x.toFloat(), b2y.toFloat(),
                    points[i + 1].x.toFloat(), points[i + 1].y.toFloat(),
                )
                i++
            }
        } else if (len == 3) {
            path.moveTo(points[1].x.toFloat(), points[1].y.toFloat())
            path.cubicTo(
                points[1].x.toFloat(), points[1].y.toFloat(),
                points[2].x.toFloat(), points[2].y.toFloat(),
                points[2].x.toFloat(), points[2].y.toFloat(),
            )
        } else if (len == 2) {
            doubleLine(path, points[0].x, points[0].y, points[1].x, points[1].y)
        }
    }

    private class EllipseParams(val increment: Double, val rx: Double, val ry: Double)

    private fun generateEllipseParams(width: Double, height: Double): EllipseParams {
        val psq = sqrt(PI * 2.0 * sqrt(((width / 2.0) * (width / 2.0) + (height / 2.0) * (height / 2.0)) / 2.0))
        require(psq.isFinite()) { "invalid ellipse size" }
        val stepCount = ceil(max(curveStepCount, (curveStepCount / sqrt(200.0)) * psq)).coerceAtMost(MAX_STEPS)
        val increment = PI * 2.0 / stepCount
        var rx = abs(width / 2.0)
        var ry = abs(height / 2.0)
        val curveFitRandomness = 1.0 - curveFitting
        rx += offsetOpt(rx * curveFitRandomness)
        ry += offsetOpt(ry * curveFitRandomness)
        return EllipseParams(increment, rx, ry)
    }

    /** Appends the ellipse outline to [path] and returns the estimated core points. */
    private fun ellipseWithParams(path: Path, cx: Double, cy: Double, p: EllipseParams): List<P> {
        val (all, core) = computeEllipsePoints(
            p.increment, cx, cy, p.rx, p.ry, 1.0,
            p.increment * offset(0.1, offset(0.4, 1.0)),
        )
        curve(path, all)
        val (all2, _) = computeEllipsePoints(p.increment, cx, cy, p.rx, p.ry, 1.5, 0.0)
        curve(path, all2)
        return core
    }

    private fun computeEllipsePoints(
        increment: Double, cx: Double, cy: Double, rx: Double, ry: Double, offset: Double, overlap: Double,
    ): Pair<List<P>, List<P>> {
        val core = ArrayList<P>()
        val all = ArrayList<P>()
        val radOffset = offsetOpt(0.5) - PI / 2.0
        all.add(
            P(
                offsetOpt(offset) + cx + 0.9 * rx * cos(radOffset - increment),
                offsetOpt(offset) + cy + 0.9 * ry * sin(radOffset - increment),
            )
        )
        val endAngle = PI * 2.0 + radOffset - 0.01
        var angle = radOffset
        while (angle < endAngle) {
            val p = P(offsetOpt(offset) + cx + rx * cos(angle), offsetOpt(offset) + cy + ry * sin(angle))
            core.add(p)
            all.add(p)
            angle += increment
        }
        all.add(
            P(
                offsetOpt(offset) + cx + rx * cos(radOffset + PI * 2.0 + overlap * 0.5),
                offsetOpt(offset) + cy + ry * sin(radOffset + PI * 2.0 + overlap * 0.5),
            )
        )
        all.add(
            P(
                offsetOpt(offset) + cx + 0.98 * rx * cos(radOffset + overlap),
                offsetOpt(offset) + cy + 0.98 * ry * sin(radOffset + overlap),
            )
        )
        all.add(
            P(
                offsetOpt(offset) + cx + 0.9 * rx * cos(radOffset + overlap * 0.5),
                offsetOpt(offset) + cy + 0.9 * ry * sin(radOffset + overlap * 0.5),
            )
        )
        return all to core
    }

    private fun bezierTo(path: Path, x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double, current: P) {
        val ros = doubleArrayOf(maxRandomnessOffset, maxRandomnessOffset + 0.3)
        for (i in 0 until 2) {
            if (i == 0) {
                path.moveTo(current.x.toFloat(), current.y.toFloat())
            } else {
                path.moveTo((current.x + offsetOpt(ros[0])).toFloat(), (current.y + offsetOpt(ros[0])).toFloat())
            }
            val fx = x + offsetOpt(ros[i])
            val fy = y + offsetOpt(ros[i])
            path.cubicTo(
                (x1 + offsetOpt(ros[i])).toFloat(), (y1 + offsetOpt(ros[i])).toFloat(),
                (x2 + offsetOpt(ros[i])).toFloat(), (y2 + offsetOpt(ros[i])).toFloat(),
                fx.toFloat(), fy.toFloat(),
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // Pattern fills
    // -------------------------------------------------------------------------------------------

    private val gap: Double get() = max(if (hachureGap < 0) strokeWidth * 4.0 else hachureGap, 0.1)

    private fun patternFill(polygon: List<P>): Path {
        val path = Path()
        when (fillStyle) {
            FillStyle.CROSSHATCH -> {
                hachureLines(polygon, hachureAngle, gap).forEach { doubleLine(path, it) }
                hachureLines(polygon, hachureAngle + 90.0, gap).forEach { doubleLine(path, it) }
            }

            FillStyle.ZIG_ZAG -> {
                val g = gap
                val angle = PI / 180.0 * hachureAngle
                val dgx = g * 0.5 * cos(angle)
                val dgy = g * 0.5 * sin(angle)
                for (l in hachureLines(polygon, hachureAngle, g)) {
                    if (l.length() > 0) {
                        doubleLine(path, LineSeg(P(l.start.x - dgx, l.start.y + dgy), l.end))
                        doubleLine(path, LineSeg(P(l.start.x + dgx, l.start.y - dgy), l.end))
                    }
                }
            }

            FillStyle.ZIG_ZAG_LINE -> {
                val g = gap
                val zigZagOffset = g
                for (l in hachureLines(polygon, hachureAngle, g + zigZagOffset)) {
                    val count = (l.length() / (2.0 * zigZagOffset)).toInt().coerceAtMost(MAX_ITEMS_PER_LINE)
                    var p1 = l.start
                    var p2 = l.end
                    if (p1.x > p2.x) {
                        p1 = l.end; p2 = l.start
                    }
                    val alpha = atan((p2.y - p1.y) / (p2.x - p1.x))
                    val dz = sqrt(zigZagOffset * zigZagOffset * 2.0)
                    for (i in 0 until count) {
                        val lstart = i * 2.0 * zigZagOffset
                        val lend = (i + 1) * 2.0 * zigZagOffset
                        val start = P(p1.x + lstart * cos(alpha), p1.y + lstart * sin(alpha))
                        val end = P(p1.x + lend * cos(alpha), p1.y + lend * sin(alpha))
                        val middle = P(start.x + dz * cos(alpha + PI / 4.0), start.y + dz * sin(alpha + PI / 4.0))
                        doubleLine(path, start.x, start.y, middle.x, middle.y)
                        doubleLine(path, middle.x, middle.y, end.x, end.y)
                    }
                }
            }

            FillStyle.DOTS -> {
                val g = gap
                val fweight = if (fillWeight < 0) strokeWidth / 2.0 else fillWeight
                val ro = g / 4.0
                for (l in hachureLines(polygon, 0.0, g)) {
                    val length = l.length()
                    val count = ceil(length / g) - 1
                    if (count < 0) continue
                    val off = length - count * g
                    val x = (l.start.x + l.end.x) / 2.0 - g / 4.0
                    val minY = min(l.start.y, l.end.y)
                    for (i in 0 until count.toInt().coerceAtMost(MAX_ITEMS_PER_LINE)) {
                        val y = minY + off + i * g
                        val cx = (x - ro) + rand() * 2.0 * ro
                        val cy = (y - ro) + rand() * 2.0 * ro
                        val params = generateEllipseParams(fweight, fweight)
                        ellipseWithParams(path, cx, cy, params)
                    }
                }
            }

            FillStyle.DASHED -> {
                val off = gap
                val g = gap
                for (l in hachureLines(polygon, hachureAngle, g)) {
                    val length = l.length()
                    val count = floor(length / (off + g))
                    val startOffset = (length + g - count * (off + g)) / 2.0
                    var p1 = l.start
                    var p2 = l.end
                    if (p1.x > p2.x) {
                        p1 = l.end; p2 = l.start
                    }
                    val alpha = atan((p2.y - p1.y) / (p2.x - p1.x))
                    for (i in 0 until count.toInt().coerceAtMost(MAX_ITEMS_PER_LINE)) {
                        val lstart = i * (off + g)
                        val lend = lstart + off
                        doubleLine(
                            path,
                            p1.x + lstart * cos(alpha) + startOffset * cos(alpha),
                            p1.y + lstart * sin(alpha) + startOffset * sin(alpha),
                            p1.x + lend * cos(alpha) + startOffset * cos(alpha),
                            p1.y + lend * sin(alpha) + startOffset * sin(alpha),
                        )
                    }
                }
            }

            else -> hachureLines(polygon, hachureAngle, gap).forEach { doubleLine(path, it) }
        }
        return path
    }

    private fun doubleLine(path: Path, l: LineSeg) = doubleLine(path, l.start.x, l.start.y, l.end.x, l.end.y)

    private fun rotate(p: P, degrees: Double): P {
        val a = degrees * PI / 180.0
        val c = cos(a)
        val s = sin(a)
        return P(p.x * c - p.y * s, p.x * s + p.y * c)
    }

    private class Edge(var ymin: Double, var ymax: Double, var x: Double, var islope: Double)

    private fun hachureLines(polygon: List<P>, hachureAngleDeg: Double, gap: Double): List<LineSeg> {
        val angle = hachureAngleDeg + 90.0
        val rotated = if (angle != 0.0) polygon.map { rotate(it, angle) } else polygon
        val lines = straightHachureLines(rotated, gap)
        return if (angle != 0.0) lines.map { LineSeg(rotate(it.start, -angle), rotate(it.end, -angle)) } else lines
    }

    private fun straightHachureLines(polygonIn: List<P>, gapIn: Double): List<LineSeg> {
        val vertices = ArrayList(polygonIn)
        if (vertices.isNotEmpty() && vertices.first() != vertices.last()) vertices.add(vertices.first())
        val lines = ArrayList<LineSeg>()
        if (vertices.size <= 2) return lines
        val gap = max(gapIn, 0.1)

        val edges = ArrayList<Edge>()
        for (i in 0 until vertices.size - 1) {
            val p1 = vertices[i]
            val p2 = vertices[i + 1]
            if (p1.y != p2.y) {
                val ymin = min(p1.y, p2.y)
                edges.add(
                    Edge(ymin, max(p1.y, p2.y), if (ymin == p1.y) p1.x else p2.x, (p2.x - p1.x) / (p2.y - p1.y))
                )
            }
        }
        edges.sortWith(compareBy<Edge> { it.ymin }.thenBy { it.x }.thenBy { it.ymax })
        if (edges.isEmpty()) return lines

        val active = ArrayList<Edge>()
        var y = edges.first().ymin
        var guard = 0
        while (guard++ < 100_000) {
            if (edges.isNotEmpty()) {
                val idx = edges.indexOfFirst { it.ymin > y }
                val take = if (idx >= 0) idx else edges.size
                repeat(take) { active.add(edges.removeAt(0)) }
            }
            active.removeAll { it.ymax <= y }
            active.sortBy { it.x }
            if (active.size > 1) {
                var i = 0
                while (i + 1 < active.size) {
                    lines.add(LineSeg(P(active[i].x, y), P(active[i + 1].x, y)))
                    i += 2
                }
            }
            y += gap
            active.forEach { it.x += gap * it.islope }
            if (edges.isEmpty() && active.isEmpty()) break
        }
        return lines
    }
}
