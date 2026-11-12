package com.coverlock.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class CoverStyle(val label: String) {
    FULL("Full screen"),
    DREAMY("Dreamy"),
    AURA("Aura"),
    VINYL("Vinyl"),
    POLAROID("Polaroid"),
    POSTER("Poster"),
    CLASSIC("Classic");

    companion object {
        fun from(name: String?): CoverStyle = values().firstOrNull { it.name == name } ?: FULL
    }
}

class StyleOptions(
    val style: CoverStyle,
    val sizePct: Int,   // main element size, % of screen width
    val topPct: Int,    // main element top edge, % of screen height
    val focusPct: Int,  // full-screen styles: which part of the cover stays in view (0 = left … 100 = right)
    val title: String,
    val artist: String
)

/**
 * Draws the lock screen wallpaper for a given album cover.
 * Everything is sized relative to the output width, so a small preview looks like the real thing.
 */
object CoverStyles {

    private class Scene(
        val c: Canvas,
        val w: Float,
        val h: Float,
        val u: Float,          // 1 "dp" at this output size
        val art: Bitmap,
        val pal: IntArray,     // 3 vivid colors pulled from the cover
        val o: StyleOptions
    )

    fun render(art: Bitmap, w: Int, h: Int, o: StyleOptions): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Huge covers are shrunk in half-steps first, so drawing them smaller stays crisp, not shimmery.
        val src = shrink(art, if (o.style == CoverStyle.FULL) max(w, h) else w)
        val s = Scene(Canvas(out), w.toFloat(), h.toFloat(), w / 360f, src, palette(src), o)
        when (o.style) {
            CoverStyle.FULL -> full(s)
            CoverStyle.DREAMY -> dreamy(s)
            CoverStyle.AURA -> aura(s)
            CoverStyle.VINYL -> vinyl(s)
            CoverStyle.POLAROID -> polaroid(s)
            CoverStyle.POSTER -> poster(s)
            CoverStyle.CLASSIC -> classic(s)
        }
        grain(s.c, 55)
        return out
    }

    // ───────────────────────── styles ─────────────────────────

    /** Mesh-gradient "aurora" in the cover's colors + glowing glass card. */
    private fun aura(s: Scene) {
        auraBackground(s, 0.28f)
        val side = sideOf(s)
        val x = (s.w - side) / 2f
        val y = topOf(s)
        card(s, RectF(x, y, x + side, y + side), side * 0.07f, s.pal[0])
    }

    /** Record sliding out of the album sleeve. */
    private fun vinyl(s: Scene) {
        auraBackground(s, 0.2f)
        val c = s.c
        val side = sideOf(s) * 0.86f
        val d = side * 0.96f
        val peek = d * 0.44f
        val groupW = side + peek
        val sx = (s.w - groupW) / 2f
        val sy = topOf(s) + side * 0.05f
        val r = d / 2f
        val cx = sx + groupW - r
        val cy = sy + side / 2f

        // disc body
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(14, 14, 16)
            setShadowLayer(18f * s.u, 0f, 8f * s.u, Color.argb(170, 0, 0, 0))
        }
        c.drawCircle(cx, cy, r, body)

        // grooves
        val labelR = r * 0.36f
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.7f * s.u
        }
        var rr = labelR * 1.18f
        var i = 0
        while (rr < r * 0.97f) {
            groove.color = Color.argb(if (i % 3 == 0) 34 else 16, 255, 255, 255)
            c.drawCircle(cx, cy, rr, groove)
            rr += 2.2f * s.u
            i++
        }

        // light sheen
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(0x00FFFFFF, 0x44FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x44FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.10f, 0.20f, 0.50f, 0.60f, 0.70f, 1f)
            )
        }
        c.drawCircle(cx, cy, r, sheen)

        // center label = the cover, center hole
        val labelRect = RectF(cx - labelR, cy - labelR, cx + labelR, cy + labelR)
        c.drawCircle(cx, cy, labelR, coverPaint(s.art, labelRect))
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * s.u
            color = Color.argb(70, 255, 255, 255)
        }
        c.drawCircle(cx, cy, labelR, ring)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(s.pal[0], 0.2f) }
        c.drawCircle(cx, cy, labelR * 0.11f, hole)

        // sleeve on top
        card(s, RectF(sx, sy, sx + side, sy + side), side * 0.02f, s.pal[1])
    }

    /** Tilted Polaroid with tape and a handwritten-style caption. */
    private fun polaroid(s: Scene) {
        auraBackground(s, 0.3f)
        val c = s.c
        val fw = sideOf(s) * 0.9f
        val m = fw * 0.055f
        val p = fw - 2f * m
        val fh = m + p + fw * 0.21f
        val x = (s.w - fw) / 2f
        val y = topOf(s)
        val cx = x + fw / 2f
        val cy = y + fh / 2f

        c.save()
        c.rotate(-5f, cx, cy)

        val frame = RectF(x, y, x + fw, y + fh)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(250, 248, 244)
            setShadowLayer(22f * s.u, 0f, 10f * s.u, Color.argb(150, 0, 0, 0))
        }
        c.drawRoundRect(frame, fw * 0.018f, fw * 0.018f, framePaint)

        val photo = RectF(x + m, y + m, x + m + p, y + m + p)
        c.drawRect(photo, coverPaint(s.art, photo))

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 34)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = fw * 0.062f
        }
        val baseY = y + m + p + fw * 0.085f
        val avail = fw - 2f * m
        c.drawText(TextUtils.ellipsize(s.o.title, tp, avail, TextUtils.TruncateAt.END).toString(), x + m, baseY, tp)
        if (s.o.artist.isNotBlank()) {
            tp.typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            tp.textSize = fw * 0.048f
            tp.color = Color.rgb(95, 95, 104)
            c.drawText(TextUtils.ellipsize(s.o.artist, tp, avail, TextUtils.TruncateAt.END).toString(), x + m, baseY + fw * 0.07f, tp)
        }

        // piece of tape
        val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 242, 230, 201) }
        c.save()
        c.rotate(-4f, cx, y)
        val tw = fw * 0.34f
        val th = fw * 0.085f
        c.drawRect(cx - tw / 2f, y - th / 2f, cx + tw / 2f, y + th / 2f, tape)
        c.restore()

        c.restore()
    }

    /** Edge-to-edge cover that melts into the background + giant outlined title down the left side. */
    private fun poster(s: Scene) {
        auraBackground(s, 0.22f)
        val c = s.c
        val side = s.w * (0.5f + s.o.sizePct / 100f * 0.75f)
        val x = (s.w - side) / 2f
        val y = topOf(s) - side * 0.10f
        val rect = RectF(x, y, x + side, y + side)

        val layer = c.saveLayer(x, y, x + side, y + side, null)
        c.drawRect(rect, coverPaint(s.art, rect))
        val fade = Paint().apply {
            shader = LinearGradient(
                0f, y, 0f, y + side,
                intArrayOf(0x00000000, Color.BLACK, Color.BLACK, 0x00000000),
                floatArrayOf(0f, 0.2f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawRect(rect, fade)
        c.restoreToCount(layer)

        // vertical title
        val title = s.o.title.uppercase()
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * s.u
            color = Color.argb(235, 255, 255, 255)
        }
        val maxLen = s.h * 0.46f
        var size = s.w * 0.17f
        tp.textSize = size
        while (tp.measureText(title) > maxLen && size > s.w * 0.07f) {
            size -= s.w * 0.005f
            tp.textSize = size
        }
        val shown = TextUtils.ellipsize(title, tp, maxLen, TextUtils.TruncateAt.END).toString()

        c.save()
        c.translate(s.w * 0.045f + size * 0.78f, s.h * 0.77f)
        c.rotate(-90f)
        c.drawText(shown, 0f, 0f, tp)
        if (s.o.artist.isNotBlank()) {
            val ap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                color = Color.argb(230, 255, 255, 255)
                textSize = s.w * 0.04f
                letterSpacing = 0.18f
            }
            val artist = TextUtils.ellipsize(s.o.artist.uppercase(), ap, maxLen, TextUtils.TruncateAt.END).toString()
            c.drawText(artist, 0f, s.w * 0.07f, ap)
        }
        c.restore()
    }

    /** The original look: blurred cover behind a rounded cover card. */
    private fun classic(s: Scene) {
        val n = 24
        val tiny = Bitmap.createScaledBitmap(s.art, n, n, true)
        val srcW = min(n.toFloat(), n * (s.w / s.h))
        val left = ((n - srcW) / 2f).toInt()
        s.c.drawBitmap(
            tiny,
            Rect(left, 0, left + max(1, srcW.toInt()), n),
            RectF(0f, 0f, s.w, s.h),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        s.c.drawColor(Color.argb(110, 0, 0, 0))
        val side = sideOf(s)
        val x = (s.w - side) / 2f
        val y = topOf(s)
        card(s, RectF(x, y, x + side, y + side), side * 0.05f, Color.BLACK)
    }

    // ───────────────────────── building blocks ─────────────────────────

    /** Cover fills the entire screen, like the iOS / OriginOS music lock screens. */
    private fun full(s: Scene) {
        val art = s.art
        val scale = max(s.w / art.width, s.h / art.height)
        val aw = art.width * scale
        val ah = art.height * scale
        val dx = (s.w - aw) * (s.o.focusPct / 100f)
        val dy = (s.h - ah) / 2f
        s.c.drawBitmap(
            art, null, RectF(dx, dy, dx + aw, dy + ah),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        legibility(s)
    }

    /** Smooth, zoomed-in, blurred cover (Spotify-style). */
    private fun dreamy(s: Scene) {
        val art = shrink(s.art, 288)
        val ow = 72
        val oh = max(1, (ow * s.h / s.w).toInt())
        val small = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val sc = max(ow.toFloat() / art.width, oh.toFloat() / art.height) * 1.1f
        val aw = art.width * sc
        val ah = art.height * sc
        val dx = (ow - aw) * (s.o.focusPct / 100f)
        val dy = (oh - ah) / 2f
        Canvas(small).drawBitmap(art, null, RectF(dx, dy, dx + aw, dy + ah), Paint(Paint.FILTER_BITMAP_FLAG))
        val blurred = boxBlur(small, 4, 3)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) })
        }
        s.c.drawBitmap(blurred, null, RectF(0f, 0f, s.w, s.h), paint)
        s.c.drawColor(Color.argb(50, 0, 0, 0))
        legibility(s)
    }

    /** Dark fade at the top and bottom so the clock, notifications and player stay readable. */
    private fun legibility(s: Scene) {
        val v = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, s.h,
                intArrayOf(Color.argb(130, 0, 0, 0), 0x00000000, 0x00000000, Color.argb(200, 0, 0, 0)),
                floatArrayOf(0f, 0.28f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        s.c.drawRect(0f, 0f, s.w, s.h, v)
    }

    private fun boxBlur(bm: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = bm.width
        val h = bm.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bm.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurPass(a, b, w, h, radius, true)
            blurPass(b, a, w, h, radius, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun pos(line: Int, i: Int, w: Int, horizontal: Boolean) =
        if (horizontal) line * w + i else i * w + line

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = src[pos(line, k.coerceIn(0, len - 1), w, horizontal)]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (i in 0 until len) {
                dst[pos(line, i, w, horizontal)] =
                    (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[pos(line, (i + r + 1).coerceAtMost(len - 1), w, horizontal)]
                val sub = src[pos(line, (i - r).coerceAtLeast(0), w, horizontal)]
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }

    /** Halves the bitmap repeatedly while it is still at least twice [target] (clean downscaling). */
    private fun shrink(bm: Bitmap, target: Int): Bitmap {
        var cur = bm
        while (min(cur.width, cur.height) / 2 >= target) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        return cur
    }

    private fun sideOf(s: Scene) = s.w * s.o.sizePct / 100f
    private fun topOf(s: Scene) = s.h * s.o.topPct / 100f

    private fun withAlpha(color: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun darken(color: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = hsv[2] * f
        return Color.HSVToColor(hsv)
    }

    /** Paint that draws [art] center-cropped into [dest]. */
    private fun coverPaint(art: Bitmap, dest: RectF): Paint {
        val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(dest.width() / art.width, dest.height() / art.height)
        val dx = dest.left + (dest.width() - art.width * scale) / 2f
        val dy = dest.top + (dest.height() - art.height * scale) / 2f
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(dx, dy)
        shader.setLocalMatrix(m)
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
    }

    /** Rounded cover with a colored glow and a thin glassy border. */
    private fun card(s: Scene, rect: RectF, radius: Float, glow: Int) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(26f * s.u, 0f, 10f * s.u, withAlpha(glow, 170))
        }
        s.c.drawRoundRect(rect, radius, radius, shadow)
        s.c.drawRoundRect(rect, radius, radius, coverPaint(s.art, rect))
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * s.u
            color = Color.argb(70, 255, 255, 255)
        }
        s.c.drawRoundRect(rect, radius, radius, border)
    }

    /** Soft overlapping color blobs = the trendy "mesh gradient" background. */
    private fun auraBackground(s: Scene, dim: Float) {
        val c = s.c
        c.drawColor(darken(s.pal[0], dim))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun blob(cx: Float, cy: Float, r: Float, col: Int, a: Int) {
            p.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(withAlpha(col, a), withAlpha(col, 0)),
                null,
                Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, p)
        }
        blob(s.w * 0.15f, s.h * 0.12f, s.w * 1.0f, s.pal[0], 235)
        blob(s.w * 0.95f, s.h * 0.42f, s.w * 0.9f, s.pal[1], 215)
        blob(s.w * 0.20f, s.h * 0.78f, s.w * 1.0f, s.pal[2], 225)
        blob(s.w * 0.90f, s.h * 0.98f, s.w * 0.8f, s.pal[0], 200)

        // slight darkening at top/bottom so the clock and system UI stay readable
        val v = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, s.h,
                intArrayOf(Color.argb(90, 0, 0, 0), 0x00000000, 0x00000000, Color.argb(120, 0, 0, 0)),
                floatArrayOf(0f, 0.25f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, s.w, s.h, v)
    }

    // film grain tile
    private val noiseBitmap: Bitmap by lazy {
        val n = 128
        val px = IntArray(n * n)
        val rnd = Random(11)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = Color.argb(rnd.nextInt(256), v, v, v)
        }
        val bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bm.setPixels(px, 0, n, 0, 0, n, n)
        bm
    }

    private fun grain(c: Canvas, amount: Int) {
        val p = Paint().apply {
            shader = BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            alpha = amount
        }
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)
    }

    /** Three vivid colors from the cover (by hue buckets, weighted by saturation x brightness). */
    fun palette(art: Bitmap): IntArray {
        val n = 24
        val small = Bitmap.createScaledBitmap(shrink(art, n * 4), n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)

        val bins = 12
        val ws = FloatArray(bins)
        val rs = FloatArray(bins)
        val gs = FloatArray(bins)
        val bs = FloatArray(bins)
        val hsv = FloatArray(3)
        for (p in px) {
            Color.colorToHSV(p, hsv)
            val wgt = hsv[1] * hsv[2] + 0.02f
            val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            ws[bin] += wgt
            rs[bin] += Color.red(p) * wgt
            gs[bin] += Color.green(p) * wgt
            bs[bin] += Color.blue(p) * wgt
        }

        val out = ArrayList<Int>()
        val chosen = ArrayList<Int>()
        for (b in (0 until bins).sortedByDescending { ws[it] }) {
            if (out.size == 3) break
            if (ws[b] <= 0f) continue
            if (chosen.any { val d = abs(it - b); min(d, bins - d) <= 1 }) continue
            var col = Color.rgb((rs[b] / ws[b]).toInt(), (gs[b] / ws[b]).toInt(), (bs[b] / ws[b]).toInt())
            Color.colorToHSV(col, hsv)
            if (hsv[1] > 0.15f) hsv[1] = min(1f, hsv[1] * 1.25f)
            hsv[2] = hsv[2].coerceIn(0.45f, 1f)
            col = Color.HSVToColor(hsv)
            out.add(col)
            chosen.add(b)
        }
        // not enough distinct hues (e.g. mostly one color): add neighbors of the main hue
        while (out.size < 3) {
            Color.colorToHSV(out[0], hsv)
            val shift = if (out.size % 2 == 1) 20f else -20f
            hsv[0] = (hsv[0] + shift + 360f) % 360f
            out.add(Color.HSVToColor(hsv))
        }
        return out.toIntArray()
    }
}
