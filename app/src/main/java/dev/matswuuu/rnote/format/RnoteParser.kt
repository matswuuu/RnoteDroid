package dev.matswuuu.rnote.format

import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.internal.bind.JsonTreeReader
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Base64
import java.util.zip.GZIPInputStream

class RnoteFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streaming reader for Rnote's `.rnote` file format.
 *
 * A `.rnote` file is gzip-compressed JSON of the shape `{"version": "x.y.z", "data": {...}}`.
 * The parser is lenient and accepts all format revisions since Rnote v0.5 without needing to
 * migrate the JSON tree first:
 *  - `< 0.5.9`: pen paths are arrays of `dot`/`line`/`quadbez`/`cubbez` segments
 *  - `< 0.5.10`: strokes live in `data.store_snapshot` instead of `data.engine_snapshot`
 *  - `< 0.13`: the document `format`/`background`/`layout` are not yet wrapped in `config`
 *  - `< 0.15`: affine transforms are stored as `"transform": {"affine": [3x3 matrix]}`
 */
object RnoteParser {

    fun parse(input: InputStream): RnoteFile {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        if (b0 < 0 || b1 < 0) throw RnoteFormatException("The file is empty.")

        val jsonStream = if (b0 == 0x1f && b1 == 0x8b) {
            GZIPInputStream(buffered, 64 * 1024)
        } else if (b0 == '{'.code || b0 == 0xEF || Character.isWhitespace(b0)) {
            // uncompressed JSON, possibly with leading whitespace or a UTF-8 byte order mark
            if (b0 == 0xEF) buffered.skip(3)
            buffered
        } else {
            throw RnoteFormatException("This is not a valid Rnote file.")
        }

        val reader = JsonReader(InputStreamReader(jsonStream, Charsets.UTF_8))
        reader.strictness = Strictness.LENIENT
        try {
            return Context().readRoot(reader)
        } catch (e: RnoteFormatException) {
            throw e
        } catch (e: java.util.zip.ZipException) {
            throw RnoteFormatException("The file is corrupted (decompression failed).", e)
        } catch (e: Exception) {
            throw RnoteFormatException("Failed to read the Rnote file: ${e.message}", e)
        } finally {
            runCatching { reader.close() }
        }
    }

    private class Slot<T>(val value: T?, val version: Long)

    private class Context {
        var version = "unknown"
        var document: Document? = null
        var camera: Camera? = null
        var strokeSlots: List<Slot<Stroke>>? = null
        var chronoSlots: List<Slot<Pair<Long, StrokeLayer>>>? = null
        var skipped = 0

        fun readRoot(r: JsonReader): RnoteFile {
            if (r.peek() != JsonToken.BEGIN_OBJECT) throw RnoteFormatException("This is not a valid Rnote file.")
            var sawData = false
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "version" -> version = r.nextString()
                    "data" -> {
                        sawData = true
                        readSnapshot(r)
                    }

                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (!sawData) throw RnoteFormatException("This is not a valid Rnote file (no data).")
            if (version.startsWith("0.") && version.substringAfter("0.").substringBefore('.').toIntOrNull()
                    ?.let { it < 5 } == true
            ) {
                throw RnoteFormatException("Rnote files older than v0.5 ($version) are not supported.")
            }

            return assemble()
        }

        /** Reads any of the (nested) snapshot objects, collecting the fields we are interested in. */
        fun readSnapshot(r: JsonReader) {
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "engine_snapshot", "store_snapshot" -> readSnapshot(r)
                    "document", "sheet" -> document = readDocument(r)
                    "camera" -> camera = readCamera(r)
                    "stroke_components" -> strokeSlots = readSlots(r, onError = { skipped++ }) { readStroke(it) }
                    "chrono_components" -> chronoSlots = readSlots(r, onError = {}) { readChrono(it) }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        fun assemble(): RnoteFile {
            val strokes = strokeSlots ?: emptyList()
            val chronos = chronoSlots ?: emptyList()
            val entries = ArrayList<Pair<Int, StrokeEntry>>(strokes.size)
            strokes.forEachIndexed { idx, slot ->
                val stroke = slot.value ?: return@forEachIndexed
                val chrono = chronos.getOrNull(idx)?.takeIf { it.version == slot.version }?.value
                val (t, layer) = chrono ?: (0L to StrokeLayer.User(0))
                entries.add(idx to StrokeEntry(stroke, layer, t))
            }
            entries.sortWith(
                compareBy<Pair<Int, StrokeEntry>> { it.second.layer.order }
                    .thenBy { (it.second.layer as? StrokeLayer.User)?.index ?: 0 }
                    .thenBy { it.second.chronoT }
                    .thenBy { it.first }
            )
            return RnoteFile(
                version = version,
                document = sanitize(document ?: Document()),
                camera = camera,
                strokes = entries.map { it.second },
                skippedStrokes = skipped,
            )
        }

        /**
         * Reads a serialized slot map. Each value is first read into a JSON tree, so that a value
         * with unexpected content only loses that value (reported through [onError]), not the whole file.
         */
        /** Replaces invalid (non-finite, non-positive) document dimensions with defaults. */
        fun sanitize(doc: Document): Document {
            fun Double.valid() = isFinite() && kotlin.math.abs(this) < 1e7
            val defaults = Document()
            val format = doc.format.let { f ->
                if (f.width.valid() && f.width > 0 && f.height.valid() && f.height > 0) f
                else f.copy(width = defaults.format.width, height = defaults.format.height)
            }
            val size = doc.background.patternSize
            val background = if (size.x.valid() && size.y.valid() && size.x > 0 && size.y > 0) doc.background
            else doc.background.copy(patternSize = Background().patternSize)
            val bounds = doc.x.valid() && doc.y.valid() && doc.width.valid() && doc.height.valid() &&
                doc.width > 0 && doc.height > 0
            return if (bounds) doc.copy(format = format, background = background)
            else doc.copy(x = 0.0, y = 0.0, width = format.width, height = format.height, format = format, background = background)
        }

        fun <T> readSlots(r: JsonReader, onError: () -> Unit, readValue: (JsonReader) -> T?): List<Slot<T>> {
            val result = ArrayList<Slot<T>>()
            r.beginArray()
            while (r.hasNext()) {
                var value: T? = null
                var version = 0L
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "value" -> value = if (r.peek() == JsonToken.NULL) {
                            r.nextNull(); null
                        } else {
                            val element = JsonParser.parseReader(r)
                            try {
                                readValue(JsonTreeReader(element))
                            } catch (e: RuntimeException) {
                                onError(); null
                            } catch (e: IOException) {
                                onError(); null
                            }
                        }

                        "version" -> version = r.nextLong()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                result.add(Slot(value, version))
            }
            r.endArray()
            return result
        }

        fun readChrono(r: JsonReader): Pair<Long, StrokeLayer> {
            var t = 0L
            var layer: StrokeLayer = StrokeLayer.User(0)
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "t" -> t = r.nextLong()
                    "layer" -> layer = readLayer(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return t to layer
        }

        fun readLayer(r: JsonReader): StrokeLayer {
            fun unitLayer(name: String): StrokeLayer = when (name) {
                "highlighter", "Highlighter" -> StrokeLayer.Highlighter
                "image", "Image" -> StrokeLayer.Image
                "document", "Document" -> StrokeLayer.Document
                else -> StrokeLayer.User(0)
            }
            return when (r.peek()) {
                JsonToken.STRING -> unitLayer(r.nextString())
                JsonToken.BEGIN_OBJECT -> {
                    var layer: StrokeLayer = StrokeLayer.User(0)
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        if ((name == "user_layer" || name == "UserLayer") && r.peek() == JsonToken.NUMBER) {
                            layer = StrokeLayer.User(r.nextInt())
                        } else {
                            r.skipValue()
                            layer = unitLayer(name)
                        }
                    }
                    r.endObject()
                    layer
                }

                else -> {
                    r.skipValue(); StrokeLayer.User(0)
                }
            }
        }

        // -----------------------------------------------------------------------------------
        // Document
        // -----------------------------------------------------------------------------------

        fun readDocument(r: JsonReader): Document {
            var doc = Document()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "x" -> doc = doc.copy(x = r.nextDouble())
                    "y" -> doc = doc.copy(y = r.nextDouble())
                    "width" -> doc = doc.copy(width = r.nextDouble())
                    "height" -> doc = doc.copy(height = r.nextDouble())
                    "config" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "format" -> doc = doc.copy(format = readFormat(r))
                                "background" -> doc = doc.copy(background = readBackground(r))
                                "layout", "expand_mode" -> doc = doc.copy(layout = readLayout(r))
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }

                    "format" -> doc = doc.copy(format = readFormat(r))
                    "background" -> doc = doc.copy(background = readBackground(r))
                    "layout", "expand_mode" -> doc = doc.copy(layout = readLayout(r))
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return doc
        }

        fun readLayout(r: JsonReader): Layout = when (r.nextString()) {
            "fixed_size", "FixedSize" -> Layout.FIXED_SIZE
            "continuous_vertical", "endless_vertical", "ContinuousVertical", "EndlessVertical" -> Layout.CONTINUOUS_VERTICAL
            "semi_infinite", "SemiInfinite" -> Layout.SEMI_INFINITE
            else -> Layout.INFINITE
        }

        fun readFormat(r: JsonReader): Format {
            var f = Format()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "width" -> f = f.copy(width = r.nextDouble())
                    "height" -> f = f.copy(height = r.nextDouble())
                    "dpi" -> f = f.copy(dpi = r.nextDouble())
                    "border_color" -> f = f.copy(borderColor = readColor(r))
                    "show_borders" -> f = f.copy(showBorders = r.nextBoolean())
                    "show_origin_indicator" -> f = f.copy(showOriginIndicator = r.nextBoolean())
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return f
        }

        fun readBackground(r: JsonReader): Background {
            var b = Background()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "color" -> b = b.copy(color = readColor(r))
                    "pattern" -> b = b.copy(
                        pattern = when (r.nextString()) {
                            "none" -> PatternStyle.NONE
                            "lines" -> PatternStyle.LINES
                            "grid" -> PatternStyle.GRID
                            "dots" -> PatternStyle.DOTS
                            "isometric_grid" -> PatternStyle.ISOMETRIC_GRID
                            "isometric_dots" -> PatternStyle.ISOMETRIC_DOTS
                            else -> PatternStyle.NONE
                        }
                    )

                    "pattern_size" -> b = b.copy(patternSize = readVec2(r))
                    "pattern_color" -> b = b.copy(patternColor = readColor(r))
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return b
        }

        fun readCamera(r: JsonReader): Camera {
            var offset = Vec2.ZERO
            var size = Vec2.ZERO
            var zoom = 1.0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "offset" -> offset = readVec2(r)
                    "size" -> size = readVec2(r)
                    "zoom" -> zoom = r.nextDouble()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return Camera(offset, size, zoom)
        }

        // -----------------------------------------------------------------------------------
        // Strokes
        // -----------------------------------------------------------------------------------

        fun readStroke(r: JsonReader): Stroke? {
            var stroke: Stroke? = null
            readEnum(r) { name ->
                stroke = when (name) {
                    "brushstroke" -> readBrushStroke(r)
                    "shapestroke" -> readShapeStroke(r)
                    "textstroke" -> readTextStroke(r)
                    "vectorimage" -> readVectorImage(r)
                    "bitmapimage" -> readBitmapImage(r)
                    else -> {
                        r.skipValue(); null
                    }
                }
            }
            if (stroke == null) skipped++
            return stroke
        }

        fun readBrushStroke(r: JsonReader): Stroke? {
            var path: PenPath? = null
            var style: Style = SmoothStyle()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "path" -> path = if (r.peek() == JsonToken.BEGIN_ARRAY) readLegacyPenPath(r) else readPenPath(r)
                    "style" -> style = readStyle(r) ?: SmoothStyle()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return path?.let { Stroke.Brush(it, style) }
        }

        fun readPenPath(r: JsonReader): PenPath {
            var start = Element(Vec2.ZERO, 1.0)
            val segments = ArrayList<Segment>()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "start" -> start = readElement(r)
                    "segments" -> {
                        r.beginArray()
                        while (r.hasNext()) readSegment(r)?.let { segments.add(it) }
                        r.endArray()
                    }

                    else -> r.skipValue()
                }
            }
            r.endObject()
            return PenPath(start, segments)
        }

        fun readSegment(r: JsonReader): Segment? {
            var seg: Segment? = null
            readEnum(r) { name ->
                var end: Element? = null
                var cp: Vec2? = null
                var cp2: Vec2? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "end" -> end = readElement(r)
                        "cp", "cp1" -> cp = readVec2(r)
                        "cp2" -> cp2 = readVec2(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val e = end ?: return@readEnum
                seg = when (name) {
                    "lineto", "line" -> Segment.LineTo(e)
                    "quadbezto", "quadbez" -> Segment.QuadBezTo(cp ?: e.pos, e)
                    "cubbezto", "cubbez" -> Segment.CubBezTo(cp ?: e.pos, cp2 ?: e.pos, e)
                    else -> null
                }
            }
            return seg
        }

        /** Pen paths before v0.5.9: an array of segments that each contain their start element. */
        fun readLegacyPenPath(r: JsonReader): PenPath? {
            var start: Element? = null
            val segments = ArrayList<Segment>()
            r.beginArray()
            while (r.hasNext()) {
                readEnum(r) { name ->
                    var segStart: Element? = null
                    var end: Element? = null
                    var cp1: Vec2? = null
                    var cp2: Vec2? = null
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "element" -> {
                                val el = readElement(r); segStart = el; end = el
                            }

                            "start" -> segStart = readElement(r)
                            "end" -> end = readElement(r)
                            "cp", "cp1" -> cp1 = readVec2(r)
                            "cp2" -> cp2 = readVec2(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (start == null) {
                        start = segStart ?: end
                        // the first element only defines the start
                        if (name == "dot") return@readEnum
                    }
                    val e = end ?: return@readEnum
                    segments.add(
                        when (name) {
                            "quadbez" -> Segment.QuadBezTo(cp1 ?: e.pos, e)
                            "cubbez" -> Segment.CubBezTo(cp1 ?: e.pos, cp2 ?: e.pos, e)
                            else -> Segment.LineTo(e)
                        }
                    )
                }
            }
            r.endArray()
            return start?.let { PenPath(it, segments) }
        }

        fun readElement(r: JsonReader): Element {
            var pos = Vec2.ZERO
            var pressure = 1.0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "pos" -> pos = readVec2(r)
                    "pressure" -> pressure = r.nextDouble()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return Element(pos, pressure)
        }

        fun readShapeStroke(r: JsonReader): Stroke? {
            var shape: Shape? = null
            var style: Style = SmoothStyle()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "shape" -> shape = readShape(r)
                    "style" -> style = readStyle(r) ?: SmoothStyle()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return shape?.let { Stroke.ShapeStroke(it, style) }
        }

        fun readShape(r: JsonReader): Shape? {
            var shape: Shape? = null
            readEnum(r) { name ->
                val fields = HashMap<String, Any>()
                r.beginObject()
                while (r.hasNext()) {
                    when (val key = r.nextName()) {
                        "start", "end", "tip", "cp", "cp1", "cp2", "radii" -> fields[key] = readVec2(r)
                        "path" -> fields[key] = readVec2List(r)
                        "cuboid" -> fields[key] = readCuboid(r)
                        "affine" -> fields[key] = readAffine(r)
                        "transform" -> fields["affine"] = readTransform(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                fun v(k: String) = fields[k] as? Vec2 ?: Vec2.ZERO
                val affine = fields["affine"] as? Affine ?: Affine.IDENTITY
                @Suppress("UNCHECKED_CAST")
                val path = fields["path"] as? List<Vec2> ?: emptyList()
                shape = when (name) {
                    "line" -> Shape.Line(v("start"), v("end"))
                    "arrow" -> Shape.Arrow(v("start"), v("tip"))
                    "rect", "rectangle" -> Shape.Rect(Rectangle(fields["cuboid"] as? Vec2 ?: Vec2.ZERO, affine))
                    "ellipse" -> Shape.Ellipse(v("radii"), affine)
                    "quadbez", "quadratic_bezier" -> Shape.QuadBez(v("start"), v("cp"), v("end"))
                    "cubbez", "cubic_bezier" -> Shape.CubBez(v("start"), v("cp1"), v("cp2"), v("end"))
                    "polyline" -> Shape.Polyline(v("start"), path)
                    "polygon" -> Shape.Polygon(v("start"), path)
                    else -> null
                }
            }
            return shape
        }

        fun readStyle(r: JsonReader): Style? {
            var style: Style? = null
            readEnum(r) { name ->
                style = when (name) {
                    "smooth" -> readSmooth(r)
                    "rough" -> readRough(r)
                    "textured" -> readTextured(r)
                    else -> {
                        r.skipValue(); null
                    }
                }
            }
            return style
        }

        fun readSmooth(r: JsonReader): SmoothStyle {
            var s = SmoothStyle()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stroke_width" -> s = s.copy(strokeWidth = r.nextDouble())
                    "stroke_color" -> s = s.copy(strokeColor = readOptColor(r))
                    "fill_color" -> s = s.copy(fillColor = readOptColor(r))
                    "pressure_curve" -> s = s.copy(pressureCurve = readPressureCurve(r))
                    "line_style" -> s = s.copy(
                        lineStyle = when (r.nextString()) {
                            "dotted" -> LineStyle.DOTTED
                            "dashed_narrow" -> LineStyle.DASHED_NARROW
                            "dashed_equidistant" -> LineStyle.DASHED_EQUIDISTANT
                            "dashed_wide" -> LineStyle.DASHED_WIDE
                            else -> LineStyle.SOLID
                        }
                    )

                    "line_cap" -> s = s.copy(lineCap = if (r.nextString() == "rounded") LineCap.ROUNDED else LineCap.STRAIGHT)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return s
        }

        fun readRough(r: JsonReader): RoughStyle {
            var s = RoughStyle()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stroke_width" -> s = s.copy(strokeWidth = r.nextDouble())
                    "stroke_color" -> s = s.copy(strokeColor = readOptColor(r))
                    "fill_color" -> s = s.copy(fillColor = readOptColor(r))
                    "fill_style" -> s = s.copy(
                        fillStyle = when (r.nextString()) {
                            // before v0.5.9 the fill style was always "Hachure", but rendered solid
                            "solid", "Hachure" -> FillStyle.SOLID
                            "hachure" -> FillStyle.HACHURE
                            "zig_zag" -> FillStyle.ZIG_ZAG
                            "zig_zag_line" -> FillStyle.ZIG_ZAG_LINE
                            "crosshatch" -> FillStyle.CROSSHATCH
                            "dots" -> FillStyle.DOTS
                            "dashed" -> FillStyle.DASHED
                            else -> FillStyle.HACHURE
                        }
                    )

                    "hachure_angle" -> s = s.copy(hachureAngle = r.nextDouble())
                    "seed" -> s = s.copy(seed = readOptSeed(r))
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return s
        }

        fun readTextured(r: JsonReader): TexturedStyle {
            var s = TexturedStyle()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "stroke_width" -> s = s.copy(strokeWidth = r.nextDouble())
                    "stroke_color" -> s = s.copy(strokeColor = readOptColor(r))
                    "density" -> s = s.copy(density = r.nextDouble())
                    "seed" -> s = s.copy(seed = readOptSeed(r))
                    "pressure_curve" -> s = s.copy(pressureCurve = readPressureCurve(r))
                    "distribution" -> s = s.copy(
                        distribution = when (r.nextString().lowercase()) {
                            "uniform" -> TexturedDotsDistribution.UNIFORM
                            "exponential" -> TexturedDotsDistribution.EXPONENTIAL
                            "reverseexponential", "reverse_exponential" -> TexturedDotsDistribution.REVERSE_EXPONENTIAL
                            else -> TexturedDotsDistribution.NORMAL
                        }
                    )

                    else -> r.skipValue()
                }
            }
            r.endObject()
            return s
        }

        fun readPressureCurve(r: JsonReader): PressureCurve = when (r.nextString()) {
            "const" -> PressureCurve.CONST
            "sqrt" -> PressureCurve.SQRT
            "cbrt" -> PressureCurve.CBRT
            "pow2" -> PressureCurve.POW2
            "pow3" -> PressureCurve.POW3
            else -> PressureCurve.LINEAR
        }

        fun readOptSeed(r: JsonReader): Long? {
            if (r.peek() == JsonToken.NULL) {
                r.nextNull(); return null
            }
            // seeds are u64 and may exceed Long.MAX_VALUE
            val s = r.nextString()
            return s.toLongOrNull() ?: s.toULongOrNull()?.toLong() ?: s.toDoubleOrNull()?.toLong()
        }

        fun readTextStroke(r: JsonReader): Stroke? {
            var text = ""
            var affine = Affine.IDENTITY
            var style = TextStyle()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "text" -> text = r.nextString()
                    "affine" -> affine = readAffine(r)
                    "transform" -> affine = readTransform(r)
                    "text_style" -> style = readTextStyle(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return Stroke.Text(text, affine, style)
        }

        fun readTextStyle(r: JsonReader): TextStyle {
            var s = TextStyle()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "font_family" -> s = s.copy(fontFamily = r.nextString())
                    "font_size" -> s = s.copy(fontSize = r.nextDouble())
                    "font_weight" -> s = s.copy(fontWeight = r.nextInt())
                    "font_style" -> s = s.copy(italic = r.nextString().equals("italic", ignoreCase = true))
                    "color" -> s = s.copy(color = readColor(r))
                    "max_width" -> s = s.copy(
                        maxWidth = if (r.peek() == JsonToken.NULL) {
                            r.nextNull(); null
                        } else r.nextDouble()
                    )

                    "alignment" -> s = s.copy(
                        alignment = when (r.nextString()) {
                            "center" -> TextAlignment.CENTER
                            "end" -> TextAlignment.END
                            "fill" -> TextAlignment.FILL
                            else -> TextAlignment.START
                        }
                    )

                    "ranged_text_attributes" -> {
                        val list = ArrayList<RangedTextAttribute>()
                        r.beginArray()
                        while (r.hasNext()) readRangedAttribute(r)?.let { list.add(it) }
                        r.endArray()
                        s = s.copy(rangedAttributes = list)
                    }

                    else -> r.skipValue()
                }
            }
            r.endObject()
            return s
        }

        fun readRangedAttribute(r: JsonReader): RangedTextAttribute? {
            var start = 0
            var end = 0
            var attr: TextAttribute? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "range" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "start" -> start = r.nextInt()
                                "end" -> end = r.nextInt()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }

                    "attribute" -> readEnum(r) { name ->
                        attr = when (name) {
                            "font_family" -> TextAttribute.FontFamily(r.nextString())
                            "font_size" -> TextAttribute.FontSize(r.nextDouble())
                            "font_weight" -> TextAttribute.FontWeight(r.nextInt())
                            "text_color" -> TextAttribute.TextColor(readColor(r))
                            "font_style" -> TextAttribute.Italic(r.nextString().equals("italic", ignoreCase = true))
                            "underline" -> TextAttribute.Underline(r.nextBoolean())
                            "strikethrough" -> TextAttribute.Strikethrough(r.nextBoolean())
                            else -> {
                                r.skipValue(); null
                            }
                        }
                    }

                    else -> r.skipValue()
                }
            }
            r.endObject()
            return attr?.let { RangedTextAttribute(start, end, it) }
        }

        fun readVectorImage(r: JsonReader): Stroke? {
            var svg = ""
            var size = Vec2.ZERO
            var rect: Rectangle? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "svg_data" -> svg = r.nextString()
                    "intrinsic_size" -> size = readVec2(r)
                    "rectangle" -> rect = readRectangle(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return rect?.let { Stroke.VectorImage(svg, size, it) }
        }

        fun readBitmapImage(r: JsonReader): Stroke? {
            var data: ByteArray? = null
            var pw = 0
            var ph = 0
            var imageRect: Rectangle? = null
            var rect: Rectangle? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "image" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "data" -> data = decodeBase64(r.nextString())
                                "pixel_width" -> pw = r.nextInt()
                                "pixel_height" -> ph = r.nextInt()
                                "rectangle" -> imageRect = readRectangle(r)
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }

                    "rectangle" -> rect = readRectangle(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val d = data ?: return null
            val target = rect ?: imageRect ?: return null
            return Stroke.BitmapImage(d, pw, ph, target)
        }

        // -----------------------------------------------------------------------------------
        // Primitives
        // -----------------------------------------------------------------------------------

        fun readRectangle(r: JsonReader): Rectangle {
            var half = Vec2.ZERO
            var affine = Affine.IDENTITY
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "cuboid" -> half = readCuboid(r)
                    "affine" -> affine = readAffine(r)
                    "transform" -> affine = readTransform(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return Rectangle(half, affine)
        }

        fun readCuboid(r: JsonReader): Vec2 {
            var half = Vec2.ZERO
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "half_extents") half = readVec2(r) else r.skipValue()
            }
            r.endObject()
            return half
        }

        /** Pre-v0.15 transform: `{"affine": [9 values of a column-major 3x3 matrix]}`. */
        fun readTransform(r: JsonReader): Affine {
            var affine = Affine.IDENTITY
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "affine") affine = readAffine(r) else r.skipValue()
            }
            r.endObject()
            return affine
        }

        fun readAffine(r: JsonReader): Affine {
            val values = readDoubleArray(r)
            return when (values.size) {
                6 -> Affine(values)
                9 -> Affine(doubleArrayOf(values[0], values[1], values[3], values[4], values[6], values[7]))
                else -> Affine.IDENTITY
            }
        }

        fun readDoubleArray(r: JsonReader): DoubleArray {
            val list = ArrayList<Double>(9)
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                    // nested column arrays
                    r.beginArray()
                    while (r.hasNext()) list.add(r.nextDouble())
                    r.endArray()
                } else {
                    list.add(r.nextDouble())
                }
            }
            r.endArray()
            return list.toDoubleArray()
        }

        fun readVec2(r: JsonReader): Vec2 {
            return when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    val x = if (r.hasNext()) r.nextDouble() else 0.0
                    val y = if (r.hasNext()) r.nextDouble() else 0.0
                    while (r.hasNext()) r.skipValue()
                    r.endArray()
                    Vec2(x, y)
                }

                JsonToken.BEGIN_OBJECT -> {
                    var x = 0.0
                    var y = 0.0
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "x" -> x = r.nextDouble()
                            "y" -> y = r.nextDouble()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    Vec2(x, y)
                }

                else -> {
                    r.skipValue(); Vec2.ZERO
                }
            }
        }

        fun readVec2List(r: JsonReader): List<Vec2> {
            val list = ArrayList<Vec2>()
            r.beginArray()
            while (r.hasNext()) list.add(readVec2(r))
            r.endArray()
            return list
        }

        fun readOptColor(r: JsonReader): RnoteColor? {
            if (r.peek() == JsonToken.NULL) {
                r.nextNull(); return null
            }
            return readColor(r)
        }

        fun readColor(r: JsonReader): RnoteColor {
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            var alpha = 1.0
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "r" -> red = r.nextDouble()
                    "g" -> green = r.nextDouble()
                    "b" -> blue = r.nextDouble()
                    "a" -> alpha = r.nextDouble()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            return RnoteColor(red, green, blue, alpha)
        }

        /**
         * Reads a serde externally tagged enum variant that carries data, i.e. an object with a single key.
         * [onVariant] must consume the variant's value.
         */
        inline fun readEnum(r: JsonReader, onVariant: (String) -> Unit) {
            when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    var first = true
                    while (r.hasNext()) {
                        val name = r.nextName()
                        if (first) {
                            first = false
                            onVariant(name)
                        } else {
                            r.skipValue()
                        }
                    }
                    r.endObject()
                }

                else -> r.skipValue()
            }
        }

        fun decodeBase64(s: String): ByteArray = try {
            Base64.getDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            Base64.getMimeDecoder().decode(s)
        }
    }
}
