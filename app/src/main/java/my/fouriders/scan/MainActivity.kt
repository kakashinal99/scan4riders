package my.fouriders.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private val BG1 = Color.parseColor("#F5F7EE")
        private val BG2 = Color.parseColor("#E9F3CF")
        private val CARD = Color.parseColor("#FDFDF8")
        private val LINE = Color.parseColor("#D9E2D3")
        private val INK = Color.parseColor("#14302B")
        private val MUTED = Color.parseColor("#667A70")
        private val DEEP = Color.parseColor("#1B4A44")
        private val LIME = Color.parseColor("#C8F04D")
        private val GOOD = Color.parseColor("#2FA36B")
        private val IDLE = Color.parseColor("#9AA79F")
    }

    private lateinit var chipStatus: TextView
    private lateinit var scanStatus: TextView
    private lateinit var fMin: EditText
    private lateinit var fRej: EditText
    private lateinit var fOk: EditText

    private class Dashed(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9DB8A5")
            strokeWidth = 2f * resources.displayMetrics.density
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 6f * resources.displayMetrics.density), 0f)
        }
        override fun onDraw(c: Canvas) {
            val x = width / 2f
            c.drawLine(x, 0f, x, height.toFloat(), p)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        window.statusBarColor = BG1
        window.navigationBarColor = BG2
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            fitsSystemWindows = true
        }
        root.addView(mono("▬  RIDER TOOLS / SETUP", 12f, DEEP, true))
        root.addView(TextView(this).apply {
            text = "4RIDERS / NIGHTSHIFT"
            textSize = 36f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(mono("ORDER SCANNER  •  ROUTE + FARE VERDICT", 12f, MUTED, false).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        intro.addView(TextView(this).apply {
            text = "Follow the three steps to get your scanner ready."
            textSize = 15f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        intro.addView(mono("SETUP\nSEQUENCE", 11f, DEEP, true).apply { gravity = Gravity.END })
        root.addView(intro, lp(bottom = 16))

        // step 1
        chipStatus = statusPill()
        val b1 = bigButton("1. Allow floating chip", DEEP, Color.WHITE) { openOverlaySettings() }
        root.addView(step("01", "Enable the floating chip", chipStatus,
            "The chip stays available above your order screen.", listOf(b1), true))

        // step 2
        scanStatus = statusPill()
        val b2 = bigButton("2. Start Scan", LIME, INK) { startFlow() }
        val stop = Button(this).apply {
            text = "Stop"
            isAllCaps = false
            textSize = 16f
            setTextColor(MUTED)
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), LINE)
            }
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
                toast("Stopped")
                handlerRefresh()
            }
        }
        root.addView(step("02", "Start screen capture", scanStatus,
            "Start Scan, then allow screen recording and share your entire screen.",
            listOf(b2, stop), true, stopIsSmall = true))

        // step 3
        fMin = field(cfg.getFloat("min", 4f))
        fRej = field(cfg.getFloat("rej", 0.9f))
        fOk = field(cfg.getFloat("ok", 1.2f))
        val limits = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        limits.addView(limitRow("Minimum fare (RM)", fMin, true))
        limits.addView(limitRow("Reject below (RM per km)", fRej, true))
        limits.addView(limitRow("Accept from (RM per km)", fOk, false))
        root.addView(step("03", "Set your fare limits", null, null, listOf(limits), false))

        // how to use
        val how = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#EAF1D2"))
            }
        }
        how.addView(View(this).apply { setBackgroundColor(Color.parseColor("#A9CF2E")) }, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
        val howText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        howText.addView(mono("HOW TO USE", 12f, DEEP, true))
        howText.addView(TextView(this).apply {
            text = "Tap Start, allow screen recording and choose Share entire screen. Open the Grab order and tap the chip. If it asks you to scroll, scroll once and it reads again by itself. Long press the chip to see the raw text."
            textSize = 13.5f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        })
        how.addView(howText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(how, lp(top = 4))

        val sv = ScrollView(this)
        sv.addView(root)
        sv.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG1, BG2))
        setContentView(sv)
    }

    // ---------- UI helpers ----------

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun mono(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = 0.12f
    }

    private fun statusPill() = TextView(this).apply {
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.1f
    }

    private fun setPill(v: TextView, label: String, dot: Int) {
        val sb = SpannableStringBuilder("● $label")
        sb.setSpan(ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(if (dot == GOOD) GOOD else MUTED), 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.text = sb
    }

    private fun bigButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 20f
        setTextColor(fg)
        stateListAnimator = null
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(bg)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        setOnClickListener { onClick() }
    }

    private fun step(
        num: String, title: String, pill: TextView?, desc: String?,
        body: List<View>, line: Boolean, stopIsSmall: Boolean = false
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        left.addView(TextView(this).apply {
            text = num
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(DEEP)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F6F8EF"))
                setStroke(dp(1), Color.parseColor("#BFD0C2"))
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        if (line) left.addView(Dashed(this), LinearLayout.LayoutParams(dp(20), 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(6) })
        row.addView(left, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(CARD)
                setStroke(dp(1), LINE)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (pill != null) head.addView(pill)
        card.addView(head)
        if (desc != null) card.addView(TextView(this).apply {
            text = desc
            textSize = 14.5f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, dp(14))
        })
        body.forEachIndexed { i, v ->
            val small = stopIsSmall && i == body.size - 1 && body.size > 1
            if (v.parent == null) {
                if (small) {
                    card.addView(v, LinearLayout.LayoutParams(dp(110), dp(44)).apply {
                        topMargin = dp(10)
                        gravity = Gravity.CENTER_HORIZONTAL
                    })
                } else {
                    card.addView(v, if (v.layoutParams == null) lp() else v.layoutParams)
                }
            }
        }
        row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(context), LinearLayout.LayoutParams(1, dp(14)))
        }
    }

    private fun limitRow(label: String, f: EditText, divider: Boolean): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        r.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(f, LinearLayout.LayoutParams(dp(104), dp(46)))
        wrap.addView(r)
        if (divider) wrap.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrap
    }

    private fun field(v: Float) = EditText(this).apply {
        setText(if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        textSize = 19f
        setTextColor(INK)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(dp(12), 0, dp(14), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#F4F6EC"))
            setStroke(dp(1), LINE)
        }
    }

    // ---------- state ----------

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun handlerRefresh() {
        window.decorView.postDelayed({ refreshStatus() }, 500)
    }

    private fun refreshStatus() {
        val on = Settings.canDrawOverlays(this)
        if (on) setPill(chipStatus, "ON", GOOD) else setPill(chipStatus, "OFF", IDLE)
        when {
            OverlayService.running -> setPill(scanStatus, "RUNNING", GOOD)
            on -> setPill(scanStatus, "READY", IDLE)
            else -> setPill(scanStatus, "LOCKED", IDLE)
        }
    }

    override fun onPause() {
        super.onPause()
        saveLimits()
    }

    private fun saveLimits() {
        val e = getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
        fMin.text.toString().replace(',', '.').toFloatOrNull()?.let { e.putFloat("min", it) }
        fRej.text.toString().replace(',', '.').toFloatOrNull()?.let { e.putFloat("rej", it) }
        fOk.text.toString().replace(',', '.').toFloatOrNull()?.let { e.putFloat("ok", it) }
        e.apply()
    }

    // ---------- actions (unchanged) ----------

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startFlow() {
        saveLimits()
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
