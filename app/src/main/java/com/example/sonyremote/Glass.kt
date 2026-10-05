package com.example.sonyremote

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The full-screen picture behind the remote: either a soft colour wash or the
 * blurred album art. Glass buttons sample this same bitmap, which is what makes
 * them look like glass instead of grey boxes.
 */
class BackdropView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        private set
    private var previous: Bitmap? = null
    private var fade = 1f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    fun setBitmap(b: Bitmap, animate: Boolean) {
        anim?.cancel()
        if (animate && bitmap != null) {
            previous = bitmap
            bitmap = b
            fade = 0f
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 650L
            a.addUpdateListener {
                fade = it.animatedValue as Float
                invalidate()
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    previous = null
                    invalidate()
                }
            })
            anim = a
            a.start()
        } else {
            previous = null
            bitmap = b
            fade = 1f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cur = bitmap ?: return
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val prev = previous
        paint.alpha = 255
        if (prev != null && fade < 1f) {
            canvas.drawBitmap(prev, null, dst, paint)
            paint.alpha = (fade * 255f).toInt()
        }
        canvas.drawBitmap(cur, null, dst, paint)
        paint.alpha = 255
    }
}

/** Builds the bitmaps that go into [BackdropView]. */
object Ambient {
    const val W = 128

    /** Soft coloured blobs, so the glass has something to show before any art exists. */
    fun gradient(w: Int, h: Int, night: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if (night) Color.rgb(11, 13, 20) else Color.rgb(236, 240, 248))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val big = max(w, h).toFloat()
        fun blob(cx: Float, cy: Float, r: Float, color: Int) {
            p.shader = RadialGradient(cx * w, cy * h, r * big, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        if (night) {
            blob(0.10f, 0.10f, 0.80f, Color.argb(210, 80, 100, 255))
            blob(0.95f, 0.52f, 0.70f, Color.argb(180, 214, 70, 160))
            blob(0.05f, 0.95f, 0.75f, Color.argb(160, 40, 190, 175))
        } else {
            blob(0.10f, 0.10f, 0.80f, Color.argb(200, 150, 165, 255))
            blob(0.95f, 0.52f, 0.70f, Color.argb(180, 255, 170, 205))
            blob(0.05f, 0.95f, 0.75f, Color.argb(170, 150, 235, 200))
        }
        return bmp
    }

    /**
     * Album art -> the Apple Music look: zoomed to fill, heavily blurred, a bit more
     * saturated and a bit darker so white text stays readable on top.
     * Runs on a worker thread. Returns null if the bitmap can't be read.
     */
    fun fromArtwork(src: Bitmap, w: Int, h: Int): Bitmap? {
        return try {
            val s: Bitmap = if (Build.VERSION.SDK_INT >= 26 && src.config == Bitmap.Config.HARDWARE) {
                src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                src
            }
            val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(small)
            val scale = max(w.toFloat() / s.width, h.toFloat() / s.height) * 1.15f
            val dw = s.width * scale
            val dh = s.height * scale
            val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(s, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))

            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, 7) }
            small.setPixels(px, 0, w, 0, 0, w, h)

            val cm = ColorMatrix()
            cm.setSaturation(1.5f)
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.72f, 0f, 0f, 0f, 0f,
                        0f, 0.72f, 0f, 0f, 0f,
                        0f, 0f, 0.72f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint()
            paint.colorFilter = ColorMatrixColorFilter(cm)
            Canvas(out).drawBitmap(small, 0f, 0f, paint)
            out
        } catch (e: Throwable) {
            null
        }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        blurPass(px, tmp, w, h, r, true)
        blurPass(tmp, px, w, h, r, false)
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, len - 1) * stride]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (i in 0 until len) {
                dst[base + i * stride] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val a = src[base + min(i + r + 1, len - 1) * stride]
                val s = src[base + max(i - r, 0) * stride]
                sr += ((a shr 16) and 255) - ((s shr 16) and 255)
                sg += ((a shr 8) and 255) - ((s shr 8) and 255)
                sb += (a and 255) - (s and 255)
            }
        }
    }
}

/**
 * A pane of glass, used as a View background.
 *
 * Layers, bottom to top: the backdrop under this view (slightly magnified and more
 * saturated, which is the cheap stand-in for refraction), a white film that is
 * lighter at the top, and a thin rim that catches light on the top-left and
 * bottom-right edges.
 */
class GlassDrawable(
    private val host: View,
    private val backdrop: BackdropView,
    private val radiusDp: Int,
    density: Float,
    private val tint: Int,
    private val boost: Float,
) : Drawable() {

    private val d = density

    /** Light style: dark ink on a bright wash. Otherwise white ink on a dark one. */
    var light = false
        set(value) {
            if (field != value) {
                field = value
                rebuild()
                invalidateSelf()
            }
        }

    /** 0..1, makes the glass brighter. Used for the Bluetooth pill while its mode is on. */
    var emphasis = 0f
        set(value) {
            if (field != value) {
                field = value
                rebuild()
                invalidateSelf()
            }
        }

    private var pressed = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val rect = RectF()
    private val m = Matrix()
    private val hostLoc = IntArray(2)
    private val bdLoc = IntArray(2)
    private var shader: BitmapShader? = null
    private var shaderBmp: Bitmap? = null

    private fun argb(c: Int, a: Float): Int =
        (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    private fun rebuild() {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val inset = rim.strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val k = boost * (1f + 0.8f * emphasis) * (if (pressed) 1.5f else 1f)
        val topA = if (light) 0.62f else 0.24f
        val botA = if (light) 0.30f else 0.07f
        wash.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            argb(tint, min(topA * k, 0.9f)), argb(tint, min(botA * k, 0.9f)),
            Shader.TileMode.CLAMP,
        )
        val white = Color.WHITE
        rim.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(argb(white, 0.85f), argb(white, 0.08f), argb(white, 0.08f), argb(white, 0.55f)),
            floatArrayOf(0f, 0.42f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        edge.color = Color.argb(if (light) 22 else 0, 0, 0, 0)
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        rebuild()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val side = min(w, h)
        val r = if (radiusDp < 0) side / 2f else min(radiusDp * d, side / 2f)
        val rr = max(0f, r - rim.strokeWidth / 2f)

        val bmp = backdrop.bitmap
        if (bmp != null && backdrop.width > 0 && backdrop.height > 0) {
            if (bmp !== shaderBmp) {
                shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderBmp = bmp
            }
            host.getLocationInWindow(hostLoc)
            backdrop.getLocationInWindow(bdLoc)
            m.setScale(backdrop.width.toFloat() / bmp.width, backdrop.height.toFloat() / bmp.height)
            m.postTranslate((bdLoc[0] - hostLoc[0]).toFloat(), (bdLoc[1] - hostLoc[1]).toFloat())
            m.postScale(LENS, LENS, w / 2f, h / 2f)
            shader?.setLocalMatrix(m)
            fill.shader = shader
            fill.colorFilter = VIBRANT
        } else {
            fill.shader = null
            fill.colorFilter = null
            fill.color = if (light) Color.rgb(232, 235, 242) else Color.rgb(34, 37, 45)
        }
        canvas.drawRoundRect(rect, rr, rr, fill)
        canvas.drawRoundRect(rect, rr, rr, wash)
        if (light) canvas.drawRoundRect(rect, rr, rr, edge)
        canvas.drawRoundRect(rect, rr, rr, rim)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** How much the backdrop is magnified under the glass: the fake lens. */
        const val LENS = 1.04f
        val VIBRANT = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) })
    }
}