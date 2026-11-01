package my.fouriders.scan

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { TrackingLegacyImport.run(this@App) } catch (_: Exception) { }
        }
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
