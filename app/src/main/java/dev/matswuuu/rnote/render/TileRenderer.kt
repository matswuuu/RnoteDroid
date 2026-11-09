package dev.matswuuu.rnote.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Identifies a tile: at [level], a tile covers `TILE_SIZE / 2^level` document units. */
data class TileKey(val level: Int, val x: Int, val y: Int)

/**
 * Renders tiles of a [DocumentScene] on a dedicated background thread and caches them.
 * [onTileReady] is invoked on the render thread whenever new content is available.
 */
class TileRenderer(
    private val scene: DocumentScene,
    private val onTileReady: () -> Unit,
) {
    companion object {
        const val TILE_SIZE = 512
        private const val PREVIEW_MAX_SIZE = 2048
        private const val TAG = "TileRenderer"

        fun tileScale(level: Int): Float = Math.scalb(1f, level)

        fun tileDocSize(level: Int): Float = TILE_SIZE / tileScale(level)
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "rnote-tile-renderer") }
    private val scope = CoroutineScope(
        SupervisorJob() + executor.asCoroutineDispatcher() +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Tile renderer stopped", e) }
    )
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private val cache: LruCache<TileKey, Bitmap> = run {
        val maxBytes = min(Runtime.getRuntime().maxMemory() / 3, 192L * 1024 * 1024).toInt()
        object : LruCache<TileKey, Bitmap>(maxBytes) {
            override fun sizeOf(key: TileKey, value: Bitmap) = value.allocationByteCount
        }
    }

    @Volatile
    private var wanted: List<TileKey> = emptyList()

    private var previewAttempted = false

    @Volatile
    var preview: Bitmap? = null
        private set

    /** Scale (pixels per document unit) of [preview]. */
    @Volatile
    var previewScale: Float = 1f
        private set

    /** Tiles that could not be rendered; they are not retried until they are requested anew. */
    private val failed = HashSet<TileKey>()

    init {
        scope.launch {
            for (signal in wakeUp) {
                while (true) {
                    val current = wanted
                    failed.retainAll(current.toSet())
                    val next = current.firstOrNull { cache.get(it) == null && it !in failed }
                    if (next != null) {
                        val bitmap = renderTile(next)
                        if (bitmap != null) cache.put(next, bitmap) else failed.add(next)
                        onTileReady()
                        continue
                    }
                    if (!previewAttempted) {
                        previewAttempted = true
                        renderPreview()
                        onTileReady()
                        continue
                    }
                    break
                }
            }
        }
    }

    fun get(key: TileKey): Bitmap? = cache.get(key)

    /**
     * Replaces the set of wanted tiles. Earlier entries are rendered first.
     * Only as many tiles as fit into the cache are kept, to avoid evicting wanted tiles in a loop.
     */
    fun request(keys: List<TileKey>) {
        val maxTiles = (cache.maxSize() / (TILE_SIZE * TILE_SIZE * 4) - 2).coerceAtLeast(1)
        wanted = if (keys.size > maxTiles) keys.subList(0, maxTiles).toList() else keys
        wakeUp.trySend(Unit)
    }

    fun release() {
        scope.cancel()
        executor.shutdown()
        cache.evictAll()
    }

    private fun renderTile(key: TileKey): Bitmap? = try {
        val scale = tileScale(key.level)
        val size = tileDocSize(key.level)
        val region = RectF(key.x * size, key.y * size, (key.x + 1) * size, (key.y + 1) * size)
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        canvas.translate(-region.left, -region.top)
        scene.draw(canvas, region, scale)
        bitmap
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory while rendering tile $key", e)
        cache.trimToSize(cache.maxSize() / 2)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "Failed to render tile $key", e)
        null
    }

    private fun renderPreview() {
        val bounds = scene.documentBounds
        val longest = max(bounds.width(), bounds.height())
        if (longest <= 0f) return
        val scale = min(1f, PREVIEW_MAX_SIZE / longest)
        val w = (bounds.width() * scale).toInt().coerceAtLeast(1)
        val h = (bounds.height() * scale).toInt().coerceAtLeast(1)
        try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(-bounds.left, -bounds.top)
            scene.draw(canvas, bounds, scale)
            previewScale = scale
            preview = bitmap
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory while rendering the preview", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to render the preview", e)
        }
    }
}
