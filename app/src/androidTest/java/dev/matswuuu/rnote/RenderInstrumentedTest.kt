package dev.matswuuu.rnote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.matswuuu.rnote.format.RnoteParser
import dev.matswuuu.rnote.render.DocumentScene
import dev.matswuuu.rnote.render.TileKey
import dev.matswuuu.rnote.render.TileRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/** Renders sample documents on a device. Output images are written to the app's external files dir. */
@RunWith(AndroidJUnit4::class)
class RenderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val outputDir = File(instrumentation.targetContext.getExternalFilesDir(null), "render-test").apply { mkdirs() }

    private fun loadScene(asset: String): DocumentScene {
        val start = System.nanoTime()
        val file = instrumentation.context.assets.open(asset).use { RnoteParser.parse(it) }
        val parsed = System.nanoTime()
        val scene = runBlocking { DocumentScene.create(file) }
        val prepared = System.nanoTime()
        Log.i(TAG, "$asset: parse ${(parsed - start) / 1_000_000} ms, prepare ${(prepared - parsed) / 1_000_000} ms")
        assertEquals(0, scene.failedStrokes)
        return scene
    }

    @Test
    fun rendersBitmapImageWithCorrectColors() {
        val scene = loadScene("v0-15-0-test.rnote")
        val region = scene.contentBounds!!
        val scale = 1f
        val bitmap = Bitmap.createBitmap(region.width().toInt(), region.height().toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-region.left, -region.top)
        val t = System.nanoTime()
        scene.draw(canvas, region, scale)
        Log.i(TAG, "full render ${(System.nanoTime() - t) / 1_000_000} ms")
        File(outputDir, "v0-15-0.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // the mandrill's nose (a bitmap image) must be red, not blue
        val noseX = (697.582f - region.left).toInt()
        val noseY = (331.67f + 40f - region.top).toInt()
        val c = bitmap.getPixel(noseX, noseY)
        Log.i(TAG, "nose color: ${Integer.toHexString(c)}")
        assertTrue("expected red nose, got ${Integer.toHexString(c)}", Color.red(c) > Color.blue(c))
    }

    @Test
    fun tileRendererProducesTiles() {
        val scene = loadScene("lecture_note_2.rnote")
        val latch = CountDownLatch(1)
        val level = 1
        val size = TileRenderer.tileDocSize(level)
        val page = scene.pages.first()
        val keys = (0..3).map { TileKey(level, floor(page.left / size).toInt() + it % 2, floor(page.top / size).toInt() + it / 2) }
        lateinit var renderer: TileRenderer
        renderer = TileRenderer(scene) { if (keys.all { renderer.get(it) != null }) latch.countDown() }
        val t = System.nanoTime()
        renderer.request(keys)
        assertTrue("tiles not rendered in time", latch.await(20, TimeUnit.SECONDS))
        Log.i(TAG, "4 tiles in ${(System.nanoTime() - t) / 1_000_000} ms")
        keys.forEach { k ->
            val tile = renderer.get(k)
            assertNotNull(tile)
            File(outputDir, "tile-${k.level}-${k.x}-${k.y}.png").outputStream().use { tile!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        renderer.release()
    }

    private companion object {
        const val TAG = "RenderInstrumentedTest"
    }
}
