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
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "scan"
        @Volatile var running = false
        // Daylight palette: strong colours that stay readable in noon sun.
        private const val BLUE = 0xFF0B57D0.toInt()
        private const val GREY = 0xFF5A6678.toInt()
        private const val AMBER = 0xFFF29C00.toInt()
        private const val GREEN = 0xFF0F8A43.toInt()
        private const val ORANGE = 0xFFF29C00.toInt()
        private const val RED = 0xFFD9263A.toInt()
        private const val INK = 0xFFEAF1FF.toInt()   // raw-text debug panel (dark)
        private const val DARK = 0xFF0D1220.toInt()  // text on light surfaces
        private const val SOFT = 0xFF3D4756.toInt()  // secondary text, still strong
        private const val BTN = 0xFFE8ECF3.toInt()
        private const val GREEN_T = 0xFFDCF5E5.toInt()
        private const val AMBER_T = 0xFFFFEFC7.toInt()
        private const val RED_T = 0xFFFDE0E4.toInt()
        private const val KM_PER_H = 24.0
        private const val FUEL_PER_KM = 0.14
    }

    private enum class St { IDLE, READING, ROUTING, NEED_MORE, RESULT, ERROR }

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
        pill?.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(3), color)
        }
    }

    private fun rescan() {
        closeCard()
        Session.reset()
        capture()
    }

    private fun onPillTap() {
        when (state) {
            St.IDLE, St.NEED_MORE, St.ERROR -> {
                if (card != null) closeCard()
                capture()
            }
            St.RESULT -> if (card == null) showCard() else closeCard()
            else -> {}
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
            setState(St.NEED_MORE, "$have/$total stops · scroll", AMBER)
            armAuto()
            return
        }
        if (Session.stops.isEmpty()) {
            setState(St.NEED_MORE, "Stops not seen · scroll", AMBER)
            armAuto()
            return
        }
        if (Session.fare == null) {
            setState(St.NEED_MORE, "Fare not seen · scroll up", AMBER)
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
                p.color = if (i == n - 1) nowColor else 0xFFCDD5E2.toInt()
                c.drawRoundRect(x, base - h, x + bw, base, 6 * d, 6 * d, p)
                p.color = DARK
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
            prefs.edit().putString("hist", keep).putString("curSig", sig).apply()
        }
        prefs.edit().putFloat("curRpk", rpk.toFloat()).apply()
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

    private fun showCard() {
        closeCard()
        val v = verdict()
        val c = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val minFare = c.getFloat("min", 4f).toDouble()
        val rej = c.getFloat("rej", 0.9f).toDouble()
        val ok = c.getFloat("ok", 1.2f).toDouble()
        val screen = wm.currentWindowMetrics.bounds

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), 0xFFD6DBE6.toInt())
            }
        }

        // top: small signal light + verdict word + rate
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Lamp(this, v.word))
        val hv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        hv.addView(tv(v.word, 22f, DARK, true).apply { letterSpacing = 0.04f })
        if (v.rpk != null) {
            val rate = android.text.SpannableStringBuilder(f2(v.rpk))
            val n = rate.length
            rate.append(" RM/km")
            rate.setSpan(android.text.style.RelativeSizeSpan(0.45f), n, rate.length, 0)
            hv.addView(TextView(this).apply {
                text = rate
                textSize = 32f
                setTextColor(DARK)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
            val mins = Math.round(v.km / KM_PER_H * 60).toInt()
            hv.addView(tv("RM ${f2(v.fare)} ÷ ${String.format(Locale.US, "%.1f", v.km)} km · $mins min", 14f, SOFT))
        } else {
            hv.addView(tv(v.note, 14f, SOFT))
        }
        head.addView(hv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)

        // what would flip the verdict
        val flip = flipText(v, rej, ok, minFare)
        if (flip != null) {
            val tint = when (v.word) { "ACCEPT" -> GREEN_T; "REJECT" -> RED_T; else -> AMBER_T }
            val fr = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = shape(tint, 16)
            }
            fr.addView(TextView(this).apply {
                text = if (v.word == "ACCEPT") "✓" else "!"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (v.word == "CONSIDER") DARK else Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(v.color) }
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
            fr.addView(tv(flip, 15f, DARK, true).apply { setPadding(dp(10), 0, 0, 0) })
            box.addView(fr, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
        val extra = (if (v.near) "Close to the limit, check the km. " else "") +
            (if (uncertain) "Stop count was not read, check every stop is below." else "")
        if (extra.isNotBlank()) box.addView(tv(extra.trim(), 13f, SOFT).apply { setPadding(0, dp(6), 0, 0) })

        // last scans
        val past = pastScans().takeLast(5)
        if (v.rpk != null && past.isNotEmpty()) {
            box.addView(Bars(this, past, v.rpk, v.color), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })
            val beat = past.count { v.rpk > it }
            val cap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cap.addView(tv("Your last ${past.size} scans", 12f, SOFT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cap.addView(tv("Beats $beat of ${past.size}", 12f, SOFT, true))
            box.addView(cap)
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // legs
        if (legs.any { it.q >= 1 || it.unknown }) {
            box.addView(tv("Some places were not found exactly, so those distances are estimates and may be off. Tap the number to type the real km.", 13.5f, DARK, true).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = shape(AMBER_T, 14)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
        box.addView(tv("Tap a place twice to copy its address. Tap a distance to type it.", 12.5f, SOFT).apply { setPadding(0, dp(12), 0, dp(2)) })
        for (leg in legs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(placeButton(leg))
            val mins = Math.round(leg.km / KM_PER_H * 60).toInt()
            val detail = "from ${leg.from} · ${leg.src}" + (if (leg.unknown) "" else " · $mins min")
            txt.addView(tv(detail, 13f, if (leg.unknown) RED else SOFT).apply { setPadding(dp(2), dp(4), 0, 0) })
            if (leg.q >= 1 && !leg.unknown) {
                txt.addView(tv("~ ${leg.how}. Check this one.", 12.5f, 0xFF9A5F00.toInt(), true).apply { setPadding(dp(2), dp(2), 0, 0) })
            }
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(stepBtn("−", { adjust(leg, -0.1) }, { adjust(leg, -1.0) }))
            row.addView(tv((if (leg.q >= 1 && !leg.unknown) "~" else "") + String.format(Locale.US, "%.1f", leg.km), 18f, DARK, true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(54)
                minHeight = dp(46)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setOnClickListener { openEdit(leg) }
            })
            row.addView(stepBtn("+", { adjust(leg, 0.1) }, { adjust(leg, 1.0) }))
            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(0xFFE4E8EF.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        }
        box.addView(list)
        box.addView(tv("Distances are road estimates from OpenStreetMap, rounded to 0.1 km. Grab's own route can differ.", 12f, SOFT).apply { setPadding(0, dp(8), 0, dp(2)) })

        val bonus = Session.bonus
        if (bonus != null) {
            box.addView(CheckBox(this).apply {
                text = "Count the + RM ${f2(bonus)}"
                textSize = 16f
                setTextColor(DARK)
                setTypeface(typeface, Typeface.BOLD)
                buttonTintList = android.content.res.ColorStateList.valueOf(DARK)
                minHeight = dp(48)
                isChecked = useBonus
                setOnCheckedChangeListener { _, on ->
                    useBonus = on
                    prefs.edit().putBoolean("bonus", on).apply()
                    refresh()
                }
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Scan again"
            isAllCaps = false
            textSize = 16f
            setTextColor(DARK)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = shape(BTN, 16)
            setOnClickListener { rescan() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
        row.addView(Button(this).apply {
            text = "Minimise"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = shape(DARK, 16)
            setOnClickListener { closeCard() }
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

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
        val outer = CappedScroll(this, (screen.height() * 0.86f).toInt())
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

    private fun placeButton(leg: Leg): TextView {
        val name = (leg.stop?.name ?: "Place").substringBefore(" - ").take(40)
        val t = tv("📍 $name", 15f, DARK, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = shape(0xFFEEF2FA.toInt(), 12)
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
                    t.background = shape(GREEN_T, 12)
                    handler.postDelayed({ t.background = shape(0xFFEEF2FA.toInt(), 12) }, 600)
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
        handler.removeCallbacksAndMessages(null)
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
