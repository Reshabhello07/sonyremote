package com.example.sonyremote

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    // Measured: the speaker takes 30 clicks to go from 0 to 100%.
    // START = the level the app assumes the very first time, until you press Sync.
    private val STEPS = 30
    private val START = 3

    private val DELAY_MS = 500L      // quiet time after the last volume tap before clicks are sent
    private val GAP_MS = 250L        // time between clicks sent to the speaker
    private val SYNC_GAP_MS = 150L   // time between clicks during sync
    private val COMBO_WAIT_MS = 2000L // Bluetooth / Pendrive: wait after Power before Input
    private val INPUT_GAP_MS = 600L   // Pendrive: wait between the two Input presses

    private val h = Handler(Looper.getMainLooper())
    private var ir: ConsumerIrManager? = null
    private var level = START        // what the app shows (the target)
    private var speakerLevel = START // what the speaker has actually received
    private var busy = false
    private var lastTap = 0L
    private var nextFree = 0L

    private lateinit var numView: TextView
    private lateinit var syncLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var volCard: LinearLayout
    private lateinit var powerIcon: IconView
    private lateinit var downBtn: View
    private lateinit var upBtn: View
    private val cells = ArrayList<GradientDrawable>()

    // Glass look
    private lateinit var backdrop: BackdropView
    private lateinit var col: LinearLayout
    private lateinit var ambient: Bitmap
    private lateinit var btGlass: GlassDrawable
    private val glassDrawables = ArrayList<GlassDrawable>()
    private val glassHosts = ArrayList<View>()
    private val inkTexts = ArrayList<TextView>()
    private val muteTexts = ArrayList<TextView>()
    private val inkIcons = ArrayList<IconView>()
    private val cAccent = Color.rgb(96, 125, 255)
    private var night = false
    private var lightStyle = false
    private var ink = Color.WHITE
    private var inkMute = Color.WHITE

    // Bluetooth mode: the remote takes the colours of the song playing on the phone
    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var accessHint: TextView
    private var btMode = false
    private var artShown = false
    private var nowPlaying: NowPlaying? = null
    private var trackKey: String? = null
    private var artToken = 0
    private var artSize = 0
    private var shownPct = -1
    private var numAnim: ValueAnimator? = null
    private var lastLimitBuzz = 0L
    private var destroyed = false
    private val worker = Executors.newSingleThreadExecutor()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(w: Int, hgt: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, hgt, weight).apply { topMargin = dp(top) }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        val prefs = getSharedPreferences("s", MODE_PRIVATE)
        speakerLevel = prefs.getInt("level", START).coerceIn(0, STEPS)
        level = speakerLevel

        setContentView(buildUi())
        val dm = resources.displayMetrics
        val bh = (Ambient.W * dm.heightPixels.toFloat() / dm.widthPixels).toInt().coerceAtLeast(Ambient.W)
        ambient = Ambient.gradient(Ambient.W, bh, night)
        setBackdrop(ambient, false)
        applyTheme()
        status(if (ir?.hasIrEmitter() == true) "Ready" else "This phone has no IR blaster")
        playIntro()
    }

    override fun onResume() {
        super.onResume()
        if (btMode) startNowPlaying()
    }

    override fun onPause() {
        super.onPause()
        nowPlaying?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        nowPlaying?.stop()
        worker.shutdownNow()
    }

    // ---------------------------------------------------------------- UI

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val root = FrameLayout(this)
        backdrop = BackdropView(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(col)

        // Draw behind the status and navigation bars, and keep the content clear of them.
        root.setOnApplyWindowInsetsListener { _, insets ->
            col.setPadding(
                dp(20), dp(20) + insets.systemWindowInsetTop,
                dp(20), dp(28) + insets.systemWindowInsetBottom,
            )
            insets
        }
        // Glass reads the backdrop at its own position, so it repaints when things move.
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> invalidateGlass() }
        col.viewTreeObserver.addOnGlobalLayoutListener { invalidateGlass() }

        // Header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(inkText(TextView(this).apply {
            text = "Sony SA-D10"; textSize = 18f; setTypeface(null, Typeface.BOLD)
        }))
        header.addView(titles, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f))
        powerIcon = IconView(this, IconView.POWER)
        val power = FrameLayout(this).apply { contentDescription = "Power" }
        glass(power)
        power.addView(powerIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        onTap(power) {
            setBtMode(false)
            press(Buttons.power)
        }
        header.addView(power, LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(header, lp(-1, -2))

        // Volume card. In Bluetooth mode it also shows the cover, title and artist.
        volCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(22))
            clipChildren = false
            clipToPadding = false
        }
        glass(volCard, radiusDp = 32, elevDp = 8)

        artSize = minOf(resources.displayMetrics.widthPixels - dp(40 + 40), dp(320))
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            contentDescription = "Album art"
            elevation = dp(14).toFloat()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                    outline.setAlpha(1f)
                }
            }
        }
        volCard.addView(artView, LinearLayout.LayoutParams(artSize, artSize).apply { bottomMargin = dp(16) })

        // Big left-aligned title, softer artist line; long names slide sideways.
        titleView = inkText(TextView(this).apply {
            textSize = 24f; setTypeface(null, Typeface.BOLD); gravity = Gravity.START
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1; isSelected = true; visibility = View.GONE
        })
        artistView = inkText(TextView(this).apply {
            textSize = 16f; gravity = Gravity.START
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1; isSelected = true; visibility = View.GONE
        }, true)
        volCard.addView(titleView, LinearLayout.LayoutParams(artSize, -2))
        volCard.addView(artistView, LinearLayout.LayoutParams(artSize, -2).apply { topMargin = dp(2) })

        numView = inkText(TextView(this).apply {
            textSize = 80f; setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        })
        volCard.addView(numView, lp(-2, -2))
        val bar = LinearLayout(this)
        for (i in 0 until STEPS) {
            val d = box(Color.WHITE, 3)
            cells.add(d)
            val v = View(this).apply { background = d }
            bar.addView(v, LinearLayout.LayoutParams(0, dp(10), 1f).apply { marginStart = dp(1); marginEnd = dp(1) })
        }
        volCard.addView(bar, lp(-1, dp(10), 22))
        col.addView(volCard, lp(-1, -2, 20))

        // Shown in Bluetooth mode until the app is allowed to read the music player.
        accessHint = inkText(TextView(this).apply {
            text = "Show album art: allow notification access"
            textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        })
        glass(accessHint, elevDp = 4)
        onTap(accessHint) { openAccessSettings() }
        col.addView(accessHint, lp(-1, -2, 12))

        // Volume buttons
        val vb = LinearLayout(this)
        val down = inkText(TextView(this).apply {
            text = "\u2212"; textSize = 30f; gravity = Gravity.CENTER; contentDescription = "Volume down"
        })
        val up = inkText(TextView(this).apply {
            text = "+"; textSize = 30f; gravity = Gravity.CENTER; contentDescription = "Volume up"
        })
        glass(down, elevDp = 6)
        glass(up, tint = cAccent, boost = 1.7f, elevDp = 6)
        downBtn = down
        upBtn = up
        hold(down) { tapVolume(false) }
        hold(up) { tapVolume(true) }
        vb.addView(down, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(6) })
        vb.addView(up, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(6) })
        col.addView(vb, lp(-1, -2, 20))

        // Sync button with progress
        val sync = pill(IconView.SYNC, "Sync volume")
        syncLabel = sync.getChildAt(1) as TextView
        onTap(sync) { resync() }
        col.addView(sync, lp(-1, dp(52), 12))

        // Input
        val input = pill(IconView.INPUT, "Input")
        onTap(input) {
            setBtMode(false)
            press(Buttons.input)
        }
        col.addView(input, lp(-1, dp(52), 12))

        // Bluetooth and Pendrive: Power, then Input (once or twice)
        val combos = LinearLayout(this)
        val bt = pill(IconView.BT, "Bluetooth")
        btGlass = bt.background as GlassDrawable
        val usb = pill(IconView.USB, "Pendrive")
        onTap(bt) {
            if (busy) return@onTap
            if (btMode) {
                // Second tap leaves Bluetooth mode. No IR is sent: Power is a toggle.
                setBtMode(false)
                status("Bluetooth mode off")
            } else {
                sequence(listOf(Buttons.power to 0L, Buttons.input to COMBO_WAIT_MS), "Bluetooth")
                setBtMode(true)
            }
        }
        onTap(usb) {
            if (busy) return@onTap
            setBtMode(false)
            sequence(listOf(Buttons.power to 0L, Buttons.input to COMBO_WAIT_MS, Buttons.input to INPUT_GAP_MS), "Pendrive")
        }
        combos.addView(bt, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
        combos.addView(usb, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        col.addView(combos, lp(-1, -2, 12))

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
            val cell = FrameLayout(this).apply { contentDescription = label }
            glass(cell)
            val icon = IconView(this, kind)
            inkIcons.add(icon)
            cell.addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            tap(cell, btn)
            media.addView(cell, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        col.addView(media, lp(-1, -2, 16))

        statusView = inkText(TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER }, true)
        col.addView(statusView, lp(-1, -2, 20))
        return root
    }

    /** Registers a text view so its colour follows the current glass style. */
    private fun inkText(t: TextView, mute: Boolean = false): TextView {
        if (mute) muteTexts.add(t) else inkTexts.add(t)
        return t
    }

    /** Turns [v] into a pane of glass: backdrop, film, rim, and a soft shadow. */
    private fun glass(
        v: View,
        radiusDp: Int = -1,
        tint: Int = Color.WHITE,
        boost: Float = 1f,
        elevDp: Int = 5,
    ): GlassDrawable {
        val d = GlassDrawable(v, backdrop, radiusDp, resources.displayMetrics.density, tint, boost)
        d.light = lightStyle
        v.background = d
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val side = minOf(view.width, view.height).toFloat()
                val r = if (radiusDp < 0) side / 2f else minOf(dp(radiusDp).toFloat(), side / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, r)
                outline.setAlpha(1f)
            }
        }
        v.elevation = dp(elevDp).toFloat()
        glassDrawables.add(d)
        glassHosts.add(v)
        return d
    }

    private fun pill(kind: Int, label: String): LinearLayout {
        val p = LinearLayout(this).apply { gravity = Gravity.CENTER; contentDescription = label }
        glass(p)
        val icon = IconView(this, kind)
        inkIcons.add(icon)
        p.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        p.addView(inkText(TextView(this).apply {
            text = label; textSize = 15f; setTypeface(null, Typeface.BOLD)
        }))
        return p
    }

    private fun invalidateGlass() {
        for (v in glassHosts) v.invalidate()
    }

    private fun setBackdrop(b: Bitmap, animate: Boolean) {
        backdrop.setBitmap(b, animate)
        invalidateGlass()
    }

    // Ink and glass follow what is behind them: light only on the plain light wash.
    @Suppress("DEPRECATION")
    private fun applyTheme() {
        lightStyle = !night && !artShown
        ink = if (lightStyle) Color.rgb(22, 24, 29) else Color.WHITE
        inkMute = if (lightStyle) Color.rgb(90, 98, 112) else withAlpha(Color.WHITE, 175)
        for (t in inkTexts) t.setTextColor(ink)
        for (t in muteTexts) t.setTextColor(inkMute)
        for (i in inkIcons) {
            i.tint = ink
            i.invalidate()
        }
        powerIcon.tint = if (lightStyle) Color.rgb(214, 64, 47) else Color.rgb(255, 107, 87)
        powerIcon.invalidate()
        for (g in glassDrawables) g.light = lightStyle

        var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (lightStyle) {
            f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = f
        render()
    }

    private fun pressFx(v: View, down: Boolean, markPressed: Boolean = false) {
        if (markPressed) v.isPressed = down
        v.animate().cancel()
        val s = if (down) 0.96f else 1f
        val ip: Interpolator = if (down) AccelerateDecelerateInterpolator() else OvershootInterpolator(2.2f)
        v.animate().scaleX(s).scaleY(s).setDuration(if (down) 90L else 280L).setInterpolator(ip).start()
    }

    private fun onTap(v: View, f: () -> Unit) {
        v.setOnTouchListener { x, e ->
            // Not touching isPressed here: clearing it before the click would swallow the click.
            when (e.action) {
                MotionEvent.ACTION_DOWN -> pressFx(x, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressFx(x, false)
            }
            false
        }
        v.setOnClickListener { f() }
    }

    // Normal button: tap to send.
    private fun tap(v: View, b: Btn) = onTap(v) { press(b) }

    // Volume button: sends once, then repeats while held.
    private fun hold(v: View, f: () -> Unit) {
        val rep = object : Runnable {
            override fun run() { f(); h.postDelayed(this, 170) }
        }
        v.setOnTouchListener { x, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isLocked(x)) pressFx(x, true, true)
                    f()
                    h.postDelayed(rep, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressFx(x, false, true)
                    x.alpha = if (isLocked(x)) 0.35f else 1f
                    h.removeCallbacks(rep)
                }
            }
            true
        }
    }

    // Vol down is locked at 0%, Vol up is locked at 100%, so the count can't drift.
    private fun isLocked(v: View) =
        (v === downBtn && level == 0) || (v === upBtn && level == STEPS)

    // ---------------------------------------------------------------- Bluetooth mode

    private fun setBtMode(on: Boolean) {
        if (btMode == on) return
        btMode = on
        TransitionManager.beginDelayedTransition(col)
        btGlass.emphasis = if (on) 1f else 0f
        numView.textSize = if (on) 40f else 80f
        (numView.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (on) 10 else 0)
        numView.requestLayout()
        if (on) {
            startNowPlaying()
        } else {
            stopNowPlaying()
            titleView.visibility = View.GONE
            artistView.visibility = View.GONE
            showArtwork(null, null)
        }
    }

    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, NowPlayingService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            status("Open Settings > Notification access and allow Sony Remote")
        }
    }

    private fun startNowPlaying() {
        val ok = hasListenerAccess()
        accessHint.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok) return
        if (nowPlaying == null) nowPlaying = NowPlaying(this) { t -> onTrack(t) }
        nowPlaying?.start()
    }

    private fun stopNowPlaying() {
        nowPlaying?.stop()
        trackKey = null
        artToken++
        accessHint.visibility = View.GONE
    }

    private fun onTrack(t: NowPlaying.Track?) {
        if (!btMode) return
        val key = if (t == null) null else "${t.title}|${t.artist}|${t.art?.width}x${t.art?.height}|${t.artUri}"
        if (key == trackKey) return
        trackKey = key
        TransitionManager.beginDelayedTransition(col)
        val title = t?.title.orEmpty()
        val artist = t?.artist.orEmpty()
        titleView.text = title
        artistView.text = artist
        titleView.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        artistView.visibility = if (artist.isEmpty()) View.GONE else View.VISIBLE
        loadArt(t)
    }

    // Decoding and blurring happen off the main thread; a newer track cancels an older result.
    private fun loadArt(t: NowPlaying.Track?) {
        val token = ++artToken
        if (t == null || (t.art == null && t.artUri == null)) {
            showArtwork(null, null)
            return
        }
        val dm = resources.displayMetrics
        val bw = Ambient.W
        val bh = (bw * dm.heightPixels.toFloat() / dm.widthPixels).toInt().coerceAtLeast(bw)
        worker.execute {
            var art = t.art
            if (art == null && t.artUri != null) art = decodeUri(t.artUri)
            val bg = art?.let { Ambient.fromArtwork(it, bw, bh) }
            h.post {
                if (!destroyed && btMode && token == artToken) showArtwork(art, bg)
            }
        }
    }

    // Only local sources: this app has no internet permission.
    private fun decodeUri(s: String): Bitmap? = try {
        val u = Uri.parse(s)
        if (u.scheme == "content" || u.scheme == "file" || u.scheme == "android.resource") {
            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun showArtwork(art: Bitmap?, bg: Bitmap?) {
        TransitionManager.beginDelayedTransition(col)
        if (art != null && bg != null) {
            artView.setImageBitmap(art)
            artView.visibility = View.VISIBLE
            artShown = true
            setBackdrop(bg, true)
        } else {
            artView.setImageDrawable(null)
            artView.visibility = View.GONE
            artShown = false
            setBackdrop(ambient, true)
        }
        applyTheme()
    }

    // ---------------------------------------------------------------- IR logic (unchanged)

    private fun pct() = Math.round(level * 100f / STEPS)

    private fun transmit(b: Btn): Boolean {
        val m = ir
        if (m == null || !m.hasIrEmitter()) { status("This phone has no IR blaster"); return false }
        m.transmit(Sirc.CARRIER_HZ, Sirc.pattern(b))
        return true
    }

    // Volume taps change the number straight away. The clicks go to the speaker
    // after DELAY_MS of quiet, one every GAP_MS, so none get missed.
    private fun tapVolume(up: Boolean) {
        if (busy) return
        if (up && level == STEPS) { status("Already at 100%"); limitBuzz(); return }
        if (!up && level == 0) { status("Already at 0%"); limitBuzz(); return }
        level += if (up) 1 else -1
        // A light tick per step, a firmer one when the end of the range is reached.
        volCard.performHapticFeedback(
            if (level == 0 || level == STEPS) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK
        )
        lastTap = SystemClock.uptimeMillis()
        render()
        status("Sending soon...")
        h.removeCallbacks(pump)
        h.post(pump)
    }

    private val pump = object : Runnable {
        override fun run() {
            if (busy) return
            val now = SystemClock.uptimeMillis()
            val wait = maxOf(lastTap + DELAY_MS - now, nextFree - now)
            if (wait > 0) { h.postDelayed(this, wait); return }
            if (level == speakerLevel) return
            val up = level > speakerLevel
            if (!transmit(if (up) Buttons.volUp else Buttons.volDn)) {
                level = speakerLevel; render(); return
            }
            speakerLevel += if (up) 1 else -1
            nextFree = now + GAP_MS
            save()
            status(if (level == speakerLevel) "Volume at ${pct()}%" else "Sending volume...")
            h.postDelayed(this, GAP_MS)
        }
    }

    // Other buttons are sent right away, but never closer than GAP_MS to another signal.
    private fun schedule(b: Btn, then: () -> Unit) {
        val t = maxOf(SystemClock.uptimeMillis(), nextFree)
        nextFree = t + GAP_MS
        h.postAtTime({ if (transmit(b)) then() }, t)
    }

    private fun press(b: Btn) {
        if (busy && b != Buttons.power) return
        volCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        schedule(b) { status("Sent ${b.name}") }
    }

    // Sends a list of buttons, each after its own wait (milliseconds).
    private fun sequence(steps: List<Pair<Btn, Long>>, name: String) {
        if (busy) return
        volCard.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        var t = maxOf(SystemClock.uptimeMillis(), nextFree)
        for ((b, wait) in steps) {
            t += wait
            h.postAtTime({ transmit(b) }, t)
        }
        h.postAtTime({ status("$name signals sent") }, t)
        nextFree = t + GAP_MS
        status("Sending $name signals...")
    }

    // Sends Vol down enough times to reach 0% (a few extra to be safe) and stays at 0%.
    private fun startSync() {
        busy = true
        h.removeCallbacks(pump)
        level = speakerLevel
        syncLabel.text = "Syncing 0%"
        status("Syncing volume...")
        val total = STEPS + 4
        var i = 0
        val r = object : Runnable {
            override fun run() {
                if (i < total) {
                    if (!transmit(Buttons.volDn)) {
                        busy = false; syncLabel.text = "Sync volume"; render(); return
                    }
                    level = maxOf(0, level - 1)
                    speakerLevel = level
                    nextFree = SystemClock.uptimeMillis() + SYNC_GAP_MS
                    i++
                    val done = i * 100 / total
                    syncLabel.text = "Syncing $done%"
                    status("Syncing volume... $done%")
                    render()
                    h.postDelayed(this, SYNC_GAP_MS)
                } else {
                    busy = false
                    level = 0; speakerLevel = 0
                    save(); render()
                    syncLabel.text = "Sync volume"
                    status("Synced at 0%")
                }
            }
        }
        h.post(r)
    }

    private fun resync() {
        if (busy) return
        startSync()
    }

    private fun save() {
        getSharedPreferences("s", MODE_PRIVATE).edit().putInt("level", speakerLevel).apply()
    }

    private fun status(t: String) { statusView.text = t }

    private fun setNumber(p: Int) {
        val t = SpannableString("$p%")
        t.setSpan(RelativeSizeSpan(0.4f), t.length - 1, t.length, 0)
        t.setSpan(ForegroundColorSpan(inkMute), t.length - 1, t.length, 0)
        numView.text = t
    }

    // The percentage counts up or down to the new value instead of jumping.
    private fun showNumber(target: Int) {
        numAnim?.cancel()
        if (shownPct < 0 || shownPct == target) {
            shownPct = target
            setNumber(target)
            return
        }
        val a = ValueAnimator.ofInt(shownPct, target)
        a.duration = (60L + 12L * Math.abs(target - shownPct)).coerceAtMost(260L)
        a.addUpdateListener {
            shownPct = it.animatedValue as Int
            setNumber(shownPct)
        }
        numAnim = a
        a.start()
    }

    // Firm buzz when you push against 0% or 100%, at most once every 0.6 s while held.
    private fun limitBuzz() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLimitBuzz < 600L) return
        lastLimitBuzz = now
        volCard.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        )
    }

    // On open, the panels fade in and rise one after another.
    private fun playIntro() {
        val rise = dp(28).toFloat()
        var n = 0
        for (i in 0 until col.childCount) {
            val v = col.getChildAt(i)
            if (v.visibility == View.GONE) continue
            v.alpha = 0f
            v.translationY = rise
            val a = ObjectAnimator.ofPropertyValuesHolder(
                v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, rise, 0f),
            )
            a.startDelay = 70L * n
            a.duration = 480L
            a.interpolator = DecelerateInterpolator(1.8f)
            a.start()
            n++
        }
        // Keep the glass sampling the backdrop at the panels' moving positions.
        val tick = ValueAnimator.ofFloat(0f, 1f)
        tick.duration = 70L * n + 520L
        tick.addUpdateListener { invalidateGlass() }
        tick.start()
    }

    private fun render() {
        showNumber(pct())
        val off = withAlpha(ink, 70)
        for (i in cells.indices) cells[i].setColor(if (i < level) ink else off)
        downBtn.alpha = if (level == 0) 0.35f else 1f
        upBtn.alpha = if (level == STEPS) 0.35f else 1f
    }
}
