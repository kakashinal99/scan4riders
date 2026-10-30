package my.fouriders.scan

import android.Manifest
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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "my.fouriders.scan.STOP"
        const val ACTION_HIDE = "my.fouriders.scan.HIDE"
        const val ACTION_SHOW = "my.fouriders.scan.SHOW"
        @Volatile var hidden = false
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
        val stop: Stop? = null, val from: String = "", var q: Int = 0, var how: String = ""
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

    private var pill: TextView? = null
    private var controls: View? = null
    private var card: View? = null
    private var raw: View? = null
    private var editPanel: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    private var frames = 0

    private var state = St.IDLE
    private var legs: List<Leg> = emptyList()
    private var uncertain = false
    private var useBonus = false
    private var tookWhat: String? = null
    private var showMore = false
    private var lastRaw = ""
    private var armed = false
    private var moved = 0
    private var autoCount = 0
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
        useBonus = prefs.getBoolean("bonus", false)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        val b = wm.currentWindowMetrics.bounds
        val w = b.width()
        val h = b.height()
        val dpi = resources.displayMetrics.densityDpi
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
        showPill()
        running = true
    }

    // ---------- the chip ----------

    private fun showPill() {
        val tv = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            elevation = dp(6).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(260)
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
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) {
                    if (e.eventTime - e.downTime > 700) showRaw()
                    else if (state == St.RESULT && e.x > tv.width - dp(52)) rescan()
                    else onPillTap()
                }
            }
            true
        }
        wm.addView(tv, lp)
        pill = tv
        setState(St.IDLE, "Scan", BLUE)
    }

    private fun setState(s: St, text: String, color: Int) {
        state = s
        val sb = android.text.SpannableStringBuilder("● ")
        sb.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, 0)
        sb.setSpan(android.text.style.RelativeSizeSpan(1.35f), 0, 1, 0)
        sb.append(text)
        pill?.text = sb
        pill?.setTextColor(INK)
        pill?.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(DARK)
            setStroke(dp(2), color)
        }
    }

    private fun showControls() {
        if (!::wm.isInitialized || pill == null) return
        closeControls()
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
                        "Scan ON" -> 0xFF0D3028.toInt()
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
        option("⌗", "Scan ON", "Detect order pada skrin", 0xFF25E0A7.toInt(), state != St.PAUSED && state != St.IDLE && state != St.ERROR) {
            handler.removeCallbacks(settle)
            autoCount = 0
            if (state == St.PAUSED || state == St.IDLE || state == St.ERROR) setState(St.NEED_MORE, "SCANNING · scroll", GREEN)
            capture()
        }
        val divider = View(this).apply { setBackgroundColor(0xFF26383D.toInt()) }
        menu.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(3); bottomMargin = dp(3); marginStart = dp(7); marginEnd = dp(7)
        })
        option("Ⅱ", "Pause", "Berhenti sementara", 0xFF7BBCFF.toInt(), state == St.PAUSED) {
            handler.removeCallbacks(settle)
            armed = false
            setState(St.PAUSED, "PAUSE", 0xFF7BBCFF.toInt())
        }
        val divider2 = View(this).apply { setBackgroundColor(0xFF26383D.toInt()) }
        menu.addView(divider2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(3); bottomMargin = dp(3); marginStart = dp(7); marginEnd = dp(7)
        })
        option("⏻", "OFF", "Matikan scanner", 0xFFFF6868.toInt(), false) { stopSelf() }

        val screen = wm.currentWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            minOf(dp(290), screen.width() - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(320)
        }
        try {
            wm.addView(menu, lp)
            controls = menu
        } catch (_: Exception) { }
    }

    private fun closeControls() {
        val v = controls ?: return
        try { wm.removeView(v) } catch (_: Exception) { }
        controls = null
    }

    /** After a Skip the order is forgotten, so the chip goes back to Scan and the next order starts clean. */
    private fun clearScan(msg: String) {
        handler.removeCallbacks(settle)
        closeControls()
        closeCard()
        Session.reset()
        legs = emptyList()
        uncertain = false
        tookWhat = null
        showMore = false
        armed = false
        moved = 0
        autoCount = 0
        setState(St.IDLE, "Scan", BLUE)
        shortToast(msg)
    }

    private fun rescan() {
        closeCard()
        Session.reset()
        capture()
    }

    private fun onPillTap() {
        when (state) {
            St.IDLE -> {
                if (card != null) closeCard()
                capture()
            }
            St.NEED_MORE, St.PAUSED, St.ERROR -> showControls()
            St.RESULT -> if (card == null) showCard() else closeCard()
            else -> showControls()
        }
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
        pill?.visibility = View.INVISIBLE // keep the chip out of the picture
        handler.postDelayed({
            val img: Image? = held
            pill?.visibility = View.VISIBLE
            if (img == null) {
                setState(St.ERROR, "No picture · tap", GREY)
                return@postDelayed
            }
            val bmp = try {
                toBitmap(img)
            } catch (e: Exception) {
                setState(St.ERROR, "Capture failed · tap", GREY)
                return@postDelayed
            }
            setState(St.READING, "Reading…", GREY)
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t ->
                    lastRaw = t.text
                    Session.merge(Parser.parse(t), System.currentTimeMillis())
                    evaluate()
                }
                .addOnFailureListener {
                    setState(St.ERROR, "Could not read · tap", GREY)
                }
        }, 350)
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
                if (h == null) {
                    h = Geo.locate(this, st, ref)
                    if (h != null) Session.geo[key] = h
                }
                hits.add(h)
                if (h != null) ref = h.p
            }
            val chain = ArrayList<Geo.P?>()
            chain.add(me)
            hits.forEach { chain.add(it?.p) }
            val out = ArrayList<Leg>()
            val all = chain.filterNotNull()
            val routed: List<Double>? = if (all.size == chain.size) Geo.routeLegs(all) else null
            for (i in stops.indices) {
                val a = chain[i]
                val b = chain[i + 1]
                val from = if (i == 0) "You" else short(stops[i - 1])
                val label = "$from to ${short(stops[i])}"
                val hitA = if (i == 0) null else hits[i - 1]
                val hitB = hits[i]
                val q = maxOf(if (i == 0) 0 else (hitA?.level ?: 2), hitB?.level ?: 2)
                val how = listOfNotNull(hitA, hitB).maxByOrNull { it.level }?.how.orEmpty()
                if (a == null || b == null) {
                    val why = if (a == null && i == 0) "No GPS, set km" else "Address not found, set km"
                    out.add(Leg(label, why, 0.0, true, stops[i], from, 2, "address not found"))
                } else if (routed != null) {
                    out.add(Leg(label, if (i == 0) "GPS + map" else "Map estimate", rnd(routed[i]), false, stops[i], from, q, how))
                } else {
                    val seg = Geo.routeLegs(listOf(a, b))
                    if (seg != null) out.add(Leg(label, "Map estimate", rnd(seg[0]), false, stops[i], from, q, how))
                    else out.add(Leg(label, "Rough estimate (no map)", rnd(Geo.rough(a, b)), false, stops[i], from, 2, "no map, straight line x 1.35"))
                }
            }
            handler.post {
                legs = out
                record()
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
        val fare = (Session.fare ?: 0.0) + (if (useBonus && bonus != null) bonus else 0.0)
        val km = legs.sumOf { it.km }
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

    private fun showResult() {
        val v = verdict()
        val label = if (v.rpk != null) "${v.word}  ${f2(v.rpk)}/km   ↻" else "${v.word}   ↻"
        setState(St.RESULT, label, v.color)
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
        val v = verdict()
        val rpk = v.rpk ?: return
        val sig = "${Session.fare}|" + Session.stops.values.joinToString(";") { it.name }
        val oldSig = prefs.getString("curSig", "").orEmpty()
        if (sig != oldSig) {
            val oldRpk = prefs.getFloat("curRpk", -1f)
            val h = pastScans().toMutableList()
            if (oldSig.isNotEmpty() && oldRpk > 0f) h.add(oldRpk.toDouble())
            val keep = h.takeLast(5).joinToString(",") { String.format(Locale.US, "%.3f", it) }
            prefs.edit().putString("hist", keep).putString("curSig", sig).putLong("curId", System.currentTimeMillis()).apply()
            tookWhat = null
        }
        prefs.edit().putFloat("curRpk", rpk.toFloat()).apply()
        saveRec(null)
    }

    /** Saves this order to the phone's history. decision: null keeps what was chosen, "" clears it. */
    private fun saveRec(decision: String?) {
        val v = verdict()
        val rpk = v.rpk ?: return
        val id = prefs.getLong("curId", 0L)
        if (id == 0L) return
        val stop = Session.stops.values.firstOrNull { it.collect } ?: Session.stops.values.firstOrNull()
        val area = Store.areaOf(stop?.address.orEmpty())
        val keep = Store.get(this, id)?.decision.orEmpty()
        val stopsStr = Session.stops.values.joinToString(";") { (if (it.collect) "P:" else "D:") + it.name.replace(";", " ").replace(":", " ") }
        Store.upsert(this, Rec(id, v.word, rpk, v.fare, v.km, area, decision ?: keep, stopsStr))
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

    private val P_MINT = 0xFF62E5A0.toInt()
    private val P_AMBER = 0xFFF4CC68.toInt()
    private val P_MUT = 0xFF91A39A.toInt()
    private val P_TEXT = 0xFFF1F7F3.toInt()
    private val P_LINE = 0xFF293831.toInt()

    private fun smallStep(label: String, plus: Boolean, onTap: () -> Unit, onLong: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(if (plus) 0xFF092219.toInt() else D_WHITE)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = dBox(if (plus) P_MINT else 0xFF22382E.toInt(), if (plus) P_MINT else 0xFF3D5A4C.toInt(), 10)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) }
        setOnClickListener { onTap() }
        setOnLongClickListener { onLong(); true }
    }

    private fun showCard() {
        closeCard()
        val v = verdict()
        val c = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val minFare = c.getFloat("min", 4f).toDouble()
        val rej = c.getFloat("rej", 0.9f).toDouble()
        val ok = c.getFloat("ok", 1.2f).toDouble()
        val screen = wm.currentWindowMetrics.bounds
        val vc = when (v.word) { "ACCEPT" -> D_MINT; "REJECT" -> D_RED; "CONSIDER" -> D_AMBER; else -> D_MUTED }
        val mins = Math.round(v.km / KM_PER_H * 60).toInt().coerceAtLeast(1)
        val pickups = legs.count { it.stop?.collect == true }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF17221E.toInt(), 0xFF101613.toInt())).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF293831.toInt())
            }
        }

        // Head: batch label + fare, value tag on the right
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hl.addView(tv("BATCH ORDER · $pickups PICKUP${if (pickups == 1) "" else "S"}", 10f, P_MUT, true).apply { letterSpacing = 0.12f })
        if (v.rpk != null) {
            val big = android.text.SpannableStringBuilder("RM${f2(v.rpk)}")
            val n = big.length
            big.append(" /km")
            big.setSpan(android.text.style.RelativeSizeSpan(0.45f), n, big.length, 0)
            big.setSpan(android.text.style.ForegroundColorSpan(P_MUT), n, big.length, 0)
            hl.addView(TextView(this).apply {
                text = big; textSize = 29f; setTextColor(vc); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            })
        } else {
            hl.addView(tv("Set km", 22f, P_MUT, true).apply { setPadding(0, dp(4), 0, 0) })
        }
        head.addView(hl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tagTxt = when (v.word) { "ACCEPT" -> "WORTH TAKING"; "CONSIDER" -> "BORDERLINE"; "REJECT" -> "LOW VALUE"; else -> "SET KM" }
        val tagFg = when (v.word) { "ACCEPT" -> P_MINT; "CONSIDER" -> P_AMBER; "REJECT" -> D_RED; else -> P_MUT }
        val tagBg = when (v.word) { "ACCEPT" -> 0xFF143324.toInt(); "CONSIDER" -> 0xFF3B2D16.toInt(); "REJECT" -> 0xFF3A1A1D.toInt(); else -> 0xFF26332D.toInt() }
        val tagLine = when (v.word) { "ACCEPT" -> 0xFF285D40.toInt(); "CONSIDER" -> 0xFF66512A.toInt(); "REJECT" -> 0xFF6B2E33.toInt(); else -> 0xFF3B5145.toInt() }
        head.addView(tv(tagTxt, 10f, tagFg, true).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5))
            letterSpacing = 0.08f
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(tagBg); setStroke(dp(1), tagLine) }
        })
        box.addView(head, matchW())

        // Route: one row per stop, dot on a thin line, km editable
        val route = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var pn = 0
        var dn = 0
        legs.forEachIndexed { i, leg ->
            val collect = leg.stop?.collect == true
            val warn = leg.q >= 1 && !leg.unknown
            val kindTxt = if (collect) "Pickup ${++pn}" else "Drop-off ${++dn}"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(13))
            }
            val rail = android.widget.FrameLayout(this)
            rail.addView(View(this).apply { setBackgroundColor(0xFF597765.toInt()) },
                android.widget.FrameLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
            rail.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (collect) P_MINT else P_AMBER)
                    setStroke(dp(4), if (collect) 0xFF1B3829.toInt() else 0xFF3B3020.toInt())
                }
            }, android.widget.FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(1) })
            row.addView(rail, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.MATCH_PARENT))
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(4), 0) }
            txt.addView(placeButton(leg, "$kindTxt · "))
            val lm = Math.round(leg.km / KM_PER_H * 60).toInt().coerceAtLeast(1)
            val fromName = leg.from.ifBlank { if (i == 0) "You" else "previous stop" }
            val meta = "From $fromName · $lm min" + (if (leg.unknown) " · not found" else if (warn) " · ${leg.how}" else "")
            txt.addView(tv(meta, 11f, if (leg.unknown) D_RED else if (warn) D_AMBER else P_MUT, warn || leg.unknown))
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(smallStep("−", false, { adjust(leg, -0.1) }, { adjust(leg, -1.0) }), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) })
            row.addView(tv((if (warn) "~" else "") + String.format(Locale.US, "%.1f", leg.km), 14f, D_WHITE, true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(38)
                minHeight = dp(36)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setOnClickListener { openEdit(leg) }
            })
            row.addView(smallStep("+", true, { adjust(leg, 0.1) }, { adjust(leg, 1.0) }), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(0) })
            route.addView(row, matchW())
        }
        box.addView(route, matchW().apply { topMargin = dp(16) })
        box.addView(tv("Tap the km value to type it, or use − / +.", 11f, P_MUT).apply { setPadding(0, 0, 0, dp(10)) })

        // Metrics
        box.addView(View(this).apply { setBackgroundColor(P_LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        val showEta = c.getBoolean("showeta", true)
        val showRpk = c.getBoolean("showrpk", true)
        val mItems = ArrayList<Pair<String, Boolean>>()
        mItems.add("↗ ${String.format(Locale.US, "%.1f", v.km)} km total" to false)
        if (showEta) mItems.add("◷ $mins min est." to false)
        if (showRpk) mItems.add("RM ${f2(v.fare)} fare" to true)
        mItems.forEach { (t, b) ->
            mrow.addView(tv(t, 10f, if (b) P_TEXT else P_MUT, b).apply { setPadding(0, 0, dp(14), 0) })
        }
        box.addView(mrow, matchW())

        fun notice(text: String, icon: String, fg: Int, bg: Int, line: Int, bold: Boolean) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = dBox(bg, line, 14)
            }
            r.addView(tv(icon, 13f, fg, true).apply {
                gravity = Gravity.CENTER
                background = dBox(line, line, 8)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            r.addView(tv(text, 13.5f, D_WHITE, bold).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(r, matchW().apply { topMargin = dp(10) })
        }
        flipText(v, rej, ok, minFare)?.let {
            when (v.word) {
                "ACCEPT" -> notice(it, "✓", D_MINT, 0xFF123D2D.toInt(), 0xFF2E6B50.toInt(), true)
                "REJECT" -> notice(it, "!", D_RED, 0xFF3A1A1D.toInt(), 0xFF6B2E33.toInt(), true)
                else -> notice(it, "!", D_AMBER, 0xFF3B2D16.toInt(), 0xFF66512A.toInt(), true)
            }
        }
        val extra = (if (v.near) "Close to the limit, check the km. " else "") +
            (if (uncertain) "Stop count was not read, check every stop is below." else "")
        if (extra.isNotBlank()) box.addView(tv(extra.trim(), 12.5f, D_MUTED).apply { setPadding(0, dp(6), 0, 0) })
        if (legs.any { it.q >= 1 || it.unknown }) {
            notice("Some places were not found exactly, so ~ distances are estimates and may be off.", "!", D_AMBER, 0xFF3B2D16.toInt(), 0xFF66512A.toInt(), false)
        }

        val bonus = Session.bonus
        if (bonus != null) {
            box.addView(CheckBox(this).apply {
                text = "Count the + RM ${f2(bonus)}"
                textSize = 15f
                setTextColor(D_WHITE)
                setTypeface(typeface, Typeface.BOLD)
                buttonTintList = android.content.res.ColorStateList.valueOf(D_MINT)
                minHeight = dp(48)
                isChecked = useBonus
                setOnCheckedChangeListener { _, on ->
                    useBonus = on
                    prefs.edit().putBoolean("bonus", on).apply()
                    refresh()
                }
            })
        }

        if (showMore) {
            val past = pastScans().takeLast(5)
            if (v.rpk != null && past.isNotEmpty()) {
                box.addView(dLab("Your last ${past.size} scans · beats ${past.count { v.rpk > it }} of ${past.size}"), matchW().apply { topMargin = dp(8); bottomMargin = dp(4) })
                box.addView(Bars(this, past, v.rpk, vc), matchW())
            }
            box.addView(tv("Road estimates from OpenStreetMap, rounded to 0.1 km. Grab's own route can differ.", 11.5f, D_MUTED).apply { setPadding(0, dp(8), 0, dp(2)) })
            box.addView(Button(this).apply {
                text = "Scan again"
                isAllCaps = false
                textSize = 15f
                setTextColor(D_MINT)
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = null
                setOnClickListener { rescan() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }

        // SKIP / AMBIL ORDER (saved inside this app only)
        val took = tookWhat
        if (took != null) {
            // Accepted: show IN PROGRESS and the same-position button now completes the order and resets the scanner.
            val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            lr.addView(tv("IN PROGRESS", 9f, P_MINT, true).apply {
                letterSpacing = 0.11f
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = dBox(0xFF193323.toInt(), 0xFF193323.toInt(), 8)
            })
            lr.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            lr.addView(Button(this).apply {
                text = "Undo"
                isAllCaps = false
                textSize = 14f
                setTextColor(D_MINT)
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = null
                setOnClickListener { tookWhat = null; saveRec(""); refresh() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            box.addView(lr, matchW().apply { topMargin = dp(12) })
            box.addView(Button(this).apply {
                text = "MARK AS COMPLETED"
                isAllCaps = false
                textSize = 14f
                setTextColor(0xFF062014.toInt())
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF38C77F.toInt(), 0xFF66E5A0.toInt())).apply { cornerRadius = dp(12).toFloat() }
                setOnClickListener { saveRec("T"); clearScan("Order completed. Ready for the next scan.") }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4) })
        } else {
            val fr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fr.addView(Button(this).apply {
                text = "REJECT"
                isAllCaps = false
                textSize = 14f
                setTextColor(0xFFD9E3DD.toInt())
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF1A2420.toInt()); setStroke(dp(1), 0xFF34433B.toInt()) }
                setOnClickListener { saveRec("S"); clearScan("Rejected, scanner cleared") }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
            fr.addView(Button(this).apply {
                text = "ACCEPT ORDER"
                isAllCaps = false
                textSize = 14f
                setTextColor(0xFF062014.toInt())
                setTypeface(typeface, Typeface.BOLD)
                stateListAnimator = null
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF38C77F.toInt(), 0xFF66E5A0.toInt())).apply { cornerRadius = dp(12).toFloat() }
                setOnClickListener { tookWhat = "Accepted"; saveRec("A"); refresh() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
            box.addView(fr, matchW().apply { topMargin = dp(15) })
        }
        box.addView(Button(this).apply {
            text = "OPEN DASHBOARD ↗"
            isAllCaps = false
            textSize = 13f
            setTextColor(P_MINT)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF10291C.toInt()); setStroke(dp(1), 0xFF3E9865.toInt()) }
            setOnClickListener {
                closeCard()
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun link(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 13f
            setTextColor(P_MINT)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = null
            setOnClickListener { f() }
        }
        links.addView(link(if (showMore) "Less" else "More: last scans, notes") { showMore = !showMore; showCard() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        links.addView(link("Minimise ↑") { closeCard() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { marginStart = dp(16) })
        box.addView(links, matchW())

        val lp = WindowManager.LayoutParams(
            screen.width() - dp(16),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }
        val outer = CappedScroll(this, (screen.height() * 0.9f).toInt())
        outer.addView(box)
        wm.addView(outer, lp)
        card = outer
    }

    private fun rnd(x: Double): Double = Math.round(x * 10.0) / 10.0

    private fun adjust(leg: Leg, d: Double) {
        leg.km = rnd(max(0.0, leg.km + d))
        leg.unknown = false
        leg.q = 0
        leg.src = "Edited by you"
        refresh()
    }

    private fun shortToast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun placeButton(leg: Leg, prefix: String = ""): TextView {
        val name = (leg.stop?.name ?: "Place").substringBefore(" - ").take(40)
        val t = tv(prefix + name, 12.5f, 0xFFF3F7F4.toInt(), true).apply {
            setPadding(0, dp(2), dp(4), dp(2))
        }
        val addr = leg.stop?.address.orEmpty()
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (addr.isBlank()) {
                    shortToast("No address to copy")
                } else {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("address", addr))
                    t.setTextColor(0xFF79F2BD.toInt())
                    handler.postDelayed({ t.setTextColor(0xFFF4F8F5.toInt()) }, 700)
                    shortToast("Address copied")
                }
                return true
            }
        })
        t.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        return t
    }

    /** Small window at the top with a keyboard, so you can type the real km. */
    private fun openEdit(leg: Leg) {
        closeEdit()
        val screen = wm.currentWindowMetrics.bounds
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.1f", leg.km))
            setSelectAllOnFocus(true)
            textSize = 30f
            setTextColor(DARK)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFF1F4F9.toInt())
                setStroke(dp(2), BLUE)
            }
        }
        fun save() {
            val v = et.text.toString().replace(',', '.').toDoubleOrNull()
            if (v == null || v < 0 || v > 999) {
                shortToast("Type a number like 9.1")
                return
            }
            leg.km = rnd(v)
            leg.unknown = false
            leg.q = 0
            leg.src = "Edited by you"
            closeEdit()
            refresh()
        }
        et.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), 0xFFD6DBE6.toInt())
            }
        }
        box.addView(tv("Distance in km", 13f, SOFT, true))
        box.addView(tv("${leg.from} to ${short(leg.stop ?: Stop(0, false, "", ""))}", 15f, DARK, true))
        box.addView(et, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            textSize = 16f
            setTextColor(DARK)
            stateListAnimator = null
            background = shape(BTN, 16)
            setOnClickListener { closeEdit() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
        row.addView(Button(this).apply {
            text = "Save"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = shape(DARK, 16)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        val lp = WindowManager.LayoutParams(
            screen.width() - dp(40),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(90)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(box, lp)
        editPanel = box
        et.post {
            et.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeEdit() {
        editPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        editPanel = null
    }

    private fun refresh() {
        showCard()
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
        handler.removeCallbacksAndMessages(null)
        closeControls()
        closeCard()
        closeRaw()
        pill?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        pill = null
        display?.release()
        held?.close()
        held = null
        reader?.close()
        try { projection?.stop() } catch (_: Exception) {}
        recognizer.close()
        pool.shutdownNow()
        Session.reset()
        super.onDestroy()
    }

    private fun f2(x: Double): String = String.format(Locale.US, "%.2f", x)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
