package dev.matswuuu.rnote.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.text.Layout

/** A single drawing operation, in document coordinates. */
sealed interface DrawOp {
    fun draw(canvas: Canvas)

    class PathOp(val path: Path, val paint: Paint) : DrawOp {
        override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)
    }

    class CircleOp(val cx: Float, val cy: Float, val radius: Float, val paint: Paint) : DrawOp {
        override fun draw(canvas: Canvas) = canvas.drawCircle(cx, cy, radius, paint)
    }

    class TextOp(val layout: Layout, val matrix: Matrix) : DrawOp {
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.concat(matrix)
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    class BitmapOp(val bitmap: Bitmap, val matrix: Matrix, val dst: RectF) : DrawOp {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.concat(matrix)
            canvas.drawBitmap(bitmap, null, dst, paint)
            canvas.restoreToCount(save)
        }
    }

    class PictureOp(val picture: Picture, val matrix: Matrix) : DrawOp {
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.concat(matrix)
            canvas.drawPicture(picture)
            canvas.restoreToCount(save)
        }
    }
}

/** A stroke, prepared for drawing. */
class PreparedStroke(
    val ops: List<DrawOp>,
    /** Bounds in document coordinates. */
    val bounds: RectF,
    val highlighter: Boolean,
)
