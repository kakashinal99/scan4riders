package my.fouriders.scan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocationTrackingService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "my.fouriders.scan.TRACK_START"
        const val ACTION_STOP = "my.fouriders.scan.TRACK_STOP"
        const val EXTRA_SESSION_ID = "tracking_session_id"
        private const val CHANNEL_ID = "rider_tracking"
        private const val NOTIFICATION_ID = 4102
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    @Volatile private var sessionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 throws if a location service starts without location permission, and
        // Android 12+ refuses to restart one from the background. Stop cleanly, never crash.
        if (!TrackingController.hasLocationPermission(this) || !tryStartForeground()) {
            scope.launch {
                TrackingController.markInterrupted(this@LocationTrackingService)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val requestedId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (!requestedId.isNullOrBlank()) {
            sessionId = requestedId
            requestUpdates()
        } else if (sessionId == null) {
            scope.launch {
                sessionId = TrackingDatabase.get(this@LocationTrackingService)
                    .trackingDao().activeSession()?.sessionId
                requestUpdates()
            }
        }
        return START_STICKY
    }

    private fun tryStartForeground(): Boolean = try {
        startAsForeground()
        true
    } catch (_: Exception) {
        false
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("4Riders · Rider Session active")
            .setContentText("GPS tracking is running. Turn Dashboard OFF to stop.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("MissingPermission")
    private fun requestUpdates() {
        if (sessionId.isNullOrBlank()) return
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            stopSelf()
            return
        }
        try {
            val provider = when {
                fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            } ?: return
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(provider, 5_000L, 5f, this, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: Exception) {
            // Keep the session alive; GPS may become available later.
        }
    }

    override fun onLocationChanged(location: Location) {
        val id = sessionId ?: return
        val sample = LocationSampleEntity(
            sessionId = id,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            provider = location.provider,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            recordedAt = location.time,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos
        )
        scope.launch {
            TrackingDatabase.get(this@LocationTrackingService).trackingDao().addLocation(sample)
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun stopTracking() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        sessionId = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rider session tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Persistent notification while 4Riders records an active rider session." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
