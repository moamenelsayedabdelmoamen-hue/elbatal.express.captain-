package com.example.data

import android.content.Context
import android.location.Location
import android.net.Uri
import android.util.Log
import com.example.data.location.GpsConfig
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.ImageUploadState
import com.example.notifications.CaptainNotificationManager
import com.example.notifications.ShiftNotificationScheduler
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class LoginOutcome {
    object Success : LoginOutcome()
    data class EmailNotVerified(val email: String) : LoginOutcome()
    data class Error(val message: String) : LoginOutcome()
}

data class SyncOrdersSummary(
    val totalSynced: Int,
    val pendingCount: Int,
    val activeCount: Int
)

interface CaptainCloudMetadataPort {
    fun currentAuthUid(): String?
    suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>)
    suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>?
    suspend fun submitOrderReceiptDetails(
        orderDocId: String,
        customerName: String,
        customerPhone: String,
        receiptImagePath: String,
        notes: String
    ): Result<String>
}



data class AuthUserSnapshot(
    val uid: String,
    val email: String,
    val isEmailVerified: Boolean,
    val displayName: String = ""
)

interface CaptainAuthBackendPort {
    fun currentUser(): AuthUserSnapshot?
    suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot
    suspend fun sendEmailVerification()
    suspend fun reloadCurrentUser(): AuthUserSnapshot?
    suspend fun updateDisplayName(displayName: String)
    suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot
    fun signOut()
    suspend fun sendPasswordResetEmail(email: String)
    suspend fun createCaptainDoc(uid: String, data: Map<String, Any>)
    suspend fun getCaptainDoc(uid: String): Map<String, Any?>?
    suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>)
    suspend fun queryCaptainEmailByPhoneVariants(
        variants: List<String>,
        numericVariants: List<Long>,
        normalizedLocal: String
    ): String?
    suspend fun deleteCurrentUser() {}
}

class CaptainRepository(
    val captainDao: CaptainDao,
    val orderDao: OrderDao,
    val shiftDao: ShiftDao? = null,
    private val context: Context? = null,
    val storageRepository: FirebaseStorageRepository = FirebaseStorageRepository.getInstance(context),
    private val cloudMetadataPortOverride: CaptainCloudMetadataPort? = null,
    private val cloudFunctionsClientOverride: com.example.data.remote.CloudFunctionsOrderClient? = null,
    private val authBackendOverride: CaptainAuthBackendPort? = null
) {
    suspend fun saveShiftsLocally(uid: String, shifts: List<ShiftEntity>) {
        saveShiftsAndScheduleAlarms(uid, shifts)
        _isShiftSynced.value = true
        _isShiftNetworkConnected.value = true
    }
    constructor(captainDao: CaptainDao, orderDao: OrderDao, context: Context?) : this(
        captainDao,
        orderDao,
        context?.let { AppDatabase.getDatabase(it).shiftDao() },
        context
    )

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val authBackend: CaptainAuthBackendPort by lazy {
        authBackendOverride ?: object : CaptainAuthBackendPort {
            private fun com.google.firebase.auth.FirebaseUser.toSnapshot(): AuthUserSnapshot =
                AuthUserSnapshot(
                    uid = uid,
                    email = email.orEmpty(),
                    isEmailVerified = isEmailVerified,
                    displayName = displayName.orEmpty()
                )

            override fun currentUser(): AuthUserSnapshot? = firebaseAuth.currentUser?.toSnapshot()

            override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
                val res = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
                val u = res.user ?: throw IllegalStateException("تعذر إنشاء حساب الكابتن في Firebase Auth")
                return u.toSnapshot()
            }

            override suspend fun sendEmailVerification() {
                val u = firebaseAuth.currentUser
                    ?: throw IllegalStateException("تعذر العثور على جلسة مستخدم نشطة لإرسال رابط التأكيد.")
                u.sendEmailVerification().await()
            }

            override suspend fun reloadCurrentUser(): AuthUserSnapshot? {
                val u = firebaseAuth.currentUser ?: return null
                u.reload().await()
                return firebaseAuth.currentUser?.toSnapshot() ?: u.toSnapshot()
            }

            override suspend fun updateDisplayName(displayName: String) {
                val u = firebaseAuth.currentUser ?: return
                val updates = UserProfileChangeRequest.Builder()
                    .setDisplayName(displayName)
                    .build()
                u.updateProfile(updates).await()
            }

            override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
                val res = firebaseAuth.signInWithEmailAndPassword(email, password).await()
                val u = res.user ?: throw IllegalStateException("فشل استعادة بيانات المستخدم من خادم Firebase.")
                return u.toSnapshot()
            }

            override fun signOut() {
                firebaseAuth.signOut()
            }

            override suspend fun deleteCurrentUser() {
                firebaseAuth.currentUser?.delete()?.await()
            }

            override suspend fun sendPasswordResetEmail(email: String) {
                firebaseAuth.sendPasswordResetEmail(email).await()
            }

            override suspend fun createCaptainDoc(uid: String, data: Map<String, Any>) {
                require(uid.isNotBlank()) { "Captain UID must not be blank" }
                require(data["accountType"]?.toString()?.trim()?.lowercase() == CAPTAIN_ACCOUNT_TYPE) {
                    "New captain document in captains/$uid MUST define accountType = '$CAPTAIN_ACCOUNT_TYPE'"
                }
                require(data["role"]?.toString()?.trim()?.lowercase() == CAPTAIN_ROLE) {
                    "New captain document in captains/$uid MUST define role = '$CAPTAIN_ROLE'"
                }
                firestore.collection("captains").document(uid).set(data).await()
            }

            override suspend fun getCaptainDoc(uid: String): Map<String, Any?>? {
                val docRef = firestore.collection("captains").document(uid)
                val snap = try {
                    docRef.get(Source.SERVER).await()
                } catch (serverErr: Exception) {
                    val fsEx = serverErr as? FirebaseFirestoreException
                    if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        throw serverErr
                    }
                    docRef.get(Source.DEFAULT).await()
                }
                return if (snap.exists()) (snap.data ?: emptyMap()) else null
            }

            override suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>) {
                firestore.collection("captains").document(uid).update(data).await()
            }

            override suspend fun queryCaptainEmailByPhoneVariants(
                variants: List<String>,
                numericVariants: List<Long>,
                normalizedLocal: String
            ): String? {
                val fieldNames = listOf("phone", "phoneNumber", "phone_number", "mobile", "driverPhone")
                var permissionDenied = false

                if (variants.isNotEmpty()) {
                    for (field in fieldNames) {
                        try {
                            val querySnap = firestore.collection("captains")
                                .whereIn(field, variants)
                                .limit(1)
                                .get()
                                .await()

                            if (!querySnap.isEmpty) {
                                val doc = querySnap.documents[0]
                                if (isEligibleCaptainIdentity(doc.safeString("accountType"), doc.safeString("role"))) {
                                    val em = doc.safeString("email")
                                    if (em.isNotBlank()) return em
                                }
                            }
                        } catch (e: Exception) {
                            val fsEx = e as? FirebaseFirestoreException
                            if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                permissionDenied = true
                                break
                            }
                        }
                    }
                }

                if (!permissionDenied && numericVariants.isNotEmpty()) {
                    for (field in listOf("phone", "phoneNumber")) {
                        try {
                            val numSnap = firestore.collection("captains")
                                .whereIn(field, numericVariants)
                                .limit(1)
                                .get()
                                .await()
                            if (!numSnap.isEmpty) {
                                val doc = numSnap.documents[0]
                                if (isEligibleCaptainIdentity(doc.safeString("accountType"), doc.safeString("role"))) {
                                    val em = doc.safeString("email")
                                    if (em.isNotBlank()) return em
                                }
                            }
                        } catch (e: Exception) {
                            val fsEx = e as? FirebaseFirestoreException
                            if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                permissionDenied = true
                                break
                            }
                        }
                    }
                }

                if (!permissionDenied) {
                    try {
                        val scanSnap = firestore.collection("captains").limit(100).get().await()
                        for (doc in scanSnap.documents) {
                            if (!isEligibleCaptainIdentity(doc.safeString("accountType"), doc.safeString("role"))) continue
                            val p = doc.safeString("phone", "phoneNumber", "phone_number", "mobile", "driverPhone")
                                .ifBlank { doc.get("phone")?.toString() ?: doc.get("phoneNumber")?.toString() ?: "" }
                            if (isSamePhone(p, normalizedLocal)) {
                                val em = doc.safeString("email")
                                if (em.isNotBlank()) return em
                            }
                        }
                    } catch (_: Exception) {}
                }

                return null
            }
        }
    }

    private val cloudMetadataPort: CaptainCloudMetadataPort by lazy {
        cloudMetadataPortOverride ?: object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String? = firebaseAuth.currentUser?.uid

            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {
                val payload = HashMap<String, Any>(fields).apply {
                    put("updatedAt", FieldValue.serverTimestamp())
                    (get("profileImagePath") as? String)?.let { put("personalImage", it) }
                    (get("idFrontImagePath") as? String)?.let { put("idCardFront", it) }
                    (get("idBackImagePath") as? String)?.let { put("idCardBack", it) }
                }
                // Strictly update existing captains/{uid} document ONLY; NEVER auto-create missing captain docs
                firestore.collection("captains").document(uid).update(payload).await()
            }

            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? {
                val snap = firestore.collection("captains").document(uid).get().await()
                if (!snap.exists()) return null
                return mapOf(
                    "profileImagePath" to snap.safeString("profileImagePath", "personalImage"),
                    "idFrontImagePath" to snap.safeString("idFrontImagePath", "idCardFront"),
                    "idBackImagePath" to snap.safeString("idBackImagePath", "idCardBack"),
                    "licenseImagePath" to snap.safeString("licenseImagePath"),
                    "profileImageUrl" to snap.safeString("profileImageUrl", "photoUrl", "avatarUri"),
                    "idCardImageUrl" to snap.safeString("idCardImageUrl", "nationalIdUrl"),
                    "licenseImageUrl" to snap.safeString("licenseImageUrl", "licenseUrl")
                )
            }

            override suspend fun submitOrderReceiptDetails(
                orderDocId: String,
                customerName: String,
                customerPhone: String,
                receiptImagePath: String,
                notes: String
            ): Result<String> {
                return cloudFunctionsOrderClient.submitCustomerDeliveryDetails(
                    orderDocId = orderDocId,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    receiptImagePath = receiptImagePath,
                    notes = notes
                )
            }
        }
    }
    private val merchantLocationCache = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Double?, Double?>>()

    private suspend fun lookupMerchantLocationFromFirestore(
        merchantId: String,
        storeName: String,
        forceRefresh: Boolean
    ): Triple<String, Double?, Double?> {
        val cleanMerchantId = merchantId.trim()
        val cleanStoreName = storeName.trim()
        val cacheKey = if (cleanMerchantId.isNotBlank()) "id:$cleanMerchantId" else "name:$cleanStoreName"
        if (!forceRefresh && cacheKey.isNotBlank()) {
            merchantLocationCache[cacheKey]?.let { return it }
        }

        suspend fun extractFromDoc(doc: DocumentSnapshot?): Triple<String, Double?, Double?>? {
            if (doc == null || !doc.exists()) return null
            val url = extractMerchantLocationUrlFromFieldsMap(doc.data)
            var lat: Double? = null
            var lng: Double? = null
            val geo = try {
                doc.getGeoPoint("location") ?: doc.getGeoPoint("restaurant_location") ?: doc.getGeoPoint("pickup_location")
            } catch (_: Exception) { null }
            if (geo != null) {
                lat = geo.latitude
                lng = geo.longitude
            } else {
                lat = (doc.get("latitude") as? Number)?.toDouble()
                    ?: (doc.get("lat") as? Number)?.toDouble()
                    ?: (doc.get("restaurantLatitude") as? Number)?.toDouble()
                lng = (doc.get("longitude") as? Number)?.toDouble()
                    ?: (doc.get("lng") as? Number)?.toDouble()
                    ?: (doc.get("restaurantLongitude") as? Number)?.toDouble()
            }
            return if (url.isNotBlank() || (lat != null && lng != null)) {
                Triple(url, lat, lng)
            } else {
                null
            }
        }

        var resolved: Triple<String, Double?, Double?>? = null
        if (cleanMerchantId.isNotBlank()) {
            for (collectionName in listOf("restaurants", "merchants", "users")) {
                try {
                    val doc = firestore.collection(collectionName).document(cleanMerchantId).get().await()
                    val candidate = extractFromDoc(doc)
                    if (candidate != null && candidate.first.isNotBlank()) {
                        resolved = candidate
                        break
                    } else if (resolved == null && candidate != null) {
                        resolved = candidate
                    }
                } catch (e: Exception) {
                    Log.d("CaptainRepository", "[MerchantLocation] $collectionName/$cleanMerchantId lookup notice: ${e.message}")
                }
            }
        }

        if ((resolved == null || resolved.first.isBlank()) &&
            cleanStoreName.isNotBlank() &&
            cleanStoreName !in listOf("مطعم الشريك", "مطعم مسجل", "التاجر")
        ) {
            try {
                val querySnap = firestore.collection("restaurants")
                    .whereEqualTo("name", cleanStoreName)
                    .limit(1)
                    .get()
                    .await()
                val candidate = extractFromDoc(querySnap.documents.firstOrNull())
                if (candidate != null && (candidate.first.isNotBlank() || resolved == null)) {
                    resolved = candidate
                }
            } catch (e: Exception) {
                Log.d("CaptainRepository", "[MerchantLocation] restaurants query by name notice: ${e.message}")
            }
        }

        val finalTriple = resolved ?: Triple("", null, null)
        if (cacheKey.isNotBlank() && finalTriple.first.isNotBlank()) {
            merchantLocationCache[cacheKey] = finalTriple
        }
        return finalTriple
    }

    suspend fun resolveMerchantLocationForOrder(
        order: OrderEntity,
        forceRefresh: Boolean = false
    ): OrderEntity = withContext(Dispatchers.IO) {
        var bestUrl = order.effectiveStoreLocationUrl
        var bestMerchantId = order.merchantId.trim()
        var bestLat = order.restaurantLatitude
        var bestLng = order.restaurantLongitude

        if (forceRefresh && order.firestoreDocId.isNotBlank()) {
            try {
                val orderSnap = firestore.collection("orders").document(order.firestoreDocId).get().await()
                if (orderSnap.exists()) {
                    val freshOrderUrl = extractMerchantLocationUrlFromFieldsMap(orderSnap.data)
                    if (freshOrderUrl.isNotBlank()) {
                        bestUrl = freshOrderUrl
                    }
                    val freshMerchantId = orderSnap.safeString(
                        "merchantId", "merchant_id", "restaurantId", "restaurant_id",
                        "storeId", "store_id", "vendorId", "vendor_id", "ownerId",
                        "owner_id", "merchantUid", "restaurantUid"
                    )
                    if (freshMerchantId.isNotBlank()) {
                        bestMerchantId = freshMerchantId
                    }
                }
            } catch (e: Exception) {
                Log.d("CaptainRepository", "[MerchantLocation] order refresh notice: ${e.message}")
            }
        }

        if (bestUrl.isBlank() || forceRefresh) {
            val (accountUrl, accountLat, accountLng) = lookupMerchantLocationFromFirestore(
                merchantId = bestMerchantId,
                storeName = order.storeName,
                forceRefresh = forceRefresh
            )
            if (accountUrl.isNotBlank()) {
                bestUrl = accountUrl
            }
            if (bestLat == null && accountLat != null) bestLat = accountLat
            if (bestLng == null && accountLng != null) bestLng = accountLng
        }

        if (bestUrl != order.storeLocationUrl ||
            bestMerchantId != order.merchantId ||
            bestLat != order.restaurantLatitude ||
            bestLng != order.restaurantLongitude
        ) {
            orderDao.updateMerchantLocationInfo(
                orderId = order.id,
                locationUrl = bestUrl,
                merchantId = bestMerchantId,
                lat = bestLat,
                lng = bestLng
            )
            order.copy(
                merchantId = bestMerchantId,
                storeLocationUrl = bestUrl,
                restaurantLatitude = bestLat,
                restaurantLongitude = bestLng
            )
        } else {
            order
        }
    }

    private val ordersListenerRegistrations = mutableListOf<ListenerRegistration>()
    private var captainScheduleDocListenerRegistration: ListenerRegistration? = null
    private var captainScheduleSubcollectionListenerRegistration: ListenerRegistration? = null
    private var captainScheduleQueryListenerRegistration: ListenerRegistration? = null
    private var captainScheduleUidQueryListenerRegistration: ListenerRegistration? = null
    private var captainScheduleDriverQueryListenerRegistration: ListenerRegistration? = null
    private var captainSubcollectionShiftListenerRegistration: ListenerRegistration? = null
    private var shiftListenerRegistration: ListenerRegistration? = null
    private var captainDocShiftListenerRegistration: ListenerRegistration? = null

    private val _isShiftSynced = MutableStateFlow(false)
    val isShiftSynced: StateFlow<Boolean> = _isShiftSynced.asStateFlow()

    private val _isShiftNetworkConnected = MutableStateFlow(true)
    val isShiftNetworkConnected: StateFlow<Boolean> = _isShiftNetworkConnected.asStateFlow()

    val captainFlow: Flow<CaptainEntity?> = captainDao.getCaptainFlow()
    val allOrdersFlow: Flow<List<OrderEntity>> = orderDao.getAllOrdersFlow()
    val activeOrderFlow: Flow<OrderEntity?> = orderDao.getActiveOrderFlow()
    val availableOrdersFlow: Flow<List<OrderEntity>> = orderDao.getAvailablePendingOrdersFlow()
    val pendingOrderFlow: Flow<OrderEntity?> = orderDao.getPendingIncomingOrderFlow()
    val completedOrdersFlow: Flow<List<OrderEntity>> = orderDao.getCompletedOrdersFlow()
    val completedCountFlow: Flow<Int> = orderDao.getCompletedCountFlow()
    val totalEarningsFlow: Flow<Double?> = orderDao.getTotalEarningsFlow()
    val shiftsFlow: Flow<List<ShiftEntity>> = shiftDao?.getAllShiftsFlow() ?: emptyFlow()
    val dailyWalletDao: DailyWalletDao? = context?.let { AppDatabase.getDatabase(it).dailyWalletDao() }
    val dailyWalletsFlow: Flow<List<DailyWalletEntity>> = dailyWalletDao?.getAllDailyWalletsFlow() ?: emptyFlow()
    private val cloudFunctionsOrderClient: com.example.data.remote.CloudFunctionsOrderClient by lazy {
        cloudFunctionsClientOverride ?: com.example.data.remote.CloudFunctionsOrderClient(firebaseAuth)
    }

    init {
        if (cloudMetadataPortOverride == null && cloudFunctionsClientOverride == null && authBackendOverride == null) {
            try {
                // 1. Only start listeners if already authenticated AND email is verified
                val existingUser = firebaseAuth.currentUser
                if (existingUser != null && existingUser.isEmailVerified) {
                    startRealtimeOrdersListener()
                    startRealtimeShiftListener()
                }
                // 2. Listen to Auth State changes and require verified Firebase user
                firebaseAuth.addAuthStateListener { auth ->
                    val user = auth.currentUser
                    if (user != null && user.isEmailVerified) {
                        startRealtimeOrdersListener()
                        startRealtimeShiftListener()
                    } else {
                        stopRealtimeOrdersListener()
                        stopRealtimeShiftListener()
                    }
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not initialize Firebase Auth listeners: ${e.message}")
            }
        }
    }

    suspend fun clearPendingOrdersLocally() {
        orderDao.deleteAllPendingOrders()
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
    }

    suspend fun syncFcmToken() {
        val uid = authBackend.currentUser()?.uid?.takeIf { it.isNotBlank() }
            ?: cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            ?: context?.let { CaptainNotificationManager.getSavedFcmToken(it) }
            ?: return
        context?.let { CaptainNotificationManager.saveFcmToken(it, token) }
        val payload = mapOf<String, Any>(
            "fcmToken" to token,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        runCatching { authBackend.updateCaptainDoc(uid, payload) }
    }

    private suspend fun saveShiftsAndScheduleAlarms(uid: String, shifts: List<ShiftEntity>) {
        shiftDao?.atomicReplaceCaptainShifts(uid, shifts)
        context?.let { ShiftNotificationScheduler.scheduleShiftNotifications(it, shifts, uid) }
    }

    /**
     * Checks whether the captain is eligible to be assigned or receive new/pending orders.
     * Enforces:
     * 1. Captain exists and isOnline == true (never assign when offline).
     * 2. Account status is not suspended/banned/pending/inactive.
     * 3. When shift schedules exist, current Cairo time falls inside an active shift.
     */
    private suspend fun isCaptainEligibleForNewOrders(captain: CaptainEntity?): Boolean {
        if (captain == null || !captain.isOnline) {
            return false
        }
        val normalizedStatus = captain.status.trim().lowercase()
        if (normalizedStatus in listOf("banned", "suspended", "disabled", "blocked", "pending", "inactive")) {
            return false
        }
        val currentShifts = getShifts()
        if (currentShifts.isNotEmpty()) {
            val shiftStatus = ShiftManager.calculateShiftStatus(currentShifts)
            if (!shiftStatus.isAvailableForOrders) {
                return false
            }
        }
        return true
    }

    fun startRealtimeOrdersListener() {
        stopRealtimeOrdersListener()
        val currentUser = firebaseAuth.currentUser
        if (currentUser == null) {
            Log.d("CaptainRepository", "[FirestoreOrdersListener] Waiting for captain authentication before starting orders listener")
            return
        }
        val authUid = currentUser.uid
        try {
            Log.i("CaptainRepository", "[FirestoreOrdersListener] Registering least-privilege scoped listeners on 'orders' for captain $authUid...")

            fun processOrderDocuments(
                docs: List<DocumentSnapshot>,
                removedPendingDocs: List<DocumentSnapshot> = emptyList()
            ) {
                CoroutineScope(Dispatchers.IO).launch {
                    val currentCaptain = captainDao.getCaptain()
                    val myPhone = currentCaptain?.phoneNumber ?: ""
                    val myUid = authUid.ifBlank { currentCaptain?.uid.orEmpty() }
                    val eligibleForNewOrders = isCaptainEligibleForNewOrders(currentCaptain)

                    if (!eligibleForNewOrders) {
                        orderDao.deleteAllPendingOrders()
                        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                    }

                    // Clean up pending orders that were claimed by another captain or cancelled
                    for (removedDoc in removedPendingDocs) {
                        val parsedRemoved = removedDoc.toOrderEntity() ?: continue
                        val localExisting = allOrdersFlow.firstOrNull()?.find {
                            it.id == parsedRemoved.id || it.firestoreDocId == parsedRemoved.firestoreDocId
                        }
                        if (localExisting != null && localExisting.status == OrderStatus.PENDING) {
                            orderDao.deleteOrderById(localExisting.id)
                            context?.let {
                                CaptainNotificationManager.stopIncomingOrderBell(
                                    it,
                                    localExisting.firestoreDocId.ifBlank { parsedRemoved.firestoreDocId }
                                )
                            }
                        }
                    }

                    for (doc in docs) {
                        try {
                            val order = doc.toOrderEntity() ?: continue
                            val docCaptainId = doc.safeString(
                                "captainId", "captain_id", "driverId", "driver_id",
                                "assignedCaptainId", "assignedDriverId", "captainUid",
                                "driverUid", "assignedCaptainPhone", "driverPhone", "captain_phone"
                            )
                            val offeredCaptainId = doc.safeString("offeredCaptainId", "offered_captain_id")
                            val isExcluded = doc.isExcludedCaptain(myUid)
                            val hasNoCaptain = isPlaceholderOrUnassigned(docCaptainId)
                            val isAssignedToMe = !hasNoCaptain && (
                                (myUid.isNotBlank() && (
                                    docCaptainId == myUid ||
                                        doc.safeString("captainUid", "driverUid", "offeredCaptainId") == myUid
                                    )) ||
                                    (myPhone.isNotBlank() && (
                                        isSamePhone(docCaptainId, myPhone) ||
                                            isSamePhone(doc.safeString("driverPhone", "assignedCaptainPhone"), myPhone)
                                        ))
                                )

                            val offeredToOtherCaptain = offeredCaptainId.isNotBlank() &&
                                offeredCaptainId != myUid &&
                                !isAssignedToMe
                            val isAvailable = (order.status == OrderStatus.PENDING) &&
                                hasNoCaptain &&
                                !isExcluded &&
                                !offeredToOtherCaptain
                            val isAcceptedByOther = !isAvailable && !hasNoCaptain && !isAssignedToMe

                            if (isAcceptedByOther || isExcluded || offeredToOtherCaptain) {
                                val existingToRemove = orderDao.findByFirestoreDocId(order.firestoreDocId)
                                orderDao.deleteOrderById(existingToRemove?.id ?: order.id)
                                context?.let {
                                    CaptainNotificationManager.stopIncomingOrderBell(
                                        it,
                                        order.firestoreDocId.ifBlank { doc.id }
                                    )
                                }
                            } else if (order.status == OrderStatus.PENDING && !eligibleForNewOrders) {
                                // Never insert or assign new/pending offers when captain is offline or outside shift hours
                                val existingToRemove = orderDao.findByFirestoreDocId(order.firestoreDocId)
                                orderDao.deleteOrderById(existingToRemove?.id ?: order.id)
                                context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                            } else {
                                val existingBefore = orderDao.findByFirestoreDocId(order.firestoreDocId)
                                val resolvedExpiresAt = when {
                                    order.offerExpiresAt > 0L -> order.offerExpiresAt
                                    existingBefore != null && existingBefore.offerExpiresAt > 0L -> existingBefore.offerExpiresAt
                                    order.status == OrderStatus.PENDING -> System.currentTimeMillis() + 60_000L
                                    else -> 0L
                                }
                                val finalOrderBase = if (isAvailable || (order.status == OrderStatus.PENDING && offeredCaptainId == myUid)) {
                                    order.copy(status = OrderStatus.PENDING, offerExpiresAt = resolvedExpiresAt)
                                } else {
                                    order.copy(offerExpiresAt = resolvedExpiresAt)
                                }
                                val preservedActiveStatuses = setOf(
                                    OrderStatus.AT_STORE,
                                    OrderStatus.PICKED_UP,
                                    OrderStatus.ORDER_DATA_ENTERED,
                                    OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
                                    OrderStatus.MERCHANT_REJECTED,
                                    OrderStatus.EN_ROUTE
                                )
                                val finalOrder = if (
                                    finalOrderBase.status == OrderStatus.PENDING &&
                                    isAssignedToMe &&
                                    existingBefore != null &&
                                    existingBefore.status in preservedActiveStatuses
                                ) {
                                    if (finalOrderBase.merchantConfirmed || existingBefore.merchantConfirmed) {
                                        finalOrderBase.copy(status = OrderStatus.EN_ROUTE, merchantConfirmed = true)
                                    } else {
                                        finalOrderBase.copy(status = existingBefore.status)
                                    }
                                } else {
                                    finalOrderBase
                                }
                                val baseWithExistingLocation = if (finalOrder.storeLocationUrl.isBlank() && !existingBefore?.storeLocationUrl.isNullOrBlank()) {
                                    finalOrder.copy(storeLocationUrl = existingBefore!!.storeLocationUrl)
                                } else {
                                    finalOrder
                                }
                                val enrichedOrder = if (baseWithExistingLocation.storeLocationUrl.isBlank() &&
                                    (baseWithExistingLocation.merchantId.isNotBlank() || baseWithExistingLocation.storeName.isNotBlank())
                                ) {
                                    val (accountUrl, accLat, accLng) = lookupMerchantLocationFromFirestore(
                                        merchantId = baseWithExistingLocation.merchantId,
                                        storeName = baseWithExistingLocation.storeName,
                                        forceRefresh = false
                                    )
                                    baseWithExistingLocation.copy(
                                        storeLocationUrl = accountUrl.ifBlank { baseWithExistingLocation.storeLocationUrl },
                                        restaurantLatitude = baseWithExistingLocation.restaurantLatitude ?: accLat,
                                        restaurantLongitude = baseWithExistingLocation.restaurantLongitude ?: accLng
                                    )
                                } else {
                                    baseWithExistingLocation
                                }
                                val savedOrderId = orderDao.upsertByFirestoreId(enrichedOrder)
                                if (enrichedOrder.status == OrderStatus.PENDING &&
                                    eligibleForNewOrders &&
                                    (existingBefore == null || (offeredCaptainId == myUid && enrichedOrder.offerExpiresAt > (existingBefore.offerExpiresAt)))
                                ) {
                                    context?.let { ctx ->
                                        CaptainNotificationManager.showNewOrderNotification(
                                            context = ctx,
                                            orderKey = enrichedOrder.firestoreDocId.ifBlank { savedOrderId.toString() },
                                            storeName = enrichedOrder.storeName,
                                            pickupAddress = enrichedOrder.pickupAddress,
                                            deliveryAddress = enrichedOrder.deliveryAddress,
                                            deliveryFee = enrichedOrder.deliveryFee,
                                            orderCode = enrichedOrder.cleanOrderCode,
                                            bypassDedupe = existingBefore != null && offeredCaptainId == myUid,
                                            orderId = savedOrderId,
                                            offerExpiresAtMs = enrichedOrder.offerExpiresAt,
                                            distanceKm = enrichedOrder.distanceKm,
                                            startContinuousBell = true
                                        )
                                    }
                                } else if (enrichedOrder.status != OrderStatus.PENDING) {
                                    context?.let { ctx ->
                                        CaptainNotificationManager.stopIncomingOrderBell(
                                            ctx,
                                            enrichedOrder.firestoreDocId.ifBlank { doc.id }
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CaptainRepository", "[FirestoreOrdersListener] Error parsing Firestore order document: ${doc.id}", e)
                        }
                    }
                    reconcileDailyWallets()
                }
            }

            // 1. Available pending orders scoped to this captain (matches firestore.rules isAvailablePendingOrder)
            ordersListenerRegistrations += firestore.collection("orders")
                .whereEqualTo("status", "pending")
                .whereArrayContains("eligibleCaptainIds", authUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("CaptainRepository", "[FirestoreOrdersListener] pending query notice: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val removed = snapshot.documentChanges
                            .filter { it.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED }
                            .map { it.document }
                        processOrderDocuments(snapshot.documents, removed)
                    }
                }

            // 2. Captain's own assigned / offered orders (matches firestore.rules isOrderAssignedOrOfferedToCaptain)
            for (field in listOf("captainId", "driverId", "captainUid", "assignedCaptainId", "offeredCaptainId")) {
                ordersListenerRegistrations += firestore.collection("orders")
                    .whereEqualTo(field, authUid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w("CaptainRepository", "[FirestoreOrdersListener] $field query notice: ${error.message}")
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            processOrderDocuments(snapshot.documents)
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[FirestoreOrdersListener] Error starting Firestore listener: ${e.message}")
        }
    }

    /**
     * Dedicated query for orders filtered strictly by status == 'pending'.
     * Logs query execution and all matched documents for verification.
     */
    suspend fun queryPendingOrdersFromFirestore(): Result<List<OrderEntity>> {
        return try {
            val currentCaptain = captainDao.getCaptain()
            if (!isCaptainEligibleForNewOrders(currentCaptain)) {
                Log.i("CaptainRepository", "[FirestoreQuery] Captain is offline or outside active shift; skipping pending order assignment and clearing pending cache.")
                orderDao.deleteAllPendingOrders()
                return Result.success(emptyList())
            }

            val myUid = currentCaptain?.uid?.takeIf { it.isNotBlank() }
                ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull().orEmpty()
            if (myUid.isBlank()) {
                return Result.success(emptyList())
            }
            Log.i("CaptainRepository", "[FirestoreQuery] Executing scoped query on 'orders' where status == 'pending' and eligibleCaptainIds array-contains captain...")
            val querySnapshot = firestore.collection("orders")
                .whereEqualTo("status", "pending")
                .whereArrayContains("eligibleCaptainIds", myUid)
                .get()
                .await()

            val docs = querySnapshot.documents
            Log.i("CaptainRepository", "[FirestoreQuery] Query 'status == pending' returned ${docs.size} documents (source=${if (querySnapshot.metadata.isFromCache) "cache" else "server"})")

            val pendingList = mutableListOf<OrderEntity>()
            for (doc in docs) {
                val order = doc.toOrderEntity()
                if (order != null) {
                    val captId = doc.safeString(
                        "captainId", "captain_id", "driverId", "driver_id",
                        "assignedCaptainId", "assignedDriverId", "captainUid",
                        "driverUid", "assignedCaptainPhone", "driverPhone", "captain_phone"
                    )
                    val rawStatus = doc.safeString("status", default = "pending")
                    val isUnassigned = isPlaceholderOrUnassigned(captId)
                    Log.d("CaptainRepository", "[FirestoreQuery] -> Pending Doc ID: ${doc.id}, orderNumber: ${order.orderNumber}, rawStatus: '$rawStatus', unassigned: $isUnassigned")
                    val finalOrder = order.copy(status = OrderStatus.PENDING)
                    orderDao.upsertByFirestoreId(finalOrder)
                    pendingList.add(finalOrder)
                }
            }
            Result.success(pendingList)
        } catch (e: Exception) {
            Log.e("CaptainRepository", "[FirestoreQuery] Query for status=='pending' failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * One-time sync of orders from Firestore, executing and logging queries for status == 'pending'
     * as well as syncing captain's active orders into Room database.
     */
    suspend fun syncOrdersFromFirestore(): Result<Int> {
        return syncOrdersDetailedFromFirestore().map { it.pendingCount }
    }

    /**
     * One-time sync of orders from Firestore, returning breakdown of synced total,
     * available pending orders, and ongoing active orders.
     */
    suspend fun syncOrdersDetailedFromFirestore(): Result<SyncOrdersSummary> {
        return try {
            val currentCaptain = captainDao.getCaptain()
            val myPhone = currentCaptain?.phoneNumber ?: ""
            val myUid = firebaseAuth.currentUser?.uid
                ?: currentCaptain?.uid.orEmpty()
            if (myUid.isBlank()) {
                return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً لمزامنة الطلبات"))
            }

            val eligibleForNewOrders = isCaptainEligibleForNewOrders(currentCaptain)
            if (!eligibleForNewOrders) {
                Log.i("CaptainRepository", "[FirestoreSync] Captain is offline or outside active shift; clearing local PENDING offers and syncing only active/archived captain orders.")
                orderDao.deleteAllPendingOrders()
                context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
            }

            Log.i("CaptainRepository", "[FirestoreSync] Syncing scoped orders for captain UID: $myUid (eligibleForNewOrders=$eligibleForNewOrders)...")
            val matchedDocs = linkedMapOf<String, DocumentSnapshot>()

            // 1. Available pending orders (ONLY when captain is online and inside shift hours)
            if (eligibleForNewOrders) {
                try {
                    val pendingSnapshot = firestore.collection("orders")
                        .whereEqualTo("status", "pending")
                        .whereArrayContains("eligibleCaptainIds", myUid)
                        .get()
                        .await()
                    for (doc in pendingSnapshot.documents) {
                        matchedDocs[doc.id] = doc
                    }
                } catch (e: Exception) {
                    Log.w("CaptainRepository", "[FirestoreSync] scoped pending query notice: ${e.message}")
                }
            }

            // 2. Captain's own assigned / offered orders
            for (field in listOf("captainId", "driverId", "captainUid", "assignedCaptainId", "offeredCaptainId")) {
                try {
                    val ownSnap = firestore.collection("orders")
                        .whereEqualTo(field, myUid)
                        .get()
                        .await()
                    for (doc in ownSnap.documents) {
                        matchedDocs[doc.id] = doc
                    }
                } catch (e: Exception) {
                    Log.w("CaptainRepository", "[FirestoreSync] $field == uid query notice: ${e.message}")
                }
            }

            var count = 0
            var pendingCount = 0
            var activeCount = 0

            for (doc in matchedDocs.values) {
                val order = doc.toOrderEntity() ?: continue
                val docCaptainId = doc.safeString(
                    "captainId", "captain_id", "driverId", "driver_id",
                    "assignedCaptainId", "assignedDriverId", "captainUid",
                    "driverUid", "assignedCaptainPhone", "driverPhone", "captain_phone"
                )
                val offeredCaptainId = doc.safeString("offeredCaptainId", "offered_captain_id")
                val isExcluded = doc.isExcludedCaptain(myUid)

                val hasNoCaptain = isPlaceholderOrUnassigned(docCaptainId)
                val isAssignedToMe = !hasNoCaptain && (
                    docCaptainId == myUid ||
                        doc.safeString("captainUid", "driverUid", "offeredCaptainId") == myUid ||
                        (myPhone.isNotBlank() && isSamePhone(docCaptainId, myPhone))
                    )
                val offeredToOtherCaptain = offeredCaptainId.isNotBlank() &&
                    offeredCaptainId != myUid &&
                    !isAssignedToMe

                val isAvailable = (order.status == OrderStatus.PENDING) &&
                    hasNoCaptain &&
                    !isExcluded &&
                    !offeredToOtherCaptain
                val isAcceptedByOther = !isAvailable && !hasNoCaptain && !isAssignedToMe

                if (isAcceptedByOther || isExcluded || offeredToOtherCaptain) {
                    val existingToDelete = orderDao.findByFirestoreDocId(order.firestoreDocId)
                    orderDao.deleteOrderById(existingToDelete?.id ?: order.id)
                    context?.let {
                        CaptainNotificationManager.stopIncomingOrderBell(
                            it,
                            order.firestoreDocId.ifBlank { doc.id }
                        )
                    }
                } else if (order.status == OrderStatus.PENDING && !eligibleForNewOrders) {
                    val existingToDelete = orderDao.findByFirestoreDocId(order.firestoreDocId)
                    orderDao.deleteOrderById(existingToDelete?.id ?: order.id)
                    context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
                } else {
                    val existingLocal = orderDao.findByFirestoreDocId(order.firestoreDocId)
                    val resolvedExpiresAt = when {
                        order.offerExpiresAt > 0L -> order.offerExpiresAt
                        existingLocal != null && existingLocal.offerExpiresAt > 0L -> existingLocal.offerExpiresAt
                        order.status == OrderStatus.PENDING -> System.currentTimeMillis() + 60_000L
                        else -> 0L
                    }
                    val preservedActiveStatuses = setOf(
                        OrderStatus.AT_STORE,
                        OrderStatus.PICKED_UP,
                        OrderStatus.ORDER_DATA_ENTERED,
                        OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
                        OrderStatus.MERCHANT_REJECTED,
                        OrderStatus.EN_ROUTE
                    )
                    val resolvedOrder = if (
                        order.status == OrderStatus.PENDING &&
                        isAssignedToMe &&
                        existingLocal != null &&
                        existingLocal.status in preservedActiveStatuses
                    ) {
                        if (order.merchantConfirmed || existingLocal.merchantConfirmed) {
                            order.copy(status = OrderStatus.EN_ROUTE, merchantConfirmed = true)
                        } else {
                            order.copy(status = existingLocal.status)
                        }
                    } else {
                        order.copy(offerExpiresAt = resolvedExpiresAt)
                    }
                    val finalOrder = if (isAvailable || (resolvedOrder.status == OrderStatus.PENDING && (isAssignedToMe || offeredCaptainId == myUid))) {
                        pendingCount++
                        resolvedOrder.copy(status = OrderStatus.PENDING, offerExpiresAt = resolvedExpiresAt)
                    } else {
                        if (resolvedOrder.status != OrderStatus.DELIVERED && resolvedOrder.status != OrderStatus.CANCELLED && resolvedOrder.status != OrderStatus.EXPIRED) {
                            activeCount++
                        }
                        resolvedOrder
                    }
                    val baseWithLocation = if (finalOrder.storeLocationUrl.isBlank() &&
                        (finalOrder.merchantId.isNotBlank() || finalOrder.storeName.isNotBlank())
                    ) {
                        val (accountUrl, accLat, accLng) = lookupMerchantLocationFromFirestore(
                            merchantId = finalOrder.merchantId,
                            storeName = finalOrder.storeName,
                            forceRefresh = false
                        )
                        finalOrder.copy(
                            storeLocationUrl = accountUrl.ifBlank { finalOrder.storeLocationUrl },
                            restaurantLatitude = finalOrder.restaurantLatitude ?: accLat,
                            restaurantLongitude = finalOrder.restaurantLongitude ?: accLng
                        )
                    } else {
                        finalOrder
                    }
                    orderDao.upsertByFirestoreId(baseWithLocation)
                    count++
                }
            }

            reconcileDailyWallets()
            Log.i("CaptainRepository", "[FirestoreSync] Sync complete: $count orders saved ($pendingCount PENDING, $activeCount active)")
            Result.success(SyncOrdersSummary(totalSynced = count, pendingCount = pendingCount, activeCount = activeCount))
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[FirestoreSync] syncOrdersFromFirestore error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Syncs all historical orders assigned to this captain from Firestore (strictly by authenticated UID)
     * and reconciles daily wallet balances rolled over at 1:00 PM Cairo time.
     */
    suspend fun syncCaptainOrdersHistoryFromFirestore(): Result<Int> {
        val currentCaptain = captainDao.getCaptain()
        val myUid = firebaseAuth.currentUser?.uid ?: currentCaptain?.uid ?: ""
        if (myUid.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد كابتن مسجل حالياً"))
        }

        return try {
            val matchedDocs = linkedMapOf<String, DocumentSnapshot>()

            val myPhone = currentCaptain?.phoneNumber ?: ""
            val queryFields = listOf("captainId", "driverId", "captainUid", "assignedCaptainId")
            var succeededQueries = 0
            var lastQueryError: Exception? = null

            for (field in queryFields) {
                try {
                    val snap = firestore.collection("orders")
                        .whereEqualTo(field, myUid)
                        .get()
                        .await()
                    succeededQueries++
                    for (doc in snap.documents) {
                        matchedDocs[doc.id] = doc
                    }
                } catch (e: Exception) {
                    lastQueryError = e
                    Log.w("CaptainRepository", "[HistorySync] Query orders.$field == uid notice: ${e.message}")
                }
            }

            if (succeededQueries == 0 && lastQueryError != null) {
                reconcileDailyWallets()
                return Result.failure(lastQueryError)
            }

            var savedCount = 0
            for (doc in matchedDocs.values) {
                val parsed = doc.toOrderEntity() ?: continue
                val docCapt = doc.safeString(
                    "captainId", "captain_id", "driverId", "driver_id",
                    "assignedCaptainId", "assignedDriverId", "captainUid",
                    "driverUid", "assignedCaptainPhone", "driverPhone", "captain_phone"
                )
                val isOwnedByMe = docCapt == myUid ||
                    doc.safeString("captainUid", "driverUid") == myUid ||
                    (myPhone.isNotBlank() && isSamePhone(docCapt, myPhone))
                if (!isOwnedByMe) continue

                val ensuredCaptainId = if (isPlaceholderOrUnassigned(parsed.captainId)) {
                    parsed.copy(captainId = myUid)
                } else {
                    parsed
                }
                orderDao.upsertByFirestoreId(ensuredCaptainId)
                savedCount++
            }

            // Also read any previously archived daily wallet snapshots from Firestore if permitted
            if (myUid.isNotBlank()) {
                try {
                    val dailySnap = firestore.collection("captains")
                        .document(myUid)
                        .collection("daily_wallets")
                        .get()
                        .await()
                    val remoteRecords = dailySnap.documents.mapNotNull { d ->
                        val dayKey = d.safeString("dayKey", default = d.id)
                        if (!dayKey.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return@mapNotNull null
                        val (startMs, endMs) = DailyWalletManager.getOperationalDayBounds(dayKey)
                        DailyWalletEntity(
                            dayKey = dayKey,
                            captainUid = myUid,
                            periodStartMs = d.safeLong("periodStartMs", default = startMs),
                            periodEndMs = d.safeLong("periodEndMs", default = endMs),
                            walletBalance = d.safeDouble("walletBalance", default = 0.0),
                            deliveredOrdersCount = d.safeLong("deliveredOrdersCount", default = 0L).toInt(),
                            totalOrdersCount = d.safeLong("totalOrdersCount", default = 0L).toInt(),
                            isClosed = (d.get("isClosed") as? Boolean) ?: (dayKey < DailyWalletManager.getOperationalDayKey()),
                            closedAt = d.safeLong("closedAt", default = 0L),
                            updatedAt = d.safeLong("updatedAt", default = System.currentTimeMillis())
                        )
                    }
                    if (remoteRecords.isNotEmpty()) {
                        dailyWalletDao?.upsertAll(remoteRecords)
                    }
                } catch (_: Exception) {
                    // Subcollection might be restricted by rules; local Room daily_wallet_records handles persistence seamlessly
                }
            }

            reconcileDailyWallets()
            Result.success(savedCount)
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[HistorySync] syncCaptainOrdersHistoryFromFirestore notice: ${e.message}")
            reconcileDailyWallets()
            Result.failure(e)
        }
    }

    /**
     * Fetches and syncs archived finished orders (DELIVERED and CANCELLED) strictly for the
     * logged-in captain and counts orders finished on the requested calendar dateKey ("yyyy-MM-dd").
     */
    suspend fun syncCaptainArchivedOrdersForDate(dateKey: String): Result<Int> {
        val currentCaptain = captainDao.getCaptain()
        val myUid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: currentCaptain?.uid.orEmpty()
        if (myUid.isBlank()) {
            return Result.failure(IllegalStateException("يجب تسجيل الدخول لعرض سجل الطلبات"))
        }

        val syncResult = syncCaptainOrdersHistoryFromFirestore()
        if (syncResult.isFailure) {
            return syncResult
        }

        val myPhone = currentCaptain?.phoneNumber.orEmpty()
        val allLocal = allOrdersFlow.firstOrNull().orEmpty()
        val matchingForDate = OrderHistoryHelper.filterArchivedOrdersForDate(
            allOrders = allLocal,
            captainUid = myUid,
            captainPhone = myPhone,
            selectedDateKey = dateKey
        )
        return Result.success(matchingForDate.size)
    }

    /**
     * Reconciles daily wallet records using the 1:00 PM (13:00 Cairo) rollover boundary:
     * - Archives completed cycles (< currentOperationalDayKey) with their recorded wallet balance.
     * - Ensures the new operational day starting at 1:00 PM begins at 0.0 EGP and accumulates only
     *   orders delivered during the new cycle.
     */
    suspend fun reconcileDailyWallets(nowMs: Long = System.currentTimeMillis()) {
        val dao = dailyWalletDao ?: return
        try {
            val currentCaptain = captainDao.getCaptain()
            val myUid = firebaseAuth.currentUser?.uid ?: currentCaptain?.uid ?: ""
            val myPhone = currentCaptain?.phoneNumber ?: ""
            val allOrders = allOrdersFlow.firstOrNull().orEmpty()
            val ownOrders = allOrders.filter { DailyWalletManager.isCaptainOwnOrder(it, myUid, myPhone) }
            val existing = dao.getAllDailyWallets()

            val updatedRecords = DailyWalletManager.buildDailyRecords(
                captainUid = myUid,
                captainOrders = ownOrders,
                existingRecords = existing,
                nowMs = nowMs
            )
            dao.upsertAll(updatedRecords)
            // Financial daily_wallets subcollection in Firestore is server/admin-authoritative (read-only for clients).
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[DailyWallet] reconcileDailyWallets notice: ${e.message}")
        }
    }

    /**
     * Stops all active realtime listeners for shifts.
     */
    fun stopRealtimeShiftListener() {
        captainScheduleDocListenerRegistration?.remove()
        captainScheduleDocListenerRegistration = null
        captainScheduleSubcollectionListenerRegistration?.remove()
        captainScheduleSubcollectionListenerRegistration = null
        captainScheduleQueryListenerRegistration?.remove()
        captainScheduleQueryListenerRegistration = null
        captainScheduleUidQueryListenerRegistration?.remove()
        captainScheduleUidQueryListenerRegistration = null
        captainScheduleDriverQueryListenerRegistration?.remove()
        captainScheduleDriverQueryListenerRegistration = null
        captainSubcollectionShiftListenerRegistration?.remove()
        captainSubcollectionShiftListenerRegistration = null
        shiftListenerRegistration?.remove()
        shiftListenerRegistration = null
        captainDocShiftListenerRegistration?.remove()
        captainDocShiftListenerRegistration = null
    }

    /**
     * Stops realtime orders listeners.
     */
    fun stopRealtimeOrdersListener() {
        ordersListenerRegistrations.forEach { it.remove() }
        ordersListenerRegistrations.clear()
    }

    /**
     * Starts the single realtime listener for the captain's canonical work schedule.
     * The admin dashboard writes to captain_schedules/{uid}; this document is the
     * only realtime source of truth so legacy listeners cannot reintroduce deleted shifts.
     * Every snapshot replaces Room, including an empty or deleted schedule, and refreshes
     * shift alarms (an empty list cancels previously scheduled alarms).
     */
    fun startRealtimeShiftListener() {
        if (authBackendOverride != null) return

        val user = runCatching { firebaseAuth.currentUser }.getOrNull()
        if (user == null) {
            Log.d("CaptainRepository", "[ShiftListener] Waiting for authenticated captain")
            return
        }

        val uid = user.uid
        stopRealtimeShiftListener()
        Log.i("CaptainRepository", "[ShiftListener] Listening to canonical captain_schedules/$uid document")

        try {
            captainScheduleDocListenerRegistration = firestore.collection("captain_schedules")
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(
                            "CaptainRepository",
                            "[ShiftListener] Canonical schedule listener failed: ${error.message}"
                        )
                        _isShiftNetworkConnected.value = false
                        return@addSnapshotListener
                    }

                    if (snapshot == null) return@addSnapshotListener

                    _isShiftNetworkConnected.value = !snapshot.metadata.isFromCache

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // A deleted document and an empty weeklySchedule both mean no shifts.
                            val latestShifts = if (snapshot.exists()) {
                                snapshot.extractAllShifts(uid)
                            } else {
                                emptyList()
                            }

                            // Atomically replace Room and refresh/cancel alarms from the same snapshot.
                            saveShiftsAndScheduleAlarms(uid, latestShifts)
                            _isShiftSynced.value = true

                            Log.i(
                                "CaptainRepository",
                                "[ShiftListener] Applied ${latestShifts.size} canonical shifts and refreshed alarms"
                            )
                        } catch (syncError: Exception) {
                            Log.e(
                                "CaptainRepository",
                                "[ShiftListener] Failed to apply canonical shifts",
                                syncError
                            )
                            _isShiftSynced.value = false
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[ShiftListener] Error starting canonical listener: ${e.message}", e)
            _isShiftNetworkConnected.value = false
        }
    }

    /**
     * One-time sync of shifts from Firestore for manual refresh or initial load.
     * Prioritizes 'captain_schedules/{uid}', then subcollections, then collection queries.
     */
    suspend fun syncShiftsFromFirestore(): Result<List<ShiftEntity>> {
        val user = firebaseAuth.currentUser ?: return Result.failure(IllegalStateException("يرجى تسجيل الدخول أولاً"))
        val uid = user.uid
        return try {
            Log.i("CaptainRepository", "[ShiftSync] Reading canonical captain_schedules/$uid first")
            val canonicalDoc = firestore.collection("captain_schedules").document(uid).get().await()
            if (canonicalDoc.exists()) {
                val canonicalShifts = canonicalDoc.extractAllShifts(uid)
                saveShiftsAndScheduleAlarms(uid, canonicalShifts)
                _isShiftSynced.value = true
                _isShiftNetworkConnected.value = true
                Log.i("CaptainRepository", "[ShiftSync] Applied ${canonicalShifts.size} shifts from canonical schedule")
                return Result.success(canonicalShifts)
            }

            // Legacy fallback is used only when the canonical admin schedule document is absent.
            val candidateLists = mutableListOf<List<ShiftEntity>>()

            // 1. Fetch from 'captain_schedules/{uid}'
            try {
                val doc = firestore.collection("captain_schedules").document(uid).get().await()
                if (doc.exists()) {
                    val shifts = doc.extractAllShifts(uid)
                    if (shifts.isNotEmpty()) {
                        candidateLists.add(shifts)
                        Log.d("CaptainRepository", "[ShiftSync] Found ${shifts.size} shifts in captain_schedules/$uid")
                    }
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "[ShiftSync] captain_schedules/$uid fetch notice: ${e.message}")
            }

            // 1b. Fetch from 'captain_schedules/{uid}/shifts' subcollection
            try {
                val subSnap = firestore.collection("captain_schedules").document(uid).collection("shifts").get().await()
                val subList = mutableListOf<ShiftEntity>()
                for (d in subSnap.documents) {
                    subList.addAll(d.extractAllShifts(uid))
                }
                if (subList.isNotEmpty()) {
                    candidateLists.add(subList)
                    Log.d("CaptainRepository", "[ShiftSync] Found ${subList.size} shifts in captain_schedules/$uid/shifts subcollection")
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "[ShiftSync] captain_schedules/$uid/shifts subcollection notice: ${e.message}")
            }

            // 2. Fetch from 'captain_schedules' where captainId == uid
            try {
                val querySnap = firestore.collection("captain_schedules").whereEqualTo("captainId", uid).get().await()
                for (d in querySnap.documents) {
                    val shifts = d.extractAllShifts(uid)
                    if (shifts.isNotEmpty()) candidateLists.add(shifts)
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "[ShiftSync] captain_schedules query notice: ${e.message}")
            }

            // 2b. Fetch from 'captain_schedules' where captainUid == uid
            try {
                val querySnap = firestore.collection("captain_schedules").whereEqualTo("captainUid", uid).get().await()
                for (d in querySnap.documents) {
                    val shifts = d.extractAllShifts(uid)
                    if (shifts.isNotEmpty()) candidateLists.add(shifts)
                }
            } catch (_: Exception) {}

            // 2b-2. Fetch from 'captain_schedules' where driverId == uid
            try {
                val querySnap = firestore.collection("captain_schedules").whereEqualTo("driverId", uid).get().await()
                for (d in querySnap.documents) {
                    val shifts = d.extractAllShifts(uid)
                    if (shifts.isNotEmpty()) candidateLists.add(shifts)
                }
            } catch (_: Exception) {}

            // 2c. Fetch from 'captains/{uid}/shifts' subcollection
            try {
                val subSnap = firestore.collection("captains").document(uid).collection("shifts").get().await()
                val subList = mutableListOf<ShiftEntity>()
                for (d in subSnap.documents) {
                    subList.addAll(d.extractAllShifts(uid))
                }
                if (subList.isNotEmpty()) candidateLists.add(subList)
            } catch (_: Exception) {}

            // 3. Fetch from captain profile document
            try {
                val captainDoc = firestore.collection("captains").document(uid).get().await()
                if (captainDoc.exists()) {
                    val embedded = captainDoc.extractAllShifts(uid)
                    if (embedded.isNotEmpty()) {
                        candidateLists.add(embedded)
                        Log.d("CaptainRepository", "[ShiftSync] Found ${embedded.size} embedded shifts in captain doc")
                    }
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "[ShiftSync] Captain doc fetch notice: ${e.message}")
            }

            // 4. Legacy fetch from 'shifts' collection
            if (candidateLists.isEmpty()) {
                try {
                    val legacySnap = firestore.collection("shifts").whereEqualTo("captainId", uid).get().await()
                    val legacyList = legacySnap.documents.mapNotNull { it.toShiftEntity(uid) }
                    if (legacyList.isNotEmpty()) candidateLists.add(legacyList)
                } catch (e: Exception) {
                    Log.w("CaptainRepository", "[ShiftSync] Query 'shifts' notice: ${e.message}")
                }
            }

            // Choose the candidate that was most recently updated
            val chosenList = candidateLists.maxByOrNull { list ->
                list.maxOfOrNull { it.updatedAt } ?: 0L
            } ?: emptyList()

            // Always replace the local schedule, even when no valid legacy source exists.
            // Empty schedules also cancel any previously scheduled shift alarms.
            saveShiftsAndScheduleAlarms(uid, chosenList)
            _isShiftSynced.value = true
            _isShiftNetworkConnected.value = true

            if (chosenList.isEmpty()) {
                Log.i("CaptainRepository", "[ShiftSync] No canonical or valid legacy shifts found; cleared Room and shift alarms")
            } else {
                Log.i("CaptainRepository", "[ShiftSync] Synced and atomically saved ${chosenList.size} shifts locally in Room")
            }
            Result.success(chosenList)
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[ShiftSync] syncShiftsFromFirestore error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getShifts(): List<ShiftEntity> {
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull()
        return if (!uid.isNullOrBlank() && shiftDao != null) {
            shiftDao.getShiftsForCaptainFlow(uid).firstOrNull()
                ?: shiftDao.getAllShiftsFlow().firstOrNull()
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    suspend fun getCaptain(): CaptainEntity? = captainDao.getCaptain()

    /**
     * Resolves the registered captain email corresponding to a phone number.
     * Checks strictly local captain cache and 'captains' collection (NEVER drivers, merchants, or restaurants).
     */
    suspend fun lookupEmailByPhone(inputPhone: String): String? {
        val cleaned = PhoneAuthRegistry.cleanInvisibleChars(inputPhone)
        val normalizedLocal = PhoneAuthRegistry.normalizeEgyptianPhone(cleaned)
        val plainDigits = cleaned.replace(Regex("[^0-9]"), "")
        if (plainDigits.isBlank()) return null

        // 1. Check persistent PhoneAuthRegistry first (0ms, works offline & across logouts)
        PhoneAuthRegistry.getEmailForPhone(context, cleaned)?.let { cachedEmail ->
            Log.i("CaptainRepository", "[PhoneLookup] Resolved email from PhoneAuthRegistry: $cachedEmail")
            return cachedEmail
        }

        // 2. Check local Room database cache
        try {
            val local = captainDao.getCaptainByPhone(normalizedLocal)
                ?: captainDao.getCaptainByPhone(cleaned)
                ?: captainDao.getCaptainByPhone(plainDigits)
                ?: captainDao.getCaptain()?.takeIf { isSamePhone(it.phoneNumber, normalizedLocal) }
            if (local != null && local.email.isNotBlank() && local.accountType.lowercase() == "captain") {
                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, local.email)
                Log.d("CaptainRepository", "[PhoneLookup] Found email in local Room cache: ${local.email}")
                return local.email
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[PhoneLookup] Local check notice: ${e.message}")
        }

        val variantsList = PhoneAuthRegistry.generatePhoneQueryVariants(cleaned)

        // 3. Server-side Cloud Function resolution (if available)
        try {
            val cfEmail = cloudFunctionsOrderClient.resolveEmailByPhone(normalizedLocal, variantsList)
            if (!cfEmail.isNullOrBlank()) {
                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, cfEmail)
                Log.i("CaptainRepository", "[PhoneLookup] Resolved email via Cloud Function: $cfEmail")
                return cfEmail
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[PhoneLookup] Cloud Function lookup notice: ${e.message}")
        }

        // 4. Query strictly 'captains' collection where accountType == 'captain'
        val numericVariants = listOfNotNull(
            plainDigits.toLongOrNull(),
            plainDigits.removePrefix("0").toLongOrNull(),
            plainDigits.removePrefix("20").toLongOrNull()
        ).distinct()

        val foundEmail = runCatching {
            authBackend.queryCaptainEmailByPhoneVariants(variantsList, numericVariants, normalizedLocal)
        }.getOrNull()

        if (!foundEmail.isNullOrBlank()) {
            PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, foundEmail)
        }
        return foundEmail
    }

    /**
     * Sends password reset email for an entered email or phone number.
     */
    suspend fun sendPasswordReset(phoneOrEmail: String): Result<String> {
        val trimmed = PhoneAuthRegistry.cleanInvisibleChars(phoneOrEmail)
        val isEmail = trimmed.contains("@")
        val emailToSend = if (isEmail) {
            trimmed
        } else {
            lookupEmailByPhone(trimmed)
                ?: return Result.failure(Exception("لم يتم العثور على بريد إلكتروني مسجل لرقم الهاتف ($trimmed). يرجى إدخال البريد الإلكتروني المسجل مباشرة لإرسال رابط الاستعادة."))
        }

        return try {
            authBackend.sendPasswordResetEmail(emailToSend)
            val maskedEmail = if (emailToSend.contains("@")) {
                val namePart = emailToSend.substringBefore("@")
                val domain = emailToSend.substringAfter("@")
                val prefix = namePart.take(2)
                "$prefix***@$domain"
            } else emailToSend
            Result.success("تم إرسال رابط استعادة كلمة السر إلى البريد الإلكتروني المسجل ($maskedEmail) بنجاح.")
        } catch (e: Exception) {
            Result.failure(Exception("تعذر إرسال رابط استعادة كلمة السر: ${e.localizedMessage ?: e.message}"))
        }
    }

    private fun Map<String, Any?>.mapString(vararg keys: String, default: String = ""): String {
        for (key in keys) {
            val v = this[key]?.toString()?.trim()
            if (!v.isNullOrBlank() && v != "null") return v
        }
        return default
    }

    private fun Map<String, Any?>.mapDouble(vararg keys: String, default: Double = 0.0): Double {
        for (key in keys) {
            val raw = this[key] ?: continue
            when (raw) {
                is Number -> return raw.toDouble()
                is String -> raw.toDoubleOrNull()?.let { return it }
            }
        }
        return default
    }

    private fun Map<String, Any?>.mapInt(vararg keys: String, default: Int = 0): Int {
        for (key in keys) {
            val raw = this[key] ?: continue
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return default
    }

    /**
     * Validates that a Firestore document retrieved from 'captains/{uid}' is a genuine Captain account.
     * - If 'accountType' is present: it MUST equal "captain" (rejects merchants even if role='captain').
     * - If 'accountType' is missing/blank on a legacy captains/{uid} document: accepts ONLY if 'role' == "captain",
     *   preserving existing legacy fields without performing any unapproved Firestore mutation.
     * - Strictly rejects missing documents, UID mismatches, merchant accounts, and suspended/banned accounts.
     */
    private fun validateCaptainDoc(
        docData: Map<String, Any?>?,
        uid: String
    ): Result<Map<String, Any?>> {
        if (docData == null) {
            return Result.failure(
                IllegalStateException("هذا الحساب غير مسجل ككابتن في قاعدة البيانات. تطبيق الكابتن يقبل حسابات الكباتن فقط.")
            )
        }
        val docUid = docData.mapString("uid")
        if (docUid.isNotBlank() && docUid != uid) {
            return Result.failure(
                IllegalStateException("عدم تطابق معرف الحساب (UID) مع مستند الكابتن.")
            )
        }
        val accountType = docData.mapString("accountType").lowercase()
        val role = docData.mapString("role").lowercase()
        if (!isEligibleCaptainIdentity(accountType, role)) {
            val conflictingValue = accountType.ifBlank { role }
            val msg = if (conflictingValue in listOf("merchant", "restaurant", "vendor", "store")) {
                "هذا الحساب مسجل كحساب تاجر وليس كابتن. ممنوع دخول حسابات التجار إلى تطبيق الكابتن."
            } else {
                "هذا الحساب غير مسجل ككابتن (يجب أن يساوي accountType أو role القيمة captain)."
            }
            return Result.failure(IllegalStateException(msg))
        }
        if (accountType.isBlank() && role == CAPTAIN_ROLE) {
            Log.w(
                "CaptainRepository",
                "Legacy captain document captains/$uid uses role='captain' without accountType; allowing login without altering Firestore schema."
            )
        }
        val docStatus = docData.mapString("status", default = "active").lowercase()
        if (docStatus in listOf("disabled", "blocked", "suspended", "banned")) {
            return Result.failure(
                IllegalStateException("تم حظر أو تعطيل هذا الحساب. يرجى التواصل مع إدارة البطل إكسبريس.")
            )
        }
        return Result.success(docData)
    }

    private fun buildCaptainEntityFromDoc(
        uid: String,
        authEmail: String,
        authDisplayName: String,
        docData: Map<String, Any?>,
        fallbackPhone: String = "",
        isLoggedIn: Boolean = true,
        isOnline: Boolean = true
    ): CaptainEntity {
        val fullName = docData.mapString("name", "fullName", default = authDisplayName.ifBlank { "كابتن البطل" })
        val rawPhone = docData.mapString("phone", "phoneNumber", default = fallbackPhone)
        val normalizedPhone = PhoneAuthRegistry.normalizeEgyptianPhone(rawPhone).ifBlank { fallbackPhone }
        val resolvedEmail = docData.mapString("email", default = authEmail)
        val profilePath = docData.mapString("personalImage", "profileImagePath")
        val idFrontPath = docData.mapString("idCardFront", "idFrontImagePath")
        val idBackPath = docData.mapString("idCardBack", "idBackImagePath")
        val licensePath = docData.mapString("licenseImagePath", "licenseImage")
        val avatarUri = profilePath.ifBlank {
            docData.mapString("profileImageUrl", "photoUrl", "avatarUri")
        }
        val resolvedRole = docData.mapString("role", default = CAPTAIN_ROLE).lowercase()
        val resolvedAccountType = docData.mapString("accountType", default = resolvedRole).lowercase()
        return CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullName,
            phoneNumber = normalizedPhone,
            email = resolvedEmail,
            password = "", // NEVER store password
            avatarUri = avatarUri,
            avatarPresetIndex = docData.mapInt("avatarPresetIndex", default = 0),
            nationalIdUrl = docData.mapString("idCardImageUrl", "nationalIdUrl"),
            licenseUrl = docData.mapString("licenseImageUrl", "licenseUrl"),
            profileImagePath = profilePath,
            idFrontImagePath = idFrontPath,
            idBackImagePath = idBackPath,
            licenseImagePath = licensePath,
            address = docData.mapString("address"),
            accountType = resolvedAccountType.ifBlank { CAPTAIN_ACCOUNT_TYPE },
            role = resolvedRole.ifBlank { CAPTAIN_ROLE },
            status = docData.mapString("status", default = "active").lowercase(),
            availabilityStatus = docData.mapString("availabilityStatus", default = if (isOnline) "available" else "offline"),
            shiftStatus = docData.mapString("shiftStatus", default = "outside_shift"),
            vehicleType = docData.mapString("vehicleType", "vehicle", default = "سكوتر / دراجة نارية"),
            licensePlate = docData.mapString("licensePlate", default = "أ ب ج 1 2 3"),
            rating = docData.mapDouble("rating", default = 5.0).toFloat(),
            totalOrdersCount = docData.mapInt("totalOrdersCount", default = 0),
            walletBalance = docData.mapDouble("walletBalance", default = 0.0),
            isOnline = isOnline,
            isLoggedIn = isLoggedIn
        )
    }

    suspend fun login(phoneOrEmail: String, pass: String): LoginOutcome {
        val cleanedInput = PhoneAuthRegistry.cleanInvisibleChars(phoneOrEmail)
        val isEmail = cleanedInput.contains("@")
        if (cleanedInput.isBlank()) {
            return LoginOutcome.Error("يرجى إدخال البريد الإلكتروني أو رقم الهاتف.")
        }

        var authEmail = cleanedInput
        var preSignedUser: AuthUserSnapshot? = null

        // 1. Resolve registered captain email if phone number entered
        if (!isEmail) {
            val digitsOnly = cleanedInput.replace(Regex("[^0-9]"), "")
            if (digitsOnly.length < 8) {
                return LoginOutcome.Error("يرجى إدخال رقم هاتف صحيح مكون من 11 رقماً (مثال: 01012345678).")
            }

            val foundEmail = lookupEmailByPhone(cleanedInput)
            if (foundEmail != null) {
                authEmail = foundEmail
                try {
                    preSignedUser = authBackend.signInWithEmailAndPassword(authEmail, pass)
                } catch (firstErr: Exception) {
                    return mapAuthExceptionToLoginError(firstErr, isEmail = false)
                }
            } else {
                var lastCandidateException: Exception? = null
                for (cand in PhoneAuthRegistry.generateSyntheticPhoneEmails(cleanedInput)) {
                    try {
                        val res = authBackend.signInWithEmailAndPassword(cand, pass)
                        authEmail = cand
                        preSignedUser = res
                        break
                    } catch (e: Exception) {
                        lastCandidateException = e
                        val msg = e.localizedMessage?.lowercase() ?: ""
                        if (msg.contains("too-many-requests") || msg.contains("network")) {
                            break
                        }
                    }
                }
                if (preSignedUser == null) {
                    val lastMsg = lastCandidateException?.localizedMessage?.lowercase() ?: ""
                    return when {
                        lastMsg.contains("too-many-requests") ->
                            LoginOutcome.Error("تم حظر المحاولات مؤقتاً لتكرار المحاولات الخاطئة. يرجى الانتظار دقيقة والمحاولة مجدداً.")
                        lastMsg.contains("network") ->
                            LoginOutcome.Error("يرجى التحقق من اتصال الإنترنت والمحاولة مرة أخرى.")
                        else ->
                            LoginOutcome.Error("لم يتم العثور على حساب كابتن مسجل برقم الهاتف ($cleanedInput) أو كلمة المرور غير صحيحة.")
                    }
                }
            }
        }

        // 2. Authenticate with Firebase Authentication
        val initialUser = preSignedUser ?: try {
            authBackend.signInWithEmailAndPassword(authEmail, pass)
        } catch (e: Exception) {
            return mapAuthExceptionToLoginError(e, isEmail = isEmail)
        }

        // 3. Reload Firebase Auth user & enforce Email Verification unconditionally (NEVER bypass!)
        val verifiedUser = try {
            authBackend.reloadCurrentUser() ?: initialUser
        } catch (e: Exception) {
            Log.w("CaptainRepository", "User reload warning: ${e.message}")
            initialUser
        }

        if (!verifiedUser.isEmailVerified) {
            captainDao.updateLoginState(false)
            return LoginOutcome.EmailNotVerified(verifiedUser.email.ifBlank { authEmail })
        }

        val uid = verifiedUser.uid

        // 4. Fetch Captain profile EXCLUSIVELY from captains/{uid} — NEVER drivers/merchants/restaurants, NEVER auto-create!
        val rawCaptainDoc = try {
            authBackend.getCaptainDoc(uid)
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Failed reading captains/$uid during login: ${e.message}", e)
            authBackend.signOut()
            captainDao.updateLoginState(false)
            val fsEx = e as? FirebaseFirestoreException
            val errMessage = if (fsEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "تم رفض قراءة بيانات الكابتن من قواعد الأمان (PERMISSION_DENIED) للمسار captains/$uid."
            } else {
                "تعذر التحقق من بيانات حساب الكابتن في قاعدة البيانات. يرجى التحقق من الاتصال والمحاولة مجدداً."
            }
            return LoginOutcome.Error(errMessage)
        }

        val validDoc = validateCaptainDoc(rawCaptainDoc, uid).getOrElse { err ->
            authBackend.signOut()
            captainDao.updateLoginState(false)
            return LoginOutcome.Error(err.message ?: "هذا الحساب غير مصرح له بالدخول ككابتن.")
        }

        val resolvedEmail = verifiedUser.email.ifBlank { authEmail }
        val captainEntity = buildCaptainEntityFromDoc(
            uid = uid,
            authEmail = resolvedEmail,
            authDisplayName = verifiedUser.displayName,
            docData = validDoc,
            fallbackPhone = if (!isEmail) PhoneAuthRegistry.normalizeEgyptianPhone(cleanedInput) else "",
            isLoggedIn = true,
            isOnline = true
        )

        if (captainEntity.phoneNumber.isNotBlank()) {
            PhoneAuthRegistry.savePhoneEmailMapping(context, captainEntity.phoneNumber, resolvedEmail)
        }
        captainDao.insertOrUpdate(captainEntity)

        // 5. Sync verified login & online state strictly on captains/{uid}
        runCatching {
            authBackend.updateCaptainDoc(
                uid,
                mapOf(
                    "emailVerified" to true,
                    "isEmailVerified" to true,
                    "isOnline" to true,
                    "isLoggedIn" to true,
                    "availabilityStatus" to "available",
                    "presenceStatus" to "available",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        // 6. Start realtime listeners & shift/orders sync ONLY when real listeners are active
        if (authBackendOverride == null) {
            startRealtimeOrdersListener()
            startRealtimeShiftListener()
            CoroutineScope(Dispatchers.IO).launch {
                syncShiftsFromFirestore()
                syncCaptainOrdersHistoryFromFirestore()
            }
        }

        return LoginOutcome.Success
    }

    private fun mapAuthExceptionToLoginError(e: Exception, isEmail: Boolean): LoginOutcome.Error {
        if (e is FirebaseAuthInvalidCredentialsException) {
            val fieldType = if (isEmail) "البريد الإلكتروني" else "رقم الهاتف"
            return LoginOutcome.Error("كلمة المرور أو $fieldType غير صحيح. يرجى التأكد من كتابة البيانات بدقة.")
        }
        Log.w("CaptainRepository", "Firebase Auth sign in notice: ${e.message}")
        val msg = e.localizedMessage?.lowercase() ?: ""
        val userFriendlyMsg = when {
            msg.contains("incorrect") || msg.contains("malformed") || msg.contains("expired") ||
                msg.contains("invalid-credential") || msg.contains("invalid credential") ||
                msg.contains("invalid_login_credentials") ->
                "كلمة المرور أو بيانات الدخول غير صحيحة. يرجى التأكد من البيانات وكلمة المرور."
            msg.contains("user-not-found") || msg.contains("no user record") ->
                "لم يتم العثور على حساب مسجل بهذه البيانات. يرجى التأكد من البيانات أو إنشاء حساب جديد."
            msg.contains("too-many-requests") || msg.contains("blocked") ->
                "تم حظر المحاولات مؤقتاً لتكرار المحاولات الخاطئة. يرجى الانتظار دقيقة والمحاولة مجدداً."
            else ->
                "خطأ في تسجيل الدخول: ${e.localizedMessage ?: "يرجى التحقق من صحة البيانات والمحاولة مجدداً"}"
        }
        return LoginOutcome.Error(userFriendlyMsg)
    }

    /**
     * Strict session validation on app resume / splash:
     * 1. Requires non-null Firebase Auth currentUser.
     * 2. Reloads Firebase Auth user and strictly requires isEmailVerified == true.
     * 3. Fetches exclusively captains/{uid} and requires existence + accountType == "captain" + active status.
     */
    suspend fun validateCurrentSession(): Boolean {
        val currentAuth = authBackend.currentUser()
        if (currentAuth == null) {
            captainDao.updateLoginState(false)
            stopRealtimeOrdersListener()
            stopRealtimeShiftListener()
            return false
        }

        val reloadedUser = try {
            authBackend.reloadCurrentUser() ?: currentAuth
        } catch (reloadErr: Exception) {
            Log.w("CaptainRepository", "Session reload notice: ${reloadErr.message}")
            if (reloadErr is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                authBackend.signOut()
                captainDao.updateLoginState(false)
                stopRealtimeOrdersListener()
                stopRealtimeShiftListener()
                return false
            }
            currentAuth
        }

        if (!reloadedUser.isEmailVerified) {
            captainDao.updateLoginState(false)
            stopRealtimeOrdersListener()
            stopRealtimeShiftListener()
            return false
        }

        val uid = reloadedUser.uid
        return try {
            val rawDoc = authBackend.getCaptainDoc(uid)
            val validDoc = validateCaptainDoc(rawDoc, uid).getOrElse {
                authBackend.signOut()
                captainDao.updateLoginState(false)
                stopRealtimeOrdersListener()
                stopRealtimeShiftListener()
                return false
            }

            val current = captainDao.getCaptain()
            val refreshed = buildCaptainEntityFromDoc(
                uid = uid,
                authEmail = reloadedUser.email,
                authDisplayName = reloadedUser.displayName,
                docData = validDoc,
                fallbackPhone = current?.phoneNumber.orEmpty(),
                isLoggedIn = true,
                isOnline = current?.isOnline ?: (validDoc["isOnline"] as? Boolean ?: true)
            )
            if (refreshed.phoneNumber.isNotBlank() && refreshed.email.isNotBlank()) {
                PhoneAuthRegistry.savePhoneEmailMapping(context, refreshed.phoneNumber, refreshed.email)
            }
            captainDao.insertOrUpdate(refreshed)

            if (authBackendOverride == null) {
                startRealtimeOrdersListener()
                startRealtimeShiftListener()
                CoroutineScope(Dispatchers.IO).launch {
                    reconcileDailyWallets()
                }
            }
            true
        } catch (e: Exception) {
            Log.w("CaptainRepository", "Session validation network check: ${e.message}")
            val current = captainDao.getCaptain()
            if (reloadedUser.isEmailVerified &&
                current != null &&
                current.isLoggedIn &&
                current.uid == uid &&
                current.accountType.lowercase() == "captain" &&
                current.status.lowercase() !in listOf("disabled", "blocked", "suspended", "banned")
            ) {
                if (authBackendOverride == null) {
                    startRealtimeOrdersListener()
                    startRealtimeShiftListener()
                }
                true
            } else {
                captainDao.updateLoginState(false)
                stopRealtimeOrdersListener()
                stopRealtimeShiftListener()
                false
            }
        }
    }

    /**
     * Uploads Captain Personal Photo to Firebase Storage ("captains/{captainId}/profile/profile.jpg"),
     * saves "profileImagePath" in Firestore captains/{captainId}, and updates Room ONLY after both succeed.
     */
    suspend fun uploadAndSaveProfilePhoto(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع الصورة الشخصية"))

        val uploadResult = storageRepository.uploadCaptainProfileImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "profileImagePath" to storagePath,
                    "profileImageUpdatedAt" to FieldValue.serverTimestamp()
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                profileImagePath = storagePath,
                avatarUri = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تم رفع الصورة لكن تعذر حفظ المسار في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Uploads National ID Front ("captains/{captainId}/documents/id_front.jpg") privately to Firebase Storage,
     * saves "idFrontImagePath" in Firestore captains/{captainId}, and NEVER marks ID complete until BOTH sides succeed.
     */
    suspend fun uploadAndSaveIdFront(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع البطاقة الشخصية"))
        if (current?.effectiveIdFrontImage?.isNotBlank() == true) {
            return Result.failure(
                IllegalStateException("تم قفل الوجه الأمامي للبطاقة بعد التوثيق ولا يمكن تعديله. لتحديث بيانات البطاقة الشخصية، يرجى التواصل مع الدعم الفني.")
            )
        }

        val uploadResult = storageRepository.uploadCaptainIdFrontImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            val hasBack = current?.effectiveIdBackImage?.isNotBlank() == true
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "idFrontImagePath" to storagePath,
                    "idFrontUploadedAt" to FieldValue.serverTimestamp(),
                    "idDocumentsComplete" to hasBack
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                idFrontImagePath = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تعذر حفظ بيانات الوجه الأمامي للبطاقة في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Uploads National ID Back ("captains/{captainId}/documents/id_back.jpg") privately to Firebase Storage,
     * saves "idBackImagePath" in Firestore captains/{captainId}, and verifies completion of both front & back.
     */
    suspend fun uploadAndSaveIdBack(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع البطاقة الشخصية"))
        if (current?.effectiveIdBackImage?.isNotBlank() == true) {
            return Result.failure(
                IllegalStateException("تم قفل الوجه الخلفي للبطاقة بعد التوثيق ولا يمكن تعديله. لتحديث بيانات البطاقة الشخصية، يرجى التواصل مع الدعم الفني.")
            )
        }

        val uploadResult = storageRepository.uploadCaptainIdBackImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            val hasFront = current?.effectiveIdFrontImage?.isNotBlank() == true
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "idBackImagePath" to storagePath,
                    "idBackUploadedAt" to FieldValue.serverTimestamp(),
                    "idDocumentsComplete" to hasFront
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                idBackImagePath = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تعذر حفظ بيانات الوجه الخلفي للبطاقة في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Uploads Driving/Vehicle License ("captains/{captainId}/documents/license.jpg") privately to Firebase Storage
     * and saves "licenseImagePath" in Firestore captains/{captainId} and Room.
     */
    suspend fun uploadAndSaveLicensePhoto(
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val current = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: current?.uid?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع صورة الرخصة"))

        val uploadResult = storageRepository.uploadCaptainLicenseImage(
            captainId = uid,
            imageUri = imageUri,
            onStateChange = onStateChange
        )
        val storagePath = uploadResult.getOrElse { return Result.failure(it) }

        return try {
            cloudMetadataPort.saveCaptainImageMetadata(
                uid = uid,
                fields = mapOf(
                    "licenseImagePath" to storagePath,
                    "licenseImageUpdatedAt" to FieldValue.serverTimestamp()
                )
            )
            val updated = (current ?: CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "كابتن البطل",
                phoneNumber = "",
                isLoggedIn = true
            )).copy(
                uid = uid,
                licenseImagePath = storagePath
            )
            captainDao.insertOrUpdate(updated)
            Result.success(storagePath)
        } catch (e: Exception) {
            val errMsg = "تم رفع الرخصة لكن تعذر حفظ المسار في قاعدة البيانات: ${e.localizedMessage ?: e.message}"
            onStateChange(ImageUploadState.Failed(errMsg))
            Result.failure(IllegalStateException(errMsg, e))
        }
    }

    /**
     * Rehydrates Captain image paths from Firestore into Room (used after re-login or session restore).
     * Preserves legacy ImgBB URLs untouched while prioritizing Firebase Storage paths.
     */
    suspend fun rehydrateCaptainImagesFromCloud(
        uid: String,
        fullNameFallback: String = "كابتن البطل",
        phoneFallback: String = "",
        emailFallback: String = ""
    ): Result<CaptainEntity> {
        val meta = cloudMetadataPort.fetchCaptainImageMetadata(uid)
            ?: return Result.failure(IllegalStateException("لم يتم العثور على مستند الكابتن في السحابة"))

        val current = captainDao.getCaptain()
        val profilePath = meta["profileImagePath"].orEmpty()
        val idFrontPath = meta["idFrontImagePath"].orEmpty()
        val idBackPath = meta["idBackImagePath"].orEmpty()
        val licensePath = meta["licenseImagePath"].orEmpty()
        val legacyAvatar = meta["profileImageUrl"].orEmpty()
        val legacyId = meta["idCardImageUrl"].orEmpty()
        val legacyLicense = meta["licenseImageUrl"].orEmpty()

        val entity = (current ?: CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullNameFallback,
            phoneNumber = phoneFallback,
            email = emailFallback,
            isLoggedIn = true,
            isOnline = true
        )).copy(
            uid = uid,
            isLoggedIn = true,
            profileImagePath = profilePath,
            idFrontImagePath = idFrontPath,
            idBackImagePath = idBackPath,
            licenseImagePath = licensePath,
            avatarUri = profilePath.ifBlank { legacyAvatar },
            nationalIdUrl = legacyId,
            licenseUrl = legacyLicense
        )
        captainDao.insertOrUpdate(entity)
        return Result.success(entity)
    }

    private suspend fun uploadRegistrationImageIfPresent(
        uriString: String,
        uploader: suspend (Uri) -> Result<String>
    ): String {
        if (uriString.isBlank()) return ""
        if (!uriString.startsWith("content://") && !uriString.startsWith("file://")) {
            return uriString
        }
        val parsed = Uri.parse(uriString)
        return uploader(parsed).getOrThrow()
    }

    /**
     * Register new captain:
     * 1. Create Firebase Auth user with email & password
     * 2. Send Firebase Email Verification
     * 3. Upload captain images strictly to Firebase Storage under captains/{uid}/...
     * 4. Create ONE document ONLY in Cloud Firestore: captains/{uid} with accountType = "captain"
     *    (NEVER creates or edits documents in merchants, restaurants, drivers, or users)
     */
    suspend fun registerCaptainWithEmailVerification(
        fullName: String,
        phone: String,
        email: String,
        pass: String,
        address: String = "",
        avatarPresetIndex: Int = 0,
        avatarUri: String = "",
        vehicleType: String = "سكوتر / دراجة نارية",
        nationalIdUri: String = "",
        idBackUri: String = "",
        licenseUri: String = ""
    ): Result<String> {
        val trimmedEmail = PhoneAuthRegistry.cleanInvisibleChars(email)
        val trimmedPhone = PhoneAuthRegistry.normalizeEgyptianPhone(phone)
        val trimmedName = fullName.trim()
        val trimmedAddress = address.trim()

        var createdAuthUser = false
        try {
            // Step 1: Create new Firebase Auth user & obtain UID
            val firebaseUser = authBackend.createUserWithEmailAndPassword(trimmedEmail, pass)
            createdAuthUser = true
            val uid = firebaseUser.uid
            Log.i("CaptainRepository", "AUTH SUCCESS - CAPTAIN UID: $uid")

            // Step 2: Upload captain images strictly to Firebase Storage under captains/{uid}/
            // Perform uploads BEFORE finalizing Firestore doc so no unbacked profile exists.
            val profilePath = uploadRegistrationImageIfPresent(avatarUri) {
                storageRepository.uploadCaptainProfileImage(uid, it)
            }
            val idFrontPath = uploadRegistrationImageIfPresent(nationalIdUri) {
                storageRepository.uploadCaptainIdFrontImage(uid, it)
            }
            val idBackPath = uploadRegistrationImageIfPresent(idBackUri) {
                storageRepository.uploadCaptainIdBackImage(uid, it)
            }
            val licensePath = uploadRegistrationImageIfPresent(licenseUri) {
                storageRepository.uploadCaptainLicenseImage(uid, it)
            }

            // Step 3: Create single document ONLY in captains/{uid} with canonical accountType + role
            val now = System.currentTimeMillis()
            val captainDocData = buildNewCaptainDocumentData(
                uid = uid,
                name = trimmedName,
                email = trimmedEmail,
                phone = trimmedPhone,
                profilePath = profilePath,
                idFrontPath = idFrontPath,
                idBackPath = idBackPath,
                licensePath = licensePath,
                address = trimmedAddress,
                vehicleType = vehicleType,
                avatarPresetIndex = avatarPresetIndex,
                createdAtMs = now
            )

            try {
                authBackend.createCaptainDoc(uid, captainDocData)
                Log.i("CaptainRepository", "FIRESTORE CAPTAIN CREATE SUCCESS: captains/$uid")
            } catch (fsEx: Exception) {
                val firestoreCode = (fsEx as? FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
                Log.e("CaptainRepository", "FIRESTORE CAPTAIN CREATE ERROR - code: $firestoreCode, message: ${fsEx.message}", fsEx)
                if (createdAuthUser) {
                    runCatching { authBackend.deleteCurrentUser() }
                }
                return Result.failure(
                    Exception("فشل إنشاء ملف الكابتن في Firestore: [$firestoreCode] ${fsEx.localizedMessage ?: fsEx.message}")
                )
            }

            // Step 4: Send Firebase Email Verification & update Auth display name
            try {
                authBackend.sendEmailVerification()
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not send verification email: ${e.message}")
            }

            try {
                authBackend.updateDisplayName(trimmedName)
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not set displayName: ${e.message}")
            }

            // Step 5: Save locally in Room with isLoggedIn = false until email is verified
            val newCaptain = CaptainEntity(
                id = 1,
                uid = uid,
                fullName = trimmedName,
                phoneNumber = trimmedPhone,
                email = trimmedEmail,
                password = "",
                avatarUri = profilePath,
                avatarPresetIndex = avatarPresetIndex,
                profileImagePath = profilePath,
                idFrontImagePath = idFrontPath,
                idBackImagePath = idBackPath,
                licenseImagePath = licensePath,
                address = trimmedAddress,
                accountType = "captain",
                role = "captain",
                status = "pending",
                availabilityStatus = "offline",
                shiftStatus = "outside_shift",
                vehicleType = vehicleType,
                licensePlate = "ب ط ل 1 0 0",
                rating = 5.0f,
                totalOrdersCount = 0,
                walletBalance = 0.0,
                isOnline = false,
                isLoggedIn = false
            )
            captainDao.insertOrUpdate(newCaptain)
            PhoneAuthRegistry.savePhoneEmailMapping(context, trimmedPhone, trimmedEmail)

            return Result.success(trimmedEmail)
        } catch (e: FirebaseAuthUserCollisionException) {
            // Strictly reject email collision — NEVER reuse existing account or overwrite/merge captains doc
            return Result.failure(
                Exception("هذا البريد الإلكتروني مسجل بالفعل. لا يمكن إنشاء حساب كابتن جديد بنفس البريد الإلكتروني.")
            )
        } catch (e: FirebaseAuthWeakPasswordException) {
            return Result.failure(Exception("كلمة السر ضعيفة جداً. يجب أن تتكون من 6 خانات على الأقل."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            return Result.failure(Exception("صيغة البريد الإلكتروني غير صحيحة. يرجى التأكد من كتابة البريد بشكل صحيح."))
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Registration failure", e)
            if (createdAuthUser) {
                runCatching { authBackend.deleteCurrentUser() }
            }
            val msg = e.localizedMessage ?: "حدث خطأ أثناء التسجيل"
            return Result.failure(Exception("فشل إنشاء الحساب: $msg"))
        }
    }

    /**
     * Reloads Firebase Auth user and checks isEmailVerified strictly against Firebase Authentication.
     * Upon verified == true, validates captains/{uid} (existence + accountType == "captain") before granting access.
     */
    suspend fun checkEmailVerification(): Result<Boolean> {
        return try {
            val reloaded = authBackend.reloadCurrentUser()
                ?: return Result.failure(Exception("لم يتم العثور على جلسة مستخدم نشطة. يرجى تسجيل الدخول."))

            if (!reloaded.isEmailVerified) {
                captainDao.updateLoginState(false)
                return Result.success(false)
            }

            val rawDoc = authBackend.getCaptainDoc(reloaded.uid)
            val validDoc = validateCaptainDoc(rawDoc, reloaded.uid).getOrElse { err ->
                authBackend.signOut()
                captainDao.updateLoginState(false)
                return Result.failure(err)
            }

            val current = captainDao.getCaptain()
            val verifiedCaptain = buildCaptainEntityFromDoc(
                uid = reloaded.uid,
                authEmail = reloaded.email,
                authDisplayName = reloaded.displayName,
                docData = validDoc,
                fallbackPhone = current?.phoneNumber.orEmpty(),
                isLoggedIn = true,
                isOnline = true
            )
            captainDao.insertOrUpdate(verifiedCaptain)

            runCatching {
                authBackend.updateCaptainDoc(
                    reloaded.uid,
                    mapOf(
                        "emailVerified" to true,
                        "isEmailVerified" to true,
                        "isLoggedIn" to true,
                        "isOnline" to true,
                        "availabilityStatus" to "available",
                        "presenceStatus" to "available",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            if (authBackendOverride == null) {
                syncFcmToken()
                startRealtimeOrdersListener()
                startRealtimeShiftListener()
            }

            Result.success(true)
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error checking verification", e)
            Result.failure(Exception(e.localizedMessage ?: "تعذر التحقق من حالة البريد الإلكتروني"))
        }
    }

    suspend fun resendVerificationEmail(): Result<String> {
        return try {
            val user = authBackend.currentUser()
                ?: return Result.failure(Exception("تعذر العثور على حسابك لإعادة الإرسال. يرجى تسجيل الدخول."))
            authBackend.sendEmailVerification()
            Result.success("تم إرسال رابط تأكيد جديد إلى ${user.email}")
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error resending email", e)
            Result.failure(Exception(e.localizedMessage ?: "تعذر إعادة إرسال رابط التأكيد حالياً"))
        }
    }

    private var lastUploadedLocation: Location? = null
    private var lastLocationUploadTimestamp: Long = 0L

    suspend fun updateCaptainGpsLocation(location: Location) {
        val user = firebaseAuth.currentUser ?: return
        if (!user.isEmailVerified) return
        val uid = user.uid
        if (uid.isBlank()) return

        val now = System.currentTimeMillis()
        val lastLoc = lastUploadedLocation
        val distance = if (lastLoc != null) location.distanceTo(lastLoc) else Float.MAX_VALUE
        val timeDiff = now - lastLocationUploadTimestamp

        // Smart Throttling: send only if moved >= MIN_DISPLACEMENT_METERS or heartbeat interval elapsed
        if (lastLoc != null && distance < GpsConfig.MIN_DISPLACEMENT_METERS && timeDiff < GpsConfig.FIRESTORE_HEARTBEAT_INTERVAL_MS) {
            return
        }

        val activeOrder = activeOrderFlow.firstOrNull()
        val presenceValue = if (activeOrder != null) "on_trip" else "available"

        val locationData = hashMapOf<String, Any>(
            "location" to GeoPoint(location.latitude, location.longitude),
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy.toDouble(),
            "bearing" to location.bearing.toDouble(),
            "speed" to location.speed.toDouble(),
            "isOnline" to true,
            "presenceStatus" to presenceValue,
            "availabilityStatus" to presenceValue,
            "lastLocationUpdate" to FieldValue.serverTimestamp(),
            "lastLocationTimestamp" to now,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            firestore.collection("captains").document(uid).update(locationData).await()
            lastUploadedLocation = location
            lastLocationUploadTimestamp = now
            Log.d("CaptainRepository", "Uploaded GPS location to Firestore for captain $uid: ${location.latitude}, ${location.longitude}")
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error uploading GPS location to Firestore", e)
        }
    }

    suspend fun markCaptainOfflineInFirestore() {
        val user = firebaseAuth.currentUser ?: return
        val uid = user.uid
        if (uid.isBlank()) return

        val offlineData = mapOf<String, Any>(
            "isOnline" to false,
            "presenceStatus" to "offline",
            "availabilityStatus" to "offline",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    firestore.collection("captains").document(uid).update(offlineData).await()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("CaptainRepository", "Updating captain offline doc notice: ${e.message}")
                }
            }
        } catch (_: CancellationException) {
            // Coroutine cancelled cleanly, ignore
        } catch (e: Exception) {
            Log.w("CaptainRepository", "Notice updating offline status in Firestore: ${e.message}")
        }
    }

    suspend fun setOnlineStatus(isOnline: Boolean) {
        captainDao.updateOnlineStatus(isOnline)
        if (!isOnline) {
            orderDao.deleteAllPendingOrders()
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it) }
        }
        val uid = runCatching { authBackend.currentUser()?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { cloudMetadataPort.currentAuthUid() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (!uid.isNullOrBlank()) {
            val statusValue = if (isOnline) "available" else "offline"
            val statusMap = mapOf<String, Any>(
                "isOnline" to isOnline,
                "presenceStatus" to statusValue,
                "availabilityStatus" to statusValue,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                firestore.collection("captains").document(uid).update(statusMap)
            } catch (e: Exception) {
                Log.e("CaptainRepository", "Error updating online status in Firestore", e)
            }
        }
    }

    suspend fun logout() {
        stopRealtimeShiftListener()
        stopRealtimeOrdersListener()
        context?.let { ctx ->
            ShiftNotificationScheduler.cancelAllShiftAlarms(ctx)
            CaptainNotificationManager.clearNotificationState(ctx)
        }
        storageRepository.clearSessionMemoryCache()
        try {
            val uid = authBackend.currentUser()?.uid
            if (!uid.isNullOrBlank()) {
                firestore.collection("captains").document(uid).update(
                    mapOf(
                        "isLoggedIn" to false,
                        "isOnline" to false,
                        "availabilityStatus" to "offline",
                        "presenceStatus" to "offline"
                    )
                )
            }
        } catch (_: Exception) {}
        try {
            authBackend.signOut()
        } catch (_: Exception) {}
        captainDao.clearAll()
        shiftDao?.clearAll()
        orderDao.clearAll()
        dailyWalletDao?.clearAll()
    }

    suspend fun updateProfile(
        fullName: String,
        phoneNumber: String,
        email: String,
        vehicleType: String,
        licensePlate: String,
        avatarUri: String = "",
        address: String? = null
    ): Result<Unit> {
        val current = captainDao.getCaptain()
        val normalizedPhone = PhoneAuthRegistry.normalizeEgyptianPhone(phoneNumber)
        val cleanEmail = PhoneAuthRegistry.cleanInvisibleChars(email).ifBlank { current?.email.orEmpty() }
        val resolvedAddress = (address ?: current?.address.orEmpty()).trim()
        if (normalizedPhone.isBlank()) {
            return Result.failure(IllegalArgumentException("رقم الهاتف مطلوب ولا يمكن أن يكون فارغاً"))
        }

        val uid = cloudMetadataPort.currentAuthUid() ?: current?.uid ?: ""
        var resolvedProfilePath = current?.profileImagePath ?: ""
        val resolvedAvatarDisplay: String

        if (avatarUri.isNotBlank() && (avatarUri.startsWith("content://") || avatarUri.startsWith("file://"))) {
            if (uid.isBlank()) {
                return Result.failure(IllegalStateException("يجب تسجيل الدخول لرفع الصورة الشخصية إلى Firebase Storage"))
            }
            val uploadRes = storageRepository.uploadCaptainProfileImage(uid, Uri.parse(avatarUri))
            val uploadedPath = uploadRes.getOrElse { return Result.failure(it) }
            resolvedProfilePath = uploadedPath
            resolvedAvatarDisplay = uploadedPath
        } else {
            resolvedAvatarDisplay = avatarUri.ifBlank { current?.effectiveProfileImage ?: "" }
            if (FirebaseStorageRepository.isFirebaseStoragePath(resolvedAvatarDisplay)) {
                resolvedProfilePath = resolvedAvatarDisplay
            }
        }

        if (uid.isNotBlank()) {
            try {
                val coreProfileMap = mutableMapOf<String, Any>(
                    "fullName" to fullName.trim(),
                    "name" to fullName.trim(),
                    "phone" to normalizedPhone,
                    "phoneNumber" to normalizedPhone,
                    "address" to resolvedAddress,
                    "vehicleType" to vehicleType.trim(),
                    "licensePlate" to licensePlate.trim(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (resolvedProfilePath.isNotBlank()) {
                    coreProfileMap["profileImagePath"] = resolvedProfilePath
                    coreProfileMap["personalImage"] = resolvedProfilePath
                    coreProfileMap["avatarUri"] = resolvedProfilePath
                    coreProfileMap["profileImageUpdatedAt"] = FieldValue.serverTimestamp()
                }
                cloudMetadataPort.saveCaptainImageMetadata(uid, coreProfileMap)
            } catch (e: Exception) {
                Log.e("CaptainRepository", "Error updating profile in Firestore", e)
                return Result.failure(e)
            }
            if (authBackendOverride == null) {
                runCatching { startRealtimeShiftListener() }
            }
        }

        val updated = (current ?: CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullName.trim(),
            phoneNumber = normalizedPhone,
            email = cleanEmail,
            password = "",
            avatarUri = resolvedAvatarDisplay,
            profileImagePath = resolvedProfilePath,
            address = resolvedAddress,
            vehicleType = vehicleType.trim(),
            licensePlate = licensePlate.trim(),
            rating = 5.0f,
            walletBalance = 0.0,
            isOnline = true,
            isLoggedIn = true,
            accountType = "captain",
            role = "captain",
            status = "active"
        )).copy(
            fullName = fullName.trim(),
            phoneNumber = normalizedPhone,
            email = cleanEmail,
            address = resolvedAddress,
            vehicleType = vehicleType.trim(),
            licensePlate = licensePlate.trim(),
            avatarUri = resolvedAvatarDisplay,
            profileImagePath = resolvedProfilePath
        )
        captainDao.insertOrUpdate(updated)
        PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedPhone, cleanEmail)
        return Result.success(Unit)
    }

    private suspend fun resolveCaptainUidAndPhone(): Pair<String, String> {
        val captain = captainDao.getCaptain()
        val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: captain?.uid?.takeIf { it.isNotBlank() }
            ?: ""
        val phone = captain?.phoneNumber.orEmpty()
        return uid to phone
    }

    suspend fun acceptOrder(orderId: Long): Result<Unit> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        // Verify captain is currently online and active before assigning/accepting any order
        val currentCaptain = captainDao.getCaptain()
        if (currentCaptain != null && !currentCaptain.isOnline) {
            Log.w("CaptainRepository", "[acceptOrder] Blocked order acceptance: captain is offline")
            return Result.failure(IllegalStateException("لا يمكنك قبول أو استلام طلبات وأنت في وضع غير متصل."))
        }
        if (currentCaptain != null &&
            currentCaptain.status.trim().lowercase() in listOf("banned", "suspended", "disabled", "blocked", "pending", "inactive")
        ) {
            return Result.failure(IllegalStateException("حسابك غير مفعل حالياً لاستقبال أو قبول الطلبات."))
        }

        // Check if captain already has an ongoing active order
        val activeOrder = activeOrderFlow.firstOrNull()
        if (activeOrder != null && activeOrder.id != orderId) {
            return Result.failure(IllegalStateException("لديك طلب نشط بالفعل قيد التنفيذ، لا يمكن قبول أكثر من طلب في وقت واحد."))
        }

        // Verify working shift status: captain can only accept new orders during their active shift
        val currentShifts = getShifts()
        if (currentShifts.isNotEmpty()) {
            val shiftStatus = ShiftManager.calculateShiftStatus(currentShifts)
            if (!shiftStatus.isAvailableForOrders) {
                Log.w("CaptainRepository", "[acceptOrder] Blocked order acceptance: captain is outside active working shift")
                return Result.failure(IllegalStateException("عفواً، أنت خارج ساعات العمل الرسمية ولا يمكنك قبول طلبات جديدة حالياً."))
            }
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.acceptDeliveryOffer(orderDocId)

        return if (finalRes.isSuccess) {
            context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }
            orderDao.markOrderAccepted(orderId, currentUid, System.currentTimeMillis())
            reconcileDailyWallets()
            Result.success(Unit)
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            // Remove locally ONLY if confirmed gone/claimed on server/Firestore
            // (never when Cloud Function endpoint is 404 or on network/5xx/permission failure).
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }
                orderDao.deleteOrderById(orderId)
            }
            Result.failure(err ?: Exception("تعذر الاتصال بالخادم لقبول الطلب. يرجى إعادة المحاولة."))
        }
    }

    private fun resolveCleanOrderDocId(order: OrderEntity): String {
        val raw = order.firestoreDocId.trim().ifBlank { order.orderNumber.trim() }
        return raw.trimStart('#').trim()
    }

    /**
     * Removes a stale or cancelled order from local Room cache when the server confirms
     * it no longer exists (404 / cancelled on backend).
     */
    suspend fun removeStaleOrder(orderId: Long) {
        val existing = allOrdersFlow.firstOrNull()?.find { it.id == orderId }
        context?.let {
            CaptainNotificationManager.stopIncomingOrderBell(
                it,
                existing?.firestoreDocId.orEmpty()
            )
        }
        orderDao.deleteOrderById(orderId)
        reconcileDailyWallets()
    }

    /**
     * 2. Arrived at merchant: calls Cloud Function `captainArrivedAtMerchant` (Server-only; zero direct Firestore fallback).
     */
    suspend fun markAtStore(orderId: Long): Result<String> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.captainArrivedAtMerchant(orderDocId)

        if (finalRes.isSuccess) {
            orderDao.updateOrderStatusAndCaptain(orderId, OrderStatus.AT_STORE, currentUid)
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return finalRes
    }

    /**
     * 3. Confirm pickup from merchant: calls Cloud Function `confirmPickup` (Server-only; zero direct Firestore fallback).
     */
    suspend fun confirmPickup(orderId: Long): Result<String> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.confirmPickup(orderDocId)

        if (finalRes.isSuccess) {
            orderDao.updateOrderStatusAndCaptain(orderId, OrderStatus.PICKED_UP, currentUid)
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return finalRes
    }

    /**
     * 4. Submit customer details & receipt image to Firebase Storage ("orders/{orderId}/receipts/{uniqueFileName}")
     * and link receiptImagePath, receiptUploadedAt (ServerTimestamp), and receiptUploadedBy via Cloud Functions
     * (`submitCustomerDeliveryDetails`, server-only; zero direct Firestore fallback).
     * Never marks receipt saved until BOTH Firebase Storage upload AND Cloud Function submission succeed.
     */
    suspend fun saveOrderCustomerData(
        orderId: Long,
        customerName: String,
        customerPhone: String,
        notes: String,
        imageUri: Uri,
        onUploadStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(IllegalArgumentException("الطلب غير موجود"))

        val captainUid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً قبل رفع الإيصال"))
        val currentCaptainPhone = captainDao.getCaptain()?.phoneNumber.orEmpty()

        val normalizedAssignedCaptainId = if (
            order.captainId.trim() != captainUid &&
            currentCaptainPhone.isNotBlank() &&
            isSamePhone(order.captainId, currentCaptainPhone)
        ) {
            captainUid
        } else {
            order.captainId
        }

        val orderDocId = resolveCleanOrderDocId(order)

        // Enforce order ownership & explicit allowed lifecycle state before uploading to Firebase Storage
        val uploadResult = storageRepository.uploadOrderReceiptImage(
            orderId = orderDocId,
            assignedCaptainId = normalizedAssignedCaptainId,
            orderStatus = order.status,
            imageUri = imageUri,
            onStateChange = onUploadStateChange
        )
        val uploadedStoragePath = uploadResult.getOrElse { err ->
            return Result.failure(err)
        }

        val finalServerRes = cloudMetadataPort.submitOrderReceiptDetails(
            orderDocId = orderDocId,
            customerName = customerName,
            customerPhone = customerPhone,
            receiptImagePath = uploadedStoragePath,
            notes = notes
        )

        return if (finalServerRes.isSuccess) {
            val nowMs = System.currentTimeMillis()
            orderDao.updateCustomerDetails(
                orderId = orderId,
                customerName = customerName,
                customerPhone = customerPhone,
                imageUrl = uploadedStoragePath,
                notes = notes,
                status = OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
                receiptImagePath = uploadedStoragePath,
                receiptUploadedAt = nowMs,
                receiptUploadedBy = captainUid
            )
            Result.success(uploadedStoragePath)
        } else {
            val serverErr = finalServerRes.exceptionOrNull() ?: Exception("فشل ربط بيانات الإيصال بالطلب على الخادم")
            val cfEx = serverErr as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
            onUploadStateChange(
                ImageUploadState.Failed(
                    errorMessage = serverErr.localizedMessage ?: "فشل حفظ بيانات الإيصال على الخادم",
                    isRetryable = true
                )
            )
            Result.failure(serverErr)
        }
    }

    suspend fun saveOrderDataAndImage(orderId: Long, imageUri: Uri): Result<String> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
        val name = order?.customerName ?: "عميل البطل"
        val phone = order?.customerPhone ?: ""
        return saveOrderCustomerData(orderId, name, phone, "", imageUri)
    }

    /**
     * 5. Complete delivery: calls Cloud Function `confirmDelivery` (Server-only; zero direct Firestore fallback)
     * ONLY when merchant has confirmed (EN_ROUTE).
     * Never updates local order status or wallet if the backend call fails.
     */
    suspend fun completeDelivery(orderId: Long): Result<Double> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))

        if (order.status != OrderStatus.EN_ROUTE) {
            return Result.failure(
                IllegalStateException("لا يمكن تأكيد التسليم قبل اعتماد التاجر لبيانات الإيصال وتحول الطلب إلى حالة التوصيل.")
            )
        }

        val (currentUid, currentPhone) = resolveCaptainUidAndPhone()
        if (currentUid.isBlank()) {
            return Result.failure(Exception("يجب تسجيل الدخول أولاً"))
        }

        val orderDocId = resolveCleanOrderDocId(order)
        val finalRes = cloudFunctionsOrderClient.confirmDelivery(orderDocId)

        if (finalRes.isSuccess) {
            val earnedFee = finalRes.getOrNull()?.takeIf { it > 0.0 } ?: order.deliveryFee
            val nowMs = System.currentTimeMillis()
            orderDao.markOrderDelivered(orderId, currentUid, nowMs)

            // Immediately reflect in captain's overall wallet & total completed orders
            captainDao.addToWallet(earnedFee)
            val captain = captainDao.getCaptain()
            if (captain != null) {
                captainDao.insertOrUpdate(
                    captain.copy(
                        totalOrdersCount = captain.totalOrdersCount + 1
                    )
                )
            }
            reconcileDailyWallets()
        } else {
            val err = finalRes.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && cfEx.isNotFound && !cfEx.isEndpointNotFound) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return finalRes
    }

    /**
     * 6. Expire delivery offer after 60 seconds via server-verified Cloud Function.
     * Client timer stops ringing locally and lets Cloud Tasks / server handle reassignment.
     */
    suspend fun expireDeliveryOffer(orderId: Long): Result<Unit> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))
        val orderDocId = resolveCleanOrderDocId(order)
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }

        val finalRes = cloudFunctionsOrderClient.expireDeliveryOffer(orderDocId, reason = "expired")

        // Always mark EXPIRED locally once the 60s offer window finishes so the order leaves this
        // captain's screen and never keeps ringing locally.
        orderDao.updateOrderStatus(orderId, OrderStatus.EXPIRED)
        return if (finalRes.isSuccess) finalRes else Result.success(Unit)
    }

    /**
     * 7. Decline incoming delivery offer via Cloud Function (zero direct Firestore fallback).
     */
    suspend fun declineOrder(orderId: Long): Result<Unit> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))
        val orderDocId = resolveCleanOrderDocId(order)
        context?.let { CaptainNotificationManager.stopIncomingOrderBell(it, orderDocId) }
        val finalRes = cloudFunctionsOrderClient.expireDeliveryOffer(orderDocId, reason = "declined")

        if (finalRes.isSuccess) {
            orderDao.markOrderCancelled(orderId, System.currentTimeMillis(), "تم رفض العرض")
        }
        return finalRes
    }

    companion object {
        const val CAPTAIN_ACCOUNT_TYPE = "captain"
        const val CAPTAIN_ROLE = "captain"

        /**
         * Determines whether a document inside `captains/{uid}` has a valid Captain identity:
         * - If `accountType` is present and non-blank: it MUST equal `"captain"` (blocks merchants even if `role` is set).
         * - If `accountType` is missing/blank on a legacy document: accepts ONLY when `role` equals `"captain"`.
         * - If `role` is also present alongside `accountType == "captain"`, `role` must not be a conflicting non-captain role.
         */
        fun isEligibleCaptainIdentity(rawAccountType: String?, rawRole: String?): Boolean {
            val cleanAccountType = rawAccountType?.trim()?.lowercase().orEmpty()
            val cleanRole = rawRole?.trim()?.lowercase().orEmpty()
            if (cleanAccountType.isNotEmpty() && cleanAccountType != "null") {
                if (cleanAccountType != CAPTAIN_ACCOUNT_TYPE) return false
                if (cleanRole.isNotEmpty() && cleanRole != "null" && cleanRole !in listOf(CAPTAIN_ROLE, "driver")) {
                    return false
                }
                return true
            }
            return cleanRole == CAPTAIN_ROLE
        }

        /**
         * Builds the canonical Firestore payload for `captains/{uid}` on new captain registration,
         * always writing both `accountType = "captain"` and `role = "captain"` uniformly.
         */
        fun buildNewCaptainDocumentData(
            uid: String,
            name: String,
            email: String,
            phone: String,
            profilePath: String,
            idFrontPath: String,
            idBackPath: String,
            licensePath: String,
            address: String,
            vehicleType: String,
            avatarPresetIndex: Int,
            createdAtMs: Long
        ): HashMap<String, Any> {
            return hashMapOf(
                "uid" to uid,
                "name" to name,
                "fullName" to name,
                "email" to email,
                "phone" to phone,
                "phoneNumber" to phone,
                "personalImage" to profilePath,
                "profileImagePath" to profilePath,
                "idCardFront" to idFrontPath,
                "idFrontImagePath" to idFrontPath,
                "idCardBack" to idBackPath,
                "idBackImagePath" to idBackPath,
                "licenseImagePath" to licensePath,
                "idDocumentsComplete" to (idFrontPath.isNotBlank() && idBackPath.isNotBlank()),
                "address" to address,
                "accountType" to CAPTAIN_ACCOUNT_TYPE,
                "role" to CAPTAIN_ROLE,
                "status" to "pending",
                "emailVerified" to false,
                "isEmailVerified" to false,
                "createdAt" to createdAtMs,
                "updatedAt" to FieldValue.serverTimestamp(),
                "availabilityStatus" to "offline",
                "presenceStatus" to "offline",
                "shiftStatus" to "outside_shift",
                "vehicleType" to vehicleType,
                "licensePlate" to "ب ط ل 1 0 0",
                "avatarUri" to profilePath,
                "avatarPresetIndex" to avatarPresetIndex,
                "totalOrdersCount" to 0,
                "walletBalance" to 0.0,
                "rating" to 5.0,
                "isOnline" to false,
                "isLoggedIn" to false
            )
        }

        /**
         * Read-only diagnostic auditor for existing `captains` documents:
         * Identifies legacy documents that have `role = "captain"` without `accountType = "captain"`,
         * without mutating any Firestore data.
         */
        fun auditLegacyRoleWithoutAccountType(
            captainDocsById: Map<String, Map<String, Any?>>
        ): List<String> {
            return captainDocsById.entries.mapNotNull { (docId, fields) ->
                val accountType = fields["accountType"]?.toString()?.trim()?.lowercase().orEmpty()
                val role = fields["role"]?.toString()?.trim()?.lowercase().orEmpty()
                if ((accountType.isEmpty() || accountType == "null") && role == CAPTAIN_ROLE) {
                    docId
                } else {
                    null
                }
            }
        }
    }
}

fun DocumentSnapshot.safeDouble(vararg fields: String, default: Double = 0.0): Double {
    for (field in fields) {
        val raw = try { get(field) } catch (_: Exception) { null } ?: continue
        val num = when (raw) {
            is Number -> raw.toDouble()
            is String -> {
                val cleaned = raw.replace(Regex("[^0-9.-]"), "").trim()
                cleaned.toDoubleOrNull()
            }
            else -> null
        }
        if (num != null) return num
    }
    return default
}

fun DocumentSnapshot.safeLong(vararg fields: String, default: Long = 0L): Long {
    for (field in fields) {
        val raw = try { get(field) } catch (_: Exception) { null } ?: continue
        val num: Long? = when (raw) {
            is Timestamp -> raw.toDate().time
            is java.util.Date -> raw.time
            is Number -> {
                val v = raw.toLong()
                // Convert 10-digit Unix epoch seconds (e.g., 1762800000) to milliseconds
                if (v in 1_000_000_000L..99_999_999_999L) v * 1000L else v
            }
            is Map<*, *> -> {
                val sec = (raw["seconds"] as? Number ?: raw["_seconds"] as? Number)?.toLong()
                sec?.let { if (it in 1_000_000_000L..99_999_999_999L) it * 1000L else it }
            }
            is String -> {
                val trimmed = normalizeArabicDigits(raw).trim()
                val directLong = trimmed.toLongOrNull()
                if (directLong != null) {
                    if (directLong in 1_000_000_000L..99_999_999_999L) directLong * 1000L else directLong
                } else {
                    parseIsoDateStringOrNull(trimmed)
                }
            }
            else -> null
        }
        if (num != null && num > 0L) return num
    }
    return default
}

private fun parseIsoDateStringOrNull(raw: String): Long? {
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )
    for (pattern in patterns) {
        try {
            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
            if (!pattern.contains("X")) {
                sdf.timeZone = java.util.TimeZone.getTimeZone("Africa/Cairo")
            }
            val parsed = sdf.parse(raw)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {}
    }
    return null
}

fun isPlaceholderOrUnassigned(value: String?): Boolean {
    if (value.isNullOrBlank()) return true
    val clean = value.trim().lowercase()
    return clean in listOf(
        "", "none", "null", "nil", "unassigned", "undefined",
        "0", "-", "--", "n/a", "na", "false", "no_driver", "no-driver", "waiting"
    )
}

fun normalizeArabicDigits(input: String?): String {
    if (input == null) return ""
    val builder = StringBuilder(input.length)
    for (ch in input) {
        when (ch) {
            '٠' -> builder.append('0')
            '١' -> builder.append('1')
            '٢' -> builder.append('2')
            '٣' -> builder.append('3')
            '٤' -> builder.append('4')
            '٥' -> builder.append('5')
            '٦' -> builder.append('6')
            '٧' -> builder.append('7')
            '٨' -> builder.append('8')
            '٩' -> builder.append('9')
            '۰' -> builder.append('0')
            '۱' -> builder.append('1')
            '۲' -> builder.append('2')
            '۳' -> builder.append('3')
            '۴' -> builder.append('4')
            '۵' -> builder.append('5')
            '۶' -> builder.append('6')
            '۷' -> builder.append('7')
            '۸' -> builder.append('8')
            '۹' -> builder.append('9')
            else -> builder.append(ch)
        }
    }
    return builder.toString()
}

fun isSamePhone(phone1: String?, phone2: String?): Boolean {
    if (phone1.isNullOrBlank() || phone2.isNullOrBlank()) return false
    val p1 = normalizeArabicDigits(phone1).replace(Regex("[^0-9]"), "")
    val p2 = normalizeArabicDigits(phone2).replace(Regex("[^0-9]"), "")
    if (p1 == p2) return true
    val p1Trim = p1.removePrefix("20").removePrefix("0")
    val p2Trim = p2.removePrefix("20").removePrefix("0")
    return p1Trim.isNotEmpty() && p1Trim == p2Trim
}

fun DocumentSnapshot.isExcludedCaptain(uid: String): Boolean {
    if (uid.isBlank()) return false
    for (field in listOf("excludedCaptainIds", "excluded_captain_ids", "rejectedCaptainIds")) {
        val list = try { get(field) as? List<*> } catch (_: Exception) { null } ?: continue
        if (list.any { it?.toString()?.trim() == uid }) return true
    }
    return false
}

fun DocumentSnapshot.safeString(vararg fields: String, default: String = ""): String {
    for (field in fields) {
        val raw = try { get(field) } catch (_: Exception) { null } ?: continue
        val str = raw.toString().trim()
        if (str.isNotBlank()) return str
    }
    return default
}

private fun formatOrderCodeRawValue(raw: Any?): String {
    return when (raw) {
        null -> ""
        is Iterable<*> -> raw.mapNotNull { item ->
            formatOrderCodeRawValue(item).takeIf { it.isNotBlank() }
        }.distinct().joinToString(" • ")
        is Array<*> -> raw.mapNotNull { item ->
            formatOrderCodeRawValue(item).takeIf { it.isNotBlank() }
        }.distinct().joinToString(" • ")
        is Map<*, *> -> raw.values.mapNotNull { item ->
            formatOrderCodeRawValue(item).takeIf { it.isNotBlank() }
        }.distinct().joinToString(" • ")
        is Number -> {
            if (raw is Double && raw % 1.0 == 0.0) {
                raw.toLong().toString()
            } else {
                raw.toString().trim()
            }
        }
        else -> raw.toString().trim().trimStart('#').trim().let {
            if (it.equals("null", ignoreCase = true)) "" else it
        }
    }
}

fun extractOrderCodeFromFieldsMap(fieldsMap: Map<String, Any?>?): String {
    if (fieldsMap.isNullOrEmpty()) return ""
    val priorityKeys = listOf(
        "orderCodes", "order_codes", "orderCode", "order_code",
        "ordarCodes", "ordar_codes", "ordarCode", "ordar_code",
        "Ordar codes", "Ordar code", "Order codes", "Order code",
        "order codes", "order code", "Ordar_codes", "Ordar_code",
        "order_code_list", "orderCodesList",
        "trackingCode", "tracking_code", "shortCode", "short_code",
        "code", "codes"
    )
    for (key in priorityKeys) {
        if (fieldsMap.containsKey(key)) {
            val formatted = formatOrderCodeRawValue(fieldsMap[key])
            if (formatted.isNotBlank()) return formatted
        }
    }
    val normalizedMatches = setOf(
        "ordercodes", "ordercode", "ordarcodes", "ordarcode", "trackingcode", "shortcode"
    )
    for ((rawKey, rawVal) in fieldsMap) {
        val norm = rawKey.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (norm in normalizedMatches) {
            val formatted = formatOrderCodeRawValue(rawVal)
            if (formatted.isNotBlank()) return formatted
        }
    }
    return ""
}

fun DocumentSnapshot.extractOrderCode(): String {
    val priorityKeys = listOf(
        "orderCodes", "order_codes", "orderCode", "order_code",
        "ordarCodes", "ordar_codes", "ordarCode", "ordar_code",
        "Ordar codes", "Ordar code", "Order codes", "Order code",
        "order codes", "order code", "Ordar_codes", "Ordar_code",
        "order_code_list", "orderCodesList",
        "trackingCode", "tracking_code", "shortCode", "short_code",
        "code", "codes"
    )
    for (key in priorityKeys) {
        val raw = try { get(key) } catch (_: Exception) { null }
        val formatted = formatOrderCodeRawValue(raw)
        if (formatted.isNotBlank()) return formatted
    }
    return extractOrderCodeFromFieldsMap(try { data } catch (_: Exception) { null })
}

fun normalizeLocationUrl(raw: Any?): String {
    if (raw == null) return ""
    if (raw is Map<*, *>) {
        val preferredMapKeys = listOf(
            "url", "link", "href", "locationUrl", "location_url", "locationLink",
            "mapUrl", "map_url", "mapsUrl", "mapsLink", "googleMapsUrl", "googleMapUrl", "src"
        )
        for (k in preferredMapKeys) {
            val nested = normalizeLocationUrl(raw[k])
            if (nested.isNotBlank()) return nested
        }
        return ""
    }
    if (raw !is String) return ""
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return ""
    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("geo:") ||
            lower.startsWith("google.navigation:") -> trimmed
        lower.startsWith("maps.app.goo.gl/") ||
            lower.startsWith("goo.gl/") ||
            lower.startsWith("maps.google.") ||
            lower.startsWith("www.google.com/maps") ||
            lower.startsWith("google.com/maps") -> "https://$trimmed"
        else -> ""
    }
}

fun extractMerchantLocationUrlFromFieldsMap(fieldsMap: Map<String, Any?>?): String {
    if (fieldsMap.isNullOrEmpty()) return ""
    val priorityKeys = listOf(
        "storeLocationUrl", "store_location_url", "storeLocationLink", "store_location_link",
        "storeMapUrl", "store_map_url", "storeMapsUrl", "storeMapsLink",
        "merchantLocationUrl", "merchant_location_url", "merchantLocationLink", "merchant_location_link",
        "merchantMapUrl", "merchant_map_url", "merchantMapsUrl", "merchantMapsLink",
        "restaurantLocationUrl", "restaurant_location_url", "restaurantLocationLink", "restaurant_location_link",
        "restaurantMapUrl", "restaurant_map_url", "restaurantMapsUrl", "restaurantMapsLink",
        "pickupLocationUrl", "pickup_location_url", "pickupMapUrl", "pickup_map_url",
        "locationUrl", "location_url", "locationLink", "location_link",
        "mapUrl", "map_url", "mapLink", "map_link",
        "mapsUrl", "maps_url", "mapsLink", "maps_link",
        "googleMapsUrl", "google_maps_url", "googleMapUrl", "google_map_url",
        "googleMapsLink", "google_maps_link", "googleMapLink", "googleLocation",
        "gpsLink", "gpsUrl", "location_map_url", "location"
    )
    for (key in priorityKeys) {
        if (fieldsMap.containsKey(key)) {
            val url = normalizeLocationUrl(fieldsMap[key])
            if (url.isNotBlank()) return url
        }
    }

    val normalizedTargetKeys = setOf(
        "storelocationurl", "storelocationlink", "storemapurl", "storemapsurl", "storemapslink",
        "merchantlocationurl", "merchantlocationlink", "merchantmapurl", "merchantmapsurl", "merchantmapslink",
        "restaurantlocationurl", "restaurantlocationlink", "restaurantmapurl", "restaurantmapsurl",
        "pickuplocationurl", "pickupmapurl",
        "locationurl", "locationlink", "mapurl", "maplink", "mapsurl", "mapslink",
        "googlemapsurl", "googlemapurl", "googlemapslink", "googlemaplink", "googlelocation",
        "gpslink", "gpsurl"
    )
    for ((rawKey, rawVal) in fieldsMap) {
        val norm = rawKey.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (norm in normalizedTargetKeys) {
            val url = normalizeLocationUrl(rawVal)
            if (url.isNotBlank()) return url
        }
    }

    val nestedContainerKeys = listOf(
        "restaurant", "merchant", "store", "vendor", "shop",
        "restaurantInfo", "merchantInfo", "storeInfo", "locationInfo", "pickupLocation"
    )
    for (containerKey in nestedContainerKeys) {
        val nested = fieldsMap[containerKey] as? Map<*, *> ?: continue
        @Suppress("UNCHECKED_CAST")
        val nestedUrl = extractMerchantLocationUrlFromFieldsMap(nested as? Map<String, Any?>)
        if (nestedUrl.isNotBlank()) return nestedUrl
    }
    return ""
}

fun DocumentSnapshot.toOrderEntity(): OrderEntity? {
    val docId = id
    val cleanDocId = docId.trim().trimStart('#').trim()
    val rawOrderNum = safeString("orderNumber", "order_number", "orderId", "order_id", "id", default = (cleanDocId.ifBlank { docId }).takeLast(6).uppercase())
    val orderNum = rawOrderNum.trim().trimStart('#').trim()
    val extractedOrderCode = extractOrderCode()
    val custName = safeString(
        "customerName", "customer_name", "client_name", "clientName",
        "userName", "user_name", "name", default = "عميل البطل"
    )
    val custPhone = safeString(
        "customerPhone", "customer_phone", "client_phone", "clientPhone",
        "phone", "phoneNumber", "mobile", default = ""
    )
    val storeName = safeString(
        "merchantName", "merchant_name", "restaurant name", "restaurant_name", "restaurantName",
        "storeName", "store_name", "restaurant", "store",
        "vendorName", "vendor", "shopName", "shop",
        default = "مطعم الشريك"
    )
    // These values are copied from merchants/{merchantId} by the trusted order-creation
    // backend; Captain app is read-only for merchant identity and order data.
    val storePhone = safeString(
        "storePhone", "merchantPhone", "merchant_phone", "restaurantPhone",
        "restaurant_phone", "store_phone", "vendorPhone", "phone"
    )
    val storeImage = safeString(
        "storeImage", "merchantLogoUrl", "merchantLogo", "logoUrl", "logo",
        "storePhotoUrl", "restaurantImage", "restaurant_image",
        "restaurantPhoto", "store_image", "imageUrl", "image", "photoUrl"
    )
    val pickup = safeString(
        "pickupAddress", "pickup_address", "restaurant_address", "restaurantAddress",
        "store_address", "storeAddress", "sourceAddress", default = "عنوان المتجر"
    )
    val delivery = safeString(
        "address", "deliveryAddress", "delivery_address", "clientAddress",
        "customerAddress", "dropoffAddress", "destination", default = "عنوان العميل"
    )

    // GeoPoint or numbers for coordinates
    var lat: Double? = null
    var lng: Double? = null
    val geo = try {
        getGeoPoint("restaurant_location") ?: getGeoPoint("location") ?: getGeoPoint("pickup_location")
    } catch (_: Exception) { null }

    if (geo != null) {
        lat = geo.latitude
        lng = geo.longitude
    } else {
        lat = (get("restaurantLatitude") as? Number)?.toDouble()
            ?: (get("latitude") as? Number)?.toDouble()
            ?: (get("lat") as? Number)?.toDouble()
            ?: safeDouble("restaurantLatitude", "latitude", "lat", "pickup_lat").takeIf { it != 0.0 }
        lng = (get("restaurantLongitude") as? Number)?.toDouble()
            ?: (get("longitude") as? Number)?.toDouble()
            ?: (get("lng") as? Number)?.toDouble()
            ?: safeDouble("restaurantLongitude", "longitude", "lng", "pickup_lng").takeIf { it != 0.0 }
    }

    val itemsSum = safeString("itemsSummary", "items_summary", "order_details", "details", "items", default = "طلب من المطعم")
    val itemsCnt = safeLong("itemsCount", "items_count", "quantity", default = 1L).toInt()

    val totalAmt = safeDouble("price", "orderTotalAmount", "total", "total_price", "amount", default = 0.0)
    val fee = safeDouble("deliveryFee", "delivery_fee", "shippingFee", "serviceFee", "fee", default = 0.0)
    val dist = safeDouble("distanceKm", "distance_km", "distance", default = 3.0)

    val captId = safeString(
        "captainId", "captain_id", "driverId", "driver_id",
        "assignedCaptainId", "assignedDriverId", "captainUid", "driverUid",
        "assignedCaptainPhone", "driverPhone", "captain_phone"
    )

    val rawStatus = safeString("status", "order_status", "orderStatus", "state", default = "pending").trim()
    val created = safeLong("created_at", "createdAt", "timestamp", "date", default = System.currentTimeMillis())
    val note = safeString("notes", "note", "comment")
    val pin = safeString("deliveryPin", "pin", default = "1234")
    val accAt = safeLong("acceptedAt", "accepted_at")

    val receiptPath = safeString("receiptImagePath", "receipt_image_path")
    val receiptUpAt = safeLong("receiptUploadedAt", "receipt_uploaded_at", "receiptSubmittedAt")
    val receiptUpBy = safeString("receiptUploadedBy", "receipt_uploaded_by")
    // Backward compatibility with legacy ImgBB / URL fields without modifying them
    val imgUrl = receiptPath.ifBlank {
        safeString("orderImageUrl", "order_image_url", "receiptImageUrl", "deliveryImageUrl", "receiptImage")
    }
    val dataEnteredAt = safeLong("orderDataEnteredAt")
    val rejectionReason = safeString("rejectionReason", "rejection_reason")

    val fieldsMap = try { data } catch (_: Exception) { null }
    val rawMerchantConfirmedFlag = extractMerchantConfirmedFromFieldsMap(fieldsMap) ||
        isTruthyConfirmationValue(get("merchantConfirmed")) ||
        isTruthyConfirmationValue(get("merchant_confirmed")) ||
        isTruthyConfirmationValue(get("isMerchantConfirmed")) ||
        isTruthyConfirmationValue(get("confirmedByMerchant")) ||
        isTruthyConfirmationValue(get("merchantApproved")) ||
        isTruthyConfirmationValue(get("isConfirmed")) ||
        isTruthyConfirmationValue(get("confirmed")) ||
        (try { get("merchantConfirmedAt") != null || get("merchant_confirmed_at") != null } catch (_: Exception) { false })

    val hasReceiptOrData = receiptPath.isNotBlank() || imgUrl.isNotBlank() || receiptUpAt > 0L || dataEnteredAt > 0L
    val resolvedStatus = resolveOrderStatusFromFirestoreFields(
        rawStatus = rawStatus,
        captainId = captId,
        merchantConfirmedFlag = rawMerchantConfirmedFlag,
        hasReceiptOrOrderData = hasReceiptOrData,
        hasAcceptedAt = accAt > 0L,
        rejectionReason = rejectionReason
    )
    val statusEnum = resolvedStatus.status
    val merchantConfirmed = resolvedStatus.merchantConfirmed

    val compAtRaw = safeLong("completedAt", "completed_at", "deliveredAt", "delivered_at")
    val compAt = when {
        compAtRaw > 0L -> compAtRaw
        statusEnum == OrderStatus.DELIVERED -> {
            val upAt = safeLong("updatedAt", "updated_at")
            if (upAt > 0L) upAt else System.currentTimeMillis()
        }
        else -> 0L
    }
    val cancAtRaw = safeLong("cancelledAt", "canceledAt", "cancelled_at", "canceled_at", "rejectedAt", "declinedAt")
    val cancAt = when {
        cancAtRaw > 0L -> cancAtRaw
        statusEnum == OrderStatus.CANCELLED -> safeLong("updatedAt", "updated_at", default = created)
        else -> 0L
    }

    val offerExpiresAt = safeLong("offerExpiresAt", "offer_expires_at")
    val cancelReason = safeString(
        "cancellationReason", "cancelReason", "cancellation_reason",
        "cancel_reason", "cancelledReason", "reason"
    ).ifBlank {
        if (statusEnum == OrderStatus.CANCELLED) rejectionReason else ""
    }
    val etaMinutes = safeLong("etaMinutes", "eta", default = 15L).toInt()

    val numericId = docId.hashCode().toLong().let { if (it < 0) -it else it }

    val merchantId = safeString(
        "merchantId", "merchant_id", "restaurantId", "restaurant_id",
        "storeId", "store_id", "vendorId", "vendor_id",
        "ownerId", "owner_id", "merchantUid", "restaurantUid"
    )
    val storeLocationUrl = extractMerchantLocationUrlFromFieldsMap(fieldsMap)

    return OrderEntity(
        id = if (numericId == 0L) 1L else numericId,
        orderNumber = orderNum,
        orderCode = extractedOrderCode,
        customerName = custName,
        customerPhone = custPhone,
        storeName = storeName,
        storePhone = storePhone,
        storeImage = storeImage,
        pickupAddress = pickup,
        deliveryAddress = delivery,
        merchantId = merchantId,
        storeLocationUrl = storeLocationUrl,
        restaurantLatitude = lat,
        restaurantLongitude = lng,
        itemsSummary = itemsSum,
        itemsCount = itemsCnt,
        orderTotalAmount = totalAmt,
        deliveryFee = fee,
        distanceKm = dist,
        status = statusEnum,
        createdAt = created,
        notes = note,
        deliveryPin = pin,
        firestoreDocId = docId,
        captainId = captId,
        acceptedAt = accAt,
        orderImageUrl = imgUrl,
        receiptImagePath = receiptPath,
        receiptUploadedAt = receiptUpAt,
        receiptUploadedBy = receiptUpBy,
        orderDataEnteredAt = dataEnteredAt,
        offerExpiresAt = offerExpiresAt,
        merchantConfirmed = merchantConfirmed,
        rejectionReason = rejectionReason,
        etaMinutes = etaMinutes,
        completedAt = compAt,
        cancelledAt = cancAt,
        cancellationReason = cancelReason
    )
}

data class ResolvedOrderStatus(
    val status: OrderStatus,
    val merchantConfirmed: Boolean
)

fun isTruthyConfirmationValue(raw: Any?): Boolean {
    return when (raw) {
        null -> false
        is Boolean -> raw
        is Number -> raw.toLong() > 0L
        is Timestamp -> raw.seconds > 0L
        is java.util.Date -> raw.time > 0L
        is String -> {
            val clean = raw.trim().lowercase().replace("-", "_").replace(Regex("\\s+"), "_")
            clean in setOf(
                "true", "1", "yes", "ok", "confirmed", "approved", "accepted",
                "done", "verified", "نعم", "مؤكد", "تم_التأكيد", "تم_الاعتماد"
            )
        }
        else -> false
    }
}

private fun normalizeOrderStatusToken(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace('-', '_')
        .replace(Regex("\\s+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
}

fun extractMerchantConfirmedFromFieldsMap(fields: Map<String, Any?>?): Boolean {
    if (fields.isNullOrEmpty()) return false
    val boolKeys = listOf(
        "merchantConfirmed", "merchant_confirmed", "isMerchantConfirmed", "is_merchant_confirmed",
        "confirmedByMerchant", "confirmed_by_merchant", "merchantConfirmation", "merchant_confirmation",
        "merchantApproved", "merchant_approved", "isMerchantApproved", "is_merchant_approved",
        "approvedByMerchant", "approved_by_merchant", "orderDataConfirmed", "order_data_confirmed",
        "dataConfirmed", "data_confirmed", "receiptConfirmed", "receipt_confirmed",
        "isReceiptConfirmed", "receiptApproved", "receipt_approved", "isConfirmed", "is_confirmed", "confirmed"
    )
    for (key in boolKeys) {
        if (fields.containsKey(key) && isTruthyConfirmationValue(fields[key])) {
            return true
        }
    }
    val statusKeys = listOf(
        "merchantStatus", "merchant_status", "confirmationStatus", "confirmation_status",
        "merchantApprovalStatus", "merchant_approval_status", "receiptStatus", "receipt_status",
        "approvalStatus", "approval_status", "dataConfirmationStatus", "data_confirmation_status"
    )
    val confirmedTokens = setOf(
        "confirmed", "approved", "accepted", "verified", "en_route", "enroute",
        "تم_التأكيد", "تم_تأكيد_التاجر", "تم_تأكيد_الطلب", "مؤكد", "تم_الاعتماد"
    )
    for (key in statusKeys) {
        val raw = fields[key]?.toString() ?: continue
        val norm = normalizeOrderStatusToken(raw)
        if (norm in confirmedTokens) return true
    }
    val timestampKeys = listOf("merchantConfirmedAt", "merchant_confirmed_at", "confirmedAt", "confirmed_at")
    for (key in timestampKeys) {
        val v = fields[key] ?: continue
        when (v) {
            is Timestamp, is java.util.Date -> return true
            is Number -> if (v.toLong() > 0L) return true
            is String -> {
                val clean = v.trim().lowercase()
                if (clean.isNotBlank() && clean != "null" && clean != "0" && clean != "false") {
                    return true
                }
            }
        }
    }
    return false
}

fun resolveOrderStatusFromFirestoreFields(
    rawStatus: String,
    captainId: String,
    merchantConfirmedFlag: Boolean,
    hasReceiptOrOrderData: Boolean = false,
    hasAcceptedAt: Boolean = false,
    rejectionReason: String = ""
): ResolvedOrderStatus {
    val token = normalizeOrderStatusToken(rawStatus)
    val compact = token.replace("_", "")
    val hasAssignedCaptain = !isPlaceholderOrUnassigned(captainId)

    // 1. Terminal statuses take strict precedence
    if (compact in setOf("delivered", "completed", "done") ||
        token in setOf("تم_التسليم", "مكتمل")
    ) {
        return ResolvedOrderStatus(OrderStatus.DELIVERED, merchantConfirmed = merchantConfirmedFlag || true)
    }

    if (compact in setOf("cancelled", "canceled", "rejected", "declined") ||
        token in setOf("ملغي", "مرفوض")
    ) {
        return ResolvedOrderStatus(OrderStatus.CANCELLED, merchantConfirmed = false)
    }

    if (compact == "expired" || token in setOf("منتهي", "منتهي_الصلاحية")) {
        return ResolvedOrderStatus(OrderStatus.EXPIRED, merchantConfirmed = false)
    }

    // 2. Merchant rejection (unless explicitly re-confirmed)
    val isMerchantRejectedToken = compact in setOf(
        "merchantrejected", "rejectedbymerchant", "datarejected", "receiptrejected"
    ) || token in setOf("مرفوض_من_التاجر", "تم_رفض_البيانات")
    if (isMerchantRejectedToken && !merchantConfirmedFlag) {
        return ResolvedOrderStatus(OrderStatus.MERCHANT_REJECTED, merchantConfirmed = false)
    }

    // 3. Merchant confirmed / En route to deliver to customer
    val isEnRouteOrConfirmedToken = compact in setOf(
        "enroute", "onway", "ontheway", "onwaytocustomer", "onthewaytocustomer",
        "delivering", "deliveringtocustomer", "outfordelivery", "indelivery", "ondelivery",
        "dispatched", "confirmed", "merchantconfirmed", "confirmedbymerchant",
        "orderconfirmed", "orderdataconfirmed", "dataconfirmed", "receiptconfirmed",
        "approved", "merchantapproved", "approvedbymerchant", "orderapproved",
        "readyfordelivery", "readytodeliver", "readytocustomer", "headingtocustomer"
    ) || token in setOf(
        "في_الطريق", "في_الطريق_للعميل", "جاري_التوصيل", "جاري_التسليم",
        "تم_التأكيد", "تم_تأكيد_التاجر", "تم_تأكيد_الطلب", "تم_تأكيد_البيانات",
        "تم_تأكيد_بيانات_الطلب", "مؤكد", "مؤكد_من_التاجر", "تم_الاعتماد",
        "جاهز_للتسليم", "جاهز_للتوصيل"
    )
    if (isEnRouteOrConfirmedToken) {
        return ResolvedOrderStatus(OrderStatus.EN_ROUTE, merchantConfirmed = true)
    }

    // 4. Boolean / timestamp confirmation flag overrides pre-delivery waiting statuses
    if (merchantConfirmedFlag) {
        return ResolvedOrderStatus(OrderStatus.EN_ROUTE, merchantConfirmed = true)
    }

    // 5. Awaiting merchant confirmation
    if (compact in setOf(
            "awaitingmerchantconfirmation", "awaitingconfirmation",
            "pendingmerchantconfirmation", "waitingmerchantconfirmation", "waitingconfirmation"
        ) || token in setOf("بانتظار_تأكيد_التاجر", "في_انتظار_تأكيد_التاجر")
    ) {
        return ResolvedOrderStatus(OrderStatus.AWAITING_MERCHANT_CONFIRMATION, merchantConfirmed = false)
    }

    // 6. Order data entered / Picked up / At store
    if (compact in setOf("orderdataentered", "dataentered") ||
        token in setOf("تم_إدخال_البيانات", "تم_ادخال_البيانات")
    ) {
        return ResolvedOrderStatus(OrderStatus.ORDER_DATA_ENTERED, merchantConfirmed = false)
    }

    if (compact == "pickedup" || token in setOf("تم_الاستلام", "مستلم")) {
        return ResolvedOrderStatus(OrderStatus.PICKED_UP, merchantConfirmed = false)
    }

    if (compact in setOf("atstore", "arrivedatmerchant", "arrived", "arrivedstore", "arrivedatstore") ||
        token in setOf("في_المتجر", "تم_الوصول", "تم_الوصول_للتاجر")
    ) {
        return ResolvedOrderStatus(OrderStatus.AT_STORE, merchantConfirmed = false)
    }

    // 7. Accepted / Assigned
    if (compact in setOf("accepted", "assigned") || token in setOf("تم_القبول", "مقبول")) {
        return if (!hasAssignedCaptain) {
            ResolvedOrderStatus(OrderStatus.PENDING, merchantConfirmed = false)
        } else {
            ResolvedOrderStatus(OrderStatus.ACCEPTED, merchantConfirmed = false)
        }
    }

    // 8. Guard against already-active assigned orders regressing into PENDING
    // when a merchant dashboard sets e.g. "ready" / "ready_for_pickup" / custom confirmed state
    // after the captain has already submitted receipt & customer data.
    val isExplicitNewOrderToken = compact in setOf(
        "pending", "new", "offered", "searching", "waitingdriver", "waitingcaptain"
    ) || token in setOf("جديد", "متاح", "بانتظار_كابتن")

    if (hasAssignedCaptain && hasReceiptOrOrderData) {
        return if (rejectionReason.isNotBlank() && !merchantConfirmedFlag && isExplicitNewOrderToken) {
            ResolvedOrderStatus(OrderStatus.MERCHANT_REJECTED, merchantConfirmed = false)
        } else if (compact in setOf("ready", "readyforpickup", "prepared", "donepreparing") || !isExplicitNewOrderToken) {
            // Merchant updated an already-receipt-submitted assigned order to a non-pending/ready status -> ready to deliver
            ResolvedOrderStatus(OrderStatus.EN_ROUTE, merchantConfirmed = true)
        } else {
            ResolvedOrderStatus(OrderStatus.AWAITING_MERCHANT_CONFIRMATION, merchantConfirmed = false)
        }
    }

    if (hasAssignedCaptain && hasAcceptedAt && !isExplicitNewOrderToken) {
        return ResolvedOrderStatus(OrderStatus.ACCEPTED, merchantConfirmed = false)
    }

    return ResolvedOrderStatus(OrderStatus.PENDING, merchantConfirmed = false)
}
