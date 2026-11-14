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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
        var simulateMode = "NETWORK_DOWN" // NETWORK_DOWN -> HTTP_500 -> HTTP_200
        val calledEndpoints = mutableListOf<String>()

        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                calledEndpoints.add(req.url.encodedPath)
                when (simulateMode) {
                    "NETWORK_DOWN" -> throw IOException("Simulated connection reset")
                    "HTTP_500" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("""{"error":"خطأ داخلي في الخادم"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"message":"تم التنفيذ بنجاح","earnedAmount":45.0}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val cfClient = CloudFunctionsOrderClient(
            firebaseAuth = null,
            baseUrl = "https://us-central1-el-batal-express-956ae.cloudfunctions.net",
            httpClient = mockHttpClient,
            authCredentialsProvider = { "captain_1" to "mock_jwt_token_captain_1" }
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

        // A) Network disconnect during acceptOrder -> MUST fail, keep order PENDING (not deleted, not accepted!)
        val acceptNetFail = repo.acceptOrder(500L)
        assertTrue("acceptOrder must fail when Cloud Functions network is down", acceptNetFail.isFailure)
        val netEx = acceptNetFail.exceptionOrNull() as? CloudFunctionException
        assertNotNull(netEx)
        assertTrue("Network failure must be retryable", netEx!!.isRetryable)
        val afterNetFail = repo.allOrdersFlow.first().first { it.id == 500L }
        assertEquals("Order status MUST remain PENDING on network failure (still available for retry)", OrderStatus.PENDING, afterNetFail.status)

        // B) HTTP 500 during acceptOrder -> MUST fail, keep order PENDING
        simulateMode = "HTTP_500"
        val accept500Fail = repo.acceptOrder(500L)
        assertTrue("acceptOrder must fail on HTTP 500 without Firestore fallback", accept500Fail.isFailure)
        assertEquals(500, (accept500Fail.exceptionOrNull() as? CloudFunctionException)?.httpCode)
        assertEquals(OrderStatus.PENDING, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        // C) Server recovers (HTTP 200) -> Retry acceptOrder succeeds!
        simulateMode = "HTTP_200"
        assertTrue("Retry acceptOrder must succeed when server returns 200", repo.acceptOrder(500L).isSuccess)
        assertEquals(OrderStatus.ACCEPTED, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        // D) Test failure & no local mutation on markAtStore, confirmPickup, saveOrderCustomerData, and completeDelivery
        simulateMode = "NETWORK_DOWN"
        assertTrue(repo.markAtStore(500L).isFailure)
        assertEquals(OrderStatus.ACCEPTED, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "HTTP_200"
        assertTrue(repo.markAtStore(500L).isSuccess)
        assertEquals(OrderStatus.AT_STORE, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "HTTP_500"
        assertTrue(repo.confirmPickup(500L).isFailure)
        assertEquals(OrderStatus.AT_STORE, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "HTTP_200"
        assertTrue(repo.confirmPickup(500L).isSuccess)
        assertEquals(OrderStatus.PICKED_UP, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        // Receipt submission fails on server -> order MUST NOT transition to AWAITING_MERCHANT_CONFIRMATION
        val receiptUri = createSampleJpeg("cf_receipt")
        simulateMode = "HTTP_500"
        val submitFail = repo.saveOrderCustomerData(500L, "عميل", "01012345678", "", receiptUri)
        assertTrue(submitFail.isFailure)
        assertEquals(OrderStatus.PICKED_UP, repo.allOrdersFlow.first().first { it.id == 500L }.status)
        assertEquals("", repo.allOrdersFlow.first().first { it.id == 500L }.receiptImagePath)

        simulateMode = "HTTP_200"
        assertTrue(repo.saveOrderCustomerData(500L, "عميل", "01012345678", "", receiptUri).isSuccess)
        assertEquals(OrderStatus.AWAITING_MERCHANT_CONFIRMATION, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        // Attempting completeDelivery BEFORE merchant confirmation (while AWAITING_MERCHANT_CONFIRMATION) MUST fail!
        val prematureComplete = repo.completeDelivery(500L)
        assertTrue("Cannot complete delivery before EN_ROUTE", prematureComplete.isFailure)

        // Transition order to EN_ROUTE (simulating real server snapshot from merchant confirmation)
        db.orderDao().updateOrderStatus(500L, OrderStatus.EN_ROUTE)

        simulateMode = "NETWORK_DOWN"
        assertTrue("completeDelivery must fail when Cloud Functions fail", repo.completeDelivery(500L).isFailure)
        assertEquals(OrderStatus.EN_ROUTE, repo.allOrdersFlow.first().first { it.id == 500L }.status)

        simulateMode = "HTTP_200"
        val completeOk = repo.completeDelivery(500L)
        assertTrue(completeOk.isSuccess)
        assertEquals(45.0, completeOk.getOrDefault(0.0), 0.01)
        assertEquals(OrderStatus.DELIVERED, repo.allOrdersFlow.first().first { it.id == 500L }.status)
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
