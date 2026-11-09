package dev.matswuuu.rnote.render

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import dev.matswuuu.rnote.format.PenPath
import dev.matswuuu.rnote.format.TexturedDotsDistribution
import dev.matswuuu.rnote.format.TexturedStyle
import dev.matswuuu.rnote.format.Vec2
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Port of Rnote's "textured" pen style: every segment is filled with randomly placed small ellipses.
 * The random number generator differs from Rnote's, so the dots are not placed identically.
 */
object TexturedComposer {
    private const val DOTS_RADII_X = 1.2
    private const val DOTS_RADII_Y = 0.3
    private const val STROKE_WIDTH_RADII_WEIGHT = 0.1

    /** Upper bound of dots per segment, to protect against absurd densities. */
    private const val MAX_DOTS_PER_SEGMENT = 20_000

    fun composePenPath(path: PenPath, style: TexturedStyle, ops: MutableList<DrawOp>, bounds: RectF) {
        val color = style.strokeColor ?: return
        val full = Path()
        var seed = style.seed ?: System.nanoTime()
        var prev = path.start
        val tmp = Path()
        val matrix = Matrix()

        for (seg in path.segments) {
            if (seg.end.pos == path.start.pos) {
                seed = advanceSeed(seed)
                continue
            }
            val width = style.pressureCurve.apply(style.strokeWidth, (prev.pressure + seg.end.pressure) * 0.5)
            composeTexturedLine(full, tmp, matrix, prev.pos, seg.end.pos, width, style, seed)
            prev = seg.end
            seed = advanceSeed(seed)
        }
        if (full.isEmpty) return
        ops.add(DrawOp.PathOp(full, StrokeComposer.fillPaint(color)))
        val r = RectF()
        full.computeBounds(r, true)
        r.inset(-2f, -2f)
        if (bounds.isEmpty) bounds.set(r) else bounds.union(r)
    }

    private fun advanceSeed(seed: Long): Long {
        // splitmix64
        var z = seed + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun composeTexturedLine(
        out: Path, tmp: Path, matrix: Matrix,
        start: Vec2, end: Vec2, width: Double, style: TexturedStyle, seed: Long,
    ) {
        val vec = end - start
        val length = vec.length()
        if (length <= 0.0 || width <= 0.0) return

        val rng = Random(seed)
        val angle = atan2(vec.y, vec.x)
        val center = start + vec * 0.5
        val hx = length * 0.5
        val hy = width * 0.5
        val area = 4.0 * hx * hy

        val radiiFactor = 1.0 + width * STROKE_WIDTH_RADII_WEIGHT
        val rx = DOTS_RADII_X * radiiFactor
        val ry = DOTS_RADII_Y * radiiFactor

        val nDots = (area * 0.1 * style.density).roundToInt().coerceIn(0, MAX_DOTS_PER_SEGMENT)
        val c = cos(angle)
        val s = sin(angle)

        repeat(nDots) {
            val x = uniform(rng, -hx, hx)
            val y = sampleY(rng, style.distribution, -hy, hy)
            val px = center.x + c * x - s * y
            val py = center.y + s * x + c * y
            val rot = angle + uniform(rng, -PI / 8, PI / 8)
            val dotRx = uniform(rng, rx * 0.8, rx * 1.25)
            val dotRy = uniform(rng, ry * 0.8, ry * 1.25)

            tmp.rewind()
            tmp.addOval((-dotRx).toFloat(), (-dotRy).toFloat(), dotRx.toFloat(), dotRy.toFloat(), Path.Direction.CW)
            matrix.setRotate(Math.toDegrees(rot).toFloat())
            matrix.postTranslate(px.toFloat(), py.toFloat())
            out.addPath(tmp, matrix)
        }
    }

    private fun uniform(rng: Random, min: Double, max: Double) = min + rng.nextDouble() * (max - min)

    private fun sampleY(rng: Random, distribution: TexturedDotsDistribution, start: Double, end: Double): Double {
        val sample = when (distribution) {
            TexturedDotsDistribution.UNIFORM -> uniform(rng, start, end)
            TexturedDotsDistribution.NORMAL -> {
                val mean = (end + start) * 0.5
                val stdDev = ((end - start) * 0.5) / 3.0
                mean + rng.nextGaussian() * stdDev
            }

            TexturedDotsDistribution.EXPONENTIAL -> {
                val mid = (end + start) * 0.5
                val width = (end - start) / 4.0
                val sign = if (rng.nextBoolean()) 1.0 else -1.0
                mid + sign * width * exp(rng)
            }

            TexturedDotsDistribution.REVERSE_EXPONENTIAL -> {
                val width = (end - start) / 4.0
                val positive = rng.nextBoolean()
                val sign = if (positive) 1.0 else -1.0
                val offset = if (positive) start else end
                offset + sign * width * exp(rng)
            }
        }
        return if (sample >= start && sample < end) sample else uniform(rng, start, end)
    }

    private fun exp(rng: Random): Double = -ln(1.0 - rng.nextDouble())
}
