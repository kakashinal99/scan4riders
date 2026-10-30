package my.fouriders.scan

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private val BG = Color.parseColor("#080D0B")
        private val INK = Color.parseColor("#F1F7F3")
        private val MUT = Color.parseColor("#91A39A")
        private val LINE = Color.parseColor("#293831")
        private val LINE2 = Color.parseColor("#1B2420")
        private val SOFTBG = Color.parseColor("#17211E")
        private val BLUE = Color.parseColor("#62E5A0")
        private val OK = Color.parseColor("#62E5A0")
        private val MID = Color.parseColor("#F4CC68")
        private val BAD = Color.parseColor("#FF7770")
        private val WARNT = Color.parseColor("#352A16")
        private val GREYBAR = Color.parseColor("#3B5145")
    }

    private var tab = 0
    private var filt = 0
    private var per = 0
    private lateinit var content: ScrollView
    private lateinit var navRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = getSharedPreferences("crash", Context.MODE_PRIVATE).getString("trace", null)
        if (crash != null) showCrash(crash) else buildUi()
    }


    // ---------- shell ----------

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val NAVBG = Color.parseColor("#0C1210")
    private val NAVMUT = Color.parseColor("#819188")

    private fun lp(w: Int, h: Int, wt: Float = -1f) =
        if (wt >= 0f) LinearLayout.LayoutParams(w, h, wt) else LinearLayout.LayoutParams(w, h)

    private fun mtb(t: Int, b: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(t); bottomMargin = dp(b) }

    private fun gd(fill: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = NAVBG
        window.insetsController?.setSystemBarsAppearance(
            0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#143024"), Color.parseColor("#0A100E"), Color.parseColor("#080B0A")))
            fitsSystemWindows = true
        }
        content = ScrollView(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#25332C")) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(NAVBG) }
        root.addView(navRow, LinearLayout.LayoutParams(MATCH, dp(64)))
        setContentView(root)
        render()
    }

    private fun render() {
        val y = content.scrollY
        navRow.removeAllViews()
        listOf("HOME" to "⌂", "HISTORY" to "◷", "SETTINGS" to "⚙").forEachIndexed { i, (name, icon) ->
            val on = i == tab
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { tab = i; content.scrollTo(0, 0); render() }
            }
            cell.addView(tv(icon, 19f, if (on) OK else NAVMUT, true).apply { gravity = Gravity.CENTER })
            cell.addView(tv(name, 9f, if (on) OK else NAVMUT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
            navRow.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        content.removeAllViews()
        val page = col().apply { setPadding(dp(17), dp(20), dp(17), dp(24)) }
        topBar(page)
        when (tab) {
            0 -> home(page)
            1 -> history(page)
            else -> settings(page)
        }
        content.addView(page)
        content.post { content.scrollTo(0, y) }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    // ---------- data ----------

    private class Period(val label: String, val sub: String, val from: Long, val to: Long)

    private class Agg(val n: Int, val fare: Double, val km: Double)

    private fun dayStart(t: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun agg(rows: List<Rec>): Agg {
        val t = rows.filter { it.decision == "T" || it.decision == "A" }
        return Agg(t.size, t.sumOf { it.fare }, t.sumOf { it.km })
    }

    private fun periods(kind: Int): List<Period> {
        val out = ArrayList<Period>()
        val today = dayStart(System.currentTimeMillis())
        when (kind) {
            0 -> for (i in 0..6) {
                val a = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -i) }
                val b = (a.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                out.add(Period(SimpleDateFormat("EEE d", Locale.US).format(a.time), if (i == 0) "Today" else "", a.timeInMillis, b.timeInMillis))
            }
            1 -> {
                val w = (today.clone() as Calendar).apply {
                    firstDayOfWeek = Calendar.MONDAY
                    add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
                }
                for (i in 0..5) {
                    val a = (w.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, -i) }
                    val b = (a.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, 1) }
                    val e = (b.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                    val f = SimpleDateFormat("d MMM", Locale.US)
                    out.add(Period(if (i == 0) "This week" else f.format(a.time), f.format(a.time) + " - " + f.format(e.time), a.timeInMillis, b.timeInMillis))
                }
            }
            else -> {
                val m = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
                for (i in 0..5) {
                    val a = (m.clone() as Calendar).apply { add(Calendar.MONTH, -i) }
                    val b = (a.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
                    out.add(Period(SimpleDateFormat("MMM", Locale.US).format(a.time), if (i == 0) "So far" else "", a.timeInMillis, b.timeInMillis))
                }
            }
        }
        return out
    }

    private fun areas(rows: List<Rec>): List<Triple<String, Int, Double>> =
        rows.groupBy { it.area }.map { (k, v) -> Triple(k, v.size, v.map { it.rpk }.average()) }
            .sortedByDescending { it.second }

    private fun cfg() = getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun f2(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    // ---------- design system (4Riders prototype v1.3) ----------

    private fun m(x: Double) = "RM" + String.format(Locale.US, "%,.2f", x)

    private fun h1(t: String) = tv(t, 27f, INK, true).apply { letterSpacing = -0.04f }

    private fun subT(t: String) = tv(t, 13f, MUT).apply { setPadding(0, dp(4), 0, 0) }

    private fun eyebrow(t: String) = tv(t.uppercase(Locale.US), 10f, MUT, true).apply { letterSpacing = 0.15f }

    private fun lineV() = View(this).apply { setBackgroundColor(LINE) }

    private fun noteT(t: String) = tv(t, 9f, Color.parseColor("#75867C")).apply { gravity = Gravity.CENTER }

    private fun pillT(t: String) = tv(t, 9f, OK, true).apply {
        letterSpacing = 0.05f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = gd(Color.parseColor("#17251E"), 20)
    }

    private fun isCounted(r: Rec) = r.decision == "T" || r.decision == "A"

    private fun cfgBool(k: String) = cfg().getBoolean(k, true)

    private fun scanControl() {
        val running = OverlayService.running
        if (!running) { startFlow(); return }
        val hid = OverlayService.hidden
        android.app.AlertDialog.Builder(this)
            .setTitle("Scanner control")
            .setMessage(if (hid) "Scanner is paused." else "Scanner is currently active.")
            .setNegativeButton("STOP") { _, _ ->
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                toast("Scanner stopped")
                content.postDelayed({ render() }, 500)
            }
            .setPositiveButton(if (hid) "CONTINUE" else "PAUSE") { _, _ ->
                startService(Intent(this, OverlayService::class.java).setAction(if (hid) OverlayService.ACTION_SHOW else OverlayService.ACTION_HIDE))
                OverlayService.hidden = !hid
                render()
            }
            .setNeutralButton("CLOSE", null)
            .show()
    }

    private fun topBar(p: LinearLayout) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brand.addView(tv("4", 24f, Color.parseColor("#F5FFF9"), true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(dp(9).toFloat(), 0f, 0f, Color.parseColor("#61EAA0"))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#293A33"), Color.parseColor("#080D0B"))).apply {
                cornerRadius = dp(11).toFloat(); setStroke(dp(1), Color.parseColor("#53645B"))
            }
        }, LinearLayout.LayoutParams(dp(37), dp(37)))
        brand.addView(tv("4Riders", 21f, INK, true).apply { letterSpacing = -0.04f; setPadding(dp(10), 0, 0, 0) })
        r.addView(brand, LinearLayout.LayoutParams(0, WRAP, 1f))
        val txt = when { !OverlayService.running -> "● READY"; OverlayService.hidden -> "Ⅱ PAUSED"; else -> "● SCANNING" }
        r.addView(tv(txt, 10f, OK, true).apply {
            letterSpacing = 0.1f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = gd(Color.parseColor("#10251B"), 30, Color.parseColor("#286447"))
            setOnClickListener { scanControl() }
        })
        p.addView(r, mtb(0, 26))
    }

    private fun hero(build: LinearLayout.() -> Unit): LinearLayout = col().apply {
        setPadding(dp(18), dp(19), dp(18), dp(17))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#183D2B"), Color.parseColor("#11231B"), Color.parseColor("#101815"))).apply {
            cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.parseColor("#2B6245"))
        }
        build()
    }

    private fun money(v: String, small: String): View = TextView(this).apply {
        val sb = android.text.SpannableStringBuilder(v)
        if (small.isNotEmpty()) {
            val n = sb.length
            sb.append(" ").append(small)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.42f), n, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#B6D7C4")), n, sb.length, 0)
        }
        text = sb
        textSize = 38f
        letterSpacing = -0.045f
        setTextColor(INK)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun twin(l: String, r: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val c = Color.parseColor("#C3D7CB")
        row.addView(tv(l, 11f, c), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(tv(r, 11f, c))
        return row
    }

    private fun track(pct: Double): View {
        val f = FrameLayout(this)
        f.background = gd(Color.parseColor("#345342"), 9)
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#39C77F"), Color.parseColor("#8CF2AE"))).apply { cornerRadius = dp(9).toFloat() }
        }, FrameLayout.LayoutParams(0, MATCH))
        f.post {
            (f.getChildAt(0).layoutParams as FrameLayout.LayoutParams).width = (f.width * pct / 100.0).toInt()
            f.getChildAt(0).requestLayout()
        }
        f.layoutParams = LinearLayout.LayoutParams(MATCH, dp(7)).apply { topMargin = dp(13); bottomMargin = dp(9) }
        return f
    }

    private fun statsRow(items: List<Pair<String, String>>): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (v, l) ->
            val c = col()
            c.addView(tv(v, 19f, INK, true).apply { letterSpacing = -0.025f; maxLines = 1 })
            c.addView(tv(l, 10f, MUT).apply { letterSpacing = 0.04f })
            r.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i < items.size - 1) marginEnd = dp(12) })
        }
        r.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(21); bottomMargin = dp(25) }
        return r
    }

    private fun sectionHead(title: String, right: View?): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv(title, 15f, INK, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (right != null) row.addView(right)
        row.layoutParams = mtb(23, 12)
        return row
    }

    private fun linkT(t: String, f: () -> Unit) = tv(t, 11f, OK, true).apply { setPadding(dp(3), dp(3), dp(3), dp(3)); setOnClickListener { f() } }

    private fun mutedT(t: String) = tv(t, 10f, MUT)

    private fun pills(opts: List<String>, sel: Int, on: (Int) -> Unit): View {
        val sv = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        opts.forEachIndexed { i, s ->
            val s1 = i == sel
            r.addView(tv(s, 10f, if (s1) OK else Color.parseColor("#9AABA2"), true).apply {
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = gd(Color.parseColor(if (s1) "#153624" else "#131C18"), 30, Color.parseColor(if (s1) "#34734E" else "#2A3831"))
                setOnClickListener { on(i) }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(7) })
        }
        sv.addView(r)
        sv.layoutParams = mtb(16, 16)
        return sv
    }

    private class StopL(val collect: Boolean, val name: String)

    private fun parseStops(s: String): List<StopL> =
        s.split(";").filter { it.contains(":") }.map { StopL(it.startsWith("P"), it.substringAfter(":").trim()) }

    private fun tagFor(word: String): View {
        val (t, fg, bg, ln) = when (word) {
            "ACCEPT" -> arrayOf("WORTH TAKING", OK, Color.parseColor("#143324"), Color.parseColor("#285D40"))
            "CONSIDER" -> arrayOf("BORDERLINE", MID, Color.parseColor("#3B2D16"), Color.parseColor("#66512A"))
            else -> arrayOf("LOW VALUE", BAD, Color.parseColor("#3A1A1D"), Color.parseColor("#6B2E33"))
        }
        return tv(t as String, 9f, fg as Int, true).apply {
            letterSpacing = 0.07f
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = gd(bg as Int, 8, ln as Int)
        }
    }

    private fun routeView(o: Rec): View {
        val st = parseStops(o.stops)
        val pk = st.count { it.collect }
        val dr = st.size - pk
        val items = if (st.isEmpty()) listOf(StopL(true, o.area)) else st
        val route = col().apply { setPadding(dp(4), 0, 0, 0) }
        var pn = 0
        var dn = 0
        items.forEachIndexed { i, s ->
            val label = if (s.collect) (if (pk > 1) "Pickup ${++pn}" else "Pickup") else (if (dr > 1) "Drop-off ${++dn}" else "Drop-off")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, if (i < items.size - 1) dp(12) else 0) }
            val rail = FrameLayout(this)
            rail.addView(View(this).apply { setBackgroundColor(Color.parseColor("#426252")) }, FrameLayout.LayoutParams(dp(1), MATCH).apply { marginStart = dp(6) })
            rail.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (s.collect) OK else MID)
                    setStroke(dp(3), Color.parseColor(if (s.collect) "#193C2A" else "#41351C"))
                }
            }, FrameLayout.LayoutParams(dp(14), dp(14)))
            row.addView(rail, LinearLayout.LayoutParams(dp(15), MATCH))
            row.addView(tv("$label · ${s.name}", 12f, INK).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            route.addView(row)
        }
        route.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14); bottomMargin = dp(13) }
        return route
    }

    private fun metricsView(o: Rec): View {
        val w = col()
        w.addView(lineV(), LinearLayout.LayoutParams(MATCH, dp(1)))
        val mr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(11), 0, 0) }
        val items = ArrayList<String>()
        items.add("↗ ${f1(o.km)} km total")
        if (cfgBool("showeta")) items.add("◷ ${Math.round(o.km / 24.0 * 60).toInt().coerceAtLeast(1)} min est.")
        if (cfgBool("showrpk")) items.add("RM${f2(o.rpk)}/km")
        items.forEach { mr.addView(tv(it, 10f, Color.parseColor("#C2D0C8")).apply { setPadding(0, 0, dp(12), 0) }) }
        w.addView(mr)
        return w
    }

    private fun btn(t: String, primary: Boolean, danger: Boolean = false, f: () -> Unit) = tv(t, 11f,
        if (primary) Color.parseColor("#062014") else if (danger) Color.parseColor("#FF9A92") else Color.parseColor("#D9E3DD"), true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(12), dp(9), dp(12))
        background = if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#38C77F"), Color.parseColor("#66E5A0"))).apply { cornerRadius = dp(12).toFloat() }
        else if (danger) gd(Color.parseColor("#281715"), 12, Color.parseColor("#633C38"))
        else gd(Color.parseColor("#1A2420"), 12, Color.parseColor("#34433B"))
        setOnClickListener { f() }
    }

    private fun cardBox() = col().apply {
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#17221E"), Color.parseColor("#101613"))).apply {
            cornerRadius = dp(18).toFloat(); setStroke(dp(1), LINE)
        }
    }

    private fun orderHead(o: Rec, withTag: Boolean): View {
        val pk = parseStops(o.stops).count { it.collect }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hl = col()
        hl.addView(eyebrow(if (pk > 1) "Batch order · $pk pickups" else "Single order"))
        hl.addView(tv("RM" + f2(o.fare), 26f, INK, true).apply { letterSpacing = -0.04f })
        head.addView(hl, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (withTag) head.addView(tagFor(o.word))
        return head
    }

    /** mode 0 = new offer (Reject / Accept), 1 = in progress, 2 = completed, 3 = rejected */
    private fun orderCard(o: Rec, mode: Int): View {
        val card = cardBox()
        if (mode == 0) {
            card.addView(orderHead(o, true))
            card.addView(routeView(o))
            card.addView(metricsView(o))
            val ac = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            ac.addView(btn("REJECT", false) { Store.upsert(this, o.copy(decision = "S")); grp = 3; toast("Offer rejected"); render() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(4) })
            ac.addView(btn("ACCEPT ORDER", true) { Store.upsert(this, o.copy(decision = "A")); grp = 1; toast("Accepted · In Progress"); render() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(5) })
            card.addView(ac, mtb(14, 0))
        } else {
            val label = when (mode) { 1 -> "IN PROGRESS"; 2 -> "COMPLETED"; else -> "REJECTED" }
            card.addView(tv(label, 9f, OK, true).apply {
                letterSpacing = 0.1f
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = gd(Color.parseColor("#193323"), 8)
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            card.addView(eyebrow(if (parseStops(o.stops).count { it.collect } > 1) "Batch order · ${parseStops(o.stops).count { it.collect }} pickups" else "Single order"), mtb(10, 0))
            card.addView(tv("RM" + f2(o.fare), 26f, INK, true).apply { letterSpacing = -0.04f })
            card.addView(routeView(o))
            card.addView(metricsView(o))
            if (mode == 1) card.addView(btn("MARK AS COMPLETED", true) {
                Store.upsert(this, o.copy(decision = "T")); grp = 2; toast("Order completed"); render()
            }, mtb(12, 0))
            else card.addView(mutedT(if (mode == 2) "Delivery completed." else "Status recorded."), mtb(10, 0))
        }
        card.layoutParams = mtb(0, 12)
        return card
    }

    private fun emptyCard(t: String): View = tv(t, 11f, MUT).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.parseColor("#35463C"), dp(4).toFloat(), dp(3).toFloat())
        }
    }

    private fun scanButton(): View {
        val running = OverlayService.running
        val t = when { !running -> "START SCAN"; OverlayService.hidden -> "SCANNING PAUSED · TAP TO CONTROL"; else -> "SCANNING · TAP TO CONTROL" }
        return tv("◉   $t", 14f, OK, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.07f
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = gd(Color.parseColor("#10291C"), 17, Color.parseColor("#3E9865"))
            setOnClickListener { scanControl() }
        }
    }

    private fun clock(h: Int): String {
        val x = ((h % 24) + 24) % 24
        val h12 = if (x % 12 == 0) 12 else x % 12
        return "$h12 " + (if (x < 12) "AM" else "PM")
    }

    private fun hourShort(h: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12" + (if (h < 12) "AM" else "PM")
    }

    private fun blockLabel(h: Int): String {
        val e = (h + 2) % 24
        val sh = if (h % 12 == 0) 12 else h % 12
        val eh = if (e % 12 == 0) 12 else e % 12
        val hAm = h < 12
        val eAm = e < 12
        return if (hAm == eAm) "$sh–$eh " + (if (hAm) "AM" else "PM")
        else "$sh " + (if (hAm) "AM" else "PM") + "–$eh " + (if (eAm) "AM" else "PM")
    }

    private fun max0(x: Double) = if (x < 0) 0.0 else x

    private fun summaryRow(t: String, sub: String, v: String): View {
        val w = col()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(14)) }
        val l = col()
        l.addView(tv(t, 13f, INK, true))
        l.addView(tv(sub, 10f, MUT))
        r.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(tv(v, 15f, INK, true))
        w.addView(r)
        w.addView(lineV(), LinearLayout.LayoutParams(MATCH, dp(1)))
        return w
    }

    // ---------- screens ----------

    private var grp = 0
    private var hsub = 0
    private var hview = 0
    private var iper = 0
    private var allAreas = false

    private fun home(p: LinearLayout) {
        val all = Store.all(this)
        val start = dayStart(System.currentTimeMillis()).timeInMillis
        val rows = all.filter { it.id >= start }.sortedByDescending { it.id }
        val a = agg(rows)
        val goal = cfg().getFloat("goal", 150f).toDouble()
        val pct = if (goal > 0) (a.fare / goal * 100).coerceIn(0.0, 100.0) else 0.0
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        p.addView(h1(if (hr < 12) "Good morning, Rider." else if (hr < 18) "Good afternoon, Rider." else "Good evening, Rider."))
        p.addView(subT("Here’s your shift at a glance."), mtb(0, 20))
        p.addView(hero {
            addView(eyebrow("Today's gross target"))
            addView(money(m(a.fare), "/ RM${goal.toInt()}"))
            addView(twin("${pct.toInt()}% completed", m(max0(goal - a.fare)) + " to go"))
            addView(track(pct))
        })
        p.addView(statsRow(listOf("${a.n}" to "ORDERS", f1(a.km) + " km" to "DISTANCE", (if (a.n > 0) m(a.fare / a.n) else "RM0.00") to "AVG / ORDER")))
        val offers = rows.filter { it.decision == "" }
        p.addView(sectionHead("Order management", pillT("${offers.size} NEW OFFER${if (offers.size == 1) "" else "S"}")))
        p.addView(pills(listOf("New offers", "In progress", "Completed", "Rejected"), grp) { grp = it; render() })
        when (grp) {
            0 -> {
                p.addView(sectionHead("Latest order", linkT("ALL ORDERS →") { tab = 1; hsub = 0; hview = 1; render() }))
                val o = offers.firstOrNull()
                p.addView(if (o != null) orderCard(o, 0) else emptyCard("No new offers right now."))
            }
            else -> {
                val code = when (grp) { 1 -> "A"; 2 -> "T"; else -> "S" }
                val list = rows.filter { it.decision == code }
                if (list.isEmpty()) p.addView(emptyCard(when (grp) { 1 -> "No orders in progress yet."; 2 -> "No completed orders yet."; else -> "No rejected offers yet." }))
                else list.forEach { p.addView(orderCard(it, grp)) }
            }
        }
        p.addView(scanButton(), mtb(19, 0))
        p.addView(sectionHead("Shift snapshot", pillT("LIVE")))
        val cnt = rows.filter { isCounted(it) }
        if (cnt.isEmpty()) {
            p.addView(summaryRow("Best area today", "No accepted orders yet", "-"))
            p.addView(summaryRow("Best earning window", "No accepted orders yet", "-"))
        } else {
            val best = cnt.groupBy { it.area }.maxByOrNull { e -> e.value.sumOf { it.fare } }!!
            p.addView(summaryRow("Best area today", best.key, m(best.value.sumOf { it.fare })))
            val byH = DoubleArray(24)
            cnt.forEach { byH[Calendar.getInstance().apply { timeInMillis = it.id }.get(Calendar.HOUR_OF_DAY)] += it.fare }
            var bh = 0
            var bv = -1.0
            for (h in 0..22) if (byH[h] + byH[h + 1] > bv) { bv = byH[h] + byH[h + 1]; bh = h }
            p.addView(summaryRow("Best earning window", blockLabel(bh).replace("–", " – "), m(bv)))
        }
    }

    private fun detailText(o: Rec): String {
        val today = dayStart(System.currentTimeMillis())
        val ys = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        val t = SimpleDateFormat("h:mm a", Locale.US).format(Date(o.id))
        val head = when {
            o.id >= today.timeInMillis -> "$t · Today"
            o.id >= ys -> "Yesterday · $t"
            else -> SimpleDateFormat("d MMM", Locale.US).format(Date(o.id)) + " · $t"
        }
        return if (o.decision == "S") head + " · ${o.area} · " + (when (o.word) { "REJECT" -> "Low fare"; "CONSIDER" -> "Borderline"; else -> "Rejected by you" })
        else "$head · ${o.area} · ${f1(o.km)} km"
    }

    private fun activityTitle(o: Rec): String {
        if (o.decision == "S") return "Skipped order"
        val st = parseStops(o.stops)
        val pk = st.count { it.collect }
        return if (pk > 1) "Batch · $pk pickups / ${st.size - pk} drops" else if (o.decision == "") "Pending order" else "Single delivery"
    }

    private fun activityRow(o: Rec): View {
        val w = col()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(14)) }
        val l = col()
        l.addView(tv(activityTitle(o), 12f, INK, true))
        l.addView(mutedT(detailText(o)))
        r.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(10) })
        when {
            isCounted(o) -> r.addView(tv("+RM" + f2(o.fare), 12f, OK, true))
            o.decision == "S" -> r.addView(tv("SKIPPED", 10f, BAD, true))
            else -> r.addView(tv("PENDING", 10f, MUT, true))
        }
        r.setOnClickListener { editOrder(o) }
        w.addView(r)
        w.addView(lineV(), LinearLayout.LayoutParams(MATCH, dp(1)))
        return w
    }

    private class TrendBars(ctx: Context, val vals: List<Double>, val labels: List<String>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = resources.displayMetrics.density
        override fun onDraw(c: Canvas) {
            val n = vals.size
            if (n == 0) return
            val mx = (vals.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
            val colW = width.toFloat() / n
            val bw = minOf(colW * 0.65f, 27 * d)
            val base = height - 13 * d
            val plot = base - 15 * d
            p.textAlign = Paint.Align.CENTER
            for (i in 0 until n) {
                val cx = colW * i + colW / 2
                val h = maxOf((vals[i] / mx).toFloat() * plot, 5 * d)
                p.shader = android.graphics.LinearGradient(0f, base - h, 0f, base, 0xFF79EFAD.toInt(), 0xFF258B59.toInt(), android.graphics.Shader.TileMode.CLAMP)
                c.drawRoundRect(cx - bw / 2, base - h, cx + bw / 2, base, 6 * d, 6 * d, p)
                p.shader = null
                p.textSize = 8 * d
                p.color = 0xFFC6D7CC.toInt()
                p.typeface = Typeface.DEFAULT
                c.drawText("RM" + Math.round(vals[i]), cx, base - h - 5 * d, p)
                p.color = 0xFF91A39A.toInt()
                c.drawText(labels[i], cx, height - 3 * d, p)
            }
        }
    }

    private fun periodRows(k: Int): List<Rec> {
        val cur = periods(k)[0]
        return Store.all(this).filter { it.id >= cur.from && it.id < cur.to }.sortedByDescending { it.id }
    }

    private fun history(p: LinearLayout) {
        p.addView(h1("Performance"))
        p.addView(subT("Your records and rider intelligence, kept separate."))
        val sw = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = gd(Color.parseColor("#101914"), 14, LINE)
        }
        listOf("◷   HISTORY", "⌖   INSIGHTS").forEachIndexed { i, s ->
            val on = i == hsub
            sw.addView(tv(s, 11f, if (on) Color.parseColor("#8BF0B4") else MUT, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.03f
                setPadding(dp(8), dp(11), dp(8), dp(11))
                if (on) background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#1B5638"), Color.parseColor("#17432E"))).apply {
                    cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#34734E"))
                }
                setOnClickListener { hsub = i; render() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 0) marginEnd = dp(3) else marginStart = dp(3) })
        }
        p.addView(sw, mtb(16, 20))
        if (hsub == 0) historyRecords(p) else insights(p)
    }

    private fun historyRecords(p: LinearLayout) {
        val rows = periodRows(per)
        val taken = rows.filter { isCounted(it) }
        val g = taken.sumOf { it.fare }
        val km = taken.sumOf { it.km }
        val n = taken.size
        val goal = cfg().getFloat("goal", 150f).toDouble()
        val days = when (per) { 0 -> 1; 1 -> 7; else -> Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH) }
        val target = goal * days
        val pct = if (target > 0) (g / target * 100).coerceIn(0.0, 100.0) else 0.0
        var onlineMs = 0L
        taken.groupBy { dayStart(it.id).timeInMillis }.values.forEach { d -> if (d.size > 1) onlineMs += d.maxOf { it.id } - d.minOf { it.id } }
        val hrs = onlineMs / 3600000.0
        val hrsTxt = if (hrs >= 0.02) "${hrs.toInt()}h " + ((hrs - hrs.toInt()) * 60).toInt().toString().padStart(2, '0') + "m" else "-"

        p.addView(eyebrow("Earnings overview"))
        p.addView(pills(listOf("Daily", "Weekly", "Monthly"), per) { per = it; render() })
        p.addView(hero {
            addView(eyebrow(listOf("Today's gross earnings", "This week's gross earnings", "This month's gross earnings")[per]))
            addView(money(m(g), ""))
            addView(twin("$n completed orders", f1(km) + " km travelled"))
            addView(track(pct))
            addView(twin("${pct.toInt()}% of RM${target.toInt()} target", (if (n > 0) m(g / n) else "RM0.00") + " / order"))
        })
        p.addView(statsRow(listOf(hrsTxt to "ONLINE TIME", (if (hrs >= 0.25) m(g / hrs) else "-") to "GROSS / HOUR", (if (km > 0) m(g / km) else "-") to "GROSS / KM")))

        val vals = ArrayList<Double>()
        val labs = ArrayList<String>()
        val caption: String
        when (per) {
            0 -> {
                val byH = DoubleArray(24)
                taken.forEach { byH[Calendar.getInstance().apply { timeInMillis = it.id }.get(Calendar.HOUR_OF_DAY)] += it.fare }
                val first = byH.indexOfFirst { it > 0 }
                val last = byH.indexOfLast { it > 0 }
                if (first >= 0) {
                    val lo = minOf(first, (last - 4).coerceAtLeast(0))
                    val hi = maxOf(last, minOf(lo + 4, 23))
                    for (h in lo..hi) { vals.add(byH[h]); labs.add(hourShort(h)) }
                }
                caption = "TODAY · BY HOUR"
            }
            1 -> {
                val byD = DoubleArray(7)
                taken.forEach { byD[(Calendar.getInstance().apply { timeInMillis = it.id }.get(Calendar.DAY_OF_WEEK) + 5) % 7] += it.fare }
                if (n > 0) byD.forEachIndexed { i, v -> vals.add(v); labs.add(listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")[i]) }
                caption = "MON – SUN · BY DAY"
            }
            else -> {
                val byW = DoubleArray(5)
                taken.forEach { byW[((Calendar.getInstance().apply { timeInMillis = it.id }.get(Calendar.DAY_OF_MONTH) - 1) / 7).coerceAtMost(4)] += it.fare }
                val nowW = ((Calendar.getInstance().get(Calendar.DAY_OF_MONTH) - 1) / 7).coerceAtMost(4)
                if (n > 0) for (i in 0..nowW) { vals.add(byW[i]); labs.add("W${i + 1}") }
                caption = "THIS MONTH · BY WEEK"
            }
        }
        p.addView(sectionHead("Earnings trend", mutedT(caption)))
        if (vals.isEmpty()) p.addView(emptyCard("No completed orders in this period yet."), mtb(10, 23))
        else {
            val box = FrameLayout(this).apply {
                setPadding(dp(8), dp(12), dp(8), dp(4))
                background = gd(Color.parseColor("#111A16"), 16, LINE)
            }
            box.addView(TrendBars(this, vals, labs), FrameLayout.LayoutParams(MATCH, MATCH))
            p.addView(box, LinearLayout.LayoutParams(MATCH, dp(140)).apply { topMargin = dp(10); bottomMargin = dp(23) })
        }

        val shown = if (hview == 0) rows.take(4) else rows
        p.addView(sectionHead("Order history", pillT(if (hview == 0) "${minOf(4, rows.size)} RECENT RECORDS" else "${rows.size} TOTAL RECORDS")))
        val vs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("RECENT", "ALL HISTORY ${rows.size}").forEachIndexed { i, s ->
            val on = i == hview
            vs.addView(tv(s, 10f, if (on) OK else MUT, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.05f
                setPadding(dp(10), dp(11), dp(10), dp(11))
                background = gd(Color.parseColor(if (on) "#153624" else "#111A15"), 11, Color.parseColor(if (on) "#34734E" else "#2A3831"))
                setOnClickListener { hview = i; render() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 0) marginEnd = dp(4) else marginStart = dp(4) })
        }
        p.addView(vs, mtb(0, 8))
        if (shown.isEmpty()) p.addView(emptyCard("No order records for this period yet."))
        else shown.forEach { p.addView(activityRow(it)) }
        p.addView(noteT("Calculated from orders saved on this phone. Tap a record to edit or delete it. Online time is the span between your first and last order each day."), mtb(20, 0))
    }

    private fun kpi(label: String, value: String, sub: String): View = col().apply {
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#17221E"), Color.parseColor("#101613"))).apply {
            cornerRadius = dp(15).toFloat(); setStroke(dp(1), LINE)
        }
        addView(tv(label.uppercase(Locale.US), 9f, MUT, true).apply { letterSpacing = 0.1f })
        addView(tv(value, 21f, INK, true).apply { letterSpacing = -0.03f; maxLines = 1; setPadding(0, dp(5), 0, dp(2)) })
        addView(tv(sub, 10f, MUT))
    }

    private fun insightCard(icon: String, title: String, detail: String): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(13), 0, dp(13)) }
        r.addView(tv(icon, 17f, OK).apply { gravity = Gravity.CENTER; background = gd(Color.parseColor("#173323"), 11) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        val c = col().apply { setPadding(dp(11), 0, 0, 0) }
        c.addView(tv(title, 12f, INK, true))
        c.addView(tv(detail, 10f, Color.parseColor("#A3B2A9")).apply { setLineSpacing(0f, 1.3f); setPadding(0, dp(4), 0, 0) })
        r.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        val w = col()
        w.addView(r)
        w.addView(lineV(), LinearLayout.LayoutParams(MATCH, dp(1)))
        return w
    }

    private fun insights(p: LinearLayout) {
        val rows = periodRows(iper).filter { isCounted(it) }
        val g = rows.sumOf { it.fare }
        val km = rows.sumOf { it.km }
        var onlineMs = 0L
        rows.groupBy { dayStart(it.id).timeInMillis }.values.forEach { d -> if (d.size > 1) onlineMs += d.maxOf { it.id } - d.minOf { it.id } }
        val hrs = onlineMs / 3600000.0
        val byArea = rows.groupBy { it.area }.map { (k, v) -> Triple(k, v, v.sumOf { it.fare } / v.sumOf { it.km }.coerceAtLeast(0.01)) }.sortedByDescending { it.third }

        // time windows
        val cells = ArrayList<Pair<String, Double>>()
        fun cal(r: Rec) = Calendar.getInstance().apply { timeInMillis = r.id }
        when (iper) {
            0 -> {
                val byH = DoubleArray(24)
                rows.forEach { byH[cal(it).get(Calendar.HOUR_OF_DAY)] += it.fare }
                (0..22 step 2).map { it to byH[it] + byH[it + 1] }.filter { it.second > 0 }.sortedByDescending { it.second }.take(4).sortedBy { it.first }
                    .forEach { cells.add(blockLabel(it.first) to it.second) }
            }
            1 -> {
                val d = DoubleArray(7)
                rows.forEach { d[(cal(it).get(Calendar.DAY_OF_WEEK) + 5) % 7] += it.fare }
                cells.add("Mon–Tue" to d[0] + d[1]); cells.add("Wed–Thu" to d[2] + d[3]); cells.add("Fri" to d[4]); cells.add("Sat–Sun" to d[5] + d[6])
            }
            else -> {
                val w = DoubleArray(4)
                rows.forEach { w[((cal(it).get(Calendar.DAY_OF_MONTH) - 1) / 7).coerceAtMost(3)] += it.fare }
                for (i in 0..3) cells.add("Week ${i + 1}" to w[i])
            }
        }
        val peak = cells.maxByOrNull { it.second }?.takeIf { it.second > 0 }
        val top = byArea.firstOrNull()

        p.addView(eyebrow("Rider intelligence"))
        p.addView(tv("Know where to wait, when demand performs best and how efficiently you earn.", 13f, MUT).apply { setPadding(0, dp(5), 0, dp(10)) })
        p.addView(pills(listOf("Daily", "Weekly", "Monthly"), iper) { iper = it; render() })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun krow(a: View, b: View) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(5) })
            r.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(5) })
            grid.addView(r, mtb(0, 10))
        }
        krow(kpi("Top hotspot", top?.first ?: "-", if (top != null) "RM${f2(top.third)} earned / km" else "No data yet"),
            kpi("Peak time", peak?.first ?: "-", if (peak != null) m(peak.second) + " gross" else "No data yet"))
        krow(kpi("Gross / hour", if (hrs >= 0.25) m(g / hrs) else "-", "Online-time efficiency"),
            kpi("Gross / km", if (km > 0) m(g / km) else "-", "Distance efficiency"))
        p.addView(grid, mtb(5, 12))

        p.addView(sectionHead("Area hotspots", pillT("RANKED BY RM / KM")))
        if (byArea.isEmpty()) p.addView(emptyCard("No accepted orders in this period yet."))
        else byArea.take(5).forEachIndexed { i, (name, list, rate) ->
            val w = col()
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(13), 0, dp(13)) }
            r.addView(tv(String.format(Locale.US, "%02d", i + 1), 10f, OK, true).apply { gravity = Gravity.CENTER; background = gd(Color.parseColor("#173323"), 8) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            val l = col().apply { setPadding(dp(9), 0, 0, 0) }
            l.addView(tv(name, 12f, INK, true))
            l.addView(tv("${list.size} order${if (list.size == 1) "" else "s"} · ${f1(list.sumOf { it.km })} km", 9f, MUT).apply { setPadding(0, dp(3), 0, 0) })
            r.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f))
            val sc = col().apply { gravity = Gravity.END }
            sc.addView(tv("RM" + f2(rate), 12f, OK, true).apply { gravity = Gravity.END })
            sc.addView(tv("per km", 9f, MUT).apply { gravity = Gravity.END })
            r.addView(sc)
            w.addView(r)
            w.addView(lineV(), LinearLayout.LayoutParams(MATCH, dp(1)))
            p.addView(w)
        }

        p.addView(sectionHead("Best earning windows", mutedT("GROSS BY TIME")))
        if (cells.isEmpty()) p.addView(emptyCard("No accepted orders in this period yet."))
        else {
            val tg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cells.forEachIndexed { i, (label, v) ->
                val pk = peak != null && v == peak.second
                val c = col().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(11), dp(5), dp(11))
                    background = gd(Color.parseColor(if (pk) "#19432C" else "#15221B"), 11, Color.parseColor(if (pk) "#4CAA72" else "#2B4938"))
                }
                c.addView(tv(label, 11f, INK, true).apply { gravity = Gravity.CENTER })
                c.addView(tv(if (v > 0) m(v).removeSuffix(".00") else "RM0", 9f, Color.parseColor("#A3B2A9")).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
                tg.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(7) })
            }
            p.addView(tg, mtb(12, 8))
        }

        p.addView(sectionHead("Rider efficiency", pillT("PERFORMANCE SIGNALS")))
        p.addView(insightCard("↗", "Balance distance and time", "A high RM/km order can still produce a weak hourly return when waiting or traffic time is long. Compare both measures before choosing an area."))
        p.addView(insightCard("⌖", "Choose hotspots with consistency", "Use completed orders, gross per kilometre and online time together. One strong order does not necessarily make an area a reliable hotspot."))
        val best = rows.groupBy { it.area }.maxByOrNull { e -> e.value.sumOf { it.fare } }
        val rec = col().apply {
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#183B29"), Color.parseColor("#111D17"))).apply {
                cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.parseColor("#2E6848"))
            }
        }
        rec.addView(tv("RIDER TAKEAWAY", 12f, INK, true))
        rec.addView(tv(if (top == null || best == null) "Accept a few orders and your hotspot and timing takeaways will appear here."
        else "Your data suggests ${top.first} has the highest gross per kilometre, while ${best.key} has the strongest total gross." +
            (if (peak != null) " ${peak.first} is the strongest earning block in this period." else ""),
            11f, Color.parseColor("#C2D0C8")).apply { setLineSpacing(0f, 1.35f); setPadding(0, dp(7), 0, 0) })
        p.addView(rec, mtb(15, 0))
        p.addView(noteT("Insights are calculated from the orders saved on this phone. Accuracy improves as you save more orders."), mtb(20, 0))
    }

    private fun settingRow(title: String, sub: String, right: View?): View {
        val w = col()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(15), 0, dp(15)) }
        val l = col()
        l.addView(tv(title, 12f, INK, true))
        if (sub.isNotEmpty()) l.addView(tv(sub, 10f, MUT).apply { setPadding(0, dp(3), 0, 0) })
        r.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(12) })
        if (right != null) r.addView(right)
        w.addView(r)
        w.addView(lineV(), LinearLayout.LayoutParams(MATCH, dp(1)))
        return w
    }

    private fun switchV(on: Boolean, change: (Boolean) -> Unit): View {
        val f = FrameLayout(this)
        var st = on
        val thumb = View(this)
        f.addView(thumb, FrameLayout.LayoutParams(dp(17), dp(17)))
        fun paint() {
            f.background = gd(Color.parseColor(if (st) "#245D3D" else "#344139"), 20)
            thumb.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(if (st) "#75EFAA" else "#A6B4AC")) }
            (thumb.layoutParams as FrameLayout.LayoutParams).apply { topMargin = dp(3); marginStart = dp(if (st) 20 else 3) }
            thumb.requestLayout()
        }
        paint()
        f.setOnClickListener { st = !st; paint(); change(st) }
        f.layoutParams = LinearLayout.LayoutParams(dp(40), dp(23))
        return f
    }

    private fun sensName(i: Int) = listOf("Relaxed", "Balanced", "Strict")[i]

    private fun settings(p: LinearLayout) {
        val c = cfg()
        p.addView(h1("Settings"))
        p.addView(subT("Configure how 4Riders works for your shift."))

        p.addView(sectionHead("Earnings target", null))
        val tc = cardBox()
        tc.addView(tv("DAILY GROSS TARGET (RM)", 10f, MUT, true))
        val et = android.widget.EditText(this).apply {
            setText(c.getFloat("goal", 150f).toInt().toString())
            textSize = 15f
            setTextColor(INK)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = gd(Color.parseColor("#0D1511"), 11, Color.parseColor("#34443B"))
        }
        tc.addView(et, mtb(6, 0))
        tc.addView(btn("SAVE TARGET", true) {
            val v = (et.text.toString().replace(',', '.').toFloatOrNull() ?: 150f).coerceAtLeast(1f)
            c.edit().putFloat("goal", v).apply()
            toast("Daily target saved: RM" + f2(v.toDouble()))
            render()
        }, mtb(12, 0))
        p.addView(tc)

        p.addView(sectionHead("Order checker", null))
        val sens = c.getInt("sens", 1)
        p.addView(settingRow("Decision sensitivity", listOf("Relaxed · accept more orders", "Balanced · avoid rejecting too many orders", "Strict · only high-value orders")[sens],
            tv(sensName(sens), 13f, INK).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = gd(Color.parseColor("#0D1511"), 11, Color.parseColor("#34443B"))
                minWidth = dp(125)
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle("Decision sensitivity")
                        .setSingleChoiceItems(arrayOf("Relaxed", "Balanced", "Strict"), sens) { d, which ->
                            val (rj, ok) = when (which) { 0 -> 0.7f to 1.0f; 2 -> 1.1f to 1.5f; else -> 0.9f to 1.2f }
                            c.edit().putInt("sens", which).putFloat("rej", rj).putFloat("ok", ok).apply()
                            d.dismiss(); render()
                        }.show()
                }
            }))
        p.addView(settingRow("Show RM per kilometre", "Display fare efficiency on order cards", switchV(cfgBool("showrpk")) { c.edit().putBoolean("showrpk", it).apply() }))
        p.addView(settingRow("Show estimated delivery time", "Include time estimate in order review", switchV(cfgBool("showeta")) { c.edit().putBoolean("showeta", it).apply() }))

        p.addView(sectionHead("Scanner", null))
        val running = OverlayService.running
        p.addView(settingRow("Floating scanner control", "Show scan control over other apps",
            switchV(running && !OverlayService.hidden) { on ->
                when {
                    !running -> { if (on) startFlow() }
                    else -> { startService(Intent(this, OverlayService::class.java).setAction(if (on) OverlayService.ACTION_SHOW else OverlayService.ACTION_HIDE)); OverlayService.hidden = !on }
                }
                content.postDelayed({ render() }, 300)
            }))
        val perm = Settings.canDrawOverlays(this)
        p.addView(settingRow("Scanner permissions", "Overlay and screen access",
            linkT(if (perm) "ALLOWED ✓" else "CONFIGURE →") { if (!perm) openOverlaySettings() else toast("Overlay permission is already allowed") }))

        p.addView(sectionHead("App", null))
        p.addView(settingRow("About 4Riders", "Order scanner for Grab riders · v1.3", pillT("LIVE")))
        p.addView(btn("CLEAR ALL HISTORY", false, true) {
            android.app.AlertDialog.Builder(this).setMessage("Delete every saved order on this phone?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete") { _, _ -> Store.clear(this); toast("History cleared"); render() }.show()
        }, mtb(20, 0))
    }

    private fun wordFor(fare: Double, rpk: Double): String {
        val c = cfg()
        return when {
            fare < c.getFloat("min", 4f) -> "REJECT"
            rpk < c.getFloat("rej", 0.9f) -> "REJECT"
            rpk < c.getFloat("ok", 1.2f) -> "CONSIDER"
            else -> "ACCEPT"
        }
    }

    private fun input(v: String, decimal: Boolean) = android.widget.EditText(this).apply {
        setText(v)
        textSize = 17f
        setTextColor(INK)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        if (decimal) inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSelectAllOnFocus(true)
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#19251F")); setStroke(dp(1), LINE) }
    }

    /** Edit, delete or add one order in the history. o == null adds a new one. */
    private fun editOrder(o: Rec?) {
        var dec = o?.decision ?: "A"
        var shift = 0
        val baseId = o?.id ?: System.currentTimeMillis()
        val box = col().apply { setPadding(dp(20), dp(18), dp(20), dp(8)) }
        box.addView(tv(if (o == null) "Add an order" else "Edit order", 20f, INK, true))
        box.addView(lab("Area"), mt(14))
        val fArea = input(o?.area ?: "", false)
        box.addView(fArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) })
        val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val c1 = col(); c1.addView(lab("Fare (RM)")); val fFare = input(if (o != null) f2(o.fare) else "", true); c1.addView(fFare, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) })
        val c2 = col(); c2.addView(lab("Distance (km)")); val fKm = input(if (o != null) f1(o.km) else "", true); c2.addView(fKm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(4) })
        two.addView(c1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        two.addView(c2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        box.addView(two, mt(12))
        box.addView(lab("Date"), mt(12))
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dateTv = mono("", 15f).apply { gravity = Gravity.CENTER }
        fun showDate() { dateTv.text = SimpleDateFormat("EEE d MMM, h:mm a", Locale.US).format(Date(baseId + shift * 86400000L)) }
        fun stepB(s: String, d: Int) = Button(this).apply {
            text = s; textSize = 20f; isAllCaps = false; setTextColor(BLUE); stateListAnimator = null
            minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(SOFTBG) }
            setOnClickListener { shift += d; showDate() }
        }
        dateRow.addView(stepB("−", -1), LinearLayout.LayoutParams(dp(44), dp(44)))
        dateRow.addView(dateTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dateRow.addView(stepB("+", 1), LinearLayout.LayoutParams(dp(44), dp(44)))
        box.addView(dateRow, mt(4))
        showDate()
        box.addView(lab("What you did"), mt(12))
        val segHost = LinearLayout(this)
        fun drawSeg() {
            segHost.removeAllViews()
            val idx = when (dec) { "T" -> 0; "A" -> 1; "S" -> 2; else -> 3 }
            segHost.addView(seg(listOf("Done", "Active", "Rejected", "New"), idx) { dec = listOf("T", "A", "S", "")[it]; drawSeg() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        drawSeg()
        box.addView(segHost, mt(4))
        val dlg = android.app.AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).create()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, bg: Int, fg: Int, stroke: Int, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 16f; setTextColor(fg); setTypeface(typeface, Typeface.BOLD); stateListAnimator = null
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(bg); if (stroke != 0) setStroke(dp(2), stroke) }
            setOnClickListener { f() }
        }
        row.addView(btn("Cancel", BG, INK, INK) { dlg.dismiss() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
        row.addView(btn("Save", INK, BG, 0) {
            val fare = fFare.text.toString().replace(',', '.').toDoubleOrNull()
            val km = fKm.text.toString().replace(',', '.').toDoubleOrNull()
            if (fare == null || km == null || km <= 0 || fare < 0) { toast("Type the fare and distance as numbers"); return@btn }
            var id = baseId + shift * 86400000L
            if (id != o?.id) while (Store.get(this, id) != null) id++
            val rpk = fare / km
            val area = fArea.text.toString().trim().ifEmpty { "Unknown" }
            if (o != null && id != o.id) Store.delete(this, o.id)
            Store.upsert(this, Rec(id, wordFor(fare, rpk), rpk, fare, km, area, dec, o?.stops.orEmpty()))
            dlg.dismiss()
            render()
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        box.addView(row, mt(16))
        if (o != null) {
            box.addView(smallLink("Delete this order") {
                android.app.AlertDialog.Builder(this).setMessage("Delete this order from your history?")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Delete") { _, _ -> Store.delete(this, o.id); dlg.dismiss(); render() }.show()
            }.apply { setTextColor(BAD) })
        }
        dlg.show()
    }

    private fun bump(key: String, d: Float, min: Float) {
        val c = cfg()
        val def = when (key) { "min" -> 4f; "rej" -> 0.9f; "ok" -> 1.2f; "fuel" -> 0.14f; else -> 200f }
        val v = Math.round((c.getFloat(key, def) + d) * 100f) / 100f
        c.edit().putFloat(key, if (v < min) min else v).apply()
        render()
    }

    // ---------- small views ----------

    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun mono(t: String, size: Float, color: Int = INK) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun lab(t: String) = tv(t.uppercase(Locale.US), 11.5f, MUT, true).apply { letterSpacing = 0.08f }

    private fun sectionLab(t: String) = lab(t)

    private fun hair() = View(this).apply { setBackgroundColor(LINE) }
    private fun hair2() = View(this).apply { setBackgroundColor(LINE2); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)) }

    private fun mt(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun big(v: String, small: String): View = TextView(this).apply {
        val sb = android.text.SpannableStringBuilder(v)
        val n = sb.length
        if (small.isNotEmpty()) {
            sb.append("  ").append(small)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.34f), n, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(MUT), n, sb.length, 0)
        }
        text = sb
        textSize = 38f
        setTextColor(INK)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun progress(pct: Double): View {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(LINE2) }
        f.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(OK) } },
            FrameLayout.LayoutParams(0, dp(12)))
        f.post {
            val w = (f.width * pct / 100.0).toInt()
            (f.getChildAt(0).layoutParams as FrameLayout.LayoutParams).width = w
            f.getChildAt(0).requestLayout()
        }
        f.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
        return f
    }

    private fun split(l: String, r: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        row.addView(tv(l, 13f, MUT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(tv(r, 13f, MUT, true))
        return row
    }

    private fun splitBar(a: Int, b: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(GREYBAR) }
        if (a > 0) row.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(OK) } }, LinearLayout.LayoutParams(0, dp(12), a.toFloat()))
        if (b > 0) row.addView(View(this), LinearLayout.LayoutParams(0, dp(12), b.toFloat()))
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)).apply { topMargin = dp(8) }
        return row
    }

    private fun kp(items: List<Pair<String, String>>): View {
        val w = col()
        w.addView(hair(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (k, v) ->
            val c = col().apply { setPadding(if (i == 0) 0 else dp(12), dp(10), 0, dp(10)) }
            c.addView(lab(k))
            c.addView(mono(v, 18f))
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i < items.size - 1) r.addView(hair(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        w.addView(r)
        w.addView(hair(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return w
    }

    private fun sec(l: String, r: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, dp(6)) }
        row.addView(lab(l), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (r.isNotEmpty()) row.addView(lab(r))
        return row
    }

    private fun thead(a: String, b: String, c: String): View {
        val w = col()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        r.addView(lab(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        r.addView(lab(b).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(lab(c).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        w.addView(r)
        w.addView(hair(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return w
    }

    private fun thead4(a: String, b: String, c: String, d: String): View {
        val w = col()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        r.addView(lab(a), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
        r.addView(lab(b).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(lab(c).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(lab(d).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        w.addView(r)
        w.addView(hair(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return w
    }

    private fun tag(word: String): View {
        val col = when (word) { "ACCEPT" -> OK; "REJECT" -> BAD; else -> MID }
        return tv(word, 10.5f, if (word == "CONSIDER") INK else BG, true).apply {
            letterSpacing = 0.06f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(col) }
        }
    }

    private fun orderRow(p: LinearLayout, o: Rec, withDate: Boolean = false) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
        val l = col()
        l.addView(tv(o.area, 14.5f, INK, true))
        val d = Date(o.id)
        val whenTxt = SimpleDateFormat(if (withDate && o.id < dayStart(System.currentTimeMillis()).timeInMillis) "EEE d, h:mm a" else "h:mm a", Locale.US).format(d)
        val sb = android.text.SpannableStringBuilder("$whenTxt · ${f1(o.km)} km · ")
        val st = when (o.decision) { "T" -> "Taken"; "S" -> "Skipped"; else -> "Not logged" }
        val n = sb.length
        sb.append(st)
        sb.setSpan(android.text.style.ForegroundColorSpan(if (o.decision == "T") OK else MUT), n, sb.length, 0)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), n, sb.length, 0)
        l.addView(TextView(this).apply { text = sb; textSize = 12.5f; setTextColor(MUT) })
        r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        val m = col().apply { gravity = Gravity.END }
        m.addView(tag(o.word))
        m.addView(mono(f2(o.rpk), 14.5f).apply { setPadding(0, dp(3), 0, 0) })
        r.addView(m, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { })
        r.addView(mono(f2(o.fare), 14.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        r.setOnClickListener { editOrder(o) }
        p.addView(r)
        p.addView(hair2())
    }

    private fun areaList(p: LinearLayout, list: List<Triple<String, Int, Double>>) {
        val mx = list.maxOfOrNull { it.second } ?: 1
        val tot = list.sumOf { it.second }
        list.forEachIndexed { i, a ->
            val w = col().apply { setPadding(0, dp(8), 0, dp(8)) }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(mono("${i + 1}", 13f, MUT), LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.addView(tv(a.first, 15f, INK, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(mono("${a.second}", 15f))
            w.addView(r)
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            bar.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(LINE2) }
            bar.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(if (i == 0) OK else BLUE) } }, LinearLayout.LayoutParams(0, dp(8), a.second.toFloat()))
            if (mx > a.second) bar.addView(View(this), LinearLayout.LayoutParams(0, dp(8), (mx - a.second).toFloat()))
            w.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(4); marginStart = dp(24) })
            val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(24), dp(3), 0, 0) }
            sub.addView(tv("${Math.round(a.second * 100f / tot)}% of orders", 12.5f, MUT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sub.addView(tv("avg ${f2(a.third)} RM/km", 12.5f, MUT))
            w.addView(sub)
            p.addView(w)
            p.addView(hair2())
        }
    }

    private fun seg(opts: List<String>, sel: Int, on: (Int) -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(LINE2) }
        }
        opts.forEachIndexed { i, s ->
            r.addView(tv(s, 13.5f, INK, true).apply {
                gravity = Gravity.CENTER
                if (i == sel) background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(BG); setStroke(dp(1), LINE) }
                setOnClickListener { on(i) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        return r
    }

    private fun checkRow(t: String, good: Boolean, state: String, onTap: () -> Unit): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { onTap() } }
        r.addView(tv(if (good) "✓" else "–", 13f, BG, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (good) OK else Color.parseColor("#9AA6B8")) }
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        r.addView(tv(t, 15f, INK, true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(tv(state, 12.5f, MUT, true))
        val w = col()
        w.addView(r)
        w.addView(hair2())
        return w
    }

    private fun smallLink(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 14.5f
        setTextColor(BLUE)
        setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null
        background = null
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
        setOnClickListener { f() }
    }

    private fun stepRow(t: String, sub: String, value: String, minus: () -> Unit, plus: () -> Unit): View {
        val w = col()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val l = col()
        l.addView(tv(t, 15f, INK, true))
        l.addView(tv(sub, 12.5f, MUT))
        r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun b(s: String, f: () -> Unit) = Button(this).apply {
            text = s
            textSize = 22f
            isAllCaps = false
            setTextColor(BLUE)
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(SOFTBG) }
            setOnClickListener { f() }
        }
        r.addView(b("−", minus), LinearLayout.LayoutParams(dp(44), dp(44)))
        r.addView(mono(value, 16.5f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(b("+", plus), LinearLayout.LayoutParams(dp(44), dp(44)))
        w.addView(r)
        w.addView(hair2())
        return w
    }

    private class Bars(ctx: Context, val vals: List<Double>, val labels: List<String>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = resources.displayMetrics.density
        override fun onDraw(c: Canvas) {
            val n = vals.size
            if (n == 0) return
            val mx = (vals.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
            val gap = 5 * d
            val bw = (width - gap * (n - 1)) / n
            val base = height - 16 * d
            p.textSize = 10.5f * d
            p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            p.textAlign = Paint.Align.CENTER
            for (i in 0 until n) {
                val h = if (vals[i] <= 0) 4 * d else ((vals[i] / mx).toFloat() * (base - 6 * d)).coerceAtLeast(4 * d)
                val x = i * (bw + gap)
                p.color = if (vals[i] == mx && mx > 0) OK else GREYBAR
                c.drawRoundRect(x, base - h, x + bw, base, 4 * d, 4 * d, p)
                p.color = MUT
                c.drawText(labels[i], x + bw / 2, height - 2 * d, p)
            }
        }
    }

    // ---------- crash screen ----------

    private fun showCrash(trace: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            fitsSystemWindows = true
            setBackgroundColor(BG)
        }
        box.addView(TextView(this).apply {
            text = "The app crashed last time. Send a screenshot of this to Claude."
            textSize = 16f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
        })
        val sv = ScrollView(this)
        sv.addView(TextView(this).apply {
            text = trace.take(3500)
            textSize = 10f
            setTextColor(Color.parseColor("#B00020"))
            setPadding(0, dp(10), 0, dp(10))
        })
        box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(Button(this).apply {
            text = "Clear and open the app"
            isAllCaps = false
            setOnClickListener {
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit().clear().commit()
                recreate()
            }
        })
        setContentView(box)
    }


    // ---------- actions (unchanged) ----------

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Tap button 1 first and allow the chip")
            openOverlaySettings()
            return
        }
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 200)
            return
        }
        launchCapture()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 200) launchCapture()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startForegroundService(i)
                toast("Scan is on. Open Grab Driver.")
                moveTaskToBack(true)
            } else {
                toast("Screen recording was not allowed")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
