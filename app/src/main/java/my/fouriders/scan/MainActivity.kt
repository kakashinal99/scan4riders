package my.fouriders.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#F4F6FA"))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "4riders Scan"
            textSize = 26f
            setTextColor(Color.parseColor("#0F1A2E"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Stage 1 test: a floating bubble that reads the text on your screen."
            textSize = 14f
            setTextColor(Color.parseColor("#5B6B86"))
            setPadding(0, dp(6), 0, dp(18))
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#0F1A2E"))
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(status)
        root.addView(button("1. Allow floating bubble") { openOverlaySettings() })
        root.addView(button("2. Start Scan bubble") { startFlow() })
        root.addView(button("Stop") {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            toast("Stopped")
        })
        root.addView(TextView(this).apply {
            text = "How to test: tap Start, press Allow on the screen recording prompt, open the Grab Driver app on an order, tap the blue Scan bubble. The text read from the screen appears in a panel."
            textSize = 13f
            setTextColor(Color.parseColor("#5B6B86"))
            setPadding(0, dp(18), 0, 0)
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this))
            "Floating bubble permission: ON"
        else
            "Floating bubble permission: OFF (tap button 1)"
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
        if (!Settings.canDrawOverlays(this)) {
            toast("Tap button 1 first and allow the bubble")
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
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
                toast("Scan bubble is on. Open Grab Driver.")
                moveTaskToBack(true)
            } else {
                toast("Screen recording was not allowed")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
