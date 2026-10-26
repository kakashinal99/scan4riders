package my.fouriders.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var fMin: EditText
    private lateinit var fRej: EditText
    private lateinit var fOk: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "4riders Scan"
            textSize = 26f
            setTextColor(Color.parseColor("#0F1A2E"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Reads a Grab order, finds the km on the map and shows a verdict."
            textSize = 14f
            setTextColor(Color.parseColor("#5B6B86"))
            setPadding(0, dp(6), 0, dp(14))
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#0F1A2E"))
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)
        root.addView(button("1. Allow floating chip") { openOverlaySettings() })
        root.addView(button("2. Start Scan") { startFlow() })
        root.addView(button("Stop") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            toast("Stopped")
        })

        root.addView(TextView(this).apply {
            text = "Your limits"
            textSize = 16f
            setTextColor(Color.parseColor("#0F1A2E"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        })
        fMin = field("Minimum fare (RM)", cfg.getFloat("min", 4f))
        fRej = field("Reject below (RM per km)", cfg.getFloat("rej", 0.9f))
        fOk = field("Accept from (RM per km)", cfg.getFloat("ok", 1.2f))
        root.addView(label("Minimum fare (RM)")); root.addView(fMin)
        root.addView(label("Reject below (RM per km)")); root.addView(fRej)
        root.addView(label("Accept from (RM per km)")); root.addView(fOk)

        root.addView(TextView(this).apply {
            text = "How to use: tap Start, allow screen recording and choose Share entire screen. Open the Grab order and tap the chip. If it asks you to scroll, scroll once and it reads again by itself. Long press the chip to see the raw text."
            textSize = 13f
            setTextColor(Color.parseColor("#5B6B86"))
            setPadding(0, dp(16), 0, 0)
        })
        val sv = ScrollView(this)
        sv.addView(root)
        sv.setBackgroundColor(Color.parseColor("#F4F6FA"))
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this))
            "Floating chip permission: ON"
        else
            "Floating chip permission: OFF (tap button 1)"
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

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(Color.parseColor("#5B6B86"))
    }

    private fun field(hint: String, v: Float) = EditText(this).apply {
        setText(v.toString())
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { bottomMargin = dp(10) }
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

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
