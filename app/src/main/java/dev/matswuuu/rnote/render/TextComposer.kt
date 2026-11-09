package dev.matswuuu.rnote.render

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import dev.matswuuu.rnote.format.Bounds
import dev.matswuuu.rnote.format.Stroke
import dev.matswuuu.rnote.format.TextAlignment
import dev.matswuuu.rnote.format.TextAttribute
import dev.matswuuu.rnote.render.StrokeComposer.toMatrix
import dev.matswuuu.rnote.render.StrokeComposer.toRectF
import kotlin.math.ceil
import kotlin.math.roundToInt

object TextComposer {

    fun compose(stroke: Stroke.Text, ops: MutableList<DrawOp>, bounds: RectF) {
        if (stroke.text.isEmpty()) return
        val style = stroke.style
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = style.fontSize.toFloat()
            color = style.color.toArgb()
            typeface = resolveTypeface(style.fontFamily, style.fontWeight, style.italic)
        }

        val text = SpannableString(stroke.text)
        val byteToChar = byteToCharIndex(stroke.text)
        for (ranged in style.rangedAttributes.sortedBy { it.start }) {
            val start = byteToChar.at(ranged.start)
            val end = byteToChar.at(ranged.end)
            if (end <= start) continue
            val span: Any = when (val a = ranged.attribute) {
                is TextAttribute.FontFamily -> FontSpan(family = a.family)
                is TextAttribute.FontSize -> AbsoluteSizeSpan(a.size.roundToInt().coerceAtLeast(1))
                is TextAttribute.FontWeight -> FontSpan(weight = a.weight)
                is TextAttribute.Italic -> FontSpan(italic = a.italic)
                is TextAttribute.TextColor -> ForegroundColorSpan(a.color.toArgb())
                is TextAttribute.Underline -> if (a.underline) UnderlineSpan() else continue
                is TextAttribute.Strikethrough -> if (a.strikethrough) StrikethroughSpan() else continue
            }
            text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val width = style.maxWidth?.let { ceil(it).toInt() }
            ?: ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt().plus(1)
        val alignment = when (style.alignment) {
            TextAlignment.START, TextAlignment.FILL -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setIncludePad(false)
        if (style.alignment == TextAlignment.FILL) {
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        val layout = builder.build()

        ops.add(DrawOp.TextOp(layout, stroke.affine.toMatrix()))
        val local = Bounds(0.0, 0.0, layout.width.toDouble(), layout.height.toDouble())
        val r = stroke.affine.transformBounds(local).toRectF()
        r.inset(-2f, -2f)
        if (bounds.isEmpty) bounds.set(r) else bounds.union(r)
    }

    /** Maps byte offsets of the UTF-8 encoded string to UTF-16 char indices. */
    private class ByteToChar(private val map: IntArray, private val length: Int) {
        fun at(byteOffset: Int): Int = if (byteOffset < 0) 0 else if (byteOffset >= map.size) length else map[byteOffset]
    }

    private fun byteToCharIndex(s: String): ByteToChar {
        val bytes = s.toByteArray(Charsets.UTF_8).size
        val map = IntArray(bytes + 1)
        var b = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            for (k in 0 until len) if (b + k < map.size) map[b + k] = i
            b += len
            i += Character.charCount(cp)
        }
        if (b < map.size) map[b] = s.length
        return ByteToChar(map, s.length)
    }

    fun resolveTypeface(family: String, weight: Int, italic: Boolean): Typeface {
        val base = when (family.lowercase().trim()) {
            "serif" -> Typeface.SERIF
            "sans", "sans-serif", "sans serif" -> Typeface.SANS_SERIF
            "monospace", "mono" -> Typeface.MONOSPACE
            "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
            else -> Typeface.create(family, Typeface.NORMAL)
        }
        return withWeight(base, weight, italic)
    }

    private fun withWeight(base: Typeface, weight: Int, italic: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.coerceIn(1, 1000), italic)
        } else {
            val bold = weight >= 600
            Typeface.create(
                base,
                when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
        }

    /** Changes family, weight and/or style of the current typeface, keeping the other properties. */
    private class FontSpan(
        private val family: String? = null,
        private val weight: Int? = null,
        private val italic: Boolean? = null,
    ) : MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) = apply(textPaint)
        override fun updateDrawState(tp: TextPaint) = apply(tp)

        private fun apply(tp: TextPaint) {
            val current = tp.typeface ?: Typeface.DEFAULT
            val currentWeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) current.weight else if (current.isBold) 700 else 400
            val newWeight = weight ?: currentWeight
            val newItalic = italic ?: current.isItalic
            tp.typeface = if (family != null) {
                resolveTypeface(family, newWeight, newItalic)
            } else {
                withWeight(current, newWeight, newItalic)
            }
        }
    }
}
