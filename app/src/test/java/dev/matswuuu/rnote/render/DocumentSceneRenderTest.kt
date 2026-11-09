package dev.matswuuu.rnote.render

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.matswuuu.rnote.format.RnoteParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Renders sample documents with real Skia graphics. The images are written to
 * `build/render-output` for visual inspection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocumentSceneRenderTest {

    private val outputDir = File("build/render-output").apply { mkdirs() }

    private fun render(name: String, input: InputStream, maxSize: Int = 1600): Bitmap {
        val file = input.use { RnoteParser.parse(it) }
        val scene = runBlocking { DocumentScene.create(file) }
        assertEquals("$name: strokes failed to prepare", 0, scene.failedStrokes)

        val region = scene.contentBounds?.let { c ->
            android.graphics.RectF(c).apply { inset(-40f, -40f); intersect(scene.documentBounds) }
        } ?: scene.documentBounds
        val scale = min(1.5f, maxSize / max(region.width(), region.height()))
        println("$name: region=$region doc=${scene.documentBounds} strokes=${scene.strokeCount}")
        val bitmap = Bitmap.createBitmap((region.width() * scale).toInt(), (region.height() * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF808080.toInt())
        canvas.scale(scale, scale)
        canvas.translate(-region.left, -region.top)
        scene.draw(canvas, region, scale)
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        scene.renderThumbnail(256)?.let { thumb ->
            File(outputDir, "$name-thumb.png").outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun assertHasInk(bitmap: Bitmap) {
        // count dark or saturated pixels, there must be handwriting somewhere
        var ink = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                if (r + g + b < 300 || maxOf(r, g, b) - minOf(r, g, b) > 120) ink++
            }
        }
        assertTrue("expected visible strokes, found $ink ink pixels", ink > 100)
    }

    @Test
    fun rendersCurrentFormatSample() {
        val bitmap = render("v0-15-0-test", javaClass.classLoader!!.getResourceAsStream("samples/v0-15-0-test.rnote"))
        assertHasInk(bitmap)
    }

    @Test
    fun rendersLegacyFormatSample() {
        val bitmap = render("v0-5-5-test", javaClass.classLoader!!.getResourceAsStream("samples/v0-5-5-test.rnote"))
        assertHasInk(bitmap)
    }

    /** Renders every `.rnote` file in `$RNOTE_SAMPLES_DIR`, if set. */
    @Test
    fun rendersExternalSamples() {
        val dir = System.getenv("RNOTE_SAMPLES_DIR")?.let(::File) ?: return
        for (f in dir.walkTopDown().filter { it.extension == "rnote" }) {
            assertHasInk(render("ext-" + f.nameWithoutExtension, f.inputStream()))
        }
    }
}
