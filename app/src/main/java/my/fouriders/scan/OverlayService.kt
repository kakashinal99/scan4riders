package my.fouriders.scan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
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
        private const val BLUE = 0xFF25C8FF.toInt()
        private const val GREY = 0xFF71809B.toInt()
        private const val AMBER = 0xFFFFB547.toInt()
        private const val GREEN = 0xFFB8FF2C.toInt()
        private const val ORANGE = 0xFFFFB547.toInt()
        private const val RED = 0xFFFF5268.toInt()
        private const val INK = 0xFFEAF1FF.toInt()
    }

    private enum class St { IDLE, READING, ROUTING, NEED_MORE, RESULT, ERROR }

    private class Leg(val label: String, var src: String, var km: Double, var unknown: Boolean)

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
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
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
        pill?.text = text
        pill?.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(color)
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
            val pts = ArrayList<Geo.P?>()
            for (s in stops) {
                val key = s.name + "|" + s.address
                var p = Session.geo[key]
                if (p == null) {
                    p = Geo.geocode(this, s)
                    if (p != null) Session.geo[key] = p
                }
                pts.add(p)
            }
            val chain = ArrayList<Geo.P?>()
            chain.add(me)
            chain.addAll(pts)
            val labels = ArrayList<String>()
            for (i in stops.indices) {
                labels.add(if (i == 0) "You to ${short(stops[0])}" else "${short(stops[i - 1])} to ${short(stops[i])}")
            }
            val out = ArrayList<Leg>()
            val all = chain.filterNotNull()
            val routed: List<Double>? = if (all.size == chain.size) Geo.routeLegs(all) else null
            for (i in stops.indices) {
                val a = chain[i]
                val b = chain[i + 1]
                if (a == null || b == null) {
                    val why = if (a == null && i == 0) "No GPS, set km" else "Address not found, set km"
                    out.add(Leg(labels[i], why, 0.0, true))
                } else if (routed != null) {
                    out.add(Leg(labels[i], if (i == 0) "GPS + map" else "Map estimate", routed[i], false))
                } else {
                    val seg = Geo.routeLegs(listOf(a, b))
                    if (seg != null) out.add(Leg(labels[i], "Map estimate", seg[0], false))
                    else out.add(Leg(labels[i], "Rough estimate (no map)", Geo.rough(a, b), false))
                }
            }
            handler.post {
                legs = out
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

    private class Meter(ctx: Context, val rpk: Double, val rej: Double, val ok: Double) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val top = h / 2 - 4f * resources.displayMetrics.density
            val bot = h / 2 + 4f * resources.displayMetrics.density
            val a = (rej / (ok * 2)).toFloat().coerceIn(0f, 1f) * w
            val b = 0.5f * w
            p.color = RED; c.drawRect(0f, top, a, bot, p)
            p.color = ORANGE; c.drawRect(a, top, b, bot, p)
            p.color = GREEN; c.drawRect(b, top, w, bot, p)
            val x = (rpk / (ok * 2)).toFloat().coerceIn(0f, 1f) * w
            p.color = INK
            c.drawRect(x - 2f * resources.displayMetrics.density, 0f, x + 2f * resources.displayMetrics.density, h, p)
        }
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun stepBtn(label: String, onTap: () -> Unit, onLong: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener { onTap() }
        setOnLongClickListener { onLong(); true }
    }

    private fun showCard() {
        closeCard()
        val v = verdict()
        val c = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val rej = c.getFloat("rej", 0.9f).toDouble()
        val ok = c.getFloat("ok", 1.2f).toDouble()
        val screen = wm.currentWindowMetrics.bounds

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF0D1525.toInt())
                setStroke(dp(1), 0xFF23334D.toInt())
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(v.word, 28f, v.color, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tv(if (v.rpk != null) "${f2(v.rpk)} RM/km" else "--", 18f, INK, true))
        box.addView(head)
        val sub = "RM ${f2(v.fare)} ÷ ${String.format(Locale.US, "%.1f", v.km)} km. ${v.note}" +
            (if (v.near) " Close to the limit, check the km." else "") +
            (if (uncertain) " Stop count was not read, check every stop is below." else "")
        box.addView(tv(sub, 13f, GREY).apply { setPadding(0, dp(6), 0, dp(8)) })
        if (v.rpk != null) {
            box.addView(Meter(this, v.rpk, rej, ok), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (leg in legs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(tv(leg.label, 13f, INK, true))
            txt.addView(tv(leg.src, 11f, if (leg.unknown) RED else GREY))
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(stepBtn("−", { adjust(leg, -0.5) }, { adjust(leg, -5.0) }))
            row.addView(tv(String.format(Locale.US, "%.1f", leg.km), 14f, INK, true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(44)
            })
            row.addView(stepBtn("+", { adjust(leg, 0.5) }, { adjust(leg, 5.0) }))
            list.addView(row)
        }
        val scroll = ScrollView(this)
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val bonus = Session.bonus
        if (bonus != null) {
            box.addView(CheckBox(this).apply {
                text = "Count the + RM ${f2(bonus)}"
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
            setOnClickListener { rescan() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        row.addView(Button(this).apply {
            text = "Minimise"
            isAllCaps = false
            setOnClickListener { closeCard() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        box.addView(row)

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
        card = box
    }

    private fun adjust(leg: Leg, d: Double) {
        leg.km = max(0.0, leg.km + d)
        leg.unknown = false
        if (!leg.src.startsWith("Edited")) leg.src = "Edited by you"
        refresh()
    }

    private fun refresh() {
        showCard()
        showResult()
    }

    private fun closeCard() {
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
