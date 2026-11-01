package my.fouriders.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import java.util.UUID

object TrackingController {
    private const val PREFS = "rider_tracking_state"
    private const val ACTIVE_ID = "active_session_id"

    fun activeSessionId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE_ID, null)

    fun isActive(context: Context): Boolean = activeSessionId(context) != null

    suspend fun start(context: Context): String {
        val app = context.applicationContext
        val db = TrackingDatabase.get(app)
        val dao = db.trackingDao()
        val existing = dao.activeSession()
        if (existing != null) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ACTIVE_ID, existing.sessionId).apply()
            startService(app, existing.sessionId)
            return existing.sessionId
        }

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.putSession(RiderSessionEntity(id, now))
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACTIVE_ID, id).apply()
        startService(app, id)
        return id
    }

    suspend fun stop(context: Context) {
        val app = context.applicationContext
        val id = activeSessionId(app)
        app.stopService(Intent(app, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP))
        if (id != null) {
            TrackingDatabase.get(app).trackingDao().closeSession(id, System.currentTimeMillis())
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE_ID).apply()
    }

    private fun startService(context: Context, sessionId: String) {
        val intent = Intent(context, LocationTrackingService::class.java)
            .setAction(LocationTrackingService.ACTION_START)
            .putExtra(LocationTrackingService.EXTRA_SESSION_ID, sessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

object OrderEventRecorder {
    suspend fun record(context: Context, orderId: Long, type: String, decision: String? = null) {
        val db = TrackingDatabase.get(context.applicationContext)
        val dao = db.trackingDao()
        val existing = dao.events(orderId)
        if (existing.any { it.type == type }) return

        val accepted = existing.any { it.type == "ACCEPT" }
        val rejected = existing.any { it.type == "REJECT" }
        when (type) {
            "SCAN" -> if (existing.isNotEmpty()) return
            "ACCEPT" -> if (rejected || !existing.any { it.type == "SCAN" }) return
            "REJECT" -> if (accepted || rejected || !existing.any { it.type == "SCAN" }) return
            "COMPLETE" -> if (!accepted || rejected) return
            else -> return
        }

        val sessionId = TrackingController.activeSessionId(context)
        val location = try { Geo.gps(context) } catch (_: Exception) { null }
        val accuracy = try {
            val lm = context.getSystemService(LocationManager::class.java)
            val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            l?.takeIf { System.currentTimeMillis() - it.time < 30_000L }?.accuracy
        } catch (_: Exception) { null }

        dao.addEvent(
            OrderEventEntity(
                orderId = orderId,
                sessionId = sessionId,
                type = type,
                occurredAt = System.currentTimeMillis(),
                latitude = location?.lat,
                longitude = location?.lon,
                accuracyM = accuracy
            )
        )

        if (type == "ACCEPT") {
            dao.addTrip(TripSegmentEntity(orderId = orderId, sessionId = sessionId, startedAt = System.currentTimeMillis()))
        } else if (type == "COMPLETE") {
            val trip = dao.activeTrip(orderId)
            if (trip != null) dao.finishTrip(trip.tripId, System.currentTimeMillis(), "COMPLETED", 0.0)
        }
    }
}
