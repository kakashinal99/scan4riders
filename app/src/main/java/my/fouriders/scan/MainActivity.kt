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
        private val BG = Color.parseColor("#0C1110")
        private val INK = Color.parseColor("#F4F7F5")
        private val MUT = Color.parseColor("#8E9B95")
        private val LINE = Color.parseColor("#27322D")
        private val LINE2 = Color.parseColor("#1B2420")
        private val SOFTBG = Color.parseColor("#19251F")
        private val BLUE = Color.parseColor("#65E6A2")
        private val OK = Color.parseColor("#65E6A2")
        private val MID = Color.parseColor("#F2C66D")
        private val BAD = Color.parseColor("#FF7D83")
        private val WARNT = Color.parseColor("#352A16")
        private val GREYBAR = Color.parseColor("#3B5145")
        // New Today design (prototype)
        private val PBORD = Color.parseColor("#39734F")
        private val PPILL = Color.parseColor("#11251A")
        private val PLINE = Color.parseColor("#29372F")
        private val PMUT = Color.parseColor("#91A098")
        private val PTEXT = Color.parseColor("#F3F7F4")
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

    private fun buildUi() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        content = ScrollView(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(PLINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#0B100E")) }
        root.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        setContentView(root)
        render()
    }

    private fun render() {
        val y = content.scrollY
        navRow.removeAllViews()
        listOf("Today" to "⌂", "Orders" to "▣", "History" to "◷", "Settings" to "⚙").forEachIndexed { i, (name, icon) ->
            val on = i == tab
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { tab = i; content.scrollTo(0, 0); render() }
            }
            cell.addView(tv(icon, 18f, if (on) OK else PMUT, false).apply { gravity = Gravity.CENTER })
            cell.addView(tv(name, 10f, if (on) OK else PMUT, false).apply { gravity = Gravity.CENTER })
            navRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        content.removeAllViews()
        val page = col().apply { setPadding(dp(16), dp(16), dp(16), dp(24)) }
        when (tab) {
            0 -> today(page)
            1 -> orders(page)
            2 -> history(page)
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
        val t = rows.filter { it.decision == "T" }
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

    // ---------- screens ----------

    private fun header(p: LinearLayout, title: String, sub: String) {
        val state = when {
            !OverlayService.running -> Triple("Scanner off", BAD, false)
            OverlayService.hidden -> Triple("Scanner hidden", MID, false)
            else -> Triple("Scanner on", OK, true)
        }
        val composeHeader = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                RidersHeader(title, sub, state.first, ComposeColor(state.second))
            }
        }
        p.addView(composeHeader, mt(4))
    }

    private fun eyebrow(t: String) = tv(t.uppercase(Locale.US), 10f, PMUT, true).apply { letterSpacing = 0.12f }

    private fun pill(t: String, fg: Int = OK, bg: Int = PPILL, stroke: Int = PBORD, onTap: (() -> Unit)? = null) =
        tv(t, 10f, fg, true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(bg); setStroke(dp(1), stroke) }
            if (onTap != null) setOnClickListener { onTap() }
        }

    private fun pSection(title: String, link: String, onLink: () -> Unit): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(22), 0, dp(12)) }
        r.addView(tv(title, 14f, PTEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(tv(link, 10f, OK, true).apply { setOnClickListener { onLink() } })
        return r
    }

    private fun today(p: LinearLayout) {
        val all = Store.all(this)
        val start = dayStart(System.currentTimeMillis()).timeInMillis
        val rows = all.filter { it.id >= start }
        val a = agg(rows)
        val goal = cfg().getFloat("goal", 200f).toDouble()
        val now = Calendar.getInstance()
        val hr = now.get(Calendar.HOUR_OF_DAY)

        // top: brand + scanner state
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bl = col()
        bl.addView(TextView(this).apply {
            val sb = android.text.SpannableStringBuilder("4Riders")
            sb.setSpan(android.text.style.ForegroundColorSpan(OK), 1, 2, 0)
            text = sb; textSize = 25f; setTextColor(PTEXT); setTypeface(typeface, Typeface.BOLD)
        })
        bl.addView(tv("RIDER COMPANION", 12f, PMUT))
        top.addView(bl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val stateTxt = when { !OverlayService.running -> "● OFF"; OverlayService.hidden -> "● HIDDEN"; else -> "● READY" }
        top.addView(pill(stateTxt))
        p.addView(top)

        // scan button
        val running = OverlayService.running
        val scanTxt = when { !running -> "◉ START SCAN"; OverlayService.hidden -> "◉ SHOW CHIP"; else -> "◉ HIDE CHIP" }
        val scanRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        scanRow.addView(pill(scanTxt) {
            when {
                !running -> startFlow()
                OverlayService.hidden -> { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW)); OverlayService.hidden = false; render() }
                else -> { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE)); OverlayService.hidden = true; render() }
            }
        })
        p.addView(scanRow, mt(10))

        p.addView(eyebrow(SimpleDateFormat("EEEE · d MMMM", Locale.US).format(Date())), mt(16))
        p.addView(tv(if (hr < 12) "Good morning." else if (hr < 18) "Good afternoon." else "Good evening.", 27f, PTEXT, true), mt(6))
        p.addView(tv("Your shift at a glance.", 12f, PMUT))

        // target
        val pct = if (goal > 0) (a.fare / goal * 100).coerceIn(0.0, 100.0) else 0.0
        p.addView(View(this).apply { setBackgroundColor(PLINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(22) })
        val tg = col().apply { setPadding(0, dp(18), 0, dp(18)) }
        tg.addView(eyebrow("Today's target"))
        tg.addView(TextView(this).apply {
            val v = "RM " + f2(a.fare)
            val sb = android.text.SpannableStringBuilder(v)
            val n = sb.length
            sb.append(" / RM " + f2(goal))
            sb.setSpan(android.text.style.RelativeSizeSpan(0.36f), n, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(PMUT), n, sb.length, 0)
            text = sb; textSize = 36f; setTextColor(PTEXT); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
        })
        val track = FrameLayout(this)
        track.background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(PLINE) }
        track.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#53D18D"), Color.parseColor("#B9F26F"))).apply { cornerRadius = dp(20).toFloat() }
        }, FrameLayout.LayoutParams(0, dp(7)))
        track.post {
            (track.getChildAt(0).layoutParams as FrameLayout.LayoutParams).width = (track.width * pct / 100.0).toInt()
            track.getChildAt(0).requestLayout()
        }
        tg.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(13); bottomMargin = dp(8) })
        val bt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bt.addView(tv("${pct.toInt()}% completed", 11f, PMUT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bt.addView(tv("RM " + f2(max0(goal - a.fare)) + " remaining", 11f, PMUT))
        tg.addView(bt)
        p.addView(tg)
        p.addView(View(this).apply { setBackgroundColor(PLINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // stats
        val st = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("ORDERS" to "${a.n}", "DISTANCE" to f1(a.km) + " km", "AVG / ORDER" to (if (a.n > 0) "RM " + f2(a.fare / a.n) else "-")).forEachIndexed { i, (k, v) ->
            val c = col().apply { setPadding(if (i == 0) 0 else dp(9), 0, dp(9), 0) }
            c.addView(tv(k, 15f, PMUT))
            c.addView(tv(v, 19f, PTEXT, true).apply { setPadding(0, dp(4), 0, 0) })
            st.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i < 2) st.addView(View(this).apply { setBackgroundColor(PLINE) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        p.addView(st, mt(22))

        // latest order
        p.addView(pSection("Latest order detected", "VIEW ALL") { tab = 1; render() })
        val o = rows.maxByOrNull { it.id }
        if (o == null) {
            p.addView(tv("No orders scanned yet today. Open Grab, tap the chip, then Take or Skip.", 14f, PMUT))
        } else {
            val card = col().apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#14211A"), Color.parseColor("#101714"))).apply {
                    cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.parseColor("#34483B"))
                }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val hl = col()
            hl.addView(eyebrow("Scanned order · ${o.area}"))
            hl.addView(tv("RM " + f2(o.fare), 29f, PTEXT, true).apply { setPadding(0, dp(4), 0, 0) })
            head.addView(hl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(when (o.word) {
                "ACCEPT" -> pill("GOOD VALUE")
                "CONSIDER" -> pill("FAIR", MID, Color.parseColor("#3B2D16"), Color.parseColor("#66512A"))
                else -> pill("LOW VALUE", BAD, Color.parseColor("#3A1A1D"), Color.parseColor("#6B2E33"))
            })
            card.addView(head)

            val route = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            route.addView(View(this).apply { setBackgroundColor(Color.parseColor("#597765")) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            val sc = col().apply { setPadding(dp(15), 0, 0, 0) }
            sc.addView(tv("Pickup · ${o.area}", 12f, PTEXT, true))
            sc.addView(tv("${f1(o.km)} km total route", 11f, PMUT).apply { setPadding(0, dp(2), 0, 0) })
            route.addView(sc)
            card.addView(route, mt(16))

            val mins = Math.round(o.km / 24.0 * 60).toInt().coerceAtLeast(1)
            card.addView(View(this).apply { setBackgroundColor(PLINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(16) })
            val mt2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            listOf("↗ ${f1(o.km)} km total", "◷ $mins min est.", "RM ${f2(o.rpk)}/km").forEach {
                mt2.addView(tv(it, 10f, PMUT).apply { setPadding(0, 0, dp(14), 0) })
            }
            card.addView(mt2)

            if (o.decision == "") {
                val ac = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun act(t: String, fg: Int, bg: android.graphics.drawable.Drawable, dec: String, w: Float) = Button(this).apply {
                    text = t; isAllCaps = false; textSize = 14f; setTextColor(fg); setTypeface(typeface, Typeface.BOLD); stateListAnimator = null
                    background = bg
                    setOnClickListener { Store.upsert(this@MainActivity, o.copy(decision = dec)); render() }
                }
                ac.addView(act("SKIP", Color.parseColor("#E0E8E2"), GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#28322D")) }, "S", 1f),
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
                ac.addView(act("AMBIL ORDER", Color.parseColor("#07110B"),
                    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#65DC9A"), Color.parseColor("#B8EF70"))).apply { cornerRadius = dp(12).toFloat() }, "T", 1f),
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
                card.addView(ac, mt(15))
            } else {
                val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                lr.addView(tv("Logged as " + (if (o.decision == "T") "Taken" else "Skipped"), 13f, PTEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                lr.addView(tv("UNDO", 11f, OK, true).apply {
                    setPadding(dp(8), dp(12), 0, dp(12))
                    setOnClickListener { Store.upsert(this@MainActivity, o.copy(decision = "")); render() }
                })
                card.addView(lr, mt(8))
            }
            p.addView(card)
        }

        // shift snapshot
        p.addView(pSection("Shift snapshot", "DETAILS") { tab = 2; render() })
        fun snap(l: String, v: String, top: Int) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(tv(l, 11f, PMUT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(tv(v, 15f, PTEXT, true))
            p.addView(r, mt(top))
        }
        snap("Gross earnings", "RM " + f2(a.fare), 0)
        val left = max0(goal - a.fare)
        snap("Orders to target (est.)", if (a.n == 0) "-" else if (left <= 0) "Done" else "${Math.ceil(left / (a.fare / a.n)).toInt()} more", 10)
    }

    private fun max0(x: Double) = if (x < 0) 0.0 else x

    private fun hourName(h: Int): String {
        val x = h % 12
        return (if (x == 0) 12 else x).toString() + (if (h < 12) " AM" else " PM")
    }

    private fun orders(p: LinearLayout) {
        val all = Store.all(this).sortedByDescending { it.id }
        val rows = when (filt) { 1 -> all.filter { it.decision == "T" }; 2 -> all.filter { it.decision != "T" }; else -> all }.take(100)
        header(p, "Orders", "Latest ${rows.size} · RM " + f2(rows.filter { it.decision == "T" }.sumOf { it.fare }) + " taken")
        p.addView(seg(listOf("All", "Taken", "Skipped"), filt) { filt = it; render() }, mt(12))
        p.addView(Button(this).apply {
            text = "+ Add an order"
            isAllCaps = false
            textSize = 15f
            setTextColor(BLUE)
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(BG); setStroke(dp(2), BLUE) }
            setOnClickListener { editOrder(null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        p.addView(tv("Tap an order to edit or delete it.", 12.5f, MUT), mt(6))
        if (rows.isEmpty()) p.addView(tv("Nothing here yet.", 14f, MUT), mt(16))
        else {
            p.addView(thead("Time · Area", "RM/km", "Fare"), mt(10))
            rows.forEach { orderRow(p, it, true) }
        }
    }

    private fun history(p: LinearLayout) {
        val all = Store.all(this)
        val ps = periods(per)
        header(p, "History", "Totals count taken orders")
        p.addView(seg(listOf("Daily", "Weekly", "Monthly"), per) { per = it; render() }, mt(12))
        val inRange = all.filter { it.id >= ps.last().from }
        val per2 = ps.map { pr -> pr to agg(all.filter { it.id >= pr.from && it.id < pr.to }) }
        val tot = Agg(per2.sumOf { it.second.n }, per2.sumOf { it.second.fare }, per2.sumOf { it.second.km })
        p.addView(lab(listOf("Last 7 days", "Last 6 weeks", "Last 6 months")[per]), mt(14))
        p.addView(big("RM " + f2(tot.fare), ""))
        p.addView(kp(listOf("Orders" to "${tot.n}", "Distance" to "${Math.round(tot.km)} km", "RM / km" to (if (tot.km > 0) f2(tot.fare / tot.km) else "-"))), mt(12))
        val asc = per2.reversed()
        val mx = asc.maxOfOrNull { it.second.fare } ?: 0.0
        if (mx > 0) {
            val best = asc.first { it.second.fare == mx }.first.label
            p.addView(sec("Earned", "Best: $best"))
            p.addView(Bars(this, asc.map { it.second.fare }, asc.map { it.first.label.replace("This week", "Now").replace(" ", "").take(5) }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        }
        p.addView(thead4("Period", "Orders", "RM/km", "Earned"), mt(14))
        per2.forEach { (pr, g) ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
            val c = col()
            c.addView(tv(pr.label, 14.5f, INK, true))
            if (pr.sub.isNotEmpty()) c.addView(tv(pr.sub, 12.5f, MUT, false))
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
            r.addView(mono("${g.n}", 14.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(mono(if (g.km > 0) f2(g.fare / g.km) else "-", 14.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(mono(f2(g.fare), 14.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            p.addView(r)
            p.addView(hair2())
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(4)) }
        t.addView(tv("Total", 15f, INK, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
        t.addView(mono("${tot.n}", 14.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        t.addView(mono(if (tot.km > 0) f2(tot.fare / tot.km) else "-", 14.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        t.addView(mono(f2(tot.fare), 14.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        p.addView(t)

        p.addView(sec("Where the orders are", listOf("last 7 days", "last 6 weeks", "last 6 months")[per]))
        if (inRange.isEmpty()) p.addView(tv("No scans in this period yet.", 14f, MUT))
        else {
            val ar = areas(inRange)
            areaList(p, ar.take(8))
            p.addView(tv("Counts every order you scanned in that area, Taken or Skipped, so you can see demand. The area comes from the pickup address.", 12.5f, MUT), mt(8))
        }
    }

    private fun settings(p: LinearLayout) {
        val c = cfg()
        header(p, "Settings", "Scanner and limits")
        val on = Settings.canDrawOverlays(this)
        p.addView(sectionLab("Scanner"), mt(16))
        val running = OverlayService.running
        val status = when { !running -> "Off"; OverlayService.hidden -> "Hidden"; else -> "Ready" }
        val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(10)) }
        val sc = col()
        sc.addView(mono(status, 26f))
        sc.addView(tv(when { !running -> "Start it, then open Grab."; OverlayService.hidden -> "Show it again, no new permission needed."; else -> "Open Route details and tap the chip." }, 13f, MUT, false))
        sr.addView(sc, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sr.addView(Button(this).apply {
            text = when { !running -> "Start scan"; OverlayService.hidden -> "Show chip"; else -> "Hide chip" }
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            if (running && !OverlayService.hidden) {
                setTextColor(INK)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(BG); setStroke(dp(2), INK) }
            } else {
                setTextColor(BG)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(INK) }
            }
            setOnClickListener {
                when {
                    !running -> startFlow()
                    OverlayService.hidden -> { startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW)); OverlayService.hidden = false; render() }
                    else -> { startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE)); OverlayService.hidden = true; render() }
                }
            }
        }, LinearLayout.LayoutParams(dp(132), dp(50)))
        p.addView(sr)
        p.addView(hair2())
        val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        p.addView(checkRow("Show over other apps", on, if (on) "Allowed" else "Tap to allow") { if (!on) openOverlaySettings() })
        p.addView(checkRow("Location", loc, if (loc) "Allowed" else "Asked on Start") { })
        p.addView(checkRow("Screen capture", running, if (running) "Running" else "Asked on Start") { })
        if (running) {
            p.addView(smallLink("Stop scanner completely") {
                startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                toast("Stopped")
                content.postDelayed({ render() }, 500)
            })
        }

        p.addView(sectionLab("Verdict limits"), mt(14))
        p.addView(stepRow("Minimum fare", "Under this is always REJECT", "RM " + f1(c.getFloat("min", 4f).toDouble()), { bump("min", -0.5f, 0f) }, { bump("min", 0.5f, 0f) }))
        p.addView(stepRow("Reject below", "RM per km", f2(c.getFloat("rej", 0.9f).toDouble()), { bump("rej", -0.05f, 0.1f) }, { bump("rej", 0.05f, 0.1f) }))
        p.addView(stepRow("Accept from", "RM per km", f2(c.getFloat("ok", 1.2f).toDouble()), { bump("ok", -0.05f, 0.1f) }, { bump("ok", 0.05f, 0.1f) }))
        p.addView(sectionLab("Costs and goal"), mt(14))
        p.addView(stepRow("Fuel cost", "RM per km, estimate", f2(c.getFloat("fuel", 0.14f).toDouble()), { bump("fuel", -0.01f, 0f) }, { bump("fuel", 0.01f, 0f) }))
        p.addView(stepRow("Daily target", "Shown on Today", "RM " + c.getFloat("goal", 200f).toInt(), { bump("goal", -10f, 0f) }, { bump("goal", 10f, 0f) }))
        p.addView(sectionLab("Data"), mt(14))
        p.addView(tv("Your orders are saved on this phone only. Sync with the 4riders web app is not on yet.", 13f, MUT), mt(6))
        p.addView(smallLink("Clear all history") {
            Store.clear(this)
            toast("History cleared")
            render()
        })
        p.addView(tv("Take and Skip are saved in this app only. Nothing is pressed in Grab. Long press the chip to see the raw text.", 12.5f, MUT), mt(6))
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
        var dec = o?.decision ?: "T"
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
            val idx = when (dec) { "T" -> 0; "S" -> 1; else -> 2 }
            segHost.addView(seg(listOf("Taken", "Skipped", "Not logged"), idx) { dec = listOf("T", "S", "")[it]; drawSeg() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            Store.upsert(this, Rec(id, wordFor(fare, rpk), rpk, fare, km, area, dec))
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
