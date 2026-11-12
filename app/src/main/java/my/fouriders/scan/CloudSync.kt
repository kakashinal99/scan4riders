package my.fouriders.scan

import android.content.Context
import android.content.Intent
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/** Per-rider Firestore sync. Local SharedPreferences remain the immediate source for the UI/offline use. */
object CloudSync {
    private const val ACTION_UPDATED = "my.fouriders.scan.ORDERS_UPDATED"
    private var appContext: Context? = null
    private var auth: FirebaseAuth? = null
    private var db: FirebaseFirestore? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var ordersListener: ListenerRegistration? = null
    private var activeUid: String? = null

    @Synchronized fun initialize(context: Context) {
        if (auth != null) return
        appContext = context.applicationContext
        try {
            if (FirebaseApp.getApps(context).isEmpty()) return
            val a = FirebaseAuth.getInstance()
            auth = a
            db = FirebaseFirestore.getInstance()
            val listener = FirebaseAuth.AuthStateListener { current ->
                val uid = current.currentUser?.uid
                if (uid == activeUid) return@AuthStateListener
                ordersListener?.remove()
                ordersListener = null
                activeUid = uid
                if (uid != null) syncAccount(uid)
                broadcastChanged()
            }
            authListener = listener
            a.addAuthStateListener(listener)
        } catch (_: Throwable) {
            auth = null
            db = null
        }
    }

    fun configured(): Boolean = auth != null && db != null
    fun userEmail(): String? = auth?.currentUser?.email
    fun signedIn(): Boolean = auth?.currentUser != null

    fun signIn(email: String, password: String, create: Boolean, callback: (String?) -> Unit) {
        val a = auth ?: run { callback("Firebase config is not ready"); return }
        val task = if (create) a.createUserWithEmailAndPassword(email.trim(), password)
                   else a.signInWithEmailAndPassword(email.trim(), password)
        task.addOnSuccessListener { callback(null) }
            .addOnFailureListener { e -> callback(e.localizedMessage ?: "Could not sign in") }
    }

    fun signOut() { auth?.signOut() }

    fun upsert(record: Rec) {
        val user = auth?.currentUser ?: return
        val store = db ?: return
        store.collection("users").document(user.uid).collection("orders")
            .document(record.id.toString()).set(record.toCloudMap(), SetOptions.merge())
    }

    fun delete(id: Long) {
        val user = auth?.currentUser ?: return
        db?.collection("users")?.document(user.uid)?.collection("orders")?.document(id.toString())?.delete()
    }

    fun deleteAll(ids: List<Long>) {
        val user = auth?.currentUser ?: return
        val store = db ?: return
        val collection = store.collection("users").document(user.uid).collection("orders")
        ids.distinct().chunked(450).forEach { group ->
            val batch = store.batch()
            group.forEach { batch.delete(collection.document(it.toString())) }
            batch.commit()
        }
    }

    private fun syncAccount(uid: String) {
        val context = appContext ?: return
        val collection = db?.collection("users")?.document(uid)?.collection("orders") ?: return
        // Preserve server copies when a record exists; upload phone-only history once on first sign-in.
        collection.get().addOnSuccessListener { snapshot ->
            val remoteIds = snapshot.documents.mapNotNull { it.id.toLongOrNull() }.toHashSet()
            Store.all(context).filter { it.id !in remoteIds }.forEach { record ->
                collection.document(record.id.toString()).set(record.toCloudMap(), SetOptions.merge())
            }
            snapshot.documents.mapNotNull { it.toRecord() }.forEach { Store.importRemote(context, it) }
            ordersListener?.remove()
            ordersListener = collection.addSnapshotListener { value, error ->
                if (error != null || value == null) return@addSnapshotListener
                value.documents.mapNotNull { it.toRecord() }.forEach { Store.importRemote(context, it) }
                broadcastChanged()
            }
            broadcastChanged()
        }
    }

    private fun Rec.toCloudMap(): Map<String, Any> = mapOf(
        "id" to id, "word" to word, "rpk" to rpk, "fare" to fare, "km" to km,
        "area" to area, "decision" to decision, "st" to st, "legKm" to legKm,
        "acceptedAt" to acceptedAt, "completedAt" to completedAt, "actualKm" to actualKm
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toRecord(): Rec? = try {
        val d = data ?: return null
        val idValue = (d["id"] as? Number)?.toLong() ?: id.toLongOrNull() ?: return null
        Rec(
            idValue,
            d["word"] as? String ?: "CONSIDER",
            (d["rpk"] as? Number)?.toDouble() ?: 0.0,
            (d["fare"] as? Number)?.toDouble() ?: 0.0,
            (d["km"] as? Number)?.toDouble() ?: 0.0,
            d["area"] as? String ?: "Unknown",
            d["decision"] as? String ?: "",
            d["st"] as? String ?: "",
            (d["legKm"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList(),
            (d["acceptedAt"] as? Number)?.toLong() ?: 0L,
            (d["completedAt"] as? Number)?.toLong() ?: 0L,
            (d["actualKm"] as? Number)?.toDouble() ?: 0.0
        )
    } catch (_: Throwable) { null }

    private fun broadcastChanged() {
        appContext?.sendBroadcast(Intent(ACTION_UPDATED).setPackage(appContext?.packageName))
    }
}
