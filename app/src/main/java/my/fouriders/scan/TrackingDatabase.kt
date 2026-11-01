package my.fouriders.scan

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Entity(tableName = "rider_sessions")
data class RiderSessionEntity(
    @PrimaryKey val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = "ACTIVE"
)

@Entity(tableName = "tracked_orders", indices = [Index("sessionId"), Index("scannedAt")])
data class TrackedOrderEntity(
    @PrimaryKey val orderId: Long,
    val sessionId: String?,
    val word: String,
    val fare: Double,
    val plannedKm: Double,
    val rmPerKm: Double,
    val area: String,
    val stops: String,
    val scannedAt: Long,
    val decision: String
)

@Entity(tableName = "order_events", indices = [Index("orderId"), Index("sessionId"), Index("occurredAt")])
data class OrderEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val orderId: Long,
    val sessionId: String?,
    val type: String,
    val occurredAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null
)

@Entity(tableName = "location_samples", indices = [Index("sessionId"), Index("recordedAt")])
data class LocationSampleEntity(
    @PrimaryKey(autoGenerate = true) val sampleId: Long = 0,
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float?,
    val provider: String?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val altitudeM: Double?,
    val recordedAt: Long,
    val elapsedRealtimeNanos: Long
)

@Entity(tableName = "trip_segments", indices = [Index("orderId"), Index("sessionId")])
data class TripSegmentEntity(
    @PrimaryKey(autoGenerate = true) val tripId: Long = 0,
    val orderId: Long,
    val sessionId: String?,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: String = "ACTIVE",
    val distanceKm: Double = 0.0
)

@Dao
interface TrackingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(session: RiderSessionEntity)

    @Query("SELECT * FROM rider_sessions WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeSession(): RiderSessionEntity?

    @Query("UPDATE rider_sessions SET endedAt = :endedAt, status = :status WHERE sessionId = :sessionId")
    suspend fun closeSession(sessionId: String, endedAt: Long, status: String = "ENDED")

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOrder(order: TrackedOrderEntity)

    @Query("SELECT * FROM tracked_orders WHERE orderId = :id LIMIT 1")
    suspend fun order(id: Long): TrackedOrderEntity?

    @Insert
    suspend fun addEvent(event: OrderEventEntity)

    @Query("SELECT * FROM order_events WHERE orderId = :orderId ORDER BY occurredAt")
    suspend fun events(orderId: Long): List<OrderEventEntity>

    @Insert
    suspend fun addLocation(sample: LocationSampleEntity)

    @Query("SELECT * FROM location_samples WHERE sessionId = :sessionId ORDER BY recordedAt")
    suspend fun locations(sessionId: String): List<LocationSampleEntity>

    @Insert
    suspend fun addTrip(trip: TripSegmentEntity): Long

    @Query("SELECT * FROM trip_segments WHERE orderId = :orderId AND status = 'ACTIVE' LIMIT 1")
    suspend fun activeTrip(orderId: Long): TripSegmentEntity?

    @Query("UPDATE trip_segments SET completedAt = :completedAt, status = :status, distanceKm = :distanceKm WHERE tripId = :tripId")
    suspend fun finishTrip(tripId: Long, completedAt: Long, status: String, distanceKm: Double)

    @Query("UPDATE trip_segments SET status = 'INTERRUPTED' WHERE status = 'ACTIVE'")
    suspend fun interruptOpenTrips()

    @Query("UPDATE rider_sessions SET status = 'INTERRUPTED', endedAt = :endedAt WHERE status = 'ACTIVE'")
    suspend fun interruptOpenSessions()
}

@Database(
    entities = [
        RiderSessionEntity::class,
        TrackedOrderEntity::class,
        OrderEventEntity::class,
        LocationSampleEntity::class,
        TripSegmentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun trackingDao(): TrackingDao

    companion object {
        @Volatile private var INSTANCE: TrackingDatabase? = null

        fun get(context: Context): TrackingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrackingDatabase::class.java,
                    "4riders_tracking.db"
                ).build().also { INSTANCE = it }
            }
    }
}

/** One-time, lossless import of the existing pipe-delimited order history. */
object TrackingLegacyImport {
    suspend fun run(context: Context) {
        val prefs = context.getSharedPreferences("tracking_import", Context.MODE_PRIVATE)
        if (prefs.getBoolean("v1_done", false)) return

        val oldRows = context.getSharedPreferences("orders", Context.MODE_PRIVATE)
            .getString("rows", "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val p = line.split("|")
                if (p.size < 7) null else try {
                    TrackedOrderEntity(
                        orderId = p[0].toLong(),
                        sessionId = null,
                        word = p[1],
                        rmPerKm = p[2].toDouble(),
                        fare = p[3].toDouble(),
                        plannedKm = p[4].toDouble(),
                        decision = p[5],
                        area = p[6],
                        stops = p.getOrNull(7).orEmpty(),
                        scannedAt = p[0].toLong()
                    )
                } catch (_: Exception) { null }
            }.toList()

        val db = TrackingDatabase.get(context)
        db.withTransaction {
            oldRows.forEach { db.trackingDao().putOrder(it) }
        }
        prefs.edit().putBoolean("v1_done", true).apply()
    }
}
