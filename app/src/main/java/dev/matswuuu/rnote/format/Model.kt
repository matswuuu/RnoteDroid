package dev.matswuuu.rnote.format

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class RnoteColor(val r: Double, val g: Double, val b: Double, val a: Double) {
    fun toArgb(): Int {
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    val isTransparent get() = r == 0.0 && g == 0.0 && b == 0.0 && a == 0.0

    companion object {
        val BLACK = RnoteColor(0.0, 0.0, 0.0, 1.0)
        val WHITE = RnoteColor(1.0, 1.0, 1.0, 1.0)
        fun rgb8(r: Int, g: Int, b: Int) = RnoteColor(r / 255.0, g / 255.0, b / 255.0, 1.0)
    }
}

// ---------------------------------------------------------------------------------------------
// Document
// ---------------------------------------------------------------------------------------------

enum class PatternStyle { NONE, LINES, GRID, DOTS, ISOMETRIC_GRID, ISOMETRIC_DOTS }

data class Background(
    val color: RnoteColor = RnoteColor.WHITE,
    val pattern: PatternStyle = PatternStyle.DOTS,
    val patternSize: Vec2 = Vec2(32.0, 32.0),
    val patternColor: RnoteColor = RnoteColor(0.8, 0.9, 1.0, 1.0),
)

data class Format(
    val width: Double = 1123.0,
    val height: Double = 1587.0,
    val dpi: Double = 96.0,
    val borderColor: RnoteColor = RnoteColor.rgb8(0xde, 0xdd, 0xda),
    val showBorders: Boolean = true,
    val showOriginIndicator: Boolean = true,
)

enum class Layout { FIXED_SIZE, CONTINUOUS_VERTICAL, SEMI_INFINITE, INFINITE }

data class Document(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = Format().width,
    val height: Double = Format().height,
    val format: Format = Format(),
    val background: Background = Background(),
    val layout: Layout = Layout.INFINITE,
) {
    val bounds get() = Bounds(x, y, x + width, y + height)

    /**
     * The page bounds, aligned to the origin and extended to cover the whole document,
     * in row-major order (like Rnote's `split_extended_origin_aligned`).
     */
    fun pageBounds(): List<Bounds> {
        val pw = format.width
        val ph = format.height
        if (pw <= 0.0 || ph <= 0.0 || width <= 0.0 || height <= 0.0) return emptyList()
        val startX = floor(x / pw) * pw
        val startY = floor(y / ph) * ph
        val cols = ceil((x + width - startX) / pw).toInt().coerceAtLeast(1)
        val rows = ceil((y + height - startY) / ph).toInt().coerceAtLeast(1)
        if (cols.toLong() * rows.toLong() > 100_000) return emptyList()
        val pages = ArrayList<Bounds>(cols * rows)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val px = startX + col * pw
                val py = startY + row * ph
                pages.add(Bounds(px, py, px + pw, py + ph))
            }
        }
        return pages
    }
}

data class Camera(val offset: Vec2, val size: Vec2, val zoom: Double)

// ---------------------------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------------------------

enum class PressureCurve {
    CONST, LINEAR, SQRT, CBRT, POW2, POW3;

    fun apply(width: Double, pressure: Double): Double = when (this) {
        CONST -> width
        LINEAR -> width * pressure
        SQRT -> width * kotlin.math.sqrt(pressure)
        CBRT -> width * kotlin.math.cbrt(pressure)
        POW2 -> width * pressure * pressure
        POW3 -> width * pressure * pressure * pressure
    }
}

enum class LineStyle { SOLID, DOTTED, DASHED_NARROW, DASHED_EQUIDISTANT, DASHED_WIDE }

enum class LineCap { STRAIGHT, ROUNDED }

enum class FillStyle { SOLID, HACHURE, ZIG_ZAG, ZIG_ZAG_LINE, CROSSHATCH, DOTS, DASHED }

enum class TexturedDotsDistribution { UNIFORM, NORMAL, EXPONENTIAL, REVERSE_EXPONENTIAL }

sealed interface Style {
    val strokeWidth: Double
}

data class SmoothStyle(
    override val strokeWidth: Double = 2.0,
    val strokeColor: RnoteColor? = RnoteColor.BLACK,
    val fillColor: RnoteColor? = null,
    val pressureCurve: PressureCurve = PressureCurve.LINEAR,
    val lineStyle: LineStyle = LineStyle.SOLID,
    val lineCap: LineCap = LineCap.STRAIGHT,
) : Style

data class RoughStyle(
    val strokeColor: RnoteColor? = RnoteColor.BLACK,
    override val strokeWidth: Double = 2.4,
    val fillColor: RnoteColor? = null,
    val fillStyle: FillStyle = FillStyle.HACHURE,
    val hachureAngle: Double = -0.715585,
    val seed: Long? = null,
) : Style

data class TexturedStyle(
    val seed: Long? = null,
    override val strokeWidth: Double = 6.0,
    val strokeColor: RnoteColor? = RnoteColor.BLACK,
    val density: Double = 5.0,
    val distribution: TexturedDotsDistribution = TexturedDotsDistribution.NORMAL,
    val pressureCurve: PressureCurve = PressureCurve.LINEAR,
) : Style

// ---------------------------------------------------------------------------------------------
// Pen paths and shapes
// ---------------------------------------------------------------------------------------------

data class Element(val pos: Vec2, val pressure: Double)

sealed interface Segment {
    val end: Element

    data class LineTo(override val end: Element) : Segment
    data class QuadBezTo(val cp: Vec2, override val end: Element) : Segment
    data class CubBezTo(val cp1: Vec2, val cp2: Vec2, override val end: Element) : Segment
}

data class PenPath(val start: Element, val segments: List<Segment>) {
    fun bounds(): Bounds {
        val pts = ArrayList<Vec2>(segments.size * 2 + 1)
        pts.add(start.pos)
        for (s in segments) {
            when (s) {
                is Segment.LineTo -> {}
                is Segment.QuadBezTo -> pts.add(s.cp)
                is Segment.CubBezTo -> {
                    pts.add(s.cp1); pts.add(s.cp2)
                }
            }
            pts.add(s.end.pos)
        }
        return Bounds.fromPoints(pts)
    }
}

sealed interface Shape {
    data class Line(val start: Vec2, val end: Vec2) : Shape
    data class Arrow(val start: Vec2, val tip: Vec2) : Shape
    data class Rect(val rectangle: Rectangle) : Shape
    data class Ellipse(val radii: Vec2, val affine: Affine) : Shape
    data class QuadBez(val start: Vec2, val cp: Vec2, val end: Vec2) : Shape
    data class CubBez(val start: Vec2, val cp1: Vec2, val cp2: Vec2, val end: Vec2) : Shape
    data class Polyline(val start: Vec2, val path: List<Vec2>) : Shape
    data class Polygon(val start: Vec2, val path: List<Vec2>) : Shape
}

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

enum class TextAlignment { START, CENTER, END, FILL }

sealed interface TextAttribute {
    data class FontFamily(val family: String) : TextAttribute
    data class FontSize(val size: Double) : TextAttribute
    data class FontWeight(val weight: Int) : TextAttribute
    data class TextColor(val color: RnoteColor) : TextAttribute
    data class Italic(val italic: Boolean) : TextAttribute
    data class Underline(val underline: Boolean) : TextAttribute
    data class Strikethrough(val strikethrough: Boolean) : TextAttribute
}

/** [start] and [end] are byte offsets into the UTF-8 encoded text. */
data class RangedTextAttribute(val start: Int, val end: Int, val attribute: TextAttribute)

data class TextStyle(
    val fontFamily: String = "serif",
    val fontSize: Double = 32.0,
    val fontWeight: Int = 500,
    val italic: Boolean = false,
    val color: RnoteColor = RnoteColor.BLACK,
    val maxWidth: Double? = null,
    val alignment: TextAlignment = TextAlignment.START,
    val rangedAttributes: List<RangedTextAttribute> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Strokes
// ---------------------------------------------------------------------------------------------

sealed interface Stroke {
    data class Brush(val path: PenPath, val style: Style) : Stroke
    data class ShapeStroke(val shape: Shape, val style: Style) : Stroke
    data class Text(val text: String, val affine: Affine, val style: TextStyle) : Stroke
    data class VectorImage(val svgData: String, val intrinsicSize: Vec2, val rectangle: Rectangle) : Stroke

    /** [data] is either raw premultiplied RGBA8 pixels, or (in some files) an encoded image. */
    class BitmapImage(
        val data: ByteArray,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val rectangle: Rectangle,
    ) : Stroke
}

sealed interface StrokeLayer {
    /** Drawing order of the layers, lowest first. */
    val order: Int

    data class User(val index: Int) : StrokeLayer {
        override val order get() = 2
    }

    data object Highlighter : StrokeLayer {
        override val order get() = 3
    }

    data object Image : StrokeLayer {
        override val order get() = 1
    }

    data object Document : StrokeLayer {
        override val order get() = 0
    }
}

data class StrokeEntry(val stroke: Stroke, val layer: StrokeLayer, val chronoT: Long)

class RnoteFile(
    val version: String,
    val document: Document,
    val camera: Camera?,
    /** Strokes in the order they should be drawn. */
    val strokes: List<StrokeEntry>,
    /** Number of strokes that could not be read. */
    val skippedStrokes: Int,
)
