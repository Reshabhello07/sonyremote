package com.example.sonyremote

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

        val artSize = minOf(resources.displayMetrics.widthPixels - dp(40 + 40 + 24), dp(300))
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

        titleView = inkText(TextView(this).apply {
            textSize = 18f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
        })
        artistView = inkText(TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
        }, true)
        volCard.addView(titleView, lp(-1, -2))
        volCard.addView(artistView, lp(-1, -2, 2))

        numView = inkText(TextView(this).apply {
            textSize = 80f; setTypeface(null, Typeface.BOLD)
            includeFontPadding = false
        })
        volCard.addView(numView, lp(-2, -2))
        val bar = LinearL