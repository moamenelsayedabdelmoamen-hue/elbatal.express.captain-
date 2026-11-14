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
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.ImageUploadState
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

class CaptainRepository(
    val captainDao: CaptainDao,
    val orderDao: OrderDao,
    val shiftDao: ShiftDao? = null,
    private val context: Context? = null,
    val storageRepository: FirebaseStorageRepository = FirebaseStorageRepository.getInstance(context),
    private val cloudMetadataPortOverride: CaptainCloudMetadataPort? = null,
    private val cloudFunctionsClientOverride: com.example.data.remote.CloudFunctionsOrderClient? = null
) {
    suspend fun saveShiftsLocally(uid: String, shifts: List<ShiftEntity>) {
        shiftDao?.atomicReplaceCaptainShifts(uid, shifts)
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

    private val cloudMetadataPort: CaptainCloudMetadataPort by lazy {
        cloudMetadataPortOverride ?: object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String? = firebaseAuth.currentUser?.uid

            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {
                val payload = HashMap<String, Any>(fields).apply {
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                val docRef = firestore.collection("captains").document(uid)
                try {
                    docRef.update(payload).await()
                } catch (e: Exception) {
                    val fsEx = e as? com.google.firebase.firestore.FirebaseFirestoreException
                    if (fsEx?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND) {
                        val createPayload = HashMap<String, Any>(payload).apply {
                            put("uid", uid)
                            put("role", "captain")
                            put("status", "pending")
                            put("walletBalance", 0.0)
                        }
                        docRef.set(createPayload, SetOptions.merge()).await()
                    } else {
                        throw e
                    }
                }
            }

            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? {
                val snap = firestore.collection("captains").document(uid).get().await()
                if (!snap.exists()) return null
                return mapOf(
                    "profileImagePath" to snap.safeString("profileImagePath"),
                    "idFrontImagePath" to snap.safeString("idFrontImagePath"),
                    "idBackImagePath" to snap.safeString("idBackImagePath"),
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
        if (cloudMetadataPortOverride == null && cloudFunctionsClientOverride == null) {
            try {
                // 1. If already authenticated, start realtime orders & shifts listeners
                if (firebaseAuth.currentUser != null) {
                    startRealtimeOrdersListener()
                    startRealtimeShiftListener()
                }
                // 2. Listen to Auth State changes so listeners start immediately upon authentication
                firebaseAuth.addAuthStateListener { auth ->
                    if (auth.currentUser != null) {
                        startRealtimeOrdersListener()
                        startRealtimeShiftListener()
                    }
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not initialize Firebase Auth listeners: ${e.message}")
            }
        }
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

                    // Clean up pending orders that were claimed by another captain or cancelled
                    for (removedDoc in removedPendingDocs) {
                        val parsedRemoved = removedDoc.toOrderEntity() ?: continue
                        val localExisting = allOrdersFlow.firstOrNull()?.find { it.id == parsedRemoved.id }
                        if (localExisting != null && localExisting.status == OrderStatus.PENDING) {
                            orderDao.deleteOrderById(parsedRemoved.id)
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

                            val isAvailable = (order.status == OrderStatus.PENDING) && hasNoCaptain
                            val isAcceptedByOther = !isAvailable && !hasNoCaptain && !isAssignedToMe

                            if (isAcceptedByOther) {
                                orderDao.deleteOrderById(order.id)
                            } else {
                                val finalOrder = if (isAvailable) {
                                    order.copy(status = OrderStatus.PENDING)
                                } else {
                                    order
                                }
                                orderDao.upsertByFirestoreId(finalOrder)
                            }
                        } catch (e: Exception) {
                            Log.e("CaptainRepository", "[FirestoreOrdersListener] Error parsing Firestore order document: ${doc.id}", e)
                        }
                    }
                    reconcileDailyWallets()
                }
            }

            // 1. Available pending orders (matches firestore.rules isAvailablePendingOrder)
            ordersListenerRegistrations += firestore.collection("orders")
                .whereEqualTo("status", "pending")
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
            for (field in listOf("captainId", "driverId", "offeredCaptainId")) {
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
            Log.i("CaptainRepository", "[FirestoreQuery] Executing query on 'orders' collection filtering by: status == 'pending'...")
            val querySnapshot = firestore.collection("orders")
                .whereEqualTo("status", "pending")
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
        return try {
            val currentCaptain = captainDao.getCaptain()
            val myPhone = currentCaptain?.phoneNumber ?: ""
            val myUid = firebaseAuth.currentUser?.uid
                ?: currentCaptain?.uid.orEmpty()
            if (myUid.isBlank()) {
                return Result.failure(IllegalStateException("يجب تسجيل الدخول أولاً لمزامنة الطلبات"))
            }

            Log.i("CaptainRepository", "[FirestoreSync] Syncing scoped orders for captain UID: $myUid...")
            val matchedDocs = linkedMapOf<String, DocumentSnapshot>()

            // 1. Available pending orders
            try {
                val pendingSnapshot = firestore.collection("orders")
                    .whereEqualTo("status", "pending")
                    .get()
                    .await()
                for (doc in pendingSnapshot.documents) {
                    matchedDocs[doc.id] = doc
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "[FirestoreSync] status=='pending' query notice: ${e.message}")
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

                val hasNoCaptain = isPlaceholderOrUnassigned(docCaptainId)
                val isAssignedToMe = !hasNoCaptain && (
                    docCaptainId == myUid ||
                        doc.safeString("captainUid", "driverUid", "offeredCaptainId") == myUid ||
                        (myPhone.isNotBlank() && isSamePhone(docCaptainId, myPhone))
                    )

                val isAvailable = (order.status == OrderStatus.PENDING) && hasNoCaptain
                val isAcceptedByOther = !isAvailable && !hasNoCaptain && !isAssignedToMe

                if (isAcceptedByOther) {
                    orderDao.deleteOrderById(order.id)
                } else {
                    val finalOrder = if (isAvailable) {
                        pendingCount++
                        order.copy(status = OrderStatus.PENDING)
                    } else {
                        if (order.status != OrderStatus.DELIVERED && order.status != OrderStatus.CANCELLED) {
                            activeCount++
                        }
                        order
                    }
                    orderDao.upsertByFirestoreId(finalOrder)
                    count++
                }
            }

            reconcileDailyWallets()
            Log.i("CaptainRepository", "[FirestoreSync] Sync complete: $count orders saved ($pendingCount PENDING, $activeCount active)")
            Result.success(count)
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
     * Starts a realtime Firestore listener for the captain's work shifts.
     * Primary target: 'captain_schedules' collection where admin panel assigns shifts.
     * Listens to:
     * 1. 'captain_schedules/{uid}' document (per-captain schedule doc).
     * 2. 'captain_schedules/{uid}/shifts' subcollection.
     * 3. 'captain_schedules' collection where captainId == uid.
     * 4. 'captain_schedules' collection where captainUid == uid.
     * 5. 'captains/{uid}/shifts' subcollection.
     * 6. Embedded schedule in 'captains/{uid}' profile document.
     * 7. Legacy 'shifts' collection.
     * Updates local Room DB atomically (delete old captain shifts + insert new in single transaction).
     */
    fun startRealtimeShiftListener() {
        val user = firebaseAuth.currentUser
        if (user == null) {
            Log.d("CaptainRepository", "[ShiftListener] Captain not authenticated yet, waiting to register shift listener")
            return
        }
        val uid = user.uid
        stopRealtimeShiftListener()

        Log.i("CaptainRepository", "[ShiftListener] Registering realtime shift listener for captain UID: $uid (target: captain_schedules)")

        try {
            // 1. Primary: Listen to document in 'captain_schedules/{uid}'
            captainScheduleDocListenerRegistration = firestore.collection("captain_schedules")
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("CaptainRepository", "[ShiftListener] captain_schedules/{uid} listener notice: ${error.message}")
                        _isShiftNetworkConnected.value = false
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        _isShiftNetworkConnected.value = !snapshot.metadata.isFromCache
                        CoroutineScope(Dispatchers.IO).launch {
                            val shifts = snapshot.extractAllShifts(uid)
                            if (shifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, shifts)
                                _isShiftSynced.value = true
                                Log.i("CaptainRepository", "[ShiftListener] Received and atomically cached ${shifts.size} shifts from captain_schedules/$uid")
                            }
                        }
                    }
                }

            // 1b. Subcollection: Listen to 'captain_schedules/{uid}/shifts'
            captainScheduleSubcollectionListenerRegistration = firestore.collection("captain_schedules")
                .document(uid)
                .collection("shifts")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("CaptainRepository", "[ShiftListener] captain_schedules subcollection notice: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && !snapshot.isEmpty) {
                        _isShiftNetworkConnected.value = !snapshot.metadata.isFromCache
                        CoroutineScope(Dispatchers.IO).launch {
                            val allShifts = mutableListOf<ShiftEntity>()
                            for (doc in snapshot.documents) {
                                allShifts.addAll(doc.extractAllShifts(uid))
                            }
                            if (allShifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, allShifts)
                                _isShiftSynced.value = true
                                Log.i("CaptainRepository", "[ShiftListener] Received and cached ${allShifts.size} shifts from captain_schedules/$uid/shifts")
                            }
                        }
                    }
                }

            // 2. Collection Query: Listen to 'captain_schedules' where captainId == uid
            captainScheduleQueryListenerRegistration = firestore.collection("captain_schedules")
                .whereEqualTo("captainId", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("CaptainRepository", "[ShiftListener] captain_schedules query (captainId) notice: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && !snapshot.isEmpty) {
                        _isShiftNetworkConnected.value = !snapshot.metadata.isFromCache
                        CoroutineScope(Dispatchers.IO).launch {
                            val allShifts = mutableListOf<ShiftEntity>()
                            for (doc in snapshot.documents) {
                                allShifts.addAll(doc.extractAllShifts(uid))
                            }
                            if (allShifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, allShifts)
                                _isShiftSynced.value = true
                                Log.i("CaptainRepository", "[ShiftListener] Received and atomically cached ${allShifts.size} shifts from captain_schedules query")
                            }
                        }
                    }
                }

            // 2b. Collection Query: Listen to 'captain_schedules' where captainUid == uid
            captainScheduleUidQueryListenerRegistration = firestore.collection("captain_schedules")
                .whereEqualTo("captainUid", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null && !snapshot.isEmpty) {
                        _isShiftNetworkConnected.value = !snapshot.metadata.isFromCache
                        CoroutineScope(Dispatchers.IO).launch {
                            val allShifts = mutableListOf<ShiftEntity>()
                            for (doc in snapshot.documents) {
                                allShifts.addAll(doc.extractAllShifts(uid))
                            }
                            if (allShifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, allShifts)
                                _isShiftSynced.value = true
                                Log.i("CaptainRepository", "[ShiftListener] Received ${allShifts.size} shifts from captain_schedules query (captainUid)")
                            }
                        }
                    }
                }

            // 2b-2. Collection Query: Listen to 'captain_schedules' where driverId == uid
            captainScheduleDriverQueryListenerRegistration = firestore.collection("captain_schedules")
                .whereEqualTo("driverId", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null && !snapshot.isEmpty) {
                        _isShiftNetworkConnected.value = !snapshot.metadata.isFromCache
                        CoroutineScope(Dispatchers.IO).launch {
                            val allShifts = mutableListOf<ShiftEntity>()
                            for (doc in snapshot.documents) {
                                allShifts.addAll(doc.extractAllShifts(uid))
                            }
                            if (allShifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, allShifts)
                                _isShiftSynced.value = true
                                Log.i("CaptainRepository", "[ShiftListener] Received ${allShifts.size} shifts from captain_schedules query (driverId)")
                            }
                        }
                    }
                }

            // 2c. Subcollection: Listen to 'captains/{uid}/shifts'
            captainSubcollectionShiftListenerRegistration = firestore.collection("captains")
                .document(uid)
                .collection("shifts")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null && !snapshot.isEmpty) {
                        _isShiftNetworkConnected.value = !snapshot.metadata.isFromCache
                        CoroutineScope(Dispatchers.IO).launch {
                            val allShifts = mutableListOf<ShiftEntity>()
                            for (doc in snapshot.documents) {
                                allShifts.addAll(doc.extractAllShifts(uid))
                            }
                            if (allShifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, allShifts)
                                _isShiftSynced.value = true
                                Log.i("CaptainRepository", "[ShiftListener] Received ${allShifts.size} shifts from captains/$uid/shifts")
                            }
                        }
                    }
                }

            // 3. Fallback: Listen to captain profile doc for embedded schedule
            captainDocShiftListenerRegistration = firestore.collection("captains").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("CaptainRepository", "[ShiftListener] Captain doc listener notice: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val embeddedShifts = snapshot.extractAllShifts(uid)
                            if (embeddedShifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, embeddedShifts)
                                _isShiftSynced.value = true
                                Log.i("CaptainRepository", "[ShiftListener] Found ${embeddedShifts.size} embedded shifts in captain doc, caching locally")
                            }
                        }
                    }
                }

            // 4. Legacy fallback: 'shifts' collection
            shiftListenerRegistration = firestore.collection("shifts")
                .whereEqualTo("captainId", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("CaptainRepository", "[ShiftListener] Legacy shifts notice: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && !snapshot.isEmpty) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val legacyShifts = snapshot.documents.mapNotNull { it.toShiftEntity(uid) }
                            if (legacyShifts.isNotEmpty()) {
                                shiftDao?.atomicReplaceCaptainShifts(uid, legacyShifts)
                                _isShiftSynced.value = true
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[ShiftListener] Error starting shift listener: ${e.message}")
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
            Log.i("CaptainRepository", "[ShiftSync] Explicitly syncing shifts from Firestore for UID: $uid (target: captain_schedules)")
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

            if (chosenList.isNotEmpty()) {
                shiftDao?.atomicReplaceCaptainShifts(uid, chosenList)
                _isShiftSynced.value = true
                _isShiftNetworkConnected.value = true
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
     * Resolves the registered account email corresponding to a phone number.
     * Uses a fast, rule-resilient multi-tiered lookup:
     * 1. Persistent PhoneAuthRegistry (SharedPreferences, survives logout & multi-account)
     * 2. Local Room database cache
     * 3. Server-side Cloud Function endpoint (resolveCaptainEmailByPhone)
     * 4. Targeted Firestore whereIn queries with immediate PERMISSION_DENIED short-circuiting
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
            if (local != null && local.email.isNotBlank()) {
                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, local.email)
                Log.d("CaptainRepository", "[PhoneLookup] Found email in local Room cache: ${local.email}")
                return local.email
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "[PhoneLookup] Local check notice: ${e.message}")
        }

        val variantsList = PhoneAuthRegistry.generatePhoneQueryVariants(cleaned)

        // 3. Server-side Cloud Function resolution (bypasses unauthenticated Firestore read rules)
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

        // 4. Fast targeted Firestore whereIn queries (breaks immediately on PERMISSION_DENIED)
        val collectionsToCheck = listOf("captains", "drivers")
        val fieldNames = listOf("phone", "phoneNumber", "phone_number", "mobile", "driverPhone")
        val numericVariants = listOfNotNull(
            plainDigits.toLongOrNull(),
            plainDigits.removePrefix("0").toLongOrNull(),
            plainDigits.removePrefix("20").toLongOrNull()
        ).distinct()

        for (col in collectionsToCheck) {
            var collectionPermissionDenied = false
            if (variantsList.isNotEmpty()) {
                for (field in fieldNames) {
                    try {
                        val querySnap = firestore.collection(col)
                            .whereIn(field, variantsList)
                            .limit(1)
                            .get()
                            .await()

                        if (!querySnap.isEmpty) {
                            val em = querySnap.documents[0].safeString("email")
                            if (em.isNotBlank()) {
                                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, em)
                                Log.i("CaptainRepository", "[PhoneLookup] Found email '$em' via $col.$field query")
                                return em
                            }
                        }
                    } catch (e: Exception) {
                        val fsEx = e as? com.google.firebase.firestore.FirebaseFirestoreException
                        if (fsEx?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            Log.d("CaptainRepository", "[PhoneLookup] Unauthenticated read on '$col' denied by Firestore rules; skipping remaining queries on '$col'")
                            collectionPermissionDenied = true
                            break
                        }
                        Log.w("CaptainRepository", "[PhoneLookup] Query on $col.$field notice: ${e.message}")
                    }
                }
            }

            if (!collectionPermissionDenied && numericVariants.isNotEmpty()) {
                for (field in listOf("phone", "phoneNumber")) {
                    try {
                        val numSnap = firestore.collection(col)
                            .whereIn(field, numericVariants)
                            .limit(1)
                            .get()
                            .await()
                        if (!numSnap.isEmpty) {
                            val em = numSnap.documents[0].safeString("email")
                            if (em.isNotBlank()) {
                                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, em)
                                Log.i("CaptainRepository", "[PhoneLookup] Found email '$em' via numeric $col.$field")
                                return em
                            }
                        }
                    } catch (e: Exception) {
                        val fsEx = e as? com.google.firebase.firestore.FirebaseFirestoreException
                        if (fsEx?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            collectionPermissionDenied = true
                            break
                        }
                    }
                }
            }

            if (!collectionPermissionDenied) {
                try {
                    val scanSnap = firestore.collection(col).limit(100).get().await()
                    for (doc in scanSnap.documents) {
                        val p = doc.safeString("phone", "phoneNumber", "phone_number", "mobile", "driverPhone")
                            .ifBlank { doc.get("phone")?.toString() ?: doc.get("phoneNumber")?.toString() ?: "" }
                        if (isSamePhone(p, normalizedLocal)) {
                            val em = doc.safeString("email")
                            if (em.isNotBlank()) {
                                PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedLocal, em)
                                Log.i("CaptainRepository", "[PhoneLookup] Found email '$em' via scan (matched phone '$p')")
                                return em
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return null
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
            firebaseAuth.sendPasswordResetEmail(emailToSend).await()
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

    suspend fun login(phoneOrEmail: String, pass: String): LoginOutcome {
        val cleanedInput = PhoneAuthRegistry.cleanInvisibleChars(phoneOrEmail)
        val isEmail = cleanedInput.contains("@")
        if (cleanedInput.isBlank()) {
            return LoginOutcome.Error("يرجى إدخال رقم الهاتف أو البريد الإلكتروني.")
        }

        var authEmail = cleanedInput
        var preSignedAuthResult: com.google.firebase.auth.AuthResult? = null

        // 1. If phone number entered, resolve registered email or authenticate via synthetic phone email
        if (!isEmail) {
            val digitsOnly = cleanedInput.replace(Regex("[^0-9]"), "")
            if (digitsOnly.length < 8) {
                return LoginOutcome.Error("يرجى إدخال رقم هاتف صحيح مكون من 11 رقماً (مثال: 01012345678).")
            }

            val foundEmail = lookupEmailByPhone(cleanedInput)
            if (foundEmail != null) {
                authEmail = foundEmail
                try {
                    preSignedAuthResult = firebaseAuth.signInWithEmailAndPassword(authEmail, pass).await()
                } catch (firstErr: Exception) {
                    // If cached/looked-up email failed because account uses a synthetic phone email, try candidates
                    val errMsg = firstErr.localizedMessage?.lowercase() ?: ""
                    val isWrongCredOrNotFound = firstErr is FirebaseAuthInvalidCredentialsException ||
                        errMsg.contains("user-not-found") || errMsg.contains("no user record") ||
                        errMsg.contains("invalid-credential") || errMsg.contains("invalid_login_credentials")

                    if (isWrongCredOrNotFound) {
                        for (cand in PhoneAuthRegistry.generateSyntheticPhoneEmails(cleanedInput)) {
                            if (cand.equals(authEmail, ignoreCase = true)) continue
                            try {
                                val res = firebaseAuth.signInWithEmailAndPassword(cand, pass).await()
                                if (res.user != null) {
                                    authEmail = cand
                                    preSignedAuthResult = res
                                    break
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    if (preSignedAuthResult == null) {
                        return mapAuthExceptionToLoginError(firstErr, isEmail = false)
                    }
                }
            } else {
                // Lookup returned null (e.g. strict unauthenticated Firestore rules on a new device):
                // Try candidate synthetic phone emails directly in Firebase Auth
                var lastCandidateException: Exception? = null
                for (cand in PhoneAuthRegistry.generateSyntheticPhoneEmails(cleanedInput)) {
                    try {
                        val res = firebaseAuth.signInWithEmailAndPassword(cand, pass).await()
                        if (res.user != null) {
                            authEmail = cand
                            preSignedAuthResult = res
                            break
                        }
                    } catch (e: Exception) {
                        lastCandidateException = e
                        val msg = e.localizedMessage?.lowercase() ?: ""
                        if (msg.contains("too-many-requests") || msg.contains("network")) {
                            break
                        }
                    }
                }

                if (preSignedAuthResult == null) {
                    val lastMsg = lastCandidateException?.localizedMessage?.lowercase() ?: ""
                    if (lastMsg.contains("too-many-requests")) {
                        return LoginOutcome.Error("تم حظر المحاولات مؤقتاً لتكرار المحاولات الخاطئة. يرجى الانتظار دقيقة والمحاولة مجدداً.")
                    }
                    if (lastMsg.contains("network")) {
                        return LoginOutcome.Error("يرجى التحقق من اتصال الإنترنت والمحاولة مرة أخرى.")
                    }
                    return LoginOutcome.Error(
                        "تعذر العثور على الحساب المرتبط برقم الهاتف ($cleanedInput) على هذا الجهاز بسبب قيود الحماية أو خطأ في كلمة المرور. إذا كانت هذه أول مرة تسجل فيها الدخول على هذا الهاتف، يرجى الدخول بالبريد الإلكتروني مرة واحدة وسيتم تفعيل الدخول السريع برقم الهاتف تلقائياً."
                    )
                }
            }
        }

        // 2. Authenticate with Firebase Authentication (if not already authenticated in step 1)
        val authResult = preSignedAuthResult ?: try {
            firebaseAuth.signInWithEmailAndPassword(authEmail, pass).await()
        } catch (e: Exception) {
            return mapAuthExceptionToLoginError(e, isEmail = isEmail)
        }

        val firebaseUser = authResult.user
            ?: return LoginOutcome.Error("فشل استعادة بيانات المستخدم من خادم Firebase.")

        val uid = firebaseUser.uid

        // 3. Email verification policy:
        // Phone logins bypass email verification gate so captains can log in immediately with phone + password.
        if (isEmail) {
            try {
                firebaseUser.reload().await()
            } catch (e: Exception) {
                Log.w("CaptainRepository", "User reload warning: ${e.message}")
            }
            if (!firebaseUser.isEmailVerified) {
                val isDocActive = try {
                    val d = firestore.collection("captains").document(uid).get().await()
                    d.exists() && d.safeString("status", default = "pending").lowercase() == "active"
                } catch (_: Exception) { false }

                if (!isDocActive) {
                    return LoginOutcome.EmailNotVerified(firebaseUser.email ?: authEmail)
                }
            }
        }

        // 4. Retrieve Captain profile from Firestore or graceful fallback
        var fullName = firebaseUser.displayName?.ifBlank { "كابتن البطل" } ?: "كابتن البطل"
        var phone = if (!isEmail) PhoneAuthRegistry.normalizeEgyptianPhone(cleanedInput) else ""
        var vehicleType = "سكوتر / دراجة نارية"
        var licensePlate = "أ ب ج 1 2 3"
        var avatarUri = ""
        var legacyIdCardUrl = ""
        var legacyLicenseUrl = ""
        var profileImagePath = ""
        var idFrontImagePath = ""
        var idBackImagePath = ""
        var licenseImagePath = ""
        var rating = 5.0f
        var wallet = 0.0
        var status = "active"

        try {
            var captainDoc = firestore.collection("captains").document(uid).get().await()

            // Fallback: check captains by email if registered before UID doc migration
            if (!captainDoc.exists()) {
                try {
                    val byEmail = firestore.collection("captains")
                        .whereEqualTo("email", firebaseUser.email ?: authEmail)
                        .limit(1)
                        .get()
                        .await()
                    if (!byEmail.isEmpty) {
                        captainDoc = byEmail.documents[0]
                    }
                } catch (_: Exception) {}
            }

            // Fallback: check drivers collection
            if (!captainDoc.exists()) {
                try {
                    val driverDoc = firestore.collection("drivers").document(uid).get().await()
                    if (driverDoc.exists()) {
                        captainDoc = driverDoc
                    }
                } catch (_: Exception) {}
            }

            if (captainDoc.exists()) {
                val role = captainDoc.safeString("role", default = "captain").lowercase()
                if (role != "captain" && role != "driver") {
                    firebaseAuth.signOut()
                    return LoginOutcome.Error("هذا الحساب غير مصرح له بالدخول ككابتن.")
                }

                val docStatus = captainDoc.safeString("status", default = "active").lowercase()
                if (docStatus == "disabled" || docStatus == "blocked" || docStatus == "suspended") {
                    firebaseAuth.signOut()
                    return LoginOutcome.Error("تم حظر أو تعطيل هذا الحساب. يرجى التواصل مع إدارة البطل إكسبريس.")
                }

                status = docStatus
                fullName = captainDoc.safeString("name", "fullName", "driver_name", default = fullName)
                val docPhone = captainDoc.safeString("phone", "phoneNumber", "driverPhone", default = phone)
                phone = PhoneAuthRegistry.normalizeEgyptianPhone(docPhone).ifBlank { phone }
                vehicleType = captainDoc.safeString("vehicleType", "vehicle", default = vehicleType)
                licensePlate = captainDoc.safeString("licensePlate", default = licensePlate)
                profileImagePath = captainDoc.safeString("profileImagePath")
                idFrontImagePath = captainDoc.safeString("idFrontImagePath")
                idBackImagePath = captainDoc.safeString("idBackImagePath")
                licenseImagePath = captainDoc.safeString("licenseImagePath")
                // Preserve legacy URLs in Firestore untouched for backward compatibility
                avatarUri = profileImagePath.ifBlank {
                    captainDoc.safeString("profileImageUrl", "photoUrl", "avatarUri")
                }
                legacyIdCardUrl = captainDoc.safeString("idCardImageUrl", "nationalIdUrl")
                legacyLicenseUrl = captainDoc.safeString("licenseImageUrl", "licenseUrl")
                rating = captainDoc.safeDouble("rating", default = 5.0).toFloat()
                wallet = captainDoc.safeDouble("walletBalance", default = 0.0)
            } else {
                // Auto-initialize captain document in Firestore for authenticated user
                val newDocData = mapOf(
                    "uid" to uid,
                    "name" to fullName,
                    "fullName" to fullName,
                    "driver_name" to fullName,
                    "phone" to phone,
                    "phoneNumber" to phone,
                    "email" to (firebaseUser.email ?: authEmail),
                    "role" to "captain",
                    "status" to "pending",
                    "profileImagePath" to "",
                    "idFrontImagePath" to "",
                    "idBackImagePath" to "",
                    "licenseImagePath" to "",
                    "rating" to 5.0,
                    "walletBalance" to 0.0,
                    "vehicleType" to vehicleType,
                    "licensePlate" to licensePlate,
                    "createdAt" to System.currentTimeMillis(),
                    "updatedAt" to System.currentTimeMillis()
                )
                try {
                    firestore.collection("captains").document(uid).set(newDocData).await()
                } catch (e: Exception) {
                    Log.w("CaptainRepository", "Could not auto-create captain doc in Firestore: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("CaptainRepository", "Firestore authorization notice: ${e.message}, falling back to local captain data")
            val local = captainDao.getCaptain()
            if (local != null) {
                fullName = local.fullName.ifBlank { fullName }
                phone = local.phoneNumber.ifBlank { phone }
                vehicleType = local.vehicleType.ifBlank { vehicleType }
                licensePlate = local.licensePlate.ifBlank { licensePlate }
                profileImagePath = local.profileImagePath
                idFrontImagePath = local.idFrontImagePath
                idBackImagePath = local.idBackImagePath
                licenseImagePath = local.licenseImagePath
                avatarUri = local.effectiveProfileImage.ifBlank { avatarUri }
                legacyIdCardUrl = local.nationalIdUrl
                legacyLicenseUrl = local.licenseUrl
                rating = local.rating
                wallet = local.walletBalance
            }
        }

        val resolvedEmail = firebaseUser.email ?: authEmail

        // Persist phone -> email mapping & phone login marker so future phone logins & session checks always succeed!
        if (phone.isNotBlank()) {
            PhoneAuthRegistry.savePhoneEmailMapping(context, phone, resolvedEmail)
        }
        if (!isEmail) {
            PhoneAuthRegistry.savePhoneEmailMapping(context, cleanedInput, resolvedEmail)
        }
        PhoneAuthRegistry.markLoggedInViaPhone(context, uid, !isEmail || PhoneAuthRegistry.wasLoggedInViaPhone(context, uid))

        // Save authenticated captain profile locally
        val captainEntity = CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullName,
            phoneNumber = phone,
            email = resolvedEmail,
            password = "", // NEVER store password
            avatarUri = avatarUri,
            nationalIdUrl = legacyIdCardUrl,
            licenseUrl = legacyLicenseUrl,
            profileImagePath = profileImagePath,
            idFrontImagePath = idFrontImagePath,
            idBackImagePath = idBackImagePath,
            licenseImagePath = licenseImagePath,
            vehicleType = vehicleType,
            licensePlate = licensePlate,
            rating = rating,
            walletBalance = wallet,
            isOnline = true,
            isLoggedIn = true,
            role = "captain",
            status = status
        )
        captainDao.insertOrUpdate(captainEntity)

        // Attempt background online status sync (avoid touching restricted admin fields)
        try {
            val syncData = mapOf(
                "isOnline" to true,
                "isLoggedIn" to true,
                "updatedAt" to System.currentTimeMillis()
            )
            firestore.collection("captains").document(uid).update(syncData)
        } catch (e: Exception) {
            Log.w("CaptainRepository", "Firestore background sync notice: ${e.message}")
        }

        // Start listening to orders and shifts and sync captain historical orders + daily wallets
        startRealtimeOrdersListener()
        startRealtimeShiftListener()
        CoroutineScope(Dispatchers.IO).launch {
            syncShiftsFromFirestore()
            syncCaptainOrdersHistoryFromFirestore()
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
     * Session validation on app resume / splash
     */
    suspend fun validateCurrentSession(): Boolean {
        val user = firebaseAuth.currentUser ?: return false
        try {
            try {
                user.reload().await()
            } catch (reloadErr: Exception) {
                Log.w("CaptainRepository", "Session reload notice: ${reloadErr.message}")
            }

            var isDocActive = false
            var remoteProfilePath = ""
            var remoteIdFrontPath = ""
            var remoteIdBackPath = ""
            var remoteLicensePath = ""
            var remoteLegacyAvatar = ""
            var remoteLegacyId = ""
            var remoteLegacyLicense = ""
            try {
                val doc = firestore.collection("captains").document(user.uid).get().await()
                if (doc.exists()) {
                    val role = doc.safeString("role", default = "captain").lowercase()
                    val status = doc.safeString("status", default = "active").lowercase()

                    if ((role != "captain" && role != "driver") || status == "disabled" || status == "blocked") {
                        firebaseAuth.signOut()
                        captainDao.updateLoginState(false)
                        return false
                    }
                    isDocActive = (status == "active")
                    val docPhone = doc.safeString("phone", "phoneNumber")
                    val docEmail = doc.safeString("email", default = user.email ?: "")
                    if (docPhone.isNotBlank() && docEmail.isNotBlank()) {
                        PhoneAuthRegistry.savePhoneEmailMapping(context, docPhone, docEmail)
                    }
                    remoteProfilePath = doc.safeString("profileImagePath")
                    remoteIdFrontPath = doc.safeString("idFrontImagePath")
                    remoteIdBackPath = doc.safeString("idBackImagePath")
                    remoteLicensePath = doc.safeString("licenseImagePath")
                    remoteLegacyAvatar = doc.safeString("profileImageUrl", "photoUrl", "avatarUri")
                    remoteLegacyId = doc.safeString("idCardImageUrl", "nationalIdUrl")
                    remoteLegacyLicense = doc.safeString("licenseImageUrl", "licenseUrl")
                }
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Firestore doc check notice: ${e.message}")
            }

            val loggedInViaPhone = PhoneAuthRegistry.wasLoggedInViaPhone(context, user.uid)
            if (!user.isEmailVerified && !loggedInViaPhone && !isDocActive) {
                return false
            }

            // Sync local profile including Firebase Storage paths
            val current = captainDao.getCaptain()
            if (current != null) {
                if (current.phoneNumber.isNotBlank() && current.email.isNotBlank()) {
                    PhoneAuthRegistry.savePhoneEmailMapping(context, current.phoneNumber, current.email)
                }
                val mergedProfilePath = remoteProfilePath.ifBlank { current.profileImagePath }
                val mergedIdFrontPath = remoteIdFrontPath.ifBlank { current.idFrontImagePath }
                val mergedIdBackPath = remoteIdBackPath.ifBlank { current.idBackImagePath }
                val mergedLicensePath = remoteLicensePath.ifBlank { current.licenseImagePath }
                val mergedAvatar = mergedProfilePath.ifBlank {
                    remoteLegacyAvatar.ifBlank { current.avatarUri }
                }
                captainDao.insertOrUpdate(
                    current.copy(
                        isLoggedIn = true,
                        uid = user.uid,
                        avatarUri = mergedAvatar,
                        nationalIdUrl = remoteLegacyId.ifBlank { current.nationalIdUrl },
                        licenseUrl = remoteLegacyLicense.ifBlank { current.licenseUrl },
                        profileImagePath = mergedProfilePath,
                        idFrontImagePath = mergedIdFrontPath,
                        idBackImagePath = mergedIdBackPath,
                        licenseImagePath = mergedLicensePath
                    )
                )
            }

            startRealtimeOrdersListener()
            startRealtimeShiftListener()
            CoroutineScope(Dispatchers.IO).launch {
                reconcileDailyWallets()
            }
            return true
        } catch (e: Exception) {
            Log.w("CaptainRepository", "Session validation network check: ${e.message}")
            val current = captainDao.getCaptain()
            if (current != null && current.isLoggedIn) {
                startRealtimeOrdersListener()
                return true
            }
            return false
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
     * 1. Firebase Authentication & Obtain UID
     * 2. Upload captain images strictly to Firebase Storage:
     *    - Profile: captains/{uid}/profile/profile.jpg
     *    - ID Front: captains/{uid}/documents/id_front.jpg
     *    - ID Back: captains/{uid}/documents/id_back.jpg
     * 3. Create captains/{uid} in Cloud Firestore with profileImagePath, idFrontImagePath, idBackImagePath
     */
    suspend fun registerCaptainWithEmailVerification(
        fullName: String,
        phone: String,
        email: String,
        pass: String,
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

        try {
            // Step 1 & 2: Firebase Authentication & Obtain UID
            val authResult = firebaseAuth.createUserWithEmailAndPassword(trimmedEmail, pass).await()
            val firebaseUser = authResult.user
                ?: return Result.failure(Exception("تعذر إنشاء حساب الكابتن في Firebase Auth"))

            val uid = firebaseUser.uid
            Log.i("CaptainRepository", "AUTH SUCCESS - CAPTAIN UID: $uid")

            try {
                firebaseUser.sendEmailVerification().await()
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not send verification email: ${e.message}")
            }

            try {
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(trimmedName)
                    .build()
                firebaseUser.updateProfile(profileUpdates).await()
            } catch (e: Exception) {
                Log.w("CaptainRepository", "Could not set displayName: ${e.message}")
            }

            // Step 3: Upload captain images strictly to Firebase Storage (zero ImgBB usage)
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

            // Step 4 & 5: Create captains/{uid} Document in Firestore
            val now = System.currentTimeMillis()
            val captainDocData = hashMapOf<String, Any>(
                "uid" to uid,
                "name" to trimmedName,
                "email" to trimmedEmail,
                "phone" to trimmedPhone,
                "role" to "captain",
                "status" to "pending",
                "profileImagePath" to profilePath,
                "idFrontImagePath" to idFrontPath,
                "idBackImagePath" to idBackPath,
                "licenseImagePath" to licensePath,
                "idDocumentsComplete" to (idFrontPath.isNotBlank() && idBackPath.isNotBlank()),
                "createdAt" to now,
                "updatedAt" to FieldValue.serverTimestamp(),
                "fullName" to trimmedName,
                "phoneNumber" to trimmedPhone,
                "vehicleType" to vehicleType,
                "licensePlate" to "ب ط ل 1 0 0",
                "avatarUri" to profilePath,
                "avatarPresetIndex" to avatarPresetIndex,
                "walletBalance" to 0.0,
                "rating" to 5.0,
                "isOnline" to false,
                "isLoggedIn" to false,
                "emailVerified" to false,
                "isEmailVerified" to false
            )

            try {
                firestore.collection("captains").document(uid).set(captainDocData).await()
                Log.i("CaptainRepository", "FIRESTORE CAPTAIN CREATE SUCCESS: captains/$uid")
            } catch (fsEx: Exception) {
                val firestoreCode = (fsEx as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
                Log.e("CaptainRepository", "FIRESTORE CAPTAIN CREATE ERROR - code: $firestoreCode, message: ${fsEx.message}", fsEx)
                return Result.failure(
                    Exception("فشل إنشاء ملف الكابتن في Firestore: [$firestoreCode] ${fsEx.localizedMessage ?: fsEx.message}")
                )
            }

            // Step 6: Save locally in Room database
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
                vehicleType = vehicleType,
                licensePlate = "ب ط ل 1 0 0",
                rating = 5.0f,
                totalOrdersCount = 0,
                walletBalance = 0.0,
                isOnline = false,
                isLoggedIn = false,
                role = "captain",
                status = "pending"
            )
            captainDao.insertOrUpdate(newCaptain)
            PhoneAuthRegistry.savePhoneEmailMapping(context, trimmedPhone, trimmedEmail)

            return Result.success(trimmedEmail)
        } catch (e: FirebaseAuthUserCollisionException) {
            try {
                val signInResult = firebaseAuth.signInWithEmailAndPassword(trimmedEmail, pass).await()
                val existingUser = signInResult.user
                if (existingUser != null) {
                    val uid = existingUser.uid
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

                    val now = System.currentTimeMillis()
                    val captainDocData = hashMapOf<String, Any>(
                        "uid" to uid,
                        "name" to trimmedName,
                        "email" to trimmedEmail,
                        "phone" to trimmedPhone,
                        "role" to "captain",
                        "status" to "pending",
                        "profileImagePath" to profilePath,
                        "idFrontImagePath" to idFrontPath,
                        "idBackImagePath" to idBackPath,
                        "licenseImagePath" to licensePath,
                        "idDocumentsComplete" to (idFrontPath.isNotBlank() && idBackPath.isNotBlank()),
                        "createdAt" to now,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "fullName" to trimmedName,
                        "phoneNumber" to trimmedPhone,
                        "vehicleType" to vehicleType,
                        "licensePlate" to "ب ط ل 1 0 0",
                        "avatarUri" to profilePath,
                        "avatarPresetIndex" to avatarPresetIndex,
                        "walletBalance" to 0.0,
                        "rating" to 5.0,
                        "isOnline" to false,
                        "isLoggedIn" to false,
                        "emailVerified" to existingUser.isEmailVerified,
                        "isEmailVerified" to existingUser.isEmailVerified
                    )

                    try {
                        firestore.collection("captains").document(uid).set(captainDocData, SetOptions.merge()).await()
                    } catch (fsEx: Exception) {
                        val firestoreCode = (fsEx as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
                        return Result.failure(
                            Exception("فشل إنشاء ملف الكابتن في Firestore: [$firestoreCode] ${fsEx.localizedMessage ?: fsEx.message}")
                        )
                    }

                    if (!existingUser.isEmailVerified) {
                        try {
                            existingUser.sendEmailVerification().await()
                        } catch (_: Exception) {}
                    }

                    val repairedCaptain = CaptainEntity(
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
                        vehicleType = vehicleType,
                        licensePlate = "ب ط ل 1 0 0",
                        rating = 5.0f,
                        totalOrdersCount = 0,
                        walletBalance = 0.0,
                        isOnline = false,
                        isLoggedIn = false,
                        role = "captain",
                        status = "pending"
                    )
                    captainDao.insertOrUpdate(repairedCaptain)
                    PhoneAuthRegistry.savePhoneEmailMapping(context, trimmedPhone, trimmedEmail)
                    return Result.success(trimmedEmail)
                }
            } catch (authError: Exception) {
                Log.w("CaptainRepository", "Could not sign in existing user: ${authError.message}")
            }
            return Result.failure(Exception("هذا البريد الإلكتروني مسجل بالفعل. يرجى تسجيل الدخول أو استخدام بريد إلكتروني آخر."))
        } catch (e: FirebaseAuthWeakPasswordException) {
            return Result.failure(Exception("كلمة السر ضعيفة جداً. يجب أن تتكون من 6 خانات على الأقل."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            return Result.failure(Exception("صيغة البريد الإلكتروني غير صحيحة. يرجى التأكد من كتابة البريد بشكل صحيح."))
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Registration failure", e)
            val msg = e.localizedMessage ?: "حدث خطأ أثناء التسجيل"
            return Result.failure(Exception("فشل إنشاء الحساب: $msg"))
        }
    }

    suspend fun checkEmailVerification(): Result<Boolean> {
        return try {
            val user = firebaseAuth.currentUser
                ?: return Result.failure(Exception("لم يتم العثور على جلسة مستخدم نشطة. يرجى تسجيل الدخول."))

            user.reload().await()
            if (user.isEmailVerified) {
                val captain = captainDao.getCaptain()
                if (captain != null) {
                    markLocalAndRemoteVerified(captain, user.uid)
                }
                Result.success(true)
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error checking verification", e)
            Result.failure(Exception(e.localizedMessage ?: "تعذر التحقق من حالة البريد الإلكتروني"))
        }
    }

    private suspend fun markLocalAndRemoteVerified(captain: CaptainEntity, uid: String) {
        captainDao.insertOrUpdate(captain.copy(isLoggedIn = true, isOnline = true, uid = uid))
        val phone = captain.phoneNumber
        val updateMap = mapOf(
            "emailVerified" to true,
            "isEmailVerified" to true,
            "isLoggedIn" to true,
            "isOnline" to true,
            "updatedAt" to System.currentTimeMillis()
        )
        try {
            firestore.collection("captains").document(uid).update(updateMap)
            try {
                firestore.collection("drivers").document(uid).update(updateMap)
            } catch (ignore: Exception) {}
        } catch (e: Exception) {
            Log.e("CaptainRepository", "Error updating isEmailVerified in Firestore", e)
        }
    }

    suspend fun resendVerificationEmail(): Result<String> {
        return try {
            val user = firebaseAuth.currentUser
                ?: return Result.failure(Exception("تعذر العثور على حسابك لإعادة الإرسال. يرجى تسجيل الدخول."))
            user.sendEmailVerification().await()
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
            "lastLocationUpdate" to FieldValue.serverTimestamp(),
            "lastLocationTimestamp" to now,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            firestore.collection("captains").document(uid).update(locationData).await()
            try {
                firestore.collection("drivers").document(uid).update(locationData)
            } catch (_: Exception) {}

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
                try {
                    firestore.collection("drivers").document(uid).update(offlineData)
                } catch (_: Exception) {}
            }
        } catch (_: CancellationException) {
            // Coroutine cancelled cleanly, ignore
        } catch (e: Exception) {
            Log.w("CaptainRepository", "Notice updating offline status in Firestore: ${e.message}")
        }
    }

    suspend fun setOnlineStatus(isOnline: Boolean) {
        captainDao.updateOnlineStatus(isOnline)
        val user = firebaseAuth.currentUser
        val uid = user?.uid
        if (!uid.isNullOrBlank()) {
            val statusMap = mapOf<String, Any>(
                "isOnline" to isOnline,
                "presenceStatus" to (if (isOnline) "available" else "offline"),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                firestore.collection("captains").document(uid).update(statusMap)
                try {
                    firestore.collection("drivers").document(uid).update(statusMap)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("CaptainRepository", "Error updating online status in Firestore", e)
            }
        }
    }

    suspend fun logout() {
        stopRealtimeShiftListener()
        stopRealtimeOrdersListener()
        storageRepository.clearSessionMemoryCache()
        try {
            val uid = firebaseAuth.currentUser?.uid
            if (!uid.isNullOrBlank()) {
                firestore.collection("captains").document(uid).update(
                    mapOf("isLoggedIn" to false, "isOnline" to false)
                )
            }
        } catch (_: Exception) {}
        try {
            firebaseAuth.signOut()
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
        avatarUri: String = ""
    ): Result<Unit> {
        val current = captainDao.getCaptain()
        val normalizedPhone = PhoneAuthRegistry.normalizeEgyptianPhone(phoneNumber)
        val cleanEmail = PhoneAuthRegistry.cleanInvisibleChars(email)
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
                    "fullName" to fullName,
                    "name" to fullName,
                    "phone" to normalizedPhone,
                    "phoneNumber" to normalizedPhone,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (resolvedProfilePath.isNotBlank()) {
                    coreProfileMap["profileImagePath"] = resolvedProfilePath
                    coreProfileMap["profileImageUpdatedAt"] = FieldValue.serverTimestamp()
                }
                cloudMetadataPort.saveCaptainImageMetadata(uid, coreProfileMap)

                try {
                    val extraMap = mutableMapOf<String, Any>(
                        "vehicleType" to vehicleType,
                        "licensePlate" to licensePlate
                    )
                    if (resolvedProfilePath.isNotBlank()) {
                        extraMap["avatarUri"] = resolvedProfilePath
                    }
                    firestore.collection("captains").document(uid).update(extraMap).await()
                } catch (_: Exception) {}

                try {
                    firestore.collection("drivers").document(uid).update(coreProfileMap).await()
                } catch (_: Exception) {}
                startRealtimeShiftListener()
            } catch (e: Exception) {
                Log.e("CaptainRepository", "Error updating profile in Firestore", e)
                return Result.failure(e)
            }
        }

        val updated = (current ?: CaptainEntity(
            id = 1,
            uid = uid,
            fullName = fullName,
            phoneNumber = normalizedPhone,
            email = cleanEmail,
            password = "",
            avatarUri = resolvedAvatarDisplay,
            profileImagePath = resolvedProfilePath,
            vehicleType = vehicleType,
            licensePlate = licensePlate,
            rating = 5.0f,
            walletBalance = 0.0,
            isOnline = true,
            isLoggedIn = true,
            role = "captain",
            status = "active"
        )).copy(
            fullName = fullName,
            phoneNumber = normalizedPhone,
            email = cleanEmail,
            vehicleType = vehicleType,
            licensePlate = licensePlate,
            avatarUri = resolvedAvatarDisplay,
            profileImagePath = resolvedProfilePath
        )
        captainDao.insertOrUpdate(updated)
        PhoneAuthRegistry.savePhoneEmailMapping(context, normalizedPhone, cleanEmail)
        return Result.success(Unit)
    }

    /**
     * Atomic transaction to accept an order.
     * Prevents race condition if two captains attempt to accept the same order concurrently.
     */
    /**
     * 1. Accept order:
     * - Validates captain shift hours
     * - Ensures captain has no other active order
     * - Calls Cloud Function acceptDeliveryOffer
     * - Updates local Room state upon verified server confirmation
     */
    suspend fun acceptOrder(orderId: Long): Result<Unit> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val currentUid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
            ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("يجب تسجيل الدخول أولاً"))

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
        val res = cloudFunctionsOrderClient.acceptDeliveryOffer(orderDocId)
        return if (res.isSuccess) {
            orderDao.markOrderAccepted(orderId, currentUid, System.currentTimeMillis())
            reconcileDailyWallets()
            Result.success(Unit)
        } else {
            val err = res.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            // Remove locally ONLY if the server explicitly confirmed the order is gone/claimed (404/409/410/isNotFound).
            // On network or 5xx failure, keep the order untouched so the captain can retry!
            if (cfEx != null && (!cfEx.isRetryable || cfEx.isNotFound) && (cfEx.httpCode in listOf(404, 409, 410) || cfEx.isNotFound)) {
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
        orderDao.deleteOrderById(orderId)
        reconcileDailyWallets()
    }

    /**
     * 2. Arrived at merchant: calls Cloud Function captainArrivedAtMerchant (no local success on server failure)
     */
    suspend fun markAtStore(orderId: Long): Result<String> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val orderDocId = resolveCleanOrderDocId(order)
        val res = cloudFunctionsOrderClient.captainArrivedAtMerchant(orderDocId)
        if (res.isSuccess) {
            orderDao.updateOrderStatus(orderId, OrderStatus.AT_STORE)
        } else {
            val err = res.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && (cfEx.isNotFound || cfEx.httpCode in listOf(404, 410))) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return res
    }

    /**
     * 3. Confirm pickup from merchant: calls Cloud Function confirmPickup (no local success on server failure)
     */
    suspend fun confirmPickup(orderId: Long): Result<String> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))

        val orderDocId = resolveCleanOrderDocId(order)
        val res = cloudFunctionsOrderClient.confirmPickup(orderDocId)
        if (res.isSuccess) {
            orderDao.updateOrderStatus(orderId, OrderStatus.PICKED_UP)
        } else {
            val err = res.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && (cfEx.isNotFound || cfEx.httpCode in listOf(404, 410))) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return res
    }

    /**
     * 4. Submit customer details & receipt image to Firebase Storage ("orders/{orderId}/receipts/{uniqueFileName}")
     * and link receiptImagePath, receiptUploadedAt (ServerTimestamp), and receiptUploadedBy via Cloud Functions.
     * Never marks receipt saved until BOTH Firebase Storage upload AND server submission succeed.
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

        val orderDocId = resolveCleanOrderDocId(order)

        // Enforce order ownership & explicit allowed lifecycle state before uploading to Firebase Storage
        val uploadResult = storageRepository.uploadOrderReceiptImage(
            orderId = orderDocId,
            assignedCaptainId = order.captainId,
            orderStatus = order.status,
            imageUri = imageUri,
            onStateChange = onUploadStateChange
        )
        val uploadedStoragePath = uploadResult.getOrElse { err ->
            return Result.failure(err)
        }

        val serverRes = cloudMetadataPort.submitOrderReceiptDetails(
            orderDocId = orderDocId,
            customerName = customerName,
            customerPhone = customerPhone,
            receiptImagePath = uploadedStoragePath,
            notes = notes
        )

        return if (serverRes.isSuccess) {
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
            val serverErr = serverRes.exceptionOrNull() ?: Exception("فشل ربط بيانات الإيصال بالطلب على الخادم")
            val cfEx = serverErr as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && (cfEx.isNotFound || cfEx.httpCode in listOf(404, 410))) {
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
     * 5. Complete delivery: calls Cloud Function confirmDelivery ONLY when merchant has confirmed (EN_ROUTE).
     * Never updates local order status or wallet if the server call fails.
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

        val orderDocId = resolveCleanOrderDocId(order)
        val res = cloudFunctionsOrderClient.confirmDelivery(orderDocId)

        if (res.isSuccess) {
            val uid = cloudMetadataPort.currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: runCatching { firebaseAuth.currentUser?.uid }.getOrNull()
                ?: order.captainId
            val nowMs = System.currentTimeMillis()
            orderDao.markOrderDelivered(orderId, uid, nowMs)

            // Immediately reflect in captain's overall wallet & total completed orders
            captainDao.addToWallet(order.deliveryFee)
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
            val err = res.exceptionOrNull()
            val cfEx = err as? com.example.data.remote.CloudFunctionException
            if (cfEx != null && (cfEx.isNotFound || cfEx.httpCode in listOf(404, 410))) {
                orderDao.deleteOrderById(orderId)
            }
        }
        return res
    }

    /**
     * 6. Expire delivery offer after 60 seconds via Cloud Function (no direct Firestore write)
     */
    suspend fun expireDeliveryOffer(orderId: Long): Result<Unit> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))
        val orderDocId = resolveCleanOrderDocId(order)
        val res = cloudFunctionsOrderClient.expireDeliveryOffer(orderDocId, reason = "expired")
        if (res.isSuccess) {
            orderDao.updateOrderStatus(orderId, OrderStatus.EXPIRED)
        }
        return res
    }

    /**
     * 7. Decline incoming delivery offer via Cloud Function (no direct Firestore write)
     */
    suspend fun declineOrder(orderId: Long): Result<Unit> {
        val orders = allOrdersFlow.firstOrNull() ?: emptyList()
        val order = orders.find { it.id == orderId }
            ?: return Result.failure(Exception("الطلب غير موجود"))
        val orderDocId = resolveCleanOrderDocId(order)
        val res = cloudFunctionsOrderClient.expireDeliveryOffer(orderDocId, reason = "declined")
        if (res.isSuccess) {
            orderDao.markOrderCancelled(orderId, System.currentTimeMillis(), "تم رفض العرض")
        }
        return res
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

fun DocumentSnapshot.safeString(vararg fields: String, default: String = ""): String {
    for (field in fields) {
        val raw = try { get(field) } catch (_: Exception) { null } ?: continue
        val str = raw.toString().trim()
        if (str.isNotBlank()) return str
    }
    return default
}

fun DocumentSnapshot.toOrderEntity(): OrderEntity? {
    val docId = id
    val cleanDocId = docId.trim().removePrefix("#").trim()
    val rawOrderNum = safeString("orderNumber", "order_number", "orderId", "order_id", "id", default = (cleanDocId.ifBlank { docId }).takeLast(6).uppercase())
    val orderNum = rawOrderNum.trim().removePrefix("#").trim()
    val custName = safeString(
        "customerName", "customer_name", "client_name", "clientName",
        "userName", "user_name", "name", default = "عميل البطل"
    )
    val custPhone = safeString(
        "customerPhone", "customer_phone", "client_phone", "clientPhone",
        "phone", "phoneNumber", "mobile", default = "01000000000"
    )
    val storeName = safeString(
        "restaurant name", "restaurant_name", "restaurantName",
        "storeName", "store_name", "restaurant", "store",
        "vendorName", "vendor", "shopName", "shop",
        default = "مطعم الشريك"
    )
    val storePhone = safeString("storePhone", "restaurantPhone", "restaurant_phone", "store_phone", "vendorPhone")
    val storeImage = safeString(
        "storeImage", "storePhotoUrl", "restaurantImage", "restaurant_image",
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
    val fee = safeDouble("deliveryFee", "delivery_fee", "shippingFee", "fee", default = 25.0)
    val dist = safeDouble("distanceKm", "distance_km", "distance", default = 3.0)

    val captId = safeString(
        "captainId", "captain_id", "driverId", "driver_id",
        "assignedCaptainId", "assignedDriverId", "captainUid", "driverUid",
        "assignedCaptainPhone", "driverPhone", "captain_phone"
    )

    val rawStatus = safeString("status", "order_status", "orderStatus", "state", default = "pending").trim().lowercase()

    val statusEnum = when {
        rawStatus in listOf("cancelled", "canceled", "rejected", "declined", "ملغي", "مرفوض") -> OrderStatus.CANCELLED
        rawStatus in listOf("expired", "منتهي", "منتهي الصلاحية") -> OrderStatus.EXPIRED
        rawStatus in listOf("delivered", "completed", "done", "تم التسليم", "مكتمل") -> OrderStatus.DELIVERED
        rawStatus in listOf("en_route", "on_way", "on_the_way", "delivering", "في الطريق", "جاري التوصيل") -> OrderStatus.EN_ROUTE
        rawStatus in listOf("merchant_rejected", "مرفوض من التاجر") -> OrderStatus.MERCHANT_REJECTED
        rawStatus in listOf("awaiting_merchant_confirmation", "awaiting_confirmation", "بانتظار تأكيد التاجر") -> OrderStatus.AWAITING_MERCHANT_CONFIRMATION
        rawStatus in listOf("order_data_entered", "data_entered", "تم إدخال البيانات") -> OrderStatus.ORDER_DATA_ENTERED
        rawStatus in listOf("picked_up", "تم الاستلام", "مستلم") -> OrderStatus.PICKED_UP
        rawStatus in listOf("at_store", "arrived_at_merchant", "arrived", "arrived_store", "في المتجر", "تم الوصول") -> OrderStatus.AT_STORE
        rawStatus in listOf("accepted", "assigned", "تم القبول", "مقبول") -> {
            if (isPlaceholderOrUnassigned(captId)) OrderStatus.PENDING else OrderStatus.ACCEPTED
        }
        // Pending / Available / New / Preparing at restaurant / Ready
        else -> OrderStatus.PENDING
    }

    val created = safeLong("created_at", "createdAt", "timestamp", "date", default = System.currentTimeMillis())
    val note = safeString("notes", "note", "comment")
    val pin = safeString("deliveryPin", "pin", default = "1234")

    val accAt = safeLong("acceptedAt", "accepted_at")
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
    val receiptPath = safeString("receiptImagePath", "receipt_image_path")
    val receiptUpAt = safeLong("receiptUploadedAt", "receipt_uploaded_at", "receiptSubmittedAt")
    val receiptUpBy = safeString("receiptUploadedBy", "receipt_uploaded_by")
    // Backward compatibility with legacy ImgBB / URL fields without modifying them
    val imgUrl = receiptPath.ifBlank {
        safeString("orderImageUrl", "order_image_url", "receiptImageUrl", "deliveryImageUrl", "receiptImage")
    }
    val dataEnteredAt = safeLong("orderDataEnteredAt")

    val offerExpiresAt = safeLong("offerExpiresAt", "offer_expires_at")
    val merchantConfirmed = (get("merchantConfirmed") as? Boolean) ?: false
    val rejectionReason = safeString("rejectionReason", "rejection_reason")
    val cancelReason = safeString(
        "cancellationReason", "cancelReason", "cancellation_reason",
        "cancel_reason", "cancelledReason", "reason"
    ).ifBlank {
        if (statusEnum == OrderStatus.CANCELLED) rejectionReason else ""
    }
    val etaMinutes = safeLong("etaMinutes", "eta", default = 15L).toInt()

    val numericId = docId.hashCode().toLong().let { if (it < 0) -it else it }

    return OrderEntity(
        id = if (numericId == 0L) 1L else numericId,
        orderNumber = orderNum,
        customerName = custName,
        customerPhone = custPhone,
        storeName = storeName,
        storePhone = storePhone,
        storeImage = storeImage,
        pickupAddress = pickup,
        deliveryAddress = delivery,
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
