package dev.matswuuu.rnote.format

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)

    fun length(): Double = sqrt(x * x + y * y)

    fun normalizeOrZero(): Vec2 {
        val l = length()
        return if (l > 0.0) Vec2(x / l, y / l) else ZERO
    }

    /** Unit vector orthogonal to this one (rotated by +90°, y axis pointing down). */
    fun orthUnit(): Vec2 {
        val n = normalizeOrZero()
        return Vec2(-n.y, n.x)
    }

    fun rotate(angle: Double): Vec2 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec2(c * x - s * y, s * x + c * y)
    }

    fun lerp(o: Vec2, t: Double) = Vec2(x + (o.x - x) * t, y + (o.y - y) * t)

    companion object {
        val ZERO = Vec2(0.0, 0.0)
    }
}

/**
 * A 2D affine transformation, stored like glam's `DAffine2` column layout:
 * `[x_axis.x, x_axis.y, y_axis.x, y_axis.y, translation.x, translation.y]`.
 */
class Affine(val m: DoubleArray) {
    init {
        require(m.size == 6)
    }

    fun apply(p: Vec2): Vec2 = Vec2(
        m[0] * p.x + m[2] * p.y + m[4],
        m[1] * p.x + m[3] * p.y + m[5],
    )

    fun applyVector(v: Vec2): Vec2 = Vec2(m[0] * v.x + m[2] * v.y, m[1] * v.x + m[3] * v.y)

    fun transformBounds(b: Bounds): Bounds = Bounds.fromPoints(
        listOf(
            apply(Vec2(b.minX, b.minY)),
            apply(Vec2(b.maxX, b.minY)),
            apply(Vec2(b.maxX, b.maxY)),
            apply(Vec2(b.minX, b.maxY)),
        )
    )

    override fun toString(): String = "Affine(${m.joinToString()})"

    companion object {
        val IDENTITY = Affine(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))

        fun fromAngleTranslation(angle: Double, t: Vec2): Affine {
            val c = cos(angle)
            val s = sin(angle)
            return Affine(doubleArrayOf(c, s, -s, c, t.x, t.y))
        }
    }
}

data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val isEmpty get() = !(maxX > minX && maxY > minY)

    fun union(o: Bounds) = Bounds(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))

    fun loosened(d: Double) = Bounds(minX - d, minY - d, maxX + d, maxY + d)

    fun intersects(o: Bounds) = minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY

    companion object {
        fun fromPoints(points: Iterable<Vec2>): Bounds {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (p in points) {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            return Bounds(minX, minY, maxX, maxY)
        }
    }
}

/** An oriented rectangle: a cuboid of the given half extents, centered at the origin, transformed by [affine]. */
data class Rectangle(val halfExtents: Vec2, val affine: Affine) {
    fun corners(): List<Vec2> = listOf(
        affine.apply(Vec2(-halfExtents.x, -halfExtents.y)),
        affine.apply(Vec2(halfExtents.x, -halfExtents.y)),
        affine.apply(Vec2(halfExtents.x, halfExtents.y)),
        affine.apply(Vec2(-halfExtents.x, halfExtents.y)),
    )

    fun bounds(): Bounds = Bounds.fromPoints(corners())
}
