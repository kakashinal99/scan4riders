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
            try {
                startService(app, existing.sessionId)
            } catch (e: Exception) {
                throw e
            }
            return existing.sessionId
        }

        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.putSession(RiderSessionEntity(id, now))
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACTIVE_ID, id).apply()
        try {
            startService(app, id)
        } catch (e: Exception) {
            dao.closeSession(id, System.currentTimeMillis(), "INTERRUPTED")
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE_ID).apply()
            throw e
        }
        return id
    }

    suspend fun stop(context: Context) {
        val app = context.applicationContext
        val id = activeSessionId(app)
        app.stopService(Intent(app, LocationTrackingService::class.java).setAction(LocationTrackingService.ACTION_STOP))
        if (id != null) {
            val dao = TrackingDatabase.get(app).trackingDao()
            dao.interruptOpenTrips(id)
            dao.closeSession(id, System.currentTimeMillis(), "ENDED")
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE_ID).apply()
    }

    /** Ends the open session when Android refuses to run or restart the GPS service. */
    suspend fun markInterrupted(context: Context) {
        val app = context.applicationContext
        val id = activeSessionId(app)
        if (id != null) {
            try {
                val dao = TrackingDatabase.get(app).trackingDao()
                dao.interruptOpenTrips(id)
                dao.closeSession(id, System.currentTimeMillis(), "INTERRUPTED")
            } catch (_: Exception) { }
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
        var existing = dao.events(orderId)
        if (existing.any { it.type == type }) return
        if (type !in setOf("SCAN", "ACCEPT", "REJECT", "COMPLETE")) return

        // Preserve the state sequence even if two UI callbacks race to persist their events.
        if (type != "SCAN" && existing.none { it.type == "SCAN" }) {
            dao.addEvent(OrderEventEntity(orderId = orderId, sessionId = TrackingController.activeSessionId(context),
                type = "SCAN", occurredAt = orderId))
            existing = dao.events(orderId)
        }

        val accepted = existing.any { it.type == "ACCEPT" }
        val rejected = existing.any { it.type == "REJECT" }
        when (type) {
            "SCAN" -> if (existing.isNotEmpty()) return
            "ACCEPT" -> if (rejected || accepted) return
            "REJECT" -> if (accepted || rejected) return
            "COMPLETE" -> if (!accepted || rejected) return
        }

        val sessionId = TrackingController.activeSessionId(context)
        val location = try { Geo.lastKnown(context) } catch (_: Exception) { null }
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
            if (dao.activeTrip(orderId) == null) {
                dao.addTrip(TripSegmentEntity(orderId = orderId, sessionId = sessionId, startedAt = System.currentTimeMillis()))
            }
        } else if (type == "COMPLETE") {
            val trip = dao.activeTrip(orderId)
            if (trip != null) {
                val endedAt = System.currentTimeMillis()
                val samples = if (trip.sessionId != null) dao.locationsBetween(trip.sessionId, trip.startedAt, endedAt) else emptyList()
                val usable = samples.filter { (it.accuracyM ?: Float.MAX_VALUE) <= 50f }
                var distanceKm = 0.0
                var previous: LocationSampleEntity? = null
                for (sample in usable) {
                    val before = previous
                    if (before != null) {
                        val seconds = (sample.recordedAt - before.recordedAt) / 1000.0
                        val leg = Geo.straight(
                            Geo.P(before.latitude, before.longitude),
                            Geo.P(sample.latitude, sample.longitude)
                        )
                        if (seconds in 1.0..30.0 && leg / seconds <= 0.10) distanceKm += leg
                    }
                    previous = sample
                }
                dao.finishTrip(trip.tripId, endedAt, "COMPLETED", distanceKm)
            }
        }
    }
}
