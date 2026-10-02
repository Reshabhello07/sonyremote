
package com.example.sonyremote

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    // Measured: the speaker takes 30 clicks to go from 0 to 100%.
    // START = the step the app assumes after Power on (still to be measured).
    private val STEPS = 30
    private val START = 3

    private val h = Handler(Looper.getMainLooper())
    private var ir: ConsumerIrManager? = null
    private var on = false
    private var level = START
    private var busy = false

    private lateinit var numView: TextView
    private lateinit var stateView: TextView
    private lateinit var statusView: TextView
    private lateinit var volCard: LinearLayout
    private lateinit var powerIcon: IconView
    private lateinit var downBtn: View
    private lateinit var upBtn: View
    private val cells = ArrayList<GradientDrawable>()
    private val powerBg = GradientDrawable()

    private var cBg = 0; private var cSurface = 0; private var cInk = 0; private var cMute = 0
    private var cLine = 0; private var cAccent = 0; private var cOnAccent = 0; private var cOff = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(w: Int, hgt: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, hgt, weight).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) {
            cBg = Color.parseColor("#14161A"); cSurface = Color.parseColor("#1E2127")
            cInk = Color.parseColor("#EEF0F3"); cMute = Color.parseColor("#9AA1AD")
            cLine = Color.parseColor("#2B2F37"); cAccent = Color.parseColor("#7B93FF")
            cOnAccent = Color.parseColor("#10131A"); cOff = Color.parseColor("#FF6B57")
        } else {
            cBg = Color.parseColor("#F4F5F7"); cSurface = Color.WHITE
            cInk = Color.parseColor("#16181D"); cMute = Color.parseColor("#6B7280")
            cLine = Color.parseColor("#E4E6EB"); cAccent = Color.parseColor("#3B5BDB")
            cOnAccent = Color.WHITE; cOff = Color.parseColor("#D6402F")
        }
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        if (!night) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        val prefs = getSharedPreferences("s", MODE_PRIVATE)
        on = prefs.getBoolean("on", false)
        level = prefs.getInt("level", START).coerceIn(0, STEPS)

        setContentView(buildUi())
        render()
        status(if (ir?.hasIrEmitter() == true) "Ready" else "This phone has no IR blaster")
    }

    private fun buildUi(): View {
        val root = ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(col)

        // Header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Sony SA-D10"; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(cInk)
        })
        stateView = TextView(this).apply { textSize = 13f; setTextColor(cMute); setPadding(0, dp(2), 0, dp(6)) }
        stateView.setOnClickListener {
            on = !on; save(); render(); status("Marked as ${if (on) "on" else "off"} (nothing sent)")
        }
        titles.addView(stateView)
        header.addView(titles, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f))
        powerIcon = IconView(this, IconView.POWER)
        val power = FrameLayout(this).apply { background = powerBg; contentDescription = "Power" }
        power.addView(powerIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        tap(power, Buttons.power)
        header.addView(power, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(header, lp(-1, -2))

        // Volume card
        volCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = box(cSurface, 28, cLine)
            setPadding(dp(20), dp(28), dp(20), dp(22))
        }
        numView = TextView(this).apply {
            textSize = 80f; setTypeface(null, Typeface.BOLD); setTextColor(cInk)
            includeFontPadding = false
        }
        volCard.addView(numView, lp(-2, -2))
        val bar = LinearLayout(this)
        for (i in 0 until STEPS) {
            val d = box(cLine, 3)
            cells.add(d)
            val v = View(this).apply { background = d }
            bar.addView(v, LinearLayout.LayoutParams(0, dp(10), 1f).apply { marginStart = dp(1); marginEnd = dp(1) })
        }
        volCard.addView(bar, lp(-1, dp(10), 22))
        col.addView(volCard, lp(-1, -2, 20))

        // Volume buttons
        val vb = LinearLayout(this)
        val down = TextView(this).apply {
            text = "\u2212"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(cInk)
            background = box(cSurface, 20, cLine); contentDescription = "Volume down"
        }
        val up = TextView(this).apply {
            text = "+"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(cOnAccent)
            background = box(cAccent, 20); contentDescription = "Volume up"
        }
        downBtn = down
        upBtn = up
        hold(down) { press(Buttons.volDn) }
        hold(up) { press(Buttons.volUp) }
        vb.addView(down, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(6) })
        vb.addView(up, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(6) })
        col.addView(vb, lp(-1, -2, 20))

        col.addView(TextView(this).apply {
            text = "Re-sync volume"; textSize = 14f; setTextColor(cMute); gravity = Gravity.CENTER
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { resync() }
        }, lp(-1, -2, 8))

        // Input
        val input = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = box(cSurface, 16, cLine)
            contentDescription = "Input"
        }
        val inIcon = IconView(this, IconView.INPUT).apply { tint = cInk }
        input.addView(inIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        input.addView(TextView(this).apply {
            text = "Input"; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(cInk)
        })
        tap(input, Buttons.input)
        col.addView(input, lp(-1, dp(52), 12))

        // Media row
        val media = LinearLayout(this)
        val items = listOf(
            Triple(IconView.SKIP_BACK, Buttons.skipBack, "Skip back"),
            Triple(IconView.REW, Buttons.rewind, "Rewind"),
            Triple(IconView.PLAY, Buttons.playPause, "Play or pause"),
            Triple(IconView.FF, Buttons.fastForward, "Fast forward"),
            Triple(IconView.SKIP_FWD, Buttons.skipForward, "Skip forward")
        )
        for ((kind, btn, label) in items) {
            val cell = FrameLayout(this).apply { background = box(cSurface, 16, cLine); contentDescription = label }
            cell.addView(IconView(this, kind).apply { tint = cInk }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            tap(cell, btn)
            media.addView(cell, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        col.addView(media, lp(-1, -2, 16))

        statusView = TextView(this).apply { textSize = 13f; setTextColor(cMute); gravity = Gravity.CENTER }
        col.addView(statusView, lp(-1, -2, 20))
        return root
    }

    // Normal button: tap to send, with a quick press effect.
    private fun tap(v: View, b: Btn) {
        v.setOnTouchListener { x, e ->
            if (e.action == MotionEvent.ACTION_DOWN) x.alpha = 0.7f
            else if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) x.alpha = 1f
            false
        }
        v.setOnClickListener { press(b) }
    }

    // Volume button: sends once, then repeats while held.
    private fun hold(v: View, f: () -> Unit) {
        val rep = object : Runnable {
            override fun run() { f(); h.postDelayed(this, 170) }
        }
        v.setOnTouchListener { x, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { if (!isLocked(x)) x.alpha = 0.7f; f(); h.postDelayed(rep, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    x.alpha = if (isLocked(x)) 0.35f else 1f
                    h.removeCallbacks(rep)
                }
            }
            true
        }
    }

    // Vol down is locked at 0%, Vol up is locked at 100%, so the count can't drift.
    private fun isLocked(v: View) =
        on && ((v === downBtn && level == 0) || (v === upBtn && level == STEPS))

    private fun pct() = Math.round(level * 100f / STEPS)

    private fun transmit(b: Btn): Boolean {
        val m = ir
        if (m == null || !m.hasIrEmitter()) { status("This phone has no IR blaster"); return false }
        m.transmit(Sirc.CARRIER_HZ, Sirc.pattern(b))
        return true
    }

    private fun press(b: Btn) {
        if (busy) return
        if (on && b == Buttons.volDn && level == 0) { status("Already at 0%"); return }
        if (on && b == Buttons.volUp && level == STEPS) { status("Already at 100%"); return }
        volCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (!transmit(b)) return
        if (b == Buttons.power) {
            on = !on
            if (on) level = START
        } else if (b == Buttons.volUp) {
            if (on) level = minOf(STEPS, level + 1)
        } else if (b == Buttons.volDn) {
            if (on) level = maxOf(0, level - 1)
        }
        save(); render(); status("Sent ${b.name}")
    }

    // Drops the speaker to 0 (a couple of extra presses to be safe) and stays at 0%.
    private fun resync() {
        if (busy) return
        if (!on) { status("Turn the speaker on first"); return }
        busy = true
        val downs = STEPS + 2
        var i = 0
        val r = object : Runnable {
            override fun run() {
                if (i < downs) {
                    transmit(Buttons.volDn)
                    level = maxOf(0, level - 1)
                    i++
                    status("Syncing volume...")
                    render()
                    h.postDelayed(this, 180)
                } else {
                    busy = false; save()
                    level = 0
                    render()
                    status("Synced at 0%")
                }
            }
        }
        h.post(r)
    }

    private fun save() {
        getSharedPreferences("s", MODE_PRIVATE).edit().putBoolean("on", on).putInt("level", level).apply()
    }

    private fun status(t: String) { statusView.text = t }

    private fun render() {
        val t = SpannableString("${pct()}%")
        t.setSpan(RelativeSizeSpan(0.4f), t.length - 1, t.length, 0)
        t.setSpan(ForegroundColorSpan(cMute), t.length - 1, t.length, 0)
        numView.text = t
        for (i in cells.indices) cells[i].setColor(if (i < level) cAccent else cLine)
        stateView.text = if (on) "On" else "Off"
        powerBg.shape = GradientDrawable.OVAL
        powerBg.setColor(if (on) cOff else cSurface)
        powerBg.setStroke(dp(1), if (on) cOff else cLine)
        powerIcon.tint = if (on) Color.WHITE else cOff
        powerIcon.invalidate()
        volCard.alpha = if (on) 1f else 0.55f
        downBtn.alpha = if (on && level == 0) 0.35f else 1f
        upBtn.alpha = if (on && level == STEPS) 0.35f else 1f
    }
}
