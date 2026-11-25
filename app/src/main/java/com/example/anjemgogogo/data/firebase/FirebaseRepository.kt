package com.example.anjemgogogo.data.firebase

import android.content.Context
import com.example.anjemgogogo.data.model.ChatMessage
import com.example.anjemgogogo.data.model.DriverLocation
import com.example.anjemgogogo.data.model.OrderModel
import com.example.anjemgogogo.data.model.UserProfile
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Query
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

/**
 * Central repository for Firebase Authentication & Firebase Realtime Database operations.
 */
object FirebaseRepository {

    fun interface ListenerHandle {
        fun remove()
    }

    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }

    private val usersRef: DatabaseReference get() = database.getReference("users")
    private val ordersRef: DatabaseReference get() = database.getReference("orders")
    private val driverLocationsRef: DatabaseReference get() = database.getReference("driver_locations")
    private val chatsRef: DatabaseReference get() = database.getReference("chats")

    fun currentUser(): FirebaseUser? = auth.currentUser

    fun currentUid(): String? = auth.currentUser?.uid

    fun isUsingDummyConfig(): Boolean {
        return try {
            FirebaseApp.getInstance().options.projectId.equals("dummy-project", ignoreCase = true)
        } catch (e: Exception) {
            true
        }
    }

    fun getGoogleWebClientId(context: Context): String? {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId == 0) return null
        val clientId = context.getString(resId)
        return clientId.takeIf { it.isNotBlank() && !it.contains("dummy", ignoreCase = true) }
    }

    // ==================== AUTHENTICATION ====================

    fun signInWithEmail(
        email: String,
        password: String,
        onResult: (Result<FirebaseUser>) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    ensureUserProfile(user) {
                        onResult(Result.success(user))
                    }
                } else {
                    onResult(Result.failure(IllegalStateException("User tidak ditemukan.")))
                }
            }
            .addOnFailureListener { e ->
                onResult(Result.failure(e))
            }
    }

    fun registerWithEmail(
        name: String,
        email: String,
        password: String,
        onResult: (Result<FirebaseUser>) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(name)
                        .build()
                    user.updateProfile(profileUpdates)
                        .addOnCompleteListener {
                            ensureUserProfile(user, overrideName = name) {
                                onResult(Result.success(user))
                            }
                        }
                } else {
                    onResult(Result.failure(IllegalStateException("Gagal membuat akun.")))
                }
            }
            .addOnFailureListener { e ->
                onResult(Result.failure(e))
            }
    }

    fun signInWithGoogleIdToken(
        idToken: String,
        onResult: (Result<FirebaseUser>) -> Unit
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    ensureUserProfile(user) {
                        onResult(Result.success(user))
                    }
                } else {
                    onResult(Result.failure(IllegalStateException("Autentikasi Google gagal.")))
                }
            }
            .addOnFailureListener { e ->
                onResult(Result.failure(e))
            }
    }

    fun ensureUserProfile(
        user: FirebaseUser,
        overrideName: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val userRef = usersRef.child(user.uid)
        userRef.get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    val displayName = overrideName?.takeIf { it.isNotBlank() }
                        ?: user.displayName?.takeIf { it.isNotBlank() }
                        ?: user.email?.substringBefore("@")
                        ?: "Mahasiswa UNNES"

                    val profile = UserProfile(
                        uid = user.uid,
                        name = displayName,
                        email = user.email.orEmpty(),
                        photoUrl = user.photoUrl?.toString().orEmpty(),
                        phone = user.phoneNumber?.takeIf { it.isNotBlank() } ?: "-",
                        nimOrFaculty = "Mahasiswa UNNES",
                        balance = 45000L,
                        role = "customer",
                        createdAt = System.currentTimeMillis()
                    )
                    userRef.setValue(profile).addOnCompleteListener { onComplete?.invoke() }
                } else {
                    val updates = mutableMapOf<String, Any>()
                    if (!user.email.isNullOrBlank()) updates["email"] = user.email!!
                    if (user.photoUrl != null) updates["photoUrl"] = user.photoUrl.toString()
                    if (!overrideName.isNullOrBlank()) updates["name"] = overrideName
                    if (updates.isNotEmpty()) {
                        userRef.updateChildren(updates).addOnCompleteListener { onComplete?.invoke() }
                    } else {
                        onComplete?.invoke()
                    }
                }
            }
            .addOnFailureListener {
                onComplete?.invoke()
            }
    }

    fun signOut() {
        auth.signOut()
    }

    // ==================== USER PROFILE & BALANCE ====================

    fun observeUserProfile(
        uid: String,
        onUpdate: (UserProfile?) -> Unit
    ): ListenerHandle {
        val ref = usersRef.child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onUpdate(snapshot.getValue(UserProfile::class.java))
            }

            override fun onCancelled(error: DatabaseError) {
                // Keep UI resilient on permission/network interruption
            }
        }
        ref.addValueEventListener(listener)
        return ListenerHandle { ref.removeEventListener(listener) }
    }

    fun updateUserFields(
        uid: String,
        updates: Map<String, Any>,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        usersRef.child(uid).updateChildren(updates)
            .addOnSuccessListener { onComplete?.invoke(true, null) }
            .addOnFailureListener { e -> onComplete?.invoke(false, e.localizedMessage) }
    }

    fun adjustBalance(
        uid: String,
        delta: Long,
        onResult: ((Result<Long>) -> Unit)? = null
    ) {
        val balanceRef = usersRef.child(uid).child("balance")
        balanceRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val current = currentData.getValue(Long::class.java) ?: 45000L
                val updated = (current + delta).coerceAtLeast(0L)
                currentData.value = updated
                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                if (error != null) {
                    onResult?.invoke(Result.failure(error.toException()))
                } else {
                    val newBal = currentData?.getValue(Long::class.java) ?: 0L
                    onResult?.invoke(Result.success(newBal))
                }
            }
        })
    }

    // ==================== ORDERS & HISTORY ====================

    fun createOrder(
        order: OrderModel,
        onResult: (Result<OrderModel>) -> Unit
    ) {
        if (isUsingDummyConfig()) {
            onResult(Result.failure(IllegalStateException("File app/google-services.json masih dummy. Ganti dengan google-services.json asli dari Firebase Console.")))
            return
        }
        val key = ordersRef.push().key
        if (key == null) {
            onResult(Result.failure(IllegalStateException("Gagal membuat ID pesanan.")))
            return
        }
        val now = System.currentTimeMillis()
        val finalOrder = order.copy(
            orderId = key,
            createdAt = if (order.createdAt == 0L) now else order.createdAt,
            updatedAt = now
        )
        var completed = false
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!completed) {
                completed = true
                onResult(Result.failure(IllegalStateException("Koneksi ke Firebase Realtime Database timeout. Periksa internet & Rules Firebase Anda.")))
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 6000L)

        ordersRef.child(key).setValue(finalOrder)
            .addOnSuccessListener {
                if (!completed) {
                    completed = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    onResult(Result.success(finalOrder))
                }
            }
            .addOnFailureListener { e ->
                if (!completed) {
                    completed = true
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    onResult(Result.failure(e))
                }
            }
    }

    fun updateOrderStatus(orderId: String, status: String) {
        if (orderId.isBlank()) return
        val updates = mapOf<String, Any>(
            "status" to status,
            "updatedAt" to System.currentTimeMillis()
        )
        ordersRef.child(orderId).updateChildren(updates)
    }

    fun observeOrder(
        orderId: String,
        onUpdate: (OrderModel?) -> Unit
    ): ListenerHandle {
        val ref = ordersRef.child(orderId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onUpdate(snapshot.getValue(OrderModel::class.java))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return ListenerHandle { ref.removeEventListener(listener) }
    }

    fun observeUserOrders(
        uid: String,
        onUpdate: (List<OrderModel>) -> Unit
    ): ListenerHandle {
        val query: Query = ordersRef.orderByChild("customerId").equalTo(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { it.getValue(OrderModel::class.java) }
                    .sortedByDescending { it.createdAt }
                onUpdate(list)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        query.addValueEventListener(listener)
        return ListenerHandle { query.removeEventListener(listener) }
    }

    fun fetchLatestOrderForUser(
        uid: String,
        onResult: (OrderModel?) -> Unit
    ) {
        ordersRef.orderByChild("customerId").equalTo(uid).get()
            .addOnSuccessListener { snapshot ->
                val latest = snapshot.children.mapNotNull { it.getValue(OrderModel::class.java) }
                    .maxByOrNull { it.createdAt }
                onResult(latest)
            }
            .addOnFailureListener { onResult(null) }
    }

    // ==================== LIVE DRIVER LOCATION ====================

    fun updateDriverLocation(location: DriverLocation) {
        if (location.driverId.isBlank()) return
        driverLocationsRef.child(location.driverId).setValue(location)
    }

    fun observeDriverLocation(
        driverId: String,
        onUpdate: (DriverLocation?) -> Unit
    ): ListenerHandle {
        val ref = driverLocationsRef.child(driverId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onUpdate(snapshot.getValue(DriverLocation::class.java))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return ListenerHandle { ref.removeEventListener(listener) }
    }

    // ==================== LIVE CHAT ====================

    fun sendChatMessage(
        orderId: String,
        message: ChatMessage,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (orderId.isBlank()) {
            onComplete?.invoke(false)
            return
        }
        val msgRef = chatsRef.child(orderId).child("messages")
        val key = msgRef.push().key
        if (key == null) {
            onComplete?.invoke(false)
            return
        }
        val finalMsg = message.copy(
            messageId = key,
            orderId = orderId,
            timestamp = if (message.timestamp == 0L) System.currentTimeMillis() else message.timestamp
        )
        msgRef.child(key).setValue(finalMsg)
            .addOnSuccessListener { onComplete?.invoke(true) }
            .addOnFailureListener { onComplete?.invoke(false) }
    }

    fun observeChatMessages(
        orderId: String,
        onUpdate: (List<ChatMessage>) -> Unit
    ): ListenerHandle {
        val query = chatsRef.child(orderId).child("messages").orderByChild("timestamp")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { it.getValue(ChatMessage::class.java) }
                    .sortedBy { it.timestamp }
                onUpdate(list)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        query.addValueEventListener(listener)
        return ListenerHandle { query.removeEventListener(listener) }
    }
}
