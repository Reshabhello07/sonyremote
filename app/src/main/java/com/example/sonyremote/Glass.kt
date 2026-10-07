package com.example.sonyremote

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The full-screen picture behind the remote: either a soft colour wash or the
 * blurred album art. Glass buttons sample this same bitmap, which is what makes
 * them look like glass instead of grey boxes.
 *
 * The picture is drawn a little zoomed in and drifts very slowly, so the room
 * behind the glass is always gently moving.
 */
class BackdropView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        private set
    private var previous: Bitmap? = null
    private var fade = 1f
    private var anim: ValueAnimator? = null
    private var phase = 0f
    private var tickCount = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()

    /** Called on every redraw of the drift, so glass panes can follow the background. */
    var onDrift: (() -> Unit)? = null

    private val drift: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 90000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            tickCount++
            // Every other frame is plenty for something this slow, and saves battery.
            if (tickCount % 2 == 0) {
                invalidate()
                onDrift?.invoke()
            }
        }
    }

    fun startDrift() {
        if (!drift.isStarted) drift.start() else if (drift.isPaused) drift.resume()
    }

    fun stopDrift() {
        if (drift.isStarted && !drift.isPaused) drift.pause()
    }

    /** Maps the bitmap's pixels onto this view, including the current zoom and drift. */
    fun bitmapMatrix(out: Matrix, bmp: Bitmap) {
        val w = width.toFloat()
        val h = height.toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()
        val s = 1.18f + 0.03f * sin(twoPi * phase)
        val mx = w * (s - 1f) / 2f
        val my = h * (s - 1f) / 2f
        val dx = 0.8f * mx * sin(twoPi * 2f * phase)
        val dy = 0.8f * my * cos(twoPi * 3f * phase)
        out.setScale(w / bmp.width, h / bmp.height)
        out.postScale(s, s, w / 2f, h / 2f)
        out.postTranslate(dx, dy)
    }

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
        val prev = previous
        paint.alpha = 255
        if (prev != null && fade < 1f) {
            bitmapMatrix(m, prev)
            canvas.drawBitmap(prev, m, paint)
            paint.alpha = (fade * 255f).toInt()
        }
        bitmapMatrix(m, cur)
        canvas.drawBitmap(cur, m, paint)
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

    /**
     * Picks one bright, saturated colour from the artwork, for the + button and the
     * cover's glow. Returns null for grey / black-and-white art.
     */
    fun accentOf(src: Bitmap): Int? {
        return try {
            val s: Bitmap = if (Build.VERSION.SDK_INT >= 26 && src.config == Bitmap.Config.HARDWARE) {
                src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            } else {
                src
            }
            val n = 24
            val small = Bitmap.createScaledBitmap(s, n, n, true)
            val weight = FloatArray(12)
            val rs = FloatArray(12)
            val gs = FloatArray(12)
            val bs = FloatArray(12)
            val hsv = FloatArray(3)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val px = small.getPixel(x, y)
                    Color.colorToHSV(px, hsv)
                    if (hsv[2] < 0.15f) continue
                    val w = hsv[1] * hsv[2]
                    val b = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                    weight[b] += w
                    rs[b] += Color.red(px) * w
                    gs[b] += Color.green(px) * w
                    bs[b] += Color.blue(px) * w
                }
            }
            var best = 0
            for (i in 1 until 12) if (weight[i] > weight[best]) best = i
            if (weight[best] < n * n * 0.04f) return null
            val c = Color.rgb(
                (rs[best] / weight[best]).toInt().coerceIn(0, 255),
                (gs[best] / weight[best]).toInt().coerceIn(0, 255),
                (bs[best] / weight[best]).toInt().coerceIn(0, 255),
            )
            Color.colorToHSV(c, hsv)
            hsv[1] = max(hsv[1], 0.55f)
            hsv[2] = max(hsv[2], 0.85f)
            Color.HSVToColor(hsv)
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
    tintColor: Int,
    private val boost: Float,
) : Drawable() {

    private val d = density

    /** The colour of the film. Changing it (for example to the album's colour) repaints the glass. */
    var film: Int = tintColor
        set(value) {
            if (field != value) {
                field = value
                rebuild()
                invalidateSelf()
            }
        }

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
            argb(film, min(topA * k, 0.9f)), argb(film, min(botA * k, 0.9f)),
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
            backdrop.bitmapMatrix(m, bmp)
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

/** A soft coloured glow, drawn behind the album cover. */
class GlowView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    var color: Int = Color.TRANSPARENT
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    init {
        paint.color = color
        // BlurMaskFilter only works on a software layer; this view is drawn rarely, so that's fine.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        paint.maskFilter = BlurMaskFilter(26f * d, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 36f * d
        val drop = 12f * d
        box.set(pad, pad + drop, width - pad, height - pad + drop)
        canvas.drawRoundRect(box, 26f * d, 26f * d, paint)
    }
}

/** A thin rounded bar that shows how far into the song you are. */
class ProgressView(ctx: Context) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var fraction: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    var inkColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        track.color = (inkColor and 0x00FFFFFF) or (0x40 shl 24)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) {
            fillPaint.color = inkColor
            rect.set(0f, 0f, max(w * fraction, h), h)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }
}

/**
 * The volume slider: a glass track, a glowing fill and a round knob you can drag.
 * It counts in whole steps (0..[steps]) and tells [onChange] when you move to a new one.
 */
class GlassSlider(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * ctx.resources.displayMetrics.density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var shown = 0f          // where the knob is drawn, in steps (it glides between whole steps)
    private var glide: ValueAnimator? = null
    private var knobScale = 1f
    private var knobAnim: ValueAnimator? = null

    var steps: Int = 30

    var current: Int = 0
        private set

    /** Called with the new step whenever a finger moves the knob to a different step. */
    var onChange: ((Int) -> Unit)? = null

    var inkColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var glowColor: Int = Color.rgb(96, 125, 255)
        set(value) {
            field = value
            invalidate()
        }

    /** Sets the position from code (buttons, sync) without calling [onChange]. */
    fun setLevel(v: Int, animate: Boolean = true) {
        current = v.coerceIn(0, steps)
        if (shown != current.toFloat()) glideTo(current.toFloat(), animate)
    }

    private fun glideTo(target: Float, animate: Boolean) {
        glide?.cancel()
        if (!animate || width == 0) {
            shown = target
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(shown, target)
        a.duration = 110L
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        glide = a
        a.start()
    }

    private fun growKnob(grow: Boolean) {
        knobAnim?.cancel()
        val a = ValueAnimator.ofFloat(knobScale, if (grow) 1.2f else 1f)
        a.duration = 140L
        a.addUpdateListener {
            knobScale = it.animatedValue as Float
            invalidate()
        }
        knobAnim = a
        a.start()
    }

    private fun moveTo(x: Float) {
        val pad = 18f * d
        val span = width - 2f * pad
        if (span <= 0f) return
        val f = ((x - pad) / span).coerceIn(0f, 1f)
        val v = Math.round(f * steps)
        if (v != current) {
            current = v
            glideTo(v.toFloat(), true)
            onChange?.invoke(v)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Don't let the scroll view steal the drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                growKnob(true)
                moveTo(e.x)
            }
            MotionEvent.ACTION_MOVE -> moveTo(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> growKnob(false)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 18f * d
        val left = pad
        val right = w - pad
        val cy = h / 2f
        val tr = 7f * d                      // half the thickness of the track
        val frac = if (steps > 0) (shown / steps).coerceIn(0f, 1f) else 0f
        val kx = left + (right - left) * frac

        // Track
        track.color = (inkColor and 0x00FFFFFF) or (0x38 shl 24)
        rect.set(left, cy - tr, right, cy + tr)
        canvas.drawRoundRect(rect, tr, tr, track)
        rimPaint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRoundRect(rect, tr, tr, rimPaint)

        // Glowing fill: a few soft halos around it, then the solid part
        if (frac > 0f) {
            val fillRight = max(kx, left + 2f * tr)
            for (i in 4 downTo 1) {
                val g = i * 2.5f * d
                glowPaint.color = (glowColor and 0x00FFFFFF) or ((22 * (5 - i)) shl 24)
                rect.set(left - g * 0.6f, cy - tr - g, fillRight + g * 0.6f, cy + tr + g)
                canvas.drawRoundRect(rect, tr + g, tr + g, glowPaint)
            }
            fillPaint.color = inkColor
            rect.set(left, cy - tr, fillRight, cy + tr)
            canvas.drawRoundRect(rect, tr, tr, fillPaint)
        }

        // Knob
        val kr = 15f * d * knobScale
        knobPaint.color = Color.argb(45, 0, 0, 0)
        canvas.drawCircle(kx, cy + 2f * d, kr + 2f * d, knobPaint)
        knobPaint.color = Color.argb(250, 255, 255, 255)
        canvas.drawCircle(kx, cy, kr, knobPaint)
        rimPaint.color = Color.argb(40, 0, 0, 0)
        canvas.drawCircle(kx, cy, kr, rimPaint)
    }
}
