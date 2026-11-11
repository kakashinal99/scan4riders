package my.fouriders.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.google.android.gms.location.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class RiderLocationService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private val uploader = Executors.newSingleThreadExecutor()
    @Volatile private var accessToken = ""
    @Volatile private var userId = ""
    private var lastCloudWriteAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification())
        client = LocationServices.getFusedLocationProviderClient(this)
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                // Persist/upload is performed by the map bridge while foreground; native service
                // remains alive independently of WebView visibility.
                val intent = Intent(ACTION_LOCATION).apply {
                    setPackage(packageName)
                    putExtra("lat", location.latitude)
                    putExtra("lon", location.longitude)
                    putExtra("accuracy", location.accuracy)
                    putExtra("bearing", location.bearing)
                }
                sendBroadcast(intent)
                val now = System.currentTimeMillis()
                if (accessToken.isNotBlank() && userId.isNotBlank() && now - lastCloudWriteAt >= 15000L) {
                    lastCloudWriteAt = now
                    val token = accessToken
                    val riderId = userId
                    val lat = location.latitude
                    val lon = location.longitude
                    val accuracy = location.accuracy
                    val speed = location.speed
                    val heading = location.bearing
                    uploader.execute { uploadGpsPoint(token, riderId, lat, lon, accuracy, speed, heading) }
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2500L)
            .build()
        try { client.requestLocationUpdates(request, callback, mainLooper) } catch (_: SecurityException) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        accessToken = intent?.getStringExtra(EXTRA_ACCESS_TOKEN) ?: accessToken
        userId = intent?.getStringExtra(EXTRA_USER_ID) ?: userId
        return START_STICKY
    }

    private fun uploadGpsPoint(token: String, riderId: String, lat: Double, lon: Double, accuracy: Float, speed: Float, heading: Float) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(BuildConfig.SUPABASE_URL + "/rest/v1/gps_points").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "return=minimal")
            }
            val body = JSONObject().apply {
                put("user_id", riderId)
                put("latitude", lat)
                put("longitude", lon)
                put("accuracy_m", accuracy.toDouble())
                put("speed_mps", speed.toDouble())
                put("heading_deg", heading.toDouble())
            }.toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) android.util.Log.w("4RidersGPS", "Cloud GPS upload failed: HTTP $code")
        } catch (error: Exception) {
            android.util.Log.w("4RidersGPS", "Cloud GPS upload unavailable", error)
        } finally {
            connection?.disconnect()
        }
    }

    override fun onDestroy() {
        if (::client.isInitialized && ::callback.isInitialized) client.removeLocationUpdates(callback)
        uploader.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "4Riders live tracking", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("4Riders tracking active")
            .setContentText("Rider location is being updated while the master scanner is ON")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_LOCATION = "my.fouriders.scan.LOCATION"
        const val EXTRA_ACCESS_TOKEN = "cloud_access_token"
        const val EXTRA_USER_ID = "cloud_user_id"
        private const val CHANNEL_ID = "fouriders_location"
        private const val NOTIFICATION_ID = 414
    }
}
