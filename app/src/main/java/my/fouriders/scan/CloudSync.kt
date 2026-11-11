package my.fouriders.scan

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Small REST client for the Android app. Uses Supabase anonymous auth so each
 * installation gets an isolated auth.uid(); RLS keeps rider data private.
 * Network work is serialized and never blocks the scanner/UI thread.
 */
object CloudSync {
    private const val TAG = "4RidersCloud"
    private const val SESSION_PREFS = "cloud_session"
    private const val ACCESS = "access_token"
    private const val REFRESH = "refresh_token"
    private const val EXPIRES = "expires_at"
    private val worker = Executors.newSingleThreadExecutor()

    fun syncAll(context: Context, rows: List<Rec>) {
        val app = context.applicationContext
        worker.execute {
            rows.forEach { row ->
                try {
                    upsertOrder(app, row)
                } catch (e: Exception) {
                    Log.w(TAG, "Initial order sync failed for ${row.id}", e)
                }
            }
        }
    }

    fun enqueue(context: Context, row: Rec) {
        val app = context.applicationContext
        worker.execute {
            try {
                upsertOrder(app, row)
            } catch (e: Exception) {
                // Local history remains intact; next app launch retries via syncAll().
                Log.w(TAG, "Order sync deferred for ${row.id}", e)
            }
        }
    }

    @Synchronized
    private fun accessToken(context: Context): String {
        val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(ACCESS, null)
        val refresh = prefs.getString(REFRESH, null)
        val expiresAt = prefs.getLong(EXPIRES, 0L)
        if (!current.isNullOrBlank() && System.currentTimeMillis() < expiresAt - 60_000L) {
            return current
        }

        if (!refresh.isNullOrBlank()) {
            try {
                val refreshed = requestJson(
                    "POST",
                    "/auth/v1/token?grant_type=refresh_token",
                    JSONObject().put("refresh_token", refresh).toString(),
                    null
                )
                saveSession(prefs, refreshed)
                return refreshed.getString("access_token")
            } catch (e: Exception) {
                Log.w(TAG, "Session refresh failed; requesting a new anonymous session", e)
            }
        }

        val created = requestJson(
            "POST",
            "/auth/v1/signup",
            JSONObject().put("data", JSONObject().put("app", "4Riders")).toString(),
            null
        )
        saveSession(prefs, created)
        return created.getString("access_token")
    }

    private fun saveSession(
        prefs: android.content.SharedPreferences,
        response: JSONObject
    ) {
        val now = System.currentTimeMillis()
        val expiresIn = response.optLong("expires_in", 3600L)
        prefs.edit()
            .putString(ACCESS, response.getString("access_token"))
            .putString(REFRESH, response.optString("refresh_token").takeIf { it.isNotBlank() })
            .putLong(EXPIRES, now + expiresIn * 1000L)
            .apply()
    }

    private fun upsertOrder(context: Context, row: Rec) {
        val token = accessToken(context)
        val stableId = UUID.nameUUIDFromBytes(
            ("4riders-order:" + row.id).toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val status = when (row.decision) {
            "T" -> "accepted"
            "S" -> "rejected"
            else -> "detected"
        }
        val raw = JSONObject()
            .put("legacy_local_id", row.id)
            .put("verdict", row.word)
            .put("rm_per_km", row.rpk)
            .put("area_hint", row.area)

        val payload = JSONObject()
            .put("id", stableId)
            .put("source", "grab")
            .put("external_order_id", row.id.toString())
            .put("status", status)
            .put("fare_amount", row.fare)
            .put("pickup_to_dropoff_m", (row.km * 1000.0).toInt())
            .put("pickup_verified", false)
            .put("dropoff_verified", false)
            .put("scanned_at", Instant.ofEpochMilli(row.id).toString())
            .put("raw_scan", raw)

        if (row.decision == "T") {
            payload.put("accepted_at", Instant.ofEpochMilli(System.currentTimeMillis()).toString())
        }

        requestJson(
            "POST",
            "/rest/v1/orders?on_conflict=id",
            payload.toString(),
            token,
            "resolution=merge-duplicates,return=minimal"
        )
    }

    private fun requestJson(
        method: String,
        path: String,
        body: String?,
        bearer: String?,
        prefer: String? = null
    ): JSONObject {
        val connection = (URL(BuildConfig.SUPABASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            if (!bearer.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (!prefer.isNullOrBlank()) connection.setRequestProperty("Prefer", prefer)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Supabase HTTP $code: ${text.take(500)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
