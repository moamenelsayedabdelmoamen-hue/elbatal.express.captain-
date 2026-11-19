package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.CaptainCloudMetadataPort
import com.example.data.CaptainEntity
import com.example.data.CaptainRepository
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.data.remote.CloudFunctionException
import com.example.data.remote.CloudFunctionsOrderClient
import com.example.data.remote.OrderCallableGateway
import com.example.data.storage.AuthUidProvider
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.NetworkConnectivityChecker
import com.example.data.storage.OfficialFirebaseStorageGateway
import com.example.data.storage.StorageGateway
import com.example.data.storage.StorageUploadException
import com.example.data.storage.UploadErrorKind
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Socket

/**
 * Security audit verification test suite covering:
 * 1. CloudFunctionsOrderClient & CaptainRepository failure handling (HTTP 500, IOException, HTTP 403)
 *    proving ZERO direct Firestore fallback writes occur and local Room state never fakes success.
 * 2. Explicit receipt upload allowlist blocking unknown, early, en_route, and closed order states.
 * 3. Private cache hygiene on logout & replacement + zero public downloadUrl usage + project ID verification.
 * 4. Real Android Firebase SDK -> Firebase Emulator (Auth + Firestore + Storage) security rules verification
 *    when the local emulators (9099, 8085, 9199) are active.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityAuditRuleTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = File(context.cacheDir, "sec_audit_${System.nanoTime()}").apply { mkdirs() }
        File(context.cacheDir, "fb_storage_cache").deleteRecursively()
        File(context.cacheDir, "camera_captures").deleteRecursively()

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        tempDir.deleteRecursively()
        File(context.cacheDir, "fb_storage_cache").deleteRecursively()
        File(context.cacheDir, "camera_captures").deleteRecursively()
    }

    private fun createSampleJpeg(name: String): Uri {
        val bmp = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(30, 140, 210))
        val file = File(tempDir, "$name.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return Uri.fromFile(file)
    }

    // -------------------------------------------------------------------------
    // 1. CloudFunctionsOrderClient failure: ZERO Firestore fallback, clear Arabic error, retry works
    // -------------------------------------------------------------------------
    @Test
    fun cloudFunctionsFailure_neverMutatesLocalStateOrFallsBackToFirestore_andRetrySucceeds() = runBlocking {
        val db = checkNotNull(database)
        var simulateMode = "NETWORK_DOWN" // NETWORK_DOWN -> INTERNAL_500 -> SUCCESS_200
        val calledFunctions = mutableListOf<String>()
        val capturedPayloads = mutableListOf<Pair<String, Map<String, Any?>>>()

        val fakeGateway = object : OrderCallableGateway {
            override suspend fun callFunction(
                functionName: String,
                payload: Map<String, Any?>
            ): Map<String, Any?> {
                calledFunctions.add(functionName)
                capturedPayloads.add(functionName to payload)
                return when (simulateMode) {
                    "NETWORK_DOWN" -> throw IOException("Simulated connection reset")
                    "INTERNAL_500" -> throw CloudFunctionException(
                        message = "خطأ داخلي في الخادم",
                        httpCode = 500,
                        functionCode = "INTERNAL",
                        isRetryable = true
                    )
                    else -> mapOf(
                        "success" to true,
                        "message" to "تم التنفيذ بنجاح",
                        "earnedAmount" to 45.0
                    )
                }
            }
        }

        val cfClient = CloudFunctionsOrderClient(
            firebaseAuth = null,
            callableGateway = fakeGateway,
            authUidProvider = { "captain_1" }
        )

        val storageGateway = object : StorageGateway {
            override suspend fun putBytesWithProgress(
                storagePath: String,
                bytes: ByteArray,
                contentType: String,
                customMetadata: Map<String, String>,
                requesterUid: String,
                onProgress: (Int) -> Unit
            ) {
                onProgress(100)
            }

            override suspend fun getBytes(storagePath: String, maxDownloadBytes: Long, requesterUid: String): ByteArray {
                return ByteArray(16) { 1 }
            }
        }

        val storageRepo = FirebaseStorageRepository(
            context = context,
            gateway = storageGateway,
            authUidProvider = AuthUidProvider { "captain_1" },
            connectivityChecker = NetworkConnectivityChecker { true },
            maxAutoRetries = 1
        )

        val metadataPort = object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String = "captain_1"
            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {}
            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? = emptyMap()
            override suspend fun submitOrderReceiptDetails(
                orderDocId: String,
                customerName: String,
                customerPhone: String,
                receiptImagePath: String,
                notes: String
            ): Result<String> {
                return cfClient.submitCustomerDeliveryDetails(orderDocId, customerName, customerPhone, receiptImagePath, notes)
            }
        }

        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = context,
            storageRepository = storageRepo,
            cloudMetadataPortOverride = metadataPort,
            cloudFunctionsClientOverride = cfClient
        )

        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "captain_1", fullName = "كابتن 1", phoneNumber = "01011111111", isOnline = true, isLoggedIn = true)
        )
        db.orderDao().insertOrder(
            OrderEntity(
                id = 500L,
                orderNumber = "EXP-500",
                firestoreDocId = "ORD_CF_500",
                customerName = "",
                customerPhone = "",
                storeName = "مطعم البطل",
                pickupAddress = "التجمع",
                deliveryAddress = "مدينة نصر",
                itemsSummary = "وجبة",
                itemsCount = 1,
                orderTotalAmount = 200.0,
                deliveryFee = 45.0,
                distanceKm = 4.0,
                status = OrderStatus.PENDING,
                captainId = ""
            )
        )

        // A) Network disconnect during acceptOrder -> MUST fail, keep order PENDING (zero Firestore fallback)
        val acceptNetFail = repo.acceptOrder(500L)
        assertTrue("acceptOrder must fail when Cloud Functions network is down", acceptNetFail.isFailure)
        val netEx = acceptNetFail.exceptionOrNull() as? CloudFunctionException
        assertNotNull(netEx)
        assertTrue("Network failure must be retryable", netEx!!.isRetryable)
        assertEquals("acceptOrder", calledFunctions.last())
        val afterNetFail = repo.allOrdersFlow.first().first { it.id == 500L }
        assertEquals("Order status MUST remain PENDING on network failure", OrderStatus.PENDING, afterNetFail.status)

        // B) INTERNAL 500 during acceptOrder -> MUST fail, keep order PENDING
        simulateMode = "INTERNAL_500"
        val accept500Fail = repo.acceptOrder(500L)
        assertTrue("acceptOrder must fail on INTERNAL without Firestore fallback", accept500Fail.isFailure)
        assertEquals(500, (accept500Fail.exceptionOrNull() as? CloudFunctionException)?.httpCode)
        assertEquals(OrderStatus.PENDING, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        // C) Server recovers -> Retry acceptOrder succeeds using canonical callable `acceptOrder`!
        simulateMode = "SUCCESS_200"
        assertTrue("Retry acceptOrder must succeed when server succeeds", repo.acceptOrder(500L).isSuccess)
        assertEquals(OrderStatus.ACCEPTED, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        // Verify client NEVER injects captainId or earned fees in accept/confirmDelivery payloads
        val acceptPayload = capturedPayloads.last { it.first == "acceptOrder" }.second
        assertEquals("ORD_CF_500", acceptPayload["orderId"])
        assertFalse("Client must not send self-asserted captainId", acceptPayload.containsKey("captainId"))

        // D) Test failure & no local mutation on markAtStore, confirmPickup, saveOrderCustomerData, and completeDelivery
        simulateMode = "NETWORK_DOWN"
        assertTrue(repo.markAtStore(500L).isFailure)
        assertEquals(OrderStatus.ACCEPTED, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "SUCCESS_200"
        assertTrue(repo.markAtStore(500L).isSuccess)
        assertEquals(OrderStatus.AT_STORE, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "INTERNAL_500"
        assertTrue(repo.confirmPickup(500L).isFailure)
        assertEquals(OrderStatus.AT_STORE, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "SUCCESS_200"
        assertTrue(repo.confirmPickup(500L).isSuccess)
        assertEquals(OrderStatus.PICKED_UP, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        val receiptUri = createSampleJpeg("cf_receipt")
        simulateMode = "INTERNAL_500"
        val submitFail = repo.saveOrderCustomerData(500L, "عميل", "01012345678", "", receiptUri)
        assertTrue(submitFail.isFailure)
        assertEquals(OrderStatus.PICKED_UP, repo.allOrdersFlow.first().first { it.id == 500L }.status)
        assertEquals("", repo.allOrdersFlow.first().first { it.id == 500L }.receiptImagePath)

        simulateMode = "SUCCESS_200"
        assertTrue(repo.saveOrderCustomerData(500L, "عميل", "01012345678", "", receiptUri).isSuccess)
        assertEquals(OrderStatus.AWAITING_MERCHANT_CONFIRMATION, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        val prematureComplete = repo.completeDelivery(500L)
        assertTrue("Cannot complete delivery before EN_ROUTE", prematureComplete.isFailure)

        db.orderDao().updateOrderStatus(500L, OrderStatus.EN_ROUTE)

        simulateMode = "NETWORK_DOWN"
        assertTrue("completeDelivery must fail when Cloud Functions fail", repo.completeDelivery(500L).isFailure)
        assertEquals(OrderStatus.EN_ROUTE, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "SUCCESS_200"
        val completeOk = repo.completeDelivery(500L)
        assertTrue(completeOk.isSuccess)
        assertEquals(45.0, completeOk.getOrDefault(0.0), 0.01)
        assertEquals(OrderStatus.DELIVERED, repo.allOrdersFlow.first().first { it.id == 500L }.status)
        val deliverPayload = capturedPayloads.last { it.first == "confirmDelivery" }.second
        assertFalse("Client must not send fee/earnedAmount in confirmDelivery", deliverPayload.containsKey("earnedAmount"))
        assertFalse("Client must not send deliveryFee in confirmDelivery", deliverPayload.containsKey("deliveryFee"))
    }

    @Test
    fun cloudFunctionsErrorClassification_preservesOrderOnUnimplementedOrConflict_andDeletesOnlyOnDomainGone() = runBlocking {
        val db = checkNotNull(database)
        var scenario = "UNIMPLEMENTED" // UNIMPLEMENTED -> ACTIVE_CONFLICT -> ACCEPT_OK -> DOMAIN_GONE

        val fakeGateway = object : OrderCallableGateway {
            override suspend fun callFunction(
                functionName: String,
                payload: Map<String, Any?>
            ): Map<String, Any?> {
                return when (scenario) {
                    "UNIMPLEMENTED" -> throw CloudFunctionException(
                        message = "خدمة الخادم لقبول وتنفيذ الطلبات غير منشورة حالياً.",
                        httpCode = 404,
                        functionCode = "UNIMPLEMENTED",
                        isRetryable = true,
                        isNotFound = false,
                        isEndpointNotFound = true
                    )
                    "ACTIVE_CONFLICT" -> throw CloudFunctionException(
                        message = "لديك طلب نشط بالفعل قيد التنفيذ، لا يمكن قبول أكثر من طلب في وقت واحد.",
                        httpCode = 409,
                        functionCode = "FAILED_PRECONDITION",
                        isRetryable = false,
                        isNotFound = false,
                        isEndpointNotFound = false
                    )
                    "DOMAIN_GONE" -> throw CloudFunctionException(
                        message = "عذراً، تم قبول هذا الطلب بالفعل بواسطة كابتن آخر.",
                        httpCode = 409,
                        functionCode = "ALREADY_EXISTS",
                        isRetryable = false,
                        isNotFound = true,
                        isEndpointNotFound = false
                    )
                    else -> mapOf("success" to true, "message" to "تم قبول الطلب بنجاح")
                }
            }
        }

        val cfClient = CloudFunctionsOrderClient(
            firebaseAuth = null,
            callableGateway = fakeGateway,
            authUidProvider = { "captain_1" }
        )
        val storageRepo = FirebaseStorageRepository(
            context = context,
            gateway = object : StorageGateway {
                override suspend fun putBytesWithProgress(
                    storagePath: String, bytes: ByteArray, contentType: String,
                    customMetadata: Map<String, String>, requesterUid: String, onProgress: (Int) -> Unit
                ) {}
                override suspend fun getBytes(storagePath: String, maxDownloadBytes: Long, requesterUid: String) = ByteArray(0)
            },
            authUidProvider = AuthUidProvider { "captain_1" },
            connectivityChecker = NetworkConnectivityChecker { true },
            maxAutoRetries = 1
        )
        val metadataPort = object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String = "captain_1"
            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {}
            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? = emptyMap()
            override suspend fun submitOrderReceiptDetails(
                orderDocId: String, customerName: String, customerPhone: String, receiptImagePath: String, notes: String
            ): Result<String> = Result.success("")
        }
        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = context,
            storageRepository = storageRepo,
            cloudMetadataPortOverride = metadataPort,
            cloudFunctionsClientOverride = cfClient
        )

        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "captain_1", fullName = "كابتن 1", phoneNumber = "01011111111", isOnline = true, isLoggedIn = true)
        )
        db.orderDao().insertOrder(
            OrderEntity(
                id = 600L, orderNumber = "##ELB-600", firestoreDocId = "ORD_600",
                customerName = "عميل", customerPhone = "0100", storeName = "مطعم",
                pickupAddress = "عنوان 1", deliveryAddress = "عنوان 2",
                itemsSummary = "وجبة", itemsCount = 1, orderTotalAmount = 100.0,
                deliveryFee = 30.0, distanceKm = 2.0, status = OrderStatus.PENDING, captainId = ""
            )
        )

        // 1) Undeployed Cloud Function UNIMPLEMENTED -> ZERO Firestore fallback, preserves PENDING order!
        val unimplementedRes = repo.acceptOrder(600L)
        assertTrue(unimplementedRes.isFailure)
        val unimplEx = unimplementedRes.exceptionOrNull() as? CloudFunctionException
        assertNotNull(unimplEx)
        assertTrue(unimplEx!!.isEndpointNotFound)
        assertFalse(unimplEx.isNotFound)
        assertTrue(unimplEx.isRetryable)
        assertEquals(OrderStatus.PENDING, repo.allOrdersFlow.first().first { it.id == 600L }.status)

        // 2) Active conflict FAILED_PRECONDITION -> preserves PENDING order in Room
        scenario = "ACTIVE_CONFLICT"
        val conflictRes = repo.acceptOrder(600L)
        assertTrue(conflictRes.isFailure)
        val conflictEx = conflictRes.exceptionOrNull() as CloudFunctionException
        assertFalse("Active order conflict must NOT delete target order", conflictEx.isNotFound)
        assertEquals(OrderStatus.PENDING, repo.allOrdersFlow.first().first { it.id == 600L }.status)

        // 3) acceptOrder succeeds -> order transitions to ACCEPTED
        scenario = "ACCEPT_OK"
        assertTrue(repo.acceptOrder(600L).isSuccess)
        assertEquals(OrderStatus.ACCEPTED, repo.allOrdersFlow.first().first { it.id == 600L }.status)

        db.orderDao().markOrderDelivered(600L, "captain_1", System.currentTimeMillis())

        // 4) Genuine domain error (already claimed by another captain) -> flagged isNotFound=true & deletes stale order
        db.orderDao().insertOrder(
            OrderEntity(
                id = 601L, orderNumber = "ELB-601", firestoreDocId = "ORD_601",
                customerName = "عميل", customerPhone = "0100", storeName = "مطعم",
                pickupAddress = "عنوان 1", deliveryAddress = "عنوان 2",
                itemsSummary = "وجبة", itemsCount = 1, orderTotalAmount = 100.0,
                deliveryFee = 30.0, distanceKm = 2.0, status = OrderStatus.PENDING, captainId = ""
            )
        )
        scenario = "DOMAIN_GONE"
        val goneRes = repo.acceptOrder(601L)
        assertTrue(goneRes.isFailure)
        val goneEx = goneRes.exceptionOrNull() as CloudFunctionException
        assertTrue("Genuine claimed error must set isNotFound=true", goneEx.isNotFound)
        assertFalse(goneEx.isEndpointNotFound)
        assertFalse(goneEx.isRetryable)
        assertTrue("Stale order must be removed from Room on genuine claimed error", repo.allOrdersFlow.first().none { it.id == 601L })
        Unit
    }

    // -------------------------------------------------------------------------
    // 2. Explicit receipt status allowlist & private cache wipe & project config audit
    // -------------------------------------------------------------------------
    @Test
    fun storageAudit_enforcesExplicitStatusAllowlist_wipesPrivateCacheOnLogout_andTargetsCorrectProject() = runBlocking {
        val gateway = object : StorageGateway {
            override suspend fun putBytesWithProgress(
                storagePath: String,
                bytes: ByteArray,
                contentType: String,
                customMetadata: Map<String, String>,
                requesterUid: String,
                onProgress: (Int) -> Unit
            ) {
                onProgress(100)
            }

            override suspend fun getBytes(storagePath: String, maxDownloadBytes: Long, requesterUid: String): ByteArray {
                return ByteArray(32) { 7 }
            }
        }

        val storageRepo = FirebaseStorageRepository(
            context = context,
            gateway = gateway,
            authUidProvider = AuthUidProvider { "captain_1" },
            connectivityChecker = NetworkConnectivityChecker { true }
        )
        val uri = createSampleJpeg("audit_img")

        // Disallowed receipt statuses (early, en_route, closed) MUST all be rejected
        val disallowedStatuses = listOf(
            OrderStatus.PENDING,
            OrderStatus.ACCEPTED,
            OrderStatus.AT_STORE,
            OrderStatus.EN_ROUTE,
            OrderStatus.DELIVERED,
            OrderStatus.CANCELLED,
            OrderStatus.EXPIRED
        )
        for (badStatus in disallowedStatuses) {
            val res = storageRepo.uploadOrderReceiptImage(
                orderId = "ORD_1",
                assignedCaptainId = "captain_1",
                orderStatus = badStatus,
                imageUri = uri
            )
            assertTrue("Status $badStatus MUST be blocked from receipt upload", res.isFailure)
            assertEquals(UploadErrorKind.ORDER_CLOSED, (res.exceptionOrNull() as? StorageUploadException)?.kind)
        }

        // Allowed receipt statuses MUST succeed
        for (goodStatus in listOf(OrderStatus.PICKED_UP, OrderStatus.MERCHANT_REJECTED, OrderStatus.AWAITING_MERCHANT_CONFIRMATION)) {
            val res = storageRepo.uploadOrderReceiptImage(
                orderId = "ORD_1",
                assignedCaptainId = "captain_1",
                orderStatus = goodStatus,
                imageUri = uri
            )
            assertTrue("Explicitly allowed status $goodStatus must succeed", res.isSuccess)
        }

        // Upload ID front to populate UID-scoped private cache, then verify clearSessionMemoryCache wipes disk cache
        assertTrue(storageRepo.uploadCaptainIdFrontImage("captain_1", uri).isSuccess)
        val cacheRoot = File(context.cacheDir, "fb_storage_cache")
        assertTrue("Private cache folder should exist while session is active", cacheRoot.exists() && cacheRoot.walkTopDown().any { it.isFile })
        storageRepo.clearSessionMemoryCache()
        assertFalse("Logout clearSessionMemoryCache MUST wipe private ID/receipt cache from disk", cacheRoot.exists())

        // Verify google-services.json targets el-batal-express-956ae
        val googleServicesFile = listOf(
            File("google-services.json"),
            File("app/google-services.json"),
            File("../app/google-services.json")
        ).firstOrNull { it.exists() }
        assertNotNull("google-services.json must exist", googleServicesFile)
        val gsJson = JSONObject(googleServicesFile!!.readText())
        val projectInfo = gsJson.getJSONObject("project_info")
        assertEquals("el-batal-express-956ae", projectInfo.getString("project_id"))
        assertEquals("el-batal-express-956ae.firebasestorage.app", projectInfo.getString("storage_bucket"))

        // Verify zero public downloadUrl calls in production Kotlin source files
        val mainSrcDir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val filesCallingDownloadUrl = mainSrcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val trimmed = line.trim()
                    !trimmed.startsWith("//") && !trimmed.startsWith("*") &&
                        (trimmed.contains(".downloadUrl") || trimmed.contains("getDownloadUrl("))
                }
            }
            .map { it.name }
            .toList()
        assertTrue(
            "Production source MUST NOT generate public downloadUrl links for private Storage objects: $filesCallingDownloadUrl",
            filesCallingDownloadUrl.isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // 3. Real Android Firebase SDK -> Live Firebase Emulators (Auth + Firestore + Storage)
    // -------------------------------------------------------------------------
    @Test
    fun liveFirebaseEmulators_androidSdkEnforcesStorageAndFirestoreRulesEndToEnd() = runBlocking {
        if (!isPortOpen("127.0.0.1", 8085) || !isPortOpen("127.0.0.1", 9199) || !isPortOpen("127.0.0.1", 9099)) {
            println("Skipping live emulator SDK check because emulators are not listening on 8085/9199/9099")
            return@runBlocking
        }

        val appName = "emulatorAuditApp_${System.nanoTime()}"
        val options = FirebaseOptions.Builder()
            .setProjectId("el-batal-express-956ae")
            .setApplicationId("1:1234567890:android:audit")
            .setApiKey("fake-api-key-for-emulator")
            .setStorageBucket("el-batal-express-956ae.firebasestorage.app")
            .build()

        val app = FirebaseApp.initializeApp(context, options, appName)
        try {
            val auth = FirebaseAuth.getInstance(app).apply { useEmulator("127.0.0.1", 9099) }
            val firestore = FirebaseFirestore.getInstance(app).apply {
                useEmulator("127.0.0.1", 8085)
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
            }
            val storage = FirebaseStorage.getInstance(app, "gs://el-batal-express-956ae.firebasestorage.app").apply {
                useEmulator("127.0.0.1", 9199)
                maxUploadRetryTimeMillis = 3_000L
                maxOperationRetryTimeMillis = 3_000L
            }

            // Register two distinct captains, a merchant, and an admin in Auth Emulator
            val cap1Uid = signUpOrSignIn(auth, "cap1_sdk@elbatal.com", "Pass123456!")
            val cap2Uid = signUpOrSignIn(auth, "cap2_sdk@elbatal.com", "Pass123456!")
            val merchUid = signUpOrSignIn(auth, "merch1_sdk@elbatal.com", "Pass123456!")
            val adminUid = signUpOrSignIn(auth, "admin_sdk@elbatal.com", "Pass123456!")

            // Seed trusted admin, merchant, and orders via Emulator Admin REST bypass ("Bearer owner")
            seedFirestoreDocAsAdmin(
                "admins/$adminUid",
                mapOf("role" to strVal("admin"))
            )
            seedFirestoreDocAsAdmin(
                "restaurants/$merchUid",
                mapOf("name" to strVal("مطعم SDK"), "isOpen" to boolVal(true))
            )
            seedFirestoreDocAsAdmin(
                "captains/$cap1Uid",
                mapOf(
                    "uid" to strVal(cap1Uid),
                    "accountType" to strVal("captain"),
                    "role" to strVal("captain"),
                    "status" to strVal("active"),
                    "walletBalance" to doubleVal(0.0),
                    "activeMerchantId" to strVal(merchUid)
                )
            )
            seedFirestoreDocAsAdmin(
                "captains/$cap2Uid",
                mapOf(
                    "uid" to strVal(cap2Uid),
                    "accountType" to strVal("captain"),
                    "role" to strVal("captain"),
                    "status" to strVal("active"),
                    "walletBalance" to doubleVal(0.0)
                )
            )
            seedFirestoreDocAsAdmin(
                "orders/sdk_order_open",
                mapOf(
                    "status" to strVal("picked_up"),
                    "captainId" to strVal(cap1Uid),
                    "merchantId" to strVal(merchUid)
                )
            )
            seedFirestoreDocAsAdmin(
                "orders/sdk_order_closed",
                mapOf(
                    "status" to strVal("delivered"),
                    "captainId" to strVal(cap1Uid),
                    "merchantId" to strVal(merchUid)
                )
            )

            val officialGateway = OfficialFirebaseStorageGateway { storage }
            val sampleUri = createSampleJpeg("sdk_live")

            // 1) Sign in as Captain 1: upload own profile, ID front & back, and open-order receipt -> SUCCEEDS
            auth.signInWithEmailAndPassword("cap1_sdk@elbatal.com", "Pass123456!").await()
            val repoCap1 = FirebaseStorageRepository(
                context = context,
                gateway = officialGateway,
                authUidProvider = AuthUidProvider { auth.currentUser?.uid },
                connectivityChecker = NetworkConnectivityChecker { true },
                maxAutoRetries = 1
            )
            assertTrue(repoCap1.uploadCaptainProfileImage(cap1Uid, sampleUri).isSuccess)
            assertTrue(repoCap1.uploadCaptainIdFrontImage(cap1Uid, sampleUri).isSuccess)
            assertTrue(repoCap1.uploadCaptainIdBackImage(cap1Uid, sampleUri).isSuccess)
            val openReceiptRes = repoCap1.uploadOrderReceiptImage(
                orderId = "sdk_order_open",
                assignedCaptainId = cap1Uid,
                orderStatus = OrderStatus.PICKED_UP,
                imageUri = sampleUri,
                uniqueFileName = "sdk_r1.jpg"
            )
            assertTrue("Captain 1 uploading receipt to open order must succeed on real Storage Emulator", openReceiptRes.isSuccess)

            // Captain 1 trying raw Storage SDK upload to CLOSED order -> REJECTED by storage.rules!
            val closedRawAttempt = runCatching {
                val meta = com.google.firebase.storage.StorageMetadata.Builder()
                    .setContentType("image/jpeg")
                    .setCustomMetadata("orderId", "sdk_order_closed")
                    .setCustomMetadata("captainId", cap1Uid)
                    .build()
                storage.reference.child("orders/sdk_order_closed/receipts/blocked.jpg")
                    .putBytes(ByteArray(64) { 0x2A }, meta)
                    .await()
            }
            assertTrue("Real Storage Emulator MUST reject receipt upload on closed order", closedRawAttempt.isFailure)

            // Captain 1 trying direct Firestore update to walletBalance or orders -> REJECTED by firestore.rules!
            val walletTamper = runCatching {
                firestore.collection("captains").document(cap1Uid).update("walletBalance", 5000.0).await()
            }
            assertTrue("Real Firestore Emulator MUST reject captain modifying walletBalance", walletTamper.isFailure)

            val directOrderWrite = runCatching {
                firestore.collection("orders").document("sdk_order_open").update("status", "delivered").await()
            }
            assertTrue("Real Firestore Emulator MUST reject direct order status write by captain", directOrderWrite.isFailure)

            // 2) Sign in as Captain 2: attempt to read Captain 1's National ID card -> REJECTED by storage.rules!
            auth.signInWithEmailAndPassword("cap2_sdk@elbatal.com", "Pass123456!").await()
            val crossIdRead = runCatching {
                storage.reference.child("captains/$cap1Uid/documents/id_front.jpg").getBytes(1024 * 1024).await()
            }
            assertTrue("Real Storage Emulator MUST reject Captain 2 reading Captain 1's National ID", crossIdRead.isFailure)

            // 3) Sign in as Merchant: CAN read own order's receipt, CANNOT read Captain 1's National ID!
            auth.signInWithEmailAndPassword("merch1_sdk@elbatal.com", "Pass123456!").await()
            val merchReceiptRead = runCatching {
                storage.reference.child("orders/sdk_order_open/receipts/sdk_r1.jpg").getBytes(1024 * 1024).await()
            }
            assertTrue("Merchant MUST be able to read receipt of their own order", merchReceiptRead.isSuccess)

            val merchIdRead = runCatching {
                storage.reference.child("captains/$cap1Uid/documents/id_front.jpg").getBytes(1024 * 1024).await()
            }
            assertTrue("Merchant MUST NOT be able to read Captain National ID card", merchIdRead.isFailure)

            // 4) Sign in as Admin: CAN read Captain 1's National ID card & update order status!
            auth.signInWithEmailAndPassword("admin_sdk@elbatal.com", "Pass123456!").await()
            val adminIdRead = runCatching {
                storage.reference.child("captains/$cap1Uid/documents/id_front.jpg").getBytes(1024 * 1024).await()
            }
            assertTrue("Admin MUST be able to read Captain National ID card", adminIdRead.isSuccess)
            val adminOrderUpdate = runCatching {
                firestore.collection("orders").document("sdk_order_open").update("status", "en_route").await()
            }
            assertTrue("Admin MUST be able to update order status", adminOrderUpdate.isSuccess)
        } finally {
            app.delete()
        }
    }

    @Test
    fun offlineOrOutsideShiftCaptain_cannotBeAssignedOrAcceptOrders() = runBlocking {
        val db = checkNotNull(database)
        var callableCalls = 0

        val fakeGateway = object : OrderCallableGateway {
            override suspend fun callFunction(
                functionName: String,
                payload: Map<String, Any?>
            ): Map<String, Any?> {
                callableCalls++
                return mapOf("success" to true)
            }
        }

        val cfClient = CloudFunctionsOrderClient(
            firebaseAuth = null,
            callableGateway = fakeGateway,
            authUidProvider = { "cap_elig" }
        )
        val storageRepo = FirebaseStorageRepository(
            context = context,
            gateway = object : StorageGateway {
                override suspend fun putBytesWithProgress(
                    storagePath: String,
                    bytes: ByteArray,
                    contentType: String,
                    customMetadata: Map<String, String>,
                    requesterUid: String,
                    onProgress: (Int) -> Unit
                ) {}
                override suspend fun getBytes(storagePath: String, maxDownloadBytes: Long, requesterUid: String): ByteArray = ByteArray(0)
            },
            authUidProvider = AuthUidProvider { "cap_elig" },
            connectivityChecker = NetworkConnectivityChecker { true }
        )
        val metadataPort = object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String = "cap_elig"
            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {}
            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? = emptyMap()
            override suspend fun submitOrderReceiptDetails(
                orderDocId: String,
                customerName: String,
                customerPhone: String,
                receiptImagePath: String,
                notes: String
            ): Result<String> = Result.success("ok")
        }

        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = context,
            storageRepository = storageRepo,
            cloudMetadataPortOverride = metadataPort,
            cloudFunctionsClientOverride = cfClient
        )

        val pendingOrder = OrderEntity(
            id = 910L, orderNumber = "PND-910", firestoreDocId = "ORD_910",
            customerName = "عميل", customerPhone = "0100", storeName = "مطعم",
            pickupAddress = "أ", deliveryAddress = "ب", itemsSummary = "وجبة",
            itemsCount = 1, orderTotalAmount = 100.0, deliveryFee = 25.0,
            distanceKm = 2.0, status = OrderStatus.PENDING, captainId = ""
        )
        val activeOrder = OrderEntity(
            id = 911L, orderNumber = "ACT-911", firestoreDocId = "ORD_911",
            customerName = "عميل 2", customerPhone = "0101", storeName = "مطعم 2",
            pickupAddress = "أ", deliveryAddress = "ب", itemsSummary = "وجبة",
            itemsCount = 1, orderTotalAmount = 120.0, deliveryFee = 30.0,
            distanceKm = 2.5, status = OrderStatus.ACCEPTED, captainId = "cap_elig"
        )
        val deliveredOrder = OrderEntity(
            id = 912L, orderNumber = "DEL-912", firestoreDocId = "ORD_912",
            customerName = "عميل 3", customerPhone = "0102", storeName = "مطعم 3",
            pickupAddress = "أ", deliveryAddress = "ب", itemsSummary = "وجبة",
            itemsCount = 1, orderTotalAmount = 150.0, deliveryFee = 35.0,
            distanceKm = 3.0, status = OrderStatus.DELIVERED, captainId = "cap_elig"
        )

        // 1) Offline captain: acceptOrder MUST be blocked with 0 server calls
        db.captainDao().insertOrUpdate(
            CaptainEntity(id = 1, uid = "cap_elig", fullName = "كابتن", phoneNumber = "01011111111", isOnline = false, isLoggedIn = true)
        )
        db.orderDao().insertOrder(pendingOrder)
        db.orderDao().insertOrder(activeOrder)
        db.orderDao().insertOrder(deliveredOrder)

        val offlineAcceptRes = repo.acceptOrder(910L)
        assertTrue("Offline captain must NOT accept orders", offlineAcceptRes.isFailure)
        assertEquals(0, callableCalls)
        assertEquals(OrderStatus.PENDING, repo.allOrdersFlow.first().first { it.id == 910L }.status)

        val offlinePendingQuery = repo.queryPendingOrdersFromFirestore()
        assertTrue(offlinePendingQuery.isSuccess)
        assertTrue("Offline captain must receive 0 pending orders", offlinePendingQuery.getOrDefault(emptyList()).isEmpty())

        // 2) setOnlineStatus(false) removes PENDING offers while preserving ACTIVE and DELIVERED orders
        db.orderDao().insertOrder(pendingOrder)
        repo.setOnlineStatus(false)
        val remainingOrders = repo.allOrdersFlow.first()
        assertFalse("PENDING order must be cleared when captain goes offline", remainingOrders.any { it.id == 910L })
        assertTrue("Active ongoing order must be preserved", remainingOrders.any { it.id == 911L && it.status == OrderStatus.ACCEPTED })
        assertTrue("Delivered history order must be preserved", remainingOrders.any { it.id == 912L && it.status == OrderStatus.DELIVERED })

        // 3) Online captain BUT outside shift schedule (all 7 days marked isDayOff = true) -> blocked!
        db.captainDao().updateOnlineStatus(true)
        db.orderDao().insertOrder(pendingOrder)
        val allDaysOffShifts = (1..7).map { day ->
            com.example.data.ShiftEntity(
                id = "cap_elig_day_$day",
                captainUid = "cap_elig",
                dayOfWeek = day,
                dayNameArabic = "يوم",
                startTime = "00:00",
                endTime = "00:00",
                isDayOff = true
            )
        }
        db.shiftDao().insertShifts(allDaysOffShifts)

        val outOfShiftAccept = repo.acceptOrder(910L)
        assertTrue("Outside-shift captain must NOT accept orders", outOfShiftAccept.isFailure)
        assertEquals(0, callableCalls)

        val outOfShiftPendingQuery = repo.queryPendingOrdersFromFirestore()
        assertTrue(outOfShiftPendingQuery.isSuccess)
        assertTrue("Outside-shift captain must receive 0 pending orders", outOfShiftPendingQuery.getOrDefault(emptyList()).isEmpty())
        assertFalse("PENDING order must be cleared when outside shift", repo.allOrdersFlow.first().any { it.id == 910L })
    }

    private suspend fun signUpOrSignIn(auth: FirebaseAuth, email: String, pass: String): String {
        return try {
            auth.createUserWithEmailAndPassword(email, pass).await().user!!.uid
        } catch (_: Exception) {
            auth.signInWithEmailAndPassword(email, pass).await().user!!.uid
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket(host, port).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun strVal(v: String) = JSONObject().put("stringValue", v)
    private fun boolVal(v: Boolean) = JSONObject().put("booleanValue", v)
    private fun doubleVal(v: Double) = JSONObject().put("doubleValue", v)

    private fun seedFirestoreDocAsAdmin(docPath: String, fields: Map<String, JSONObject>) {
        val fieldsJson = JSONObject()
        fields.forEach { (k, v) -> fieldsJson.put(k, v) }
        val bodyJson = JSONObject().put("fields", fieldsJson)
        val url = "http://127.0.0.1:8085/v1/projects/el-batal-express-956ae/databases/(default)/documents/$docPath"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer owner")
            .patch(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        OkHttpClient().newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Failed to seed $docPath in Firestore Emulator: HTTP ${resp.code}" }
        }
    }
}
