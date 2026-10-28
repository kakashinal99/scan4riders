package my.fouriders.scan

import android.Manifest
import android.app.Activity
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

class MainActivity : Activity() {

    companion object {
        private val BG = Color.parseColor("#EEF0F4")
        private val INK = Color.parseColor("#10151F")
        private val MUT = Color.parseColor("#4A5463")
        private val LINE = Color.parseColor("#D5DAE2")
        private val LINE2 = Color.parseColor("#E4E8EE")
        private val SOFTBG = Color.parseColor("#ECEFF4")
        private val BLUE = Color.parseColor("#0B57D0")
        private val OK = Color.parseColor("#0F8A43")
        private val MID = Color.parseColor("#F29C00")
        private val BAD = Color.parseColor("#D9263A")
        private val WARNT = Color.parseColor("#FFF2D1")
        private val GREYBAR = Color.parseColor("#CDD5E2")
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
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }
        content = ScrollView(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(hair(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        setContentView(root)
        render()
    }

    private fun render() {
        val y = content.scrollY
        navRow.removeAllViews()
        listOf("Today", "Orders", "History", "Settings").forEachIndexed { i, name ->
            val on = i == tab
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { tab = i; content.scrollTo(0, 0); render() }
            }
            cell.addView(View(this).apply { setBackgroundColor(if (on) BLUE else Color.TRANSPARENT) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            cell.addView(tv(name, 13.5f, if (on) BLUE else MUT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { }, )
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
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val l = col()
        l.addView(tv(title, 24f, INK, true))
        l.addView(tv(sub, 13f, MUT, false))
        r.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = when {
            !OverlayService.running -> Triple("Scanner off", BAD, false)
            OverlayService.hidden -> Triple("Scanner hidden", MID, false)
            else -> Triple("Scanner on", OK, true)
        }
        r.addView(tv("●  " + state.first, 13f, INK, true).apply {
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE); setStroke(dp(2), INK) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        p.addView(r)
    }

    private fun today(p: LinearLayout) {
        val all = Store.all(this)
        val start = dayStart(System.currentTimeMillis()).timeInMillis
        val rows = all.filter { it.id >= start }
        val a = agg(rows)
        val fuel = a.km * cfg().getFloat("fuel", 0.14f)
        val goal = cfg().getFloat("goal", 200f).toDouble()
        val net = a.fare - fuel
        header(p, "Today", SimpleDateFormat("EEE d MMM", Locale.US).format(Date()))
        p.addView(lab("Earned"), mt(14))
        p.addView(big("RM " + f2(a.fare), "of RM ${goal.toInt()} target"))
        val pct = if (goal > 0) (a.fare / goal * 100).coerceIn(0.0, 100.0) else 0.0
        p.addView(progress(pct), mt(8))
        p.addView(split(f1(pct).substringBefore(".") + "% of target", "RM " + f2(max0(goal - a.fare)) + " to go"))
        p.addView(kp(listOf("Orders" to "${a.n}", "Distance" to f1(a.km) + " km", "RM / km" to (if (a.km > 0) f2(a.fare / a.km) else "-"))), mt(14))
        val hrs = if (rows.isEmpty()) 0.0 else (rows.maxOf { it.id } - rows.minOf { it.id }) / 3600000.0
        p.addView(kp(listOf("Fuel (est.)" to f2(fuel), "After fuel" to f2(net), "RM / hour" to (if (hrs >= 0.5) f2(net / hrs) else "-"))), mt(0))

        if (a.n > 0) {
            val byH = DoubleArray(24)
            rows.filter { it.decision == "T" }.forEach { byH[Calendar.getInstance().apply { timeInMillis = it.id }.get(Calendar.HOUR_OF_DAY)] += it.fare }
            val first = byH.indexOfFirst { it > 0 }
            val last = byH.indexOfLast { it > 0 }
            val lo = minOf(first, last - 5).coerceAtLeast(0)
            val hi = maxOf(last, lo + 5).coerceAtMost(23)
            val labels = (lo..hi).map { val h = it % 12; (if (h == 0) 12 else h).toString() }
            p.addView(sec("Earned by hour", "Best: " + hourName(byH.indices.maxByOrNull { byH[it] } ?: 0)))
            p.addView(Bars(this, (lo..hi).map { byH[it] }, labels), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        }
        if (rows.isNotEmpty()) {
            val t = a.n
            val s = rows.size - t
            p.addView(sec("Your decisions", "${rows.size} scanned"))
            p.addView(splitBar(t, s))
            p.addView(tv("Taken $t   ·   Skipped/undecided $s", 13f, MUT, true), mt(6))
            p.addView(sec("Busiest areas today", ""))
            areaList(p, areas(rows).take(4))
            p.addView(sec("Latest", ""))
            rows.sortedByDescending { it.id }.take(4).forEach { orderRow(p, it) }
        } else {
            p.addView(tv("No orders scanned yet today. Open Grab, tap the chip, then Take or Skip.", 14f, MUT), mt(18))
        }
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
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.WHITE); setStroke(dp(2), INK) }
            } else {
                setTextColor(Color.WHITE)
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
        return tv(word, 10.5f, if (word == "CONSIDER") INK else Color.WHITE, true).apply {
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
                if (i == sel) background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE); setStroke(dp(1), LINE) }
                setOnClickListener { on(i) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        return r
    }

    private fun checkRow(t: String, good: Boolean, state: String, onTap: () -> Unit): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { onTap() } }
        r.addView(tv(if (good) "✓" else "–", 13f, Color.WHITE, true).apply {
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
            setBackgroundColor(Color.WHITE)
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

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
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
