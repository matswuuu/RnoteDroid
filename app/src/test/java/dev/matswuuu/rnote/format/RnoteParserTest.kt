package dev.matswuuu.rnote.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class RnoteParserTest {

    private fun sample(name: String): RnoteFile {
        val stream = javaClass.classLoader!!.getResourceAsStream("samples/$name")
        assertNotNull("missing test resource $name", stream)
        return stream.use { RnoteParser.parse(it) }
    }

    private fun gzip(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun parsesCurrentFormat() {
        val file = sample("v0-15-0-test.rnote")
        assertEquals("0.15.0", file.version)
        assertEquals(0, file.skippedStrokes)
        assertEquals(Layout.INFINITE, file.document.layout)
        assertEquals(PatternStyle.DOTS, file.document.background.pattern)
        assertEquals(793.701, file.document.format.width, 1e-6)

        val strokes = file.strokes.map { it.stroke }
        assertEquals(409, strokes.count { it is Stroke.Brush })
        assertEquals(1, strokes.count { it is Stroke.ShapeStroke })
        assertEquals(1, strokes.count { it is Stroke.Text })
        assertEquals(2, strokes.count { it is Stroke.VectorImage })
        assertEquals(1, strokes.count { it is Stroke.BitmapImage })

        val text = strokes.filterIsInstance<Stroke.Text>().single()
        assertEquals("Rnote!", text.text)
        assertTrue(text.style.rangedAttributes.isNotEmpty())

        val bitmap = strokes.filterIsInstance<Stroke.BitmapImage>().single()
        assertEquals(bitmap.pixelWidth * bitmap.pixelHeight * 4, bitmap.data.size)

        // drawing order: layers are sorted document < image < user < highlighter
        val orders = file.strokes.map { it.layer.order }
        assertEquals(orders.sorted(), orders)
        assertTrue(file.strokes.any { it.layer == StrokeLayer.Highlighter })
    }

    @Test
    fun parsesLegacyFormat() {
        val file = sample("v0-5-5-test.rnote")
        assertEquals("0.5.5", file.version)
        assertEquals(Layout.CONTINUOUS_VERTICAL, file.document.layout)
        assertEquals(PatternStyle.GRID, file.document.background.pattern)
        assertEquals(1105, file.strokes.size)
        assertEquals(0, file.skippedStrokes)
        assertTrue(file.strokes.all { it.stroke is Stroke.Brush })
        assertTrue(file.strokes.any { (it.stroke as Stroke.Brush).path.segments.isNotEmpty() })
    }

    @Test
    fun legacyAffineIsConverted() {
        val json = """
            {"version":"0.14.0","data":{"engine_snapshot":{
              "document":{"x":0,"y":0,"width":100,"height":100,"config":{"layout":"fixed_size"}},
              "stroke_components":[{"value":null,"version":0},
                {"value":{"textstroke":{"text":"a","transform":{"affine":[1,0,0,0,1,0,10,20,1]}}},"version":1}],
              "chrono_components":[{"value":null,"version":0},{"value":{"t":1,"layer":"highlighter"},"version":1}]
            }}}
        """.trimIndent()
        val file = RnoteParser.parse(gzip(json).inputStream())
        val entry = file.strokes.single()
        assertEquals(StrokeLayer.Highlighter, entry.layer)
        val affine = (entry.stroke as Stroke.Text).affine
        assertEquals(Vec2(10.0, 20.0), affine.apply(Vec2.ZERO))
        assertEquals(Layout.FIXED_SIZE, file.document.layout)
    }

    @Test
    fun rejectsGarbage() {
        try {
            RnoteParser.parse("not an rnote file".byteInputStream())
            fail("expected exception")
        } catch (_: RnoteFormatException) {
        }
    }

    /** Parses every `.rnote` file in `$RNOTE_SAMPLES_DIR`, if set. */
    @Test
    fun parsesExternalSamples() {
        val dir = System.getenv("RNOTE_SAMPLES_DIR")?.let(::File) ?: return
        val files = dir.walkTopDown().filter { it.extension == "rnote" }.toList()
        for (f in files) {
            val parsed = f.inputStream().use { RnoteParser.parse(it) }
            println("${f.name}: v${parsed.version}, ${parsed.strokes.size} strokes, skipped ${parsed.skippedStrokes}, layout ${parsed.document.layout}")
            assertEquals("${f.name} skipped strokes", 0, parsed.skippedStrokes)
            assertTrue(parsed.strokes.isNotEmpty())
        }
    }

    @Test
    fun oneMalformedStrokeDoesNotAbortFile() {
        val json = """
            {"version":"0.15.0","data":{"engine_snapshot":{
              "document":{"x":0,"y":0,"width":100,"height":100,"config":{"layout":"fixed_size"}},
              "stroke_components":[
                {"value":{"brushstroke":{"path":{"start":{"pos":[0,0],"pressure":1},"segments":[]}}},"version":1},
                {"value":"not-a-stroke-object","version":1},
                {"value":{"shapestroke":{"shape":{"line":{"start":[0,0],"end":[1,1]}},"style":{"smooth":{"stroke_width":2}}}},"version":1}
              ],
              "chrono_components":[
                {"value":{"t":1,"layer":{"user_layer":0}},"version":1},
                {"value":{"t":2,"layer":{"user_layer":0}},"version":1},
                {"value":{"t":3,"layer":{"user_layer":0}},"version":1}
              ]
            }}}
        """.trimIndent()
        val file = RnoteParser.parse(gzip(json).inputStream())
        assertEquals(2, file.strokes.size)
        assertEquals(1, file.skippedStrokes)
    }
}
