package my.fouriders.scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "my.fouriders.scan.STOP"
        const val ACTION_HIDE = "my.fouriders.scan.HIDE"
        const val ACTION_SHOW = "my.fouriders.scan.SHOW"
        const val ACTION_ORDER_COMPLETED = "my.fouriders.scan.ORDER_COMPLETED"
        const val ACTION_ORDER_ACCEPTED = "my.fouriders.scan.ORDER_ACCEPTED"
        const val ACTION_ORDER_PASSED = "my.fouriders.scan.ORDER_PASSED"
        const val ACTION_ORDER_CHANGED = "my.fouriders.scan.ORDER_CHANGED"
        const val ACTION_RECALCULATE = "my.fouriders.scan.RECALCULATE"
        const val EXTRA_ORDER_ID = "order_id"
        @Volatile var hidden = false
        @Volatile var projectionLost = false
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "scan"
        @Volatile var running = false
        // Daylight palette: strong colours that stay readable in noon sun.
        private const val BLUE = 0xFF65E6A2.toInt()
        private const val GREY = 0xFF5A6678.toInt()
        private const val AMBER = 0xFFF29C00.toInt()
        private const val GREEN = 0xFF65E6A2.toInt()
        private const val ORANGE = 0xFFF29C00.toInt()
        private const val RED = 0xFFD9263A.toInt()
        private const val INK = 0xFFEAF1FF.toInt()   // raw-text debug panel (dark)
        private const val DARK = 0xFF0C1110.toInt()  // floating pill background
        private const val SOFT = 0xFF8E9B95.toInt()  // secondary text
        private const val BTN = 0xFF19251F.toInt()
        private const val GREEN_T = 0xFF193B2A.toInt()
        private const val AMBER_T = 0xFFFFEFC7.toInt()
        private const val RED_T = 0xFFFDE0E4.toInt()
        private const val KM_PER_H = 24.0
        private const val FUEL_PER_KM = 0.14
    }

    private enum class St { IDLE, READING, ROUTING, NEED_MORE, RESULT, PAUSED, ERROR }

    private class Leg(
        val label: String, var src: String, var km: Double, var unknown: Boolean,
        val stop: Stop? = null, val from: String = "", var q: Int = 0, var how: String = "",
        var candidate: Geo.Hit? = null, var point: Geo.P? = null,
        var refPoint: Geo.P? = null, var confirmedLocation: Boolean = false,
        var centerKm: Double? = null
    )

    private class Verdict(
        val word: String, val color: Int, val rpk: Double?,
        val fare: Double, val km: Double, val note: String, val near: Boolean
    )

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var pill: ScannerPill? = null
    private var pillLayout: WindowManager.LayoutParams? = null
    private var controls: View? = null
    private var controlsLayout: WindowManager.LayoutParams? = null
    private var card: View? = null
    private var raw: View? = null
    private var editPanel: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    private var frames = 0
    private var tracker: LocationManager? = null
    private var trackListener: LocationListener? = null
    private var trackLast: Location? = null
    private var trackOrderId = 0L

    private var state = St.IDLE
    private var legs: List<Leg> = emptyList()
    private var routeOrigin: Geo.P? = null
    private var zoneAlertId = 0
    private var uncertain = false
    private var tookWhat: String? = null
    private var showMore = false
    private var lastRaw = ""
    private var armed = false
    private var moved = 0
    private var autoCount = 0
    private var emptyReadRetries = 0
    private var captureRetries = 0
    private var hasResult = false
    private var cardMinimized = false
    private var restoreCardAfterControls = false
    private val settle = Runnable { if (state == St.NEED_MORE && autoCount < 6) { autoCount++; capture() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_HIDE || intent?.action == ACTION_SHOW) {
            val hide = intent.action == ACTION_HIDE
            hidden = hide
            handler.post {
                if (hide) closeCard()
                pill?.visibility = if (hide) View.GONE else View.VISIBLE
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ORDER_COMPLETED) {
            val completedId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
            handler.post {
                val activeId = prefs.getLong("curId", 0L)
                if (completedId != 0L && completedId == activeId && hasResult) {
                    stopTracking()
                    clearScan("Trip completed from Dashboard; scanner cleared")
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ORDER_PASSED) {
            val passedId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
            handler.post {
                if (passedId != 0L && passedId == prefs.getLong("curId", 0L)) clearScan("Passed, saved in History")
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ORDER_ACCEPTED) {
            val acceptedId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
            handler.post {
                if (acceptedId != 0L) {
                    startTracking(acceptedId)
                    if (acceptedId == prefs.getLong("curId", 0L) && hasResult) {
                        tookWhat = "Accepted"
                        if (card != null) refresh() else setState(St.RESULT, "ACCEPTED", GREEN)
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ORDER_CHANGED) {
            val changedId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
            handler.post { syncCurrentOrderFromStore(changedId) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECALCULATE) {
            handler.post {
                if (hasResult) {
                    saveRec(null)
                    if (card != null) refresh() else setState(St.RESULT, "${verdict().word} ${verdict().rpk?.let { "${f2(it)}/km" }.orEmpty()}", verdict().color)
                }
            }
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (projection == null) {
            try {
                setup(code, data)
            } catch (e: Exception) {
                toast("Could not start: ${e.message}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Scan bubble", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("4riders Scan is on")
            .setContentText("Tap the chip on an order")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        startForeground(1, n, type)
    }

    private fun setup(code: Int, data: Intent) {
        wm = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences("4riders", Context.MODE_PRIVATE)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
            ?: throw IllegalStateException("Screen capture permission was not granted")
        projection = p
        projectionLost = false
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    projectionLost = true
                    projection = null
                    display?.release(); display = null
                    held?.close(); held = null
                    reader?.close(); reader = null
                    setState(St.ERROR, "RETRY IN APP", GREY)
                    sendBroadcast(Intent(MainActivity.ACTION_ORDERS_UPDATED).setPackage(packageName))
                }
            }
        }, handler)

        val b = wm.currentWindowMetrics.bounds
        val w = b.width()
        val h = b.height()
        val dpi = resources.displayMetrics.densityDpi
        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            try {
                val im = rd.acquireLatestImage()
                if (im != null) {
                    held?.close()
                    held = im
                    frames++
                    onFrame()
                }
            } catch (e: Exception) {
                // ignore a bad frame
            }
        }, handler)
        // Android 14+ allows one virtual display per permission, so keep it alive.
        display = p.createVirtualDisplay(
            "scan", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        if (pill == null) showPill()
        running = true
        if (!hasResult) restoreAcceptedOrder()
    }

    // ---------- the chip ----------

    /** Draws the scan animation on the bubble's own border, then holds the verdict colour. */
    private inner class ScannerPill(context: Context) : TextView(context) {
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        var scanning = false
        var ringColor = BLUE
        private var angle = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = dp(2).toFloat()
            borderPaint.strokeWidth = stroke
            borderPaint.strokeCap = Paint.Cap.ROUND
            borderPaint.color = if (scanning) AMBER else ringColor
            val inset = stroke / 2f
            val oval = RectF(inset, inset, width - inset, height - inset)
            if (scanning) {
                canvas.drawArc(oval, angle, 96f, false, borderPaint)
                angle = (angle + 8f) % 360f
                postInvalidateOnAnimation()
            } else {
                canvas.drawOval(oval, borderPaint)
            }
        }
    }

    private fun showPill() {
        val tv = ScannerPill(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFF4F8F5.toInt())
            setTypeface(typeface, Typeface.BOLD)
            elevation = dp(6).toFloat()
        }
        val screen = wm.currentWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            dp(58),
            dp(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Keep the control tucked beside the screen edge instead of over the order text.
            x = (screen.width() - dp(66)).coerceAtLeast(dp(8))
            y = dp(130)
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    if (dragged) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                        positionControlsWithPill()
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) {
                    if (e.eventTime - e.downTime > 700) showRaw()
                    else onPillTap()
                } else positionControlsWithPill()
            }
            true
        }
        wm.addView(tv, lp)
        pill = tv
        pillLayout = lp
        setState(St.IDLE, "4", BLUE)
    }

    private fun setState(s: St, text: String, color: Int) {
        state = s
        val label = when {
            s == St.IDLE -> "4"
            s == St.READING -> "SCAN"
            s == St.ROUTING -> "ROUTE"
            s == St.NEED_MORE && text.contains("/") -> text.substringAfter("SCANNING · ").substringBefore(" stops") + "  ↓"
            s == St.NEED_MORE -> "↓ SCROLL"
            s == St.PAUSED -> "PAUSE"
            s == St.RESULT -> text.substringBefore(" ")
            s == St.ERROR -> "RETRY"
            else -> "4"
        }
        pill?.text = label
        pill?.textSize = if (label.length > 7) 8f else if (label.length > 5) 9f else if (label.length > 3) 10.5f else 17f
        pill?.setTextColor(0xFFF4F8F5.toInt())
        pill?.let {
            it.scanning = s == St.READING || s == St.ROUTING || s == St.NEED_MORE
            it.ringColor = if (s == St.RESULT) color else if (s == St.PAUSED) 0xFF7BBCFF.toInt() else BLUE
            it.invalidate()
        }
        pill?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF103A2C.toInt())
            // The custom border is drawn by ScannerPill so the loading arc travels on the bubble itself.
            setStroke(dp(2), Color.TRANSPARENT)
        }
    }

    private fun showControls() {
        if (!::wm.isInitialized || pill == null) return
        closeControls()
        if (card != null) {
            closeCard()
            restoreCardAfterControls = hasResult && !cardMinimized
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF111C22.toInt())
                setStroke(dp(1), 0xFF2B4248.toInt())
            }
            elevation = dp(12).toFloat()
        }
        fun option(icon: String, title: String, subtitle: String, color: Int, selected: Boolean, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(10), dp(9), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (selected) when (title) {
                        "Scan" -> 0xFF0D3028.toInt()
                        "Home" -> 0xFF183026.toInt()
                        "Pause" -> 0xFF14283A.toInt()
                        else -> 0xFF321B1D.toInt()
                    } else Color.TRANSPARENT)
                }
                setOnClickListener { closeControls(); action() }
            }
            val glyph = TextView(this).apply {
                text = icon
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(if (selected) color else 0xFFE8F0F1.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF111C22.toInt())
                    setStroke(dp(1), if (selected) color else 0xFF31444A.toInt())
                }
            }
            row.addView(glyph, LinearLayout.LayoutParams(dp(36), dp(36)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            labels.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(if (selected) color else 0xFFE8F0F1.toInt())
                setTypeface(typeface, Typeface.BOLD)
            })
            labels.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(0xFF91A5AC.toInt())
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (selected) "●" else "○"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (selected) color else 0xFF52656D.toInt())
            }, LinearLayout.LayoutParams(dp(22), dp(30)))
            menu.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        option("⌗", "Scan", "Scan the order on screen", 0xFF25E0A7.toInt(), false) {
            scanOrRestore()
        }
        val divider = View(this).apply { setBackgroundColor(0xFF26383D.toInt()) }
        menu.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(3); bottomMargin = dp(3); marginStart = dp(7); marginEnd = dp(7)
        })
        option("⌂", "Home", "Open the 4Riders dashboard", 0xFF79F2BD.toInt(), false) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        }
        val dividerHome = View(this).apply { setBackgroundColor(0xFF26383D.toInt()) }
        menu.addView(dividerHome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(3); bottomMargin = dp(3); marginStart = dp(7); marginEnd = dp(7)
        })
        option("Ⅱ", "Pause", "Pause scanning temporarily", 0xFF7BBCFF.toInt(), state == St.PAUSED) {
            handler.removeCallbacks(settle)
            armed = false
            setState(St.PAUSED, "PAUSE", 0xFF7BBCFF.toInt())
        }
        val divider2 = View(this).apply { setBackgroundColor(0xFF26383D.toInt()) }
        menu.addView(divider2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(3); bottomMargin = dp(3); marginStart = dp(7); marginEnd = dp(7)
        })
        option("⏻", "Turn off", "Stop the floating scanner", 0xFFFF6868.toInt(), false) { stopSelf() }

        val screen = wm.currentWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            minOf(dp(290), screen.width() - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(130)
        }
        try {
            wm.addView(menu, lp)
            controls = menu
            controlsLayout = lp
            menu.post { positionControlsWithPill() }
        } catch (_: Exception) { }
    }

    /** Keep the four-option panel anchored beside the draggable scanner bubble. */
    private fun positionControlsWithPill() {
        val menu = controls ?: return
        val bubble = pillLayout ?: return
        if (menu.width <= 0 || menu.height <= 0) return
        val screen = wm.currentWindowMetrics.bounds
        val lp = controlsLayout ?: (menu.layoutParams as? WindowManager.LayoutParams) ?: return
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (bubble.x + dp(58) - menu.width / 2).coerceIn(dp(8), (screen.width() - menu.width - dp(8)).coerceAtLeast(dp(8)))
        val below = bubble.y + dp(58) + dp(8)
        lp.y = if (below + menu.height <= screen.height() - dp(8)) below
            else (bubble.y - menu.height - dp(8)).coerceAtLeast(dp(8))
        try {
            wm.updateViewLayout(menu, lp)
            controlsLayout = lp
        } catch (_: Exception) { }
    }

    private fun closeControls(restoreCard: Boolean = false) {
        controls?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        controls = null
        controlsLayout = null
        val shouldRestore = restoreCard && restoreCardAfterControls && hasResult && !cardMinimized
        restoreCardAfterControls = false
        if (shouldRestore && state == St.RESULT) showResult()
    }

    /** After a Skip the order is forgotten, so the chip goes back to Scan and the next order starts clean. */
    private fun clearScan(msg: String) {
        handler.removeCallbacks(settle)
        closeControls()
        closeCard()
        Session.reset()
        legs = emptyList()
        hasResult = false
        cardMinimized = false
        restoreCardAfterControls = false
        uncertain = false
        tookWhat = null
        prefs.edit().putLong("curId", 0L).putString("curSig", "").apply()
        showMore = false
        armed = false
        moved = 0
        autoCount = 0
        emptyReadRetries = 0
        setState(St.IDLE, "4", BLUE)
        shortToast(msg)
    }

    private fun rescan() {
        closeCard()
        Session.reset()
        capture()
    }

    private fun onPillTap() {
        if (controls != null) closeControls(restoreCard = true) else showControls()
    }

    private fun scanOrRestore() {
        handler.removeCallbacks(settle)
        autoCount = 0
        if (state == St.ERROR) {
            emptyReadRetries = 0
            captureRetries = 0
        }
        if (projectionLost) {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("resume_capture", true)
            startActivity(i)
            return
        }
        if (hasResult && legs.isNotEmpty()) {
            val v = verdict()
            cardMinimized = false
            setState(St.RESULT, "${v.word} ${v.rpk?.let { "${f2(it)}/km" }.orEmpty()}", v.color)
            showCard()
            return
        }
        if (state == St.PAUSED || state == St.IDLE || state == St.ERROR) setState(St.NEED_MORE, "SCANNING · scroll", GREEN)
        capture()
    }

    private fun minimizeResultCard() {
        if (!hasResult || card == null) return
        closeCard()
        cardMinimized = true
    }

    // ---------- scroll watching ----------

    private fun onFrame() {
        if (state == St.NEED_MORE && armed) {
            moved++
            if (moved >= 3) {
                handler.removeCallbacks(settle)
                handler.postDelayed(settle, 900)
            }
        }
    }

    private fun armAuto() {
        armed = false
        moved = 0
        handler.removeCallbacks(settle)
        handler.postDelayed({ armed = true }, 1300)
    }

    // ---------- capture and read ----------

    private fun capture() {
        handler.removeCallbacks(settle)
        armed = false
        // Keep our own chip out of the screen frame that ML Kit reads, but always restore it.
        pill?.visibility = View.INVISIBLE
        handler.postDelayed({
            try {
                val img: Image? = held
                if (img == null) {
                    retryCapture("No screen frame yet")
                    return@postDelayed
                }
                val bmp = try {
                    toBitmap(img)
                } catch (_: Exception) {
                    retryCapture("Screen frame was unavailable")
                    return@postDelayed
                }
                captureRetries = 0
                setState(St.READING, "Reading…", GREY)
                try {
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { t ->
                            lastRaw = t.text
                            Session.merge(Parser.parse(t), System.currentTimeMillis())
                            if (Session.hasAny()) emptyReadRetries = 0
                            evaluate()
                        }
                        .addOnFailureListener {
                            lastRaw = "ML Kit OCR failed; tap Scan to retry."
                            setState(St.ERROR, "OCR failed · tap", GREY)
                        }
                } catch (_: Exception) {
                    lastRaw = "ML Kit could not start OCR; tap Scan to retry."
                    setState(St.ERROR, "OCR failed · tap", GREY)
                }
            } finally {
                pill?.visibility = if (hidden) View.GONE else View.VISIBLE
            }
        }, 350)
    }

    private fun retryCapture(reason: String) {
        lastRaw = reason
        if (captureRetries < 2) {
            captureRetries++
            setState(St.READING, "Reading…", GREY)
            handler.postDelayed({ if (state == St.READING) capture() }, 500)
        } else {
            setState(St.ERROR, "Capture failed · tap", GREY)
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * img.width
        val full = Bitmap.createBitmap(
            img.width + rowPadding / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
    }

    private fun evaluate() {
        if (!Session.hasAny()) {
            // A transient blank/late frame is common just after switching back to Grab.
            // Retry twice automatically before asking the rider to tap again.
            if (emptyReadRetries < 2) {
                emptyReadRetries++
                setState(St.READING, "Reading…", GREY)
                handler.postDelayed({ if (state == St.READING) capture() }, 650)
                return
            }
            setState(St.ERROR, "No order found · tap", GREY)
            return
        }
        val total = Session.total
        val have = Session.stops.size
        if (total != null && Session.missing().isNotEmpty()) {
            setState(St.NEED_MORE, "SCANNING · $have/$total stops", GREEN)
            armAuto()
            return
        }
        if (Session.stops.isEmpty()) {
            setState(St.NEED_MORE, "SCANNING · scroll", GREEN)
            armAuto()
            return
        }
        if (Session.fare == null) {
            setState(St.NEED_MORE, "SCANNING · scroll for fare", GREEN)
            armAuto()
            return
        }
        uncertain = total == null
        route()
    }

    // ---------- map ----------

    private fun short(s: Stop): String = s.name.substringBefore(" - ").take(24)

    private fun route() {
        setState(St.ROUTING, "Finding route…", GREY)
        val stops = Session.stops.values.toList()
        pool.execute {
            val me = Geo.gps(this)
            val hits = ArrayList<Geo.Hit?>()
            var ref: Geo.P? = me
            for (st in stops) {
                val key = st.name + "|" + st.address
                var h = Session.geo[key]
                if (h != null && !Geo.cacheMatchesReference(h, ref)) {
                    Session.geo.remove(key)
                    h = null
                }
                if (h == null) {
                    h = Geo.locate(this, st, ref)
                    if (h != null) Session.geo[key] = h
                }
                hits.add(h)
                if (h != null) ref = h.p
            }
            // Only full-address matches are trusted automatically. Broader results stay unconfirmed.
            val trustedHits = hits.map { it?.takeIf { h -> h.level == 0 } }
            val chain = ArrayList<Geo.P?>()
            chain.add(me)
            trustedHits.forEach { chain.add(it?.p) }
            val centerCfg = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val zoneEnabled = centerCfg.getBoolean("zoneEnabled", false)
            val zoneCenter = if (zoneEnabled && centerCfg.getString("zoneLabel", "").orEmpty().isNotBlank()) {
                val lat = centerCfg.getFloat("zoneLat", Float.NaN).toDouble()
                val lon = centerCfg.getFloat("zoneLon", Float.NaN).toDouble()
                if (lat.isFinite() && lon.isFinite()) Geo.P(lat, lon) else null
            } else null
            val zoneIndexes = trustedHits.indices.filter { trustedHits[it] != null }
            val zoneRoadKm = if (zoneCenter != null && zoneIndexes.isNotEmpty())
                Geo.roadDistancesFrom(zoneCenter, zoneIndexes.mapNotNull { trustedHits[it]?.p }) else null
            val zoneByStop = HashMap<Int, Double?>()
            zoneIndexes.forEachIndexed { ix, stopIx -> zoneByStop[stopIx] = zoneRoadKm?.getOrNull(ix) }
            val out = ArrayList<Leg>()
            val all = chain.filterNotNull()
            val routed: List<Double>? = if (all.size == chain.size) Geo.routeLegs(all) else null
            for (i in stops.indices) {
                val a = chain[i]
                val b = chain[i + 1]
                val from = if (i == 0) "You" else short(stops[i - 1])
                val label = "$from to ${short(stops[i])}"
                val hitA = if (i == 0) null else trustedHits[i - 1]
                val hitB = trustedHits[i]
                val q = maxOf(if (i == 0) 0 else (hitA?.level ?: 2), hitB?.level ?: 2)
                val how = listOfNotNull(hitA, hitB).maxByOrNull { it.level }?.how.orEmpty()
                if (a == null || b == null) {
                    val why = if (a == null && i == 0) "No GPS, set km" else "Address not found, set km"
                    out.add(Leg(label, why, 0.0, true, stops[i], from, 2, "Needs location check", hits[i], null, a, false, zoneByStop[i]))
                } else if (routed != null) {
                    out.add(Leg(label, if (i == 0) "GPS + map" else "Map estimate", rnd(routed[i]), false, stops[i], from, q, how, hitB, b, a, true, zoneByStop[i]))
                } else {
                    // Do not fan out failed public OSRM requests; use a visibly rough fallback instead.
                    out.add(Leg(label, "Rough estimate (no map)", rnd(Geo.rough(a, b)), false, stops[i], from, 2, "no map, straight line x 1.35", hitB, b, a, true, zoneByStop[i]))
                }
            }
            handler.post {
                routeOrigin = me
                legs = out
                record()
                if (zoneEnabled) notifyOutsideZone()
                showResult()
            }
        }
    }

    // ---------- verdict ----------

    private fun verdict(): Verdict {
        val c = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val minFare = c.getFloat("min", 4f).toDouble()
        val rej = c.getFloat("rej", 0.9f).toDouble()
        val ok = c.getFloat("ok", 1.2f).toDouble()
        val bonus = Session.bonus
        val includeBonus = getSharedPreferences("4riders", Context.MODE_PRIVATE).getBoolean("bonus", true)
        val fare = (Session.fare ?: 0.0) + (if (includeBonus && bonus != null) bonus else 0.0)
        val km = legs.sumOf { it.km }
        val zoneOn = c.getBoolean("zoneEnabled", false)
        val zoneLimit = c.getFloat("zoneMaxKm", 8f).toDouble()
        val outside = if (zoneOn) legs.filter { it.centerKm != null && it.centerKm!! > zoneLimit } else emptyList()
        if (outside.isNotEmpty()) {
            val details = outside.joinToString("; ") { "${stopOrdinal(it)} ${f1(it.centerKm!!)} km" }
            return Verdict("REJECT", RED, if (km > 0.0) fare / km else null, fare, km,
                "Outside coverage zone (limit ${f1(zoneLimit)} km): $details from center.", false)
        }
        if (zoneOn && legs.any { it.centerKm == null }) {
            return Verdict("CHECK ZONE", GREY, if (km > 0.0) fare / km else null, fare, km,
                "Could not verify every stop by road from your center. Confirm locations or retry the scan.", false)
        }
        if (legs.any { it.unknown } || km <= 0.0) {
            return Verdict("SET KM", GREY, null, fare, km, "Some distances are missing. Set them with + and −.", false)
        }
        val rpk = fare / km
        val near = abs(rpk - rej) < 0.08 || abs(rpk - ok) < 0.08
        return when {
            fare < minFare -> Verdict("REJECT", RED, rpk, fare, km, "Fare is below RM${f2(minFare)}.", near)
            rpk < rej -> Verdict("REJECT", RED, rpk, fare, km, "Below RM${f2(rej)}/km.", near)
            rpk < ok -> Verdict("CONSIDER", ORANGE, rpk, fare, km, "Weigh traffic, waiting time and the next order.", near)
            else -> Verdict("ACCEPT", GREEN, rpk, fare, km, "Good to take, but still check traffic.", near)
        }
    }

    private fun stopOrdinal(leg: Leg): String {
        val target = leg.stop
        val index = legs.takeWhile { it !== leg }.count { it.stop?.collect == target?.collect } + 1
        return "${if (target?.collect == true) "Pickup" else "Drop-off"} $index"
    }

    private fun notifyOutsideZone() {
        val c = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!c.getBoolean("zoneEnabled", false)) return
        val limit = c.getFloat("zoneMaxKm", 8f).toDouble()
        val outside = legs.filter { it.centerKm != null && it.centerKm!! > limit }
        if (outside.isEmpty()) return
        val text = outside.joinToString(" · ") { "${stopOrdinal(it)} ${f1(it.centerKm!!)} km" }
        shortToast("Outside zone: $text (limit ${f1(limit)} km)")
        val nm = getSystemService(NotificationManager::class.java)
        val channelId = "zone_alerts"
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(channelId, "Coverage zone alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        try {
            nm.notify(++zoneAlertId + 100, Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Order outside your zone")
                .setContentText("$text from center · limit ${f1(limit)} km")
                .setStyle(Notification.BigTextStyle().bigText("$text from center. Maximum is ${f1(limit)} km by road."))
                .setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Overlay/card and toast still show the same alert. */ }
    }

    private fun showResult() {
        val v = verdict()
        val label = if (v.rpk != null) "${v.word}  ${f2(v.rpk)}/km   ↻" else "${v.word}   ↻"
        hasResult = true
        cardMinimized = false
        setState(St.RESULT, label, v.color)
        showCard()
    }

    // ---------- details card ----------

    /** Small traffic light: only the lamp that matches the verdict is lit. */
    private class Lamp(ctx: Context, val word: String) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = resources.displayMetrics.density
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((42 * d).toInt(), (112 * d).toInt())
        override fun onDraw(c: Canvas) {
            p.color = DARK
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 21 * d, 21 * d, p)
            val cols = intArrayOf(RED, AMBER, GREEN)
            val lit = when (word) { "REJECT" -> 0; "CONSIDER" -> 1; "ACCEPT" -> 2; else -> -1 }
            for (i in 0..2) {
                val cx = width / 2f
                val cy = 6 * d + 15 * d + i * 35 * d
                if (i == lit) {
                    p.color = cols[i]; p.alpha = 70
                    c.drawCircle(cx, cy, 19 * d, p)
                    p.alpha = 255
                    c.drawCircle(cx, cy, 15 * d, p)
                } else {
                    p.color = cols[i]; p.alpha = 56
                    c.drawCircle(cx, cy, 15 * d, p)
                    p.alpha = 255
                }
            }
        }
    }

    /** Your last scans as bars, with this one highlighted. */
    private class Bars(ctx: Context, val past: List<Double>, val now: Double, val nowColor: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = resources.displayMetrics.density
        override fun onMeasure(w: Int, h: Int) =
            setMeasuredDimension(MeasureSpec.getSize(w), (86 * d).toInt())
        override fun onDraw(c: Canvas) {
            val all = past + now
            val mx = all.max().coerceAtLeast(0.1)
            val n = all.size
            val gap = 8 * d
            val bw = (width - gap * (n - 1)) / n
            val top = 20 * d
            val base = height.toFloat()
            p.textSize = 11 * d
            p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            p.textAlign = Paint.Align.CENTER
            for (i in 0 until n) {
                val h = ((all[i] / mx).toFloat() * (base - top)).coerceAtLeast(6 * d)
                val x = i * (bw + gap)
                p.color = if (i == n - 1) nowColor else 0xFF3B5145.toInt()
                c.drawRoundRect(x, base - h, x + bw, base, 6 * d, 6 * d, p)
                p.color = 0xFFF4F8F5.toInt()
                c.drawText(String.format(Locale.US, "%.2f", all[i]), x + bw / 2, base - h - 4 * d, p)
            }
        }
    }

    private class CappedScroll(ctx: Context, val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }

    // ---------- scan history (so the card can compare this order with your last ones) ----------

    private fun pastScans(): List<Double> =
        prefs.getString("hist", "").orEmpty().split(",").mapNotNull { it.toDoubleOrNull() }

    private fun record() {
        val sig = "${Session.fare}|" + Session.stops.values.joinToString(";") { it.name }
        val oldSig = prefs.getString("curSig", "").orEmpty()
        if (sig != oldSig) {
            val oldRpk = prefs.getFloat("curRpk", -1f)
            val h = pastScans().toMutableList()
            if (oldSig.isNotEmpty() && oldRpk > 0f) h.add(oldRpk.toDouble())
            val keep = h.takeLast(5).joinToString(",") { String.format(Locale.US, "%.3f", it) }
            prefs.edit().putString("hist", keep).putString("curSig", sig).putLong("curId", System.currentTimeMillis()).apply()
            tookWhat = null
        } else if (prefs.getLong("curId", 0L) == 0L) {
            prefs.edit().putLong("curId", System.currentTimeMillis()).apply()
        }
        verdict().rpk?.let { prefs.edit().putFloat("curRpk", it.toFloat()).apply() }
        saveRec(null)
    }

    /** Saves this order to the phone's history. decision: null keeps what was chosen, "" clears it. */
    private fun saveRec(decision: String?) {
        val v = verdict()
        val id = prefs.getLong("curId", 0L).takeIf { it > 0L } ?: System.currentTimeMillis().also {
            prefs.edit().putLong("curId", it).apply()
        }
        val stop = Session.stops.values.firstOrNull { it.collect } ?: Session.stops.values.firstOrNull()
        val area = Store.areaOf(stop?.address.orEmpty())
        val old = Store.get(this, id)
        val keep = decision ?: old?.decision.orEmpty()
        val routeStops = Session.stops.values.joinToString(";") { (if (it.collect) "P:" else "D:") + it.name.replace(";", " ") }
        val now = System.currentTimeMillis()
        val acceptedAt = if (keep == "A") old?.acceptedAt?.takeIf { it > 0 } ?: now else old?.acceptedAt ?: 0L
        val completedAt = if (keep == "T") old?.completedAt?.takeIf { it > 0 } ?: now else old?.completedAt ?: 0L
        // Keep a scanned order in History even when one or more distances still need editing.
        val expectedKm = if (keep == "A") v.km else old?.expectedKm ?: 0.0
        val expectedMinutes = if (keep == "A") (v.km / KM_PER_H * 60.0).coerceAtLeast(1.0) else old?.expectedMinutes ?: 0.0
        Store.upsert(this, Rec(id, v.word, v.rpk ?: 0.0, v.fare, v.km, area, keep, routeStops,
            legs.map { rnd(it.km) }, acceptedAt, completedAt, old?.actualKm ?: 0.0, expectedKm, expectedMinutes))
        if (keep == "A") startTracking(id)
        if (keep == "T") stopTracking()
        sendBroadcast(Intent(MainActivity.ACTION_ORDERS_UPDATED).setPackage(packageName))
    }

    /** Reload dashboard edits into the active scanner card without re-routing or changing its decision. */
    private fun syncCurrentOrderFromStore(changedId: Long) {
        val activeId = prefs.getLong("curId", 0L)
        if (!hasResult || changedId == 0L || changedId != activeId) return
        val record = Store.get(this, changedId) ?: return
        if (record.legKm.size == legs.size && record.legKm.isNotEmpty()) {
            legs.forEachIndexed { index, leg ->
                leg.km = rnd(record.legKm[index])
                leg.unknown = false
                leg.q = 0
                leg.src = "Edited from dashboard"
            }
        } else if (record.km > 0.0 && legs.isNotEmpty()) {
            val oldTotal = legs.sumOf { it.km }
            var assigned = 0.0
            legs.forEachIndexed { index, leg ->
                val next = if (index == legs.lastIndex) record.km - assigned
                    else if (oldTotal > 0.0) record.km * leg.km / oldTotal else record.km / legs.size
                leg.km = rnd(next)
                assigned += leg.km
                leg.unknown = false
            }
        }
        Session.fare = record.fare
        tookWhat = if (record.decision == "A") "Accepted" else tookWhat
        if (card != null) refresh() else {
            val v = verdict()
            setState(St.RESULT, "${v.word} ${v.rpk?.let { "${f2(it)}/km" }.orEmpty()}", v.color)
        }
    }

    /** Restore an accepted trip after Android or the screen-capture permission stops the service. */
    private fun restoreAcceptedOrder() {
        val id = prefs.getLong("curId", 0L)
        val rec = if (id != 0L) Store.get(this, id) else null
        if (rec == null || rec.decision != "A") return
        val savedStops = rec.st.split(';').mapIndexedNotNull { i, raw ->
            val colon = raw.indexOf(':')
            if (colon < 1) null else Stop(i + 1, raw.substring(0, colon) == "P", raw.substring(colon + 1).trim(), "")
        }
        if (savedStops.isEmpty()) return
        Session.reset()
        Session.fare = rec.fare
        savedStops.forEach { Session.stops[it.n] = it }
        val kms = if (rec.legKm.size == savedStops.size) rec.legKm else {
            val each = rec.km / savedStops.size
            List(savedStops.size) { if (it == savedStops.lastIndex) rec.km - each * it else each }
        }
        legs = savedStops.mapIndexed { i, stop ->
            val from = if (i == 0) "You" else short(savedStops[i - 1])
            Leg("$from to ${short(stop)}", "Restored from saved trip", kms[i], false, stop, from)
        }
        tookWhat = "Accepted"
        hasResult = true
        cardMinimized = true
        prefs.edit().putLong("curId", id).apply()
        setState(St.RESULT, "ACCEPTED", GREEN)
        startTracking(id)
    }

    /** Track real movement after Accept without changing the planned route distances or fare rate. */
    @SuppressLint("MissingPermission")
    private fun startTracking(id: Long) {
        if (id == 0L || trackOrderId == id) return
        stopTracking()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(LocationManager::class.java)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasAccuracy() && location.accuracy > 60f) return
                val previous = trackLast
                if (previous == null) {
                    trackLast = location
                } else {
                    val elapsed = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1e9
                    val metres = previous.distanceTo(location).toDouble()
                    if (elapsed <= 0 || metres > 65.0 * elapsed + previous.accuracy + location.accuracy) return
                    if (metres >= max(8.0, previous.accuracy.coerceAtMost(35f).toDouble() * 0.3)) {
                        val record = Store.get(this@OverlayService, id) ?: return
                        val actual = record.actualKm + metres / 1000.0
                        Store.upsert(this@OverlayService, record.copy(actualKm = actual))
                        sendBroadcast(Intent(MainActivity.ACTION_ORDERS_UPDATED).setPackage(packageName))
                    }
                    trackLast = location
                }
            }
            override fun onProviderEnabled(provider: String) { }
            override fun onProviderDisabled(provider: String) { }
            @Deprecated("Deprecated by platform") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) { }
        }
        try {
            trackOrderId = id
            trackLast = null
            tracker = manager
            trackListener = listener
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { try { manager.isProviderEnabled(it) } catch (_: Exception) { false } }
            providers.forEach { provider ->
                try { manager.requestLocationUpdates(provider, 10_000L, 8f, listener, handler.looper) } catch (_: Exception) { }
            }
            if (providers.isEmpty()) { stopTracking(); return }
            Store.get(this, id)?.let { if (it.acceptedAt == 0L) Store.upsert(this, it.copy(acceptedAt = System.currentTimeMillis())) }
        } catch (_: SecurityException) { stopTracking() }
    }

    private fun stopTracking() {
        val l = trackListener
        if (l != null) try { tracker?.removeUpdates(l) } catch (_: Exception) { }
        trackListener = null
        tracker = null
        trackLast = null
        trackOrderId = 0L
    }

    private fun flipText(v: Verdict, rej: Double, ok: Double, minFare: Double): String? {
        val rpk = v.rpk ?: return null
        if (v.fare < minFare) return "Below the RM${f2(minFare)} minimum fare."
        if (v.word == "ACCEPT") {
            val spare = v.fare / ok - v.km
            return "Safe. It stays ACCEPT up to ${String.format(Locale.US, "%.1f", max(0.0, spare))} km more."
        }
        val tgt = if (v.word == "REJECT") rej else ok
        val to = if (v.word == "REJECT") "CONSIDER" else "ACCEPT"
        val more = tgt * v.km - v.fare
        val less = v.km - v.fare / tgt
        return "+RM ${f2(max(0.0, more))} or ${String.format(Locale.US, "%.1f", max(0.0, less))} km shorter turns it to $to."
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun shape(color: Int, radius: Int = 16) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun stepBtn(label: String, onTap: () -> Unit, onLong: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 22f
        setTextColor(DARK)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        background = shape(BTN, 14)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) }
        setOnClickListener { onTap() }
        setOnLongClickListener { onLong(); true }
    }

    private fun mono(text: String, size: Float, color: Int = DARK) = tv(text, size, color, true).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun label(text: String) = tv(text.uppercase(Locale.US), 11.5f, SOFT, true).apply { letterSpacing = 0.08f }

    private fun hair(): View = View(this).apply { setBackgroundColor(0xFFD5DAE2.toInt()) }

    private fun matchW() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // Dark "studio" card palette (mint on near-black, high contrast)
    private val D_BG = 0xFF0B1513.toInt()
    private val D_LINE = 0xFF2B4038.toInt()
    private val D_PANEL = 0xFF15241F.toInt()
    private val D_WHITE = 0xFFF4F8F5.toInt()
    private val D_MUTED = 0xFFB5C9BE.toInt()
    private val D_MINT = 0xFF79F2BD.toInt()
    private val D_AMBER = 0xFFFFD27A.toInt()
    private val D_RED = 0xFFFF8B8B.toInt()

    private fun dBox(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dStep(label: String, plus: Boolean, onTap: () -> Unit, onLong: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 22f
        setTextColor(if (plus) 0xFF092219.toInt() else D_WHITE)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        background = dBox(if (plus) D_MINT else 0xFF22382E.toInt(), if (plus) D_MINT else 0xFF3D5A4C.toInt(), 12)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(5) }
        setOnClickListener { onTap() }
        setOnLongClickListener { onLong(); true }
    }

    private fun dLab(t: String, color: Int = D_MUTED) = tv(t.uppercase(Locale.US), 10.5f, color, true).apply { letterSpacing = 0.1f }

    private fun showCard() {
        closeCard()
        val v = verdict()
        val screen = wm.currentWindowMetrics.bounds
        val pickups = legs.count { it.stop?.collect == true }
        val dropoffs = legs.size - pickups
        val accepted = tookWhat == "Accepted"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(10))
            background = dBox(D_BG, D_LINE, 23)
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val type = if (pickups > 1 || dropoffs > 1) "GROUP ORDER" else "SINGLE ORDER"
        heading.addView(tv("$type · $pickups PICKUP${if (pickups == 1) "" else "S"} · $dropoffs DROP-OFF${if (dropoffs == 1) "" else "S"}", 10f, D_MUTED, true).apply {
            letterSpacing = 0.07f
            maxLines = 2
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val verdictColor = when (v.word) { "ACCEPT" -> D_MINT; "REJECT" -> D_RED; "CONSIDER" -> D_AMBER; else -> D_MUTED }
        val badges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        badges.addView(tv(if (accepted) "IN PROGRESS" else v.word, 8.5f, if (accepted) D_MINT else verdictColor, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = dBox(if (accepted) 0xFF173B2C.toInt() else 0xFF19251F.toInt(), Color.TRANSPARENT, 18)
        })
        if (accepted) {
            badges.addView(tv(v.word, 8.5f, verdictColor, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "Current verdict: ${v.word}"
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = dBox(0xFF19251F.toInt(), verdictColor, 18)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        }
        heading.addView(badges, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        box.addView(heading, matchW())

        val prices = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(9))
        }
        val rate = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        rate.addView(tv("RM${f2(v.rpk ?: 0.0)}", 34f, D_WHITE, true).apply {
            includeFontPadding = false
            letterSpacing = -0.05f
        })
        rate.addView(tv(" /km", 13f, D_MUTED, true).apply { includeFontPadding = false; setPadding(0, 0, 0, dp(3)) })
        prices.addView(rate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fareLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM or Gravity.END }
        fareLine.addView(tv("RM${f2(v.fare)}", 20f, D_WHITE, true).apply { includeFontPadding = false })
        fareLine.addView(tv(" / fare", 11f, D_MUTED).apply { includeFontPadding = false; setPadding(dp(4), 0, 0, dp(2)) })
        prices.addView(fareLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) })
        box.addView(prices, matchW())

        // Route starts at the rider, followed by each pickup/drop-off in captured order.
        val route = object : LinearLayout(this) {
            private val connector = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF526B45.toInt()
                strokeWidth = dp(1).toFloat()
            }
            init { orientation = VERTICAL; setWillNotDraw(false) }
            override fun dispatchDraw(canvas: Canvas) {
                if (childCount > 1) {
                    var previous = getChildAt(0).let { it.top + it.height / 2f }
                    for (i in 1 until childCount) {
                        val current = getChildAt(i).let { it.top + it.height / 2f }
                        canvas.drawLine(dp(9).toFloat(), previous, dp(9).toFloat(), current, connector)
                        previous = current
                    }
                }
                super.dispatchDraw(canvas)
            }
        }
        val originRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(38)
        }
        val originDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF8FA197.toInt())
            }
        }
        originRow.addView(originDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(3); marginEnd = dp(11) })
        originRow.addView(tv("Your location", 12f, D_MUTED, true).apply {
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        originRow.addView(View(this), LinearLayout.LayoutParams(dp(101), dp(1)))
        route.addView(originRow, matchW())

        legs.forEachIndexed { index, leg ->
            val isPickup = leg.stop?.collect == true
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(54)
            }
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isPickup) 0xFFA7D967.toInt() else D_AMBER)
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(2); marginEnd = dp(9) })
            val place = (leg.stop?.name ?: leg.label.substringAfter("to ")).trim().take(70)
            val ordinal = if (isPickup) "Pickup ${legs.take(index + 1).count { it.stop?.collect == true }}"
                else "Drop-off ${legs.take(index + 1).count { it.stop?.collect != true }}"
            val locationBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            val location = tv("$ordinal · $place", 12f, D_WHITE, false).apply {
                maxLines = 2; includeFontPadding = false; setPadding(0, dp(3), dp(5), dp(2))
            }
            attachPlaceGestures(location, leg)
            locationBlock.addView(location, matchW())
            locationBlock.addView(tv(if (leg.confirmedLocation) "LOCATION MATCHED" else "CHECK LOCATION", 8f,
                if (leg.confirmedLocation) D_MINT else 0xFFFFC66D.toInt(), true).apply {
                letterSpacing = .08f; setPadding(0, 0, 0, dp(3)); contentDescription = "Confirm location for $ordinal"
                setOnClickListener { openLocationCheck(leg) }
            }, matchW())
            if (getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("zoneEnabled", false)) {
                val limit = getSharedPreferences("cfg", Context.MODE_PRIVATE).getFloat("zoneMaxKm", 8f).toDouble()
                val zoneText = leg.centerKm?.let { "${f1(it)} km from center · ${if (it > limit) "OUTSIDE LIMIT" else "within zone"}" }
                    ?: "Center road distance unavailable"
                locationBlock.addView(tv(zoneText, 8f, if (leg.centerKm != null && leg.centerKm!! > limit) D_RED else D_MUTED, true)
                    .apply { setPadding(0, 0, dp(3), dp(4)); maxLines = 2 }, matchW())
            }
            row.addView(locationBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val address = leg.stop?.let { Parser.addressForCopy(it) }.orEmpty()
            row.addView(TextView(this).apply {
                text = "⧉"
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor(D_MINT)
                contentDescription = "Copy $ordinal address"
                background = dBox(0xFF142019.toInt(), 0xFF385146.toInt(), 8)
                setOnClickListener {
                    if (address.isBlank()) {
                        shortToast("No clear address detected · nothing copied")
                    } else {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Address", address))
                        shortToast("Address copied")
                    }
                }
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(5) })
            val kmButton = tv("${String.format(Locale.US, "%.1f", leg.km)} km  ✎", 10f, D_WHITE, true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                contentDescription = "Edit distance to $ordinal"
                background = dBox(0xFF101A14.toInt(), 0xFF33493C.toInt(), 11)
                setOnClickListener { openEdit(leg) }
            }
            row.addView(kmButton, LinearLayout.LayoutParams(dp(76), dp(36)))
            route.addView(row, matchW())
        }
        box.addView(route, matchW())

        val total = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(10))
            background = null
        }
        total.addView(tv("↗ ${String.format(Locale.US, "%.1f", v.km)} km", 11f, D_MUTED, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        total.addView(TextView(this).apply {
            text = "−"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(D_MINT)
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = "Minimize order card"
            background = dBox(0xFF142019.toInt(), 0xFF385146.toInt(), 8)
            setOnClickListener { minimizeResultCard() }
        }, LinearLayout.LayoutParams(dp(30), dp(27)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        val mins = Math.round(v.km / KM_PER_H * 60).toInt().coerceAtLeast(1)
        total.addView(tv("◷ ${formatDurationMinutes(mins)} est.", 11f, D_WHITE, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(View(this).apply { setBackgroundColor(D_LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        box.addView(total, matchW())
        val routeNote = when {
            legs.any { it.unknown } -> "Location unavailable · enter km · © OpenStreetMap"
            legs.any { it.src.startsWith("Rough estimate") } -> "Rough estimate · verify in maps · © OpenStreetMap · OSRM"
            legs.any { it.q >= 2 } -> "Postcode-area lookup · verify in maps · © OpenStreetMap · OSRM"
            legs.any { it.q == 1 } -> "Broader address lookup · verify in maps · © OpenStreetMap · OSRM"
            else -> "Road distance estimate · verify in maps · © OpenStreetMap · OSRM"
        }
        box.addView(tv(routeNote, 9f, D_MUTED).apply {
            setPadding(0, 0, 0, dp(4))
            maxLines = 2
            contentDescription = routeNote
        }, matchW())
        if (getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("zoneEnabled", false)) {
            box.addView(tv(v.note, 9.5f, if (v.word == "REJECT") D_RED else D_MUTED, v.word == "REJECT").apply {
                setPadding(0, dp(3), 0, dp(4)); maxLines = 3
            }, matchW())
        }

        val mapsStops = legs.mapNotNull { leg ->
            val stop = leg.stop ?: return@mapNotNull null
            (Parser.addressForCopy(stop) ?: stop.name).trim().takeIf { it.isNotBlank() }
        }
        box.addView(TextView(this).apply {
            text = "↗  OPEN FULL ROUTE IN GOOGLE MAPS  ·  ${mapsStops.size} STOPS"
            textSize = 10f
            setTextColor(D_MINT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "Open current location and all ${mapsStops.size} pickup and drop-off stops in Google Maps"
            background = dBox(0xFF14251C.toInt(), 0xFF385146.toInt(), 12)
            setPadding(dp(10), dp(11), dp(10), dp(11))
            setOnClickListener { openRouteInGoogleMaps(mapsStops) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })

        val acceptedAt = Store.get(this, prefs.getLong("curId", 0L))?.acceptedAt ?: 0L
        box.addView(tv(projectionText(v, acceptedAt), 9.5f, D_MUTED).apply {
            setPadding(0, dp(5), 0, dp(3))
            maxLines = 3
        }, matchW())

        if (!accepted) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = "PASS"
                isAllCaps = false
                textSize = 13f
                setTextColor(D_WHITE)
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = dBox(0xFF152019.toInt(), 0xFF33483A.toInt(), 13)
                setOnClickListener { saveRec("S"); clearScan("Passed, saved in History") }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
            actions.addView(Button(this).apply {
                text = "ACCEPT"
                isAllCaps = false
                textSize = 13f
                setTextColor(D_WHITE)
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = dBox(0xFF246D4F.toInt(), 0xFF246D4F.toInt(), 13)
                setOnClickListener { tookWhat = "Accepted"; saveRec("A"); refresh() }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) })
            box.addView(actions, matchW().apply { topMargin = dp(5) })
        } else {
            box.addView(Button(this).apply {
                text = "MARK AS COMPLETED"
                isAllCaps = false
                textSize = 13f
                setTextColor(D_WHITE)
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = dBox(0xFF246D4F.toInt(), 0xFF246D4F.toInt(), 13)
                setOnClickListener { saveRec("T"); clearScan("Trip completed, scanner cleared") }
            }, matchW().apply { topMargin = dp(5) }.let { it.height = dp(50); it })
        }

        val lp = WindowManager.LayoutParams(
            screen.width() - dp(16),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(8) }
        val outer = CappedScroll(this, (screen.height() * 0.88f).toInt())
        outer.addView(box)
        wm.addView(outer, lp)
        card = outer
    }

    private fun rnd(x: Double): Double = Math.round(x * 10.0) / 10.0

    private fun projectionText(v: Verdict, acceptedAt: Long): String {
        val now = System.currentTimeMillis()
        val plannedMins = Math.round(v.km / KM_PER_H * 60).toInt().coerceAtLeast(1)
        val elapsedMins = if (acceptedAt > 0L) ((now - acceptedAt).coerceAtLeast(0L) / 60_000L).toInt() else 0
        val remainingMins = (plannedMins - elapsedMins).coerceAtLeast(1)
        val finishAt = now + remainingMins * 60_000L
        val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val completed = Store.all(this).filter { it.decision == "T" && it.id >= dayStart }
        val earned = completed.sumOf { it.fare }
        val tripMs = completed.sumOf { order ->
            if (order.acceptedAt > 0L && order.completedAt > order.acceptedAt) {
                minOf(now, order.completedAt) - maxOf(dayStart, order.acceptedAt)
            } else 0L
        }.coerceAtLeast(0L)
        val pace = if (tripMs > 0L) earned / (tripMs / 3_600_000.0) else 0.0
        val goal = getSharedPreferences("cfg", Context.MODE_PRIVATE).getFloat("goal", 200f).toDouble()
        val projected = earned + v.fare
        val targetAt = when {
            projected >= goal -> finishAt
            pace > 0.0 -> finishAt + ((goal - projected) / pace * 3_600_000.0).toLong()
            else -> 0L
        }
        val target = when {
            projected >= goal -> " · Daily goal reached by ${clock.format(java.util.Date(finishAt))} if completed"
            targetAt > 0L -> " · Goal about ${clock.format(java.util.Date(targetAt))} at RM${String.format(Locale.US, "%.2f", pace)}/hr pace"
            acceptedAt > 0L -> " · Goal-time estimate after another completed order"
            else -> " · If accepted, goal-time estimate after your first completed order"
        }
        return "Est. complete ${clock.format(java.util.Date(finishAt))} · ${formatDurationMinutes(remainingMins)} left$target"
    }

    private fun formatDurationMinutes(minutes: Int): String {
        val safeMinutes = minutes.coerceAtLeast(1)
        val hours = safeMinutes / 60
        val remainder = safeMinutes % 60
        return when {
            hours == 0 -> "$safeMinutes min"
            remainder == 0 -> "${hours}h"
            else -> "${hours}h ${remainder}m"
        }
    }

    /** Google Maps URLs allow up to nine intermediate waypoints, so split longer orders into selectable legs. */
    private fun openRouteInGoogleMaps(stops: List<String>) {
        if (stops.isEmpty()) {
            shortToast("No scanned stops to route")
            return
        }
        val segments = stops.chunked(10)
        if (segments.size == 1) {
            launchGoogleMapsSegment(routeOrigin?.let { "${it.lat},${it.lon}" }, segments.first())
            return
        }
        val labels = segments.mapIndexed { index, part ->
            val first = index * 10 + 1
            val last = first + part.size - 1
            "Part ${index + 1}: stops $first–$last"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Route has ${stops.size} stops")
            .setMessage("Google Maps supports up to 10 scanned stops per route. Choose a consecutive route segment; the next segment starts from the previous segment's last stop.")
            .setItems(labels) { _, which ->
                val origin = if (which == 0) routeOrigin?.let { "${it.lat},${it.lon}" } else stops[which * 10 - 1]
                launchGoogleMapsSegment(origin, segments[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchGoogleMapsSegment(origin: String?, stops: List<String>) {
        if (stops.isEmpty()) return
        val builder = Uri.Builder()
            .scheme("https")
            .authority("www.google.com")
            .appendPath("maps")
            .appendPath("dir")
            .appendQueryParameter("api", "1")
        if (!origin.isNullOrBlank()) builder.appendQueryParameter("origin", origin)
        builder.appendQueryParameter("destination", stops.last())
        val waypoints = stops.dropLast(1)
        if (waypoints.isNotEmpty()) builder.appendQueryParameter("waypoints", waypoints.joinToString("|"))
        builder.appendQueryParameter("travelmode", "two-wheeler")
        val url = builder.build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                shortToast("Could not open Google Maps")
            }
        }
    }

    private fun adjust(leg: Leg, d: Double) {
        leg.km = rnd(max(0.0, leg.km + d))
        leg.unknown = false
        leg.q = 0
        leg.src = "Edited by you"
        saveRec(null)
        refresh()
    }

    private fun shortToast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Let the rider choose the real map result when OCR/geocoding was not an exact match. */
    private fun openLocationCheck(leg: Leg) {
        val stop = leg.stop ?: return
        val exactAddress = Parser.addressForCopy(stop).orEmpty()
        val query = if (exactAddress.isBlank()) stop.name else exactAddress
        val progress = AlertDialog.Builder(this)
            .setTitle("Confirm stop location")
            .setMessage("Scanned location:\n$query\n\nFinding map matches…")
            .setNegativeButton("CANCEL", null)
            .create()
        progress.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        try { progress.show() } catch (_: Exception) { return }
        pool.execute {
            val suggestions = Geo.suggestions(this, stop, leg.refPoint ?: routeOrigin)
            handler.post {
                progress.dismiss()
                if (suggestions.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("No map matches")
                        .setMessage("No result was returned for the scanned text. Nothing was guessed. Edit this leg's km or check the address in Maps.")
                        .setPositiveButton("EDIT KM") { _, _ -> openEdit(leg) }
                        .setNegativeButton("CLOSE", null)
                        .create().also { it.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); it.show() }
                    return@post
                }
                val labels = suggestions.mapIndexed { i, hit ->
                    val distance = leg.refPoint?.let { " · ${String.format(Locale.US, "%.1f", Geo.straight(it, hit.p))} km away" }.orEmpty()
                    "${i + 1}. ${hit.label.ifBlank { "Map result" }}$distance\n   ${hit.how}"
                }.toTypedArray()
                var selected = 0
                val dialog = AlertDialog.Builder(this)
                    .setTitle("Choose the correct location")
                    .setSingleChoiceItems(labels, 0) { _, which -> selected = which }
                    .setPositiveButton("CONFIRM LOCATION") { _, _ -> confirmLocation(leg, suggestions[selected]) }
                    .setNeutralButton("VIEW IN MAPS") { _, _ ->
                        val hit = suggestions[selected]
                        val map = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${hit.p.lat},${hit.p.lon}?q=${hit.p.lat},${hit.p.lon}(${Uri.encode(stop.name)})"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { startActivity(map) } catch (_: Exception) { shortToast("No map app available") }
                    }
                    .setNegativeButton("CANCEL", null)
                    .create()
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
            }
        }
    }

    /** Save a rider-confirmed point and recalculate only adjacent, non-manual route legs. */
    private fun confirmLocation(leg: Leg, hit: Geo.Hit) {
        val index = legs.indexOf(leg)
        if (index < 0) return
        val prev = if (index == 0) routeOrigin else legs.getOrNull(index - 1)?.point
        val next = legs.getOrNull(index + 1)?.point
        leg.candidate = hit
        leg.point = hit.p
        leg.confirmedLocation = true
        leg.q = 0
        leg.how = "Confirmed by rider"
        val nextLeg = legs.getOrNull(index + 1)
        val recalcNext = nextLeg != null && nextLeg.src != "Edited by you"
        pool.execute {
            val points = buildList { prev?.let(::add); add(hit.p); if (recalcNext) next?.let(::add) }
            val distances = if (points.size >= 2) Geo.routeLegs(points) else null
            val zoneCfg = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val zoneCenter = if (zoneCfg.getBoolean("zoneEnabled", false) && zoneCfg.getString("zoneLabel", "").orEmpty().isNotBlank())
                Geo.P(zoneCfg.getFloat("zoneLat", Float.NaN).toDouble(), zoneCfg.getFloat("zoneLon", Float.NaN).toDouble()) else null
            val zoneIndexes = legs.indices.filter { i -> (if (i == index) hit.p else legs[i].point) != null }
            val zoneDistances = if (zoneCenter != null) Geo.roadDistancesFrom(zoneCenter,
                zoneIndexes.map { i -> if (i == index) hit.p else legs[i].point!! }) else null
            handler.post {
                if (distances != null) {
                    var di = 0
                    if (prev != null && leg.src != "Edited by you") { leg.km = rnd(distances[di++]); leg.unknown = false; leg.src = "Map estimate" }
                    else if (prev == null) { leg.unknown = true; leg.km = 0.0 }
                    if (recalcNext && next != null && nextLeg != null && di < distances.size) {
                        nextLeg.km = rnd(distances[di]); nextLeg.unknown = false; nextLeg.q = 0; nextLeg.src = "Map estimate"; nextLeg.refPoint = hit.p
                    }
                } else if (prev != null && leg.src != "Edited by you") {
                    leg.km = rnd(Geo.rough(prev, hit.p)); leg.unknown = false; leg.src = "Rough estimate (no map)"
                }
                leg.q = 0
                zoneIndexes.forEachIndexed { zi, li -> legs[li].centerKm = zoneDistances?.getOrNull(zi) }
                saveRec(null)
                refresh()
                if (hasResult) notifyOutsideZone()
                shortToast("Location confirmed")
            }
        }
    }

    private fun placeButton(leg: Leg): TextView {
        val name = (leg.stop?.name ?: "Place").substringBefore(" - ").take(40)
        val t = tv(name, 14f, 0xFFF4F8F5.toInt(), true).apply {
            setPadding(0, dp(6), dp(4), dp(4))
        }
        val addr = leg.stop?.let { Parser.addressForCopy(it) }.orEmpty()
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (addr.isBlank()) {
                    shortToast("No clear address detected · nothing saved")
                } else {
                    AddressBook.save(this@OverlayService, name, addr)
                    t.setTextColor(0xFF79F2BD.toInt())
                    handler.postDelayed({ t.setTextColor(0xFFF4F8F5.toInt()) }, 700)
                    shortToast("Address saved · Settings → Saved places")
                }
                return true
            }
        })
        t.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        return t
    }

    /** Open a compact distance editor; the order card stays underneath it. */
    private fun openEdit(leg: Leg) {
        closeEdit()
        val screen = wm.currentWindowMetrics.bounds
        var draftKm = leg.km
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.1f", leg.km))
            setSelectAllOnFocus(true)
            textSize = 25f
            setTextColor(D_WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = dBox(0xFF0B130E.toInt(), 0xFF354B3D.toInt(), 12)
        }
        fun refreshDraft() { et.setText(String.format(Locale.US, "%.1f", draftKm)) }
        fun stepButton(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFC8F36A.toInt())
            background = dBox(0xFF1B2B20.toInt(), Color.TRANSPARENT, 11)
            setOnClickListener {
                val typed = et.text.toString().replace(',', '.').toDoubleOrNull()
                draftKm = max(0.0, (typed ?: draftKm) + if (label == "+") 0.1 else -0.1)
                draftKm = rnd(draftKm)
                refreshDraft()
                action()
            }
        }
        fun save() {
            val v = et.text.toString().replace(',', '.').toDoubleOrNull()
            if (v == null || v < 0 || v > 999) {
                shortToast("Enter a distance between 0 and 999 km")
                return
            }
            leg.km = rnd(v)
            leg.unknown = false
            leg.q = 0
            leg.src = "Edited by you"
            saveRec(null)
            closeEdit()
            refresh()
        }
        et.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = dBox(0xFF101A14.toInt(), 0xFF34473A.toInt(), 22)
        }
        box.addView(View(this).apply { background = dBox(0xFF405148.toInt(), Color.TRANSPARENT, 4) },
            LinearLayout.LayoutParams(dp(34), dp(4)).apply { gravity = Gravity.CENTER; bottomMargin = dp(13) })
        box.addView(tv("EDIT ROUTE DISTANCE", 9f, D_MUTED, true).apply { letterSpacing = 0.12f })
        box.addView(tv("${leg.from} to ${short(leg.stop ?: Stop(0, false, "", ""))}", 14f, D_WHITE, true).apply { setPadding(0, dp(3), 0, 0) })
        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = dBox(0xFF0B130E.toInt(), 0xFF354B3D.toInt(), 14)
        }
        stepper.addView(stepButton("−") {}, LinearLayout.LayoutParams(dp(44), dp(42)))
        stepper.addView(et, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        stepper.addView(stepButton("+") {}, LinearLayout.LayoutParams(dp(44), dp(42)))
        box.addView(stepper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(13) })
        box.addView(tv("Adjust only this leg · total and verdict recalculate when saved", 9f, D_MUTED).apply { setPadding(0, dp(8), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "CANCEL"
            isAllCaps = false
            textSize = 10f
            setTextColor(D_WHITE)
            stateListAnimator = null
            background = dBox(0xFF152019.toInt(), 0xFF34483A.toInt(), 11)
            setOnClickListener { closeEdit() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        row.addView(Button(this).apply {
            text = "DONE"
            isAllCaps = false
            textSize = 10f
            setTextColor(D_WHITE)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = dBox(0xFF246D4F.toInt(), Color.TRANSPARENT, 11)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        val lp = WindowManager.LayoutParams(
            screen.width() - dp(40),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(12)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(box, lp)
        editPanel = box
        et.clearFocus()
    }

    private fun closeEdit() {
        editPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        editPanel = null
    }

    private fun attachPlaceGestures(view: TextView, leg: Leg) {
        val stop = leg.stop ?: return
        val address = Parser.addressForCopy(stop).orEmpty()
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (address.isBlank()) shortToast("No clear address detected · nothing saved")
                else {
                    AddressBook.save(this@OverlayService, stop.name, address)
                    view.setTextColor(D_MINT)
                    handler.postDelayed({ if (view.isAttachedToWindow) view.setTextColor(D_WHITE) }, 700)
                    shortToast("Address saved · Settings → Saved places")
                }
                return true
            }
        })
        view.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        view.contentDescription = "${stop.name}. Double-tap to save address; use the adjacent copy button to copy it."
    }

    private fun refresh() {
        showResult()
    }

    private fun closeCard() {
        closeEdit()
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        card = null
    }

    // ---------- raw text (long press the chip) ----------

    private fun showRaw() {
        closeRaw()
        val screen = wm.currentWindowMetrics.bounds
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF0D1525.toInt())
            }
        }
        box.addView(tv("Raw text (debug)", 12f, GREY))
        val sv = ScrollView(this)
        sv.addView(tv(lastRaw.ifBlank { "Nothing read yet." }, 12f, INK))
        box.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (screen.height() * 0.4f).toInt()
        ))
        box.addView(Button(this).apply {
            text = "Close"
            isAllCaps = false
            setOnClickListener { closeRaw() }
        })
        val lp = WindowManager.LayoutParams(
            screen.width() - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(20)
        }
        wm.addView(box, lp)
        raw = box
    }

    private fun closeRaw() {
        raw?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        raw = null
    }

    // ---------- cleanup ----------

    override fun onDestroy() {
        running = false
        hidden = false
        projectionLost = false
        stopTracking()
        handler.removeCallbacksAndMessages(null)
        closeControls()
        closeCard()
        closeRaw()
        pill?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        pill = null
        pillLayout = null
        display?.release()
        held?.close()
        held = null
        reader?.close()
        try { projection?.stop() } catch (_: Exception) {}
        recognizer.close()
        pool.shutdownNow()
        Session.reset()
        sendBroadcast(Intent(MainActivity.ACTION_ORDERS_UPDATED).setPackage(packageName))
        super.onDestroy()
    }

    private fun f2(x: Double): String = String.format(Locale.US, "%.2f", x)
    private fun f1(x: Double): String = String.format(Locale.US, "%.1f", x)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
