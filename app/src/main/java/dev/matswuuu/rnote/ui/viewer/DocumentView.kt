package dev.matswuuu.rnote.ui.viewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import dev.matswuuu.rnote.render.DocumentScene
import dev.matswuuu.rnote.render.TileKey
import dev.matswuuu.rnote.render.TileRenderer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Displays a [DocumentScene] with panning and zooming. The document is rendered in tiles on a
 * background thread; while tiles are being rendered, lower resolution content is shown instead.
 *
 * Screen coordinates relate to document coordinates by `screen = doc * scale + offset`.
 */
@SuppressLint("ViewConstructor")
class DocumentView(context: Context) : View(context) {

    data class ViewportInfo(
        /** Zero-based index of the current page, or -1 if the layout has no pages. */
        val page: Int,
        val pageCount: Int,
        /** Zoom relative to Rnote's 100% (one document unit per density independent pixel). */
        val zoom: Float,
    )

    /** The visible part of the document, independent of the view size. */
    data class SavedViewport(val centerX: Float, val centerY: Float, val zoom: Float)

    var onViewportChanged: ((ViewportInfo) -> Unit)? = null

    /** Viewport to restore instead of the default one when the scene is shown. */
    var pendingViewport: SavedViewport? = null

    var deskColor: Int = 0xFF_E0E0E0.toInt()
        set(value) {
            field = value; invalidate()
        }

    private val density = resources.displayMetrics.density
    private val margin = 16f * density

    private var scene: DocumentScene? = null
    private var renderer: TileRenderer? = null

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var initialized = false
    private var lastInfo: ViewportInfo? = null

    private val minZoom get() = 0.05f * density
    private val maxZoom get() = 16f * density

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30000000
        setShadowLayer(8f * density, 0f, 2f * density, 0x50000000)
    }
    private val docBackgroundPaint = Paint()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val dstRectF = RectF()

    private val scroller = OverScroller(context)
    private var animator: ValueAnimator? = null

    init {
        isFocusable = true
    }

    fun setScene(newScene: DocumentScene?) {
        if (newScene === scene) return
        renderer?.release()
        scene = newScene
        renderer = newScene?.let { s -> TileRenderer(s) { postInvalidateOnAnimation() } }
        docBackgroundPaint.color = newScene?.document?.background?.color?.toArgb() ?: 0
        initialized = false
        lastInfo = null
        if (width > 0 && height > 0) initViewport()
        invalidate()
    }

    fun release() {
        renderer?.release()
        renderer = null
        scene = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized) {
            initViewport()
        } else if (oldw == 0 || oldh == 0) {
            clampOffset()
        } else {
            // keep the center of the view in place
            val cx = (oldw / 2f - offsetX) / scale
            val cy = (oldh / 2f - offsetY) / scale
            offsetX = w / 2f - cx * scale
            offsetY = h / 2f - cy * scale
            clampOffset()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    // -------------------------------------------------------------------------------------------
    // Viewport
    // -------------------------------------------------------------------------------------------

    fun saveViewport(): SavedViewport? {
        if (!initialized || scale <= 0f) return null
        return SavedViewport((width / 2f - offsetX) / scale, (height / 2f - offsetY) / scale, scale / density)
    }

    private fun initViewport() {
        val s = scene ?: return
        if (width == 0 || height == 0) return
        initialized = true
        pendingViewport?.let { saved ->
            pendingViewport = null
            scale = (saved.zoom * density).coerceIn(minZoom, maxZoom)
            offsetX = width / 2f - saved.centerX * scale
            offsetY = height / 2f - saved.centerY * scale
            clampOffset()
            invalidate()
            return
        }
        val target = s.pages.firstOrNull() ?: s.contentBounds?.let { c ->
            RectF(c).apply { inset(-24f, -24f); if (!intersect(s.documentBounds)) set(s.documentBounds) }
        } ?: s.documentBounds
        val (newScale, ox, oy) = fitWidthTransform(target)
        scale = newScale
        offsetX = ox
        offsetY = oy
        clampOffset()
        invalidate()
    }

    /** Transform that fits [rect]'s width into the view, aligned to its top. */
    private fun fitWidthTransform(rect: RectF): Triple<Float, Float, Float> {
        val availableWidth = (width - 2 * margin).coerceAtLeast(1f)
        val newScale = (availableWidth / rect.width()).coerceIn(minZoom, 2f * density)
        val ox = width / 2f - rect.centerX() * newScale
        val oy = margin - rect.top * newScale
        return Triple(newScale, ox, oy)
    }

    /** Transform that fits all of [rect] into the view, centered. */
    private fun fitTransform(rect: RectF): Triple<Float, Float, Float> {
        val sx = (width - 2 * margin) / rect.width()
        val sy = (height - 2 * margin) / rect.height()
        val newScale = min(sx, sy).coerceIn(minZoom, maxZoom)
        return Triple(newScale, width / 2f - rect.centerX() * newScale, height / 2f - rect.centerY() * newScale)
    }

    private fun clampOffset() {
        val s = scene ?: return
        val doc = s.documentBounds
        offsetX = clampAxis(offsetX, doc.left * scale, doc.right * scale, width.toFloat())
        offsetY = clampAxis(offsetY, doc.top * scale, doc.bottom * scale, height.toFloat())
    }

    private fun clampAxis(offset: Float, start: Float, end: Float, viewSize: Float): Float {
        val size = end - start
        return if (size <= viewSize - 2 * margin) {
            (viewSize - size) / 2f - start
        } else {
            offset.coerceIn(viewSize - margin - end, margin - start)
        }
    }

    private fun currentPageRect(): RectF? {
        val s = scene ?: return null
        val idx = currentPageIndex(s)
        return s.pages.getOrNull(idx)
    }

    private fun currentPageIndex(s: DocumentScene): Int {
        if (s.pages.isEmpty()) return -1
        val cx = (width / 2f - offsetX) / scale
        val cy = (height / 2f - offsetY) / scale
        var best = 0
        var bestDist = Float.MAX_VALUE
        s.pages.forEachIndexed { i, p ->
            if (p.contains(cx, cy)) return i
            val dx = max(0f, max(p.left - cx, cx - p.right))
            val dy = max(0f, max(p.top - cy, cy - p.bottom))
            val d = hypot(dx, dy)
            if (d < bestDist) {
                bestDist = d; best = i
            }
        }
        return best
    }

    private fun notifyViewport() {
        val s = scene ?: return
        val info = ViewportInfo(currentPageIndex(s), s.pages.size, scale / density)
        if (info != lastInfo) {
            lastInfo = info
            onViewportChanged?.invoke(info)
        }
    }

    private fun animateTo(target: Triple<Float, Float, Float>) {
        animator?.cancel()
        scroller.forceFinished(true)
        val (ts, tx, ty) = target
        val ss = scale
        val sx = offsetX
        val sy = offsetY
        // interpolate in document space of the view center to get a natural zoom animation
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val logScale = ln(ss) + (ln(ts) - ln(ss)) * t
                val newScale = kotlin.math.exp(logScale)
                val cxStart = (width / 2f - sx) / ss
                val cyStart = (height / 2f - sy) / ss
                val cxEnd = (width / 2f - tx) / ts
                val cyEnd = (height / 2f - ty) / ts
                val cx = cxStart + (cxEnd - cxStart) * t
                val cy = cyStart + (cyEnd - cyStart) * t
                scale = newScale
                offsetX = width / 2f - cx * newScale
                offsetY = height / 2f - cy * newScale
                invalidate()
            }
            start()
        }
    }

    fun fitWidth() {
        val rect = currentPageRect() ?: scene?.contentBounds ?: scene?.documentBounds ?: return
        val (s, ox, _) = fitWidthTransform(rect)
        // keep the vertical position of the view center
        val cy = (height / 2f - offsetY) / scale
        animateTo(clampedTarget(s, ox, height / 2f - cy * s))
    }

    fun fitPage() {
        val rect = currentPageRect() ?: scene?.contentBounds ?: scene?.documentBounds ?: return
        val (s, ox, oy) = fitTransform(rect)
        animateTo(clampedTarget(s, ox, oy))
    }

    fun goToPage(index: Int) {
        val s = scene ?: return
        val page = s.pages.getOrNull(index) ?: return
        val (newScale, ox, _) = fitWidthTransform(page)
        val keepScale = abs(newScale - scale) / newScale < 0.02f || scale > newScale
        val targetScale = if (keepScale) scale else newScale
        val targetOx = if (keepScale) offsetX else ox
        animateTo(clampedTarget(targetScale, targetOx, margin - page.top * targetScale))
    }

    private fun clampedTarget(s: Float, ox: Float, oy: Float): Triple<Float, Float, Float> {
        val prev = Triple(scale, offsetX, offsetY)
        scale = s; offsetX = ox; offsetY = oy
        clampOffset()
        val result = Triple(scale, offsetX, offsetY)
        scale = prev.first; offsetX = prev.second; offsetY = prev.third
        return result
    }

    private fun zoomAround(factor: Float, fx: Float, fy: Float) {
        val newScale = (scale * factor).coerceIn(minZoom, maxZoom)
        val f = newScale / scale
        offsetX = fx - (fx - offsetX) * f
        offsetY = fy - (fy - offsetY) * f
        scale = newScale
        clampOffset()
        invalidate()
    }

    // -------------------------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------------------------

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var scaledDuringGesture = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaledDuringGesture = true
            animator?.cancel()
            scroller.forceFinished(true)
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            animator?.cancel()
            offsetX += detector.focusX - lastFocusX
            offsetY += detector.focusY - lastFocusY
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            zoomAround(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = true }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            animator?.cancel()
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            offsetX -= distanceX
            offsetY -= distanceY
            clampOffset()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val s = scene ?: return false
            if (scaleDetector.isInProgress) return false
            val doc = s.documentBounds
            val minX = clampAxis(Float.NEGATIVE_INFINITY, doc.left * scale, doc.right * scale, width.toFloat())
            val maxX = clampAxis(Float.POSITIVE_INFINITY, doc.left * scale, doc.right * scale, width.toFloat())
            val minY = clampAxis(Float.NEGATIVE_INFINITY, doc.top * scale, doc.bottom * scale, height.toFloat())
            val maxY = clampAxis(Float.POSITIVE_INFINITY, doc.top * scale, doc.bottom * scale, height.toFloat())
            scroller.fling(
                offsetX.roundToInt(), offsetY.roundToInt(),
                velocityX.roundToInt(), velocityY.roundToInt(),
                minX.roundToInt(), maxX.roundToInt(), minY.roundToInt(), maxY.roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            scaledDuringGesture = false
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            // zoom on release of the second tap, unless it turned into a quick scale (double tap and drag)
            if (e.actionMasked != MotionEvent.ACTION_UP || scaledDuringGesture) return true
            val s = scene ?: return false
            val page = currentPageRect() ?: s.contentBounds ?: s.documentBounds
            val fitScale = fitWidthTransform(page).first
            val target = if (scale < fitScale * 1.9f) fitScale * 2.5f else fitScale
            val f = target / scale
            animateTo(clampedTarget(target, e.x - (e.x - offsetX) * f, e.y - (e.y - offsetY) * f))
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            animator?.cancel()
            scroller.forceFinished(true)
            if (event.metaState and KeyEvent.META_CTRL_ON != 0) {
                if (v != 0f) zoomAround(if (v > 0) 1.1f else 1f / 1.1f, event.x, event.y)
            } else {
                offsetX -= h * 64f * density
                offsetY += v * 64f * density
                clampOffset()
                invalidate()
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offsetX = scroller.currX.toFloat()
            offsetY = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------------------------

    private fun levelFor(scale: Float): Int =
        floor(ln(scale.toDouble()) / ln(2.0) + 0.6).toInt().coerceIn(-12, 10)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(deskColor)
        val s = scene ?: return
        val r = renderer ?: return

        val doc = s.documentBounds
        dstRectF.set(
            doc.left * scale + offsetX, doc.top * scale + offsetY,
            doc.right * scale + offsetX, doc.bottom * scale + offsetY,
        )
        canvas.drawRect(dstRectF, shadowPaint)
        canvas.drawRect(dstRectF, docBackgroundPaint)

        val save = canvas.save()
        canvas.clipRect(dstRectF)

        // the preview covers the whole document
        r.preview?.let { preview -> canvas.drawBitmap(preview, null, dstRectF, tilePaint) }

        // visible region in document coordinates
        val left = max((0 - offsetX) / scale, doc.left)
        val top = max((0 - offsetY) / scale, doc.top)
        val right = min((width - offsetX) / scale, doc.right)
        val bottom = min((height - offsetY) / scale, doc.bottom)

        val wanted = ArrayList<TileKey>()
        if (right > left && bottom > top) {
            val level = levelFor(scale)
            val size = TileRenderer.tileDocSize(level)
            val x0 = floor(left / size).toInt()
            val x1 = ceil(right / size).toInt() - 1
            val y0 = floor(top / size).toInt()
            val y1 = ceil(bottom / size).toInt() - 1

            val missing = ArrayList<TileKey>()
            for (ty in y0..y1) {
                for (tx in x0..x1) {
                    val key = TileKey(level, tx, ty)
                    if (r.get(key) == null) missing.add(key)
                }
            }
            for (key in missing) drawFallback(canvas, r, key)
            for (ty in y0..y1) {
                for (tx in x0..x1) {
                    val key = TileKey(level, tx, ty)
                    r.get(key)?.let { drawTile(canvas, it, key, null) }
                }
            }

            // render visible tiles first, closest to the center, then a ring around them
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            missing.sortBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
            wanted.addAll(missing)
            val docX0 = floor(doc.left / size).toInt()
            val docX1 = ceil(doc.right / size).toInt() - 1
            val docY0 = floor(doc.top / size).toInt()
            val docY1 = ceil(doc.bottom / size).toInt() - 1
            for (ty in max(y0 - 1, docY0)..min(y1 + 1, docY1)) {
                for (tx in max(x0 - 1, docX0)..min(x1 + 1, docX1)) {
                    if (tx in x0..x1 && ty in y0..y1) continue
                    wanted.add(TileKey(level, tx, ty))
                }
            }
        }
        canvas.restoreToCount(save)
        r.request(wanted)
        notifyViewport()
    }

    /** Draws the region of [key] using cached tiles of other levels. */
    private fun drawFallback(canvas: Canvas, r: TileRenderer, key: TileKey) {
        val children = (0..3).map { TileKey(key.level + 1, key.x * 2 + it % 2, key.y * 2 + it / 2) }
        val childBitmaps = children.map { r.get(it) }
        // coarser tiles (e.g. when zooming in), unless finer tiles cover everything
        if (childBitmaps.any { it == null }) {
            for (k in 1..4) {
                val parent = TileKey(key.level - k, Math.floorDiv(key.x, 1 shl k), Math.floorDiv(key.y, 1 shl k))
                val bitmap = r.get(parent) ?: continue
                val sub = TileRenderer.TILE_SIZE shr k
                val sx = (key.x - parent.x * (1 shl k)) * sub
                val sy = (key.y - parent.y * (1 shl k)) * sub
                srcRect.set(sx, sy, sx + sub, sy + sub)
                drawTile(canvas, bitmap, key, srcRect)
                break
            }
        }
        // finer tiles (e.g. when zooming out) on top, they are sharper
        children.forEachIndexed { i, c -> childBitmaps[i]?.let { drawTile(canvas, it, c, null) } }
    }

    private fun drawTile(canvas: Canvas, bitmap: Bitmap, key: TileKey, src: Rect?) {
        val size = TileRenderer.tileDocSize(key.level)
        dstRect.set(
            (key.x * size * scale + offsetX).roundToInt(),
            (key.y * size * scale + offsetY).roundToInt(),
            ((key.x + 1) * size * scale + offsetX).roundToInt(),
            ((key.y + 1) * size * scale + offsetY).roundToInt(),
        )
        canvas.drawBitmap(bitmap, src, dstRect, tilePaint)
    }
}
