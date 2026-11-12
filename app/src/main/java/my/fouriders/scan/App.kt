package my.fouriders.scan

import android.app.Application
import android.content.Context
import com.google.mlkit.common.MlKit
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // ML Kit's content-provider initializer is normally automatic, but the scanner
        // runs from a foreground service and some installed builds reach it before the
        // provider has populated MlKitContext. Initialize explicitly before any OCR use.
        MlKit.initialize(this)
        CloudSync.initialize(this)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                    .putString("trace", "Thread: ${t.name}\n$sw").commit()
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
