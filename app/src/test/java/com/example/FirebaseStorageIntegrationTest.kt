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
import com.example.data.storage.AuthUidProvider
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.ImageUploadState
import com.example.data.storage.NetworkConnectivityChecker
import com.example.data.storage.StorageGateway
import com.example.data.storage.StorageUploadException
import com.example.data.storage.UploadErrorKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Comprehensive Robolectric test suite verifying all 12 mandatory Firebase Storage integration
 * scenarios for the El-Batal Express Captain App, plus storage.rules security validation
 * and legacy ImgBB backward compatibility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseStorageIntegrationTest {

    private lateinit var context: Context
    private lateinit var tempRootDir: File
    private lateinit var dbFile: File
    private var database: AppDatabase? = null

    private var activeAuthUid: String? = "capt_uid_001"
    private var networkConnected: Boolean = true

    private lateinit var fakeStorage: RuleEnforcingInMemoryStorageGateway
    private lateinit var fakeCloud: FakeCaptainCloudBackend

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempRootDir = File(context.cacheDir, "test_storage_${System.nanoTime()}").apply { mkdirs() }
        dbFile = File(tempRootDir, "captain_test.db")
        // Clear any previous UID cache in context.cacheDir
        File(context.cacheDir, "fb_storage_cache").deleteRecursively()

        activeAuthUid = "capt_uid_001"
        networkConnected = true
        fakeCloud = FakeCaptainCloudBackend { activeAuthUid }
        fakeStorage = RuleEnforcingInMemoryStorageGateway(fakeCloud)
        database = openPersistentRoomDb()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        tempRootDir.deleteRecursively()
        File(context.cacheDir, "fb_storage_cache").deleteRecursively()
    }

    private fun openPersistentRoomDb(): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .build()
    }

    private fun buildStorageRepo(maxRetries: Int = 3): FirebaseStorageRepository {
        return FirebaseStorageRepository(
            context = context,
            gateway = fakeStorage,
            authUidProvider = AuthUidProvider { activeAuthUid },
            connectivityChecker = NetworkConnectivityChecker { networkConnected },
            maxAutoRetries = maxRetries,
            retryDelayMs = 10L
        )
    }

    private fun buildCaptainRepo(
        db: AppDatabase = checkNotNull(database),
        storageRepo: FirebaseStorageRepository = buildStorageRepo()
    ): CaptainRepository {
        return CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = context,
            storageRepository = storageRepo,
            cloudMetadataPortOverride = fakeCloud
        )
    }

    private fun createJpegImageUri(name: String, fillColor: Int, width: Int = 320, height: Int = 240): Uri {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(fillColor)
        val outFile = File(tempRootDir, "$name.jpg")
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        }
        bmp.recycle()
        return Uri.fromFile(outFile)
    }

    private suspend fun seedLoggedInCaptain(
        repo: CaptainRepository,
        uid: String = "capt_uid_001",
        legacyAvatarUrl: String = ""
    ) {
        repo.captainDao.insertOrUpdate(
            CaptainEntity(
                id = 1,
                uid = uid,
                fullName = "أحمد البطل",
                phoneNumber = "01012345678",
                email = "ahmed@elbatal.com",
                avatarUri = legacyAvatarUrl,
                isOnline = true,
                isLoggedIn = true
            )
        )
    }

    // -------------------------------------------------------------------------
    // 1. رفع صورة شخصية للكابتن -> المسار الدقيق captains/{captainId}/profile/profile.jpg
    // -------------------------------------------------------------------------
    @Test
    fun test01_uploadCaptainProfilePhoto_savesExactPathAndMetadata() = runBlocking {
        val repo = buildCaptainRepo()
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        val states = mutableListOf<ImageUploadState>()
        val photoUri = createJpegImageUri("profile_sample", Color.BLUE)

        val result = repo.uploadAndSaveProfilePhoto(photoUri) { states.add(it) }

        assertTrue("Profile upload must succeed", result.isSuccess)
        assertEquals("captains/capt_uid_001/profile/profile.jpg", result.getOrNull())
        assertTrue("Must emit Uploading progress", states.any { it is ImageUploadState.Uploading })
        assertTrue("Final state must be Success", states.last() is ImageUploadState.Success)

        val savedCaptain = repo.captainDao.getCaptain()
        assertNotNull(savedCaptain)
        assertEquals("captains/capt_uid_001/profile/profile.jpg", savedCaptain?.profileImagePath)
        assertEquals("captains/capt_uid_001/profile/profile.jpg", savedCaptain?.effectiveProfileImage)
        assertEquals(
            "captains/capt_uid_001/profile/profile.jpg",
            fakeCloud.captainDocs["capt_uid_001"]?.get("profileImagePath")
        )
    }

    // -------------------------------------------------------------------------
    // 2. رفع بطاقة أمامية -> المسار captains/{captainId}/documents/id_front.jpg وعدم اكتمال البطاقة وحدها
    // -------------------------------------------------------------------------
    @Test
    fun test02_uploadIdFront_savesExactPathAndDoesNotMarkCompleteAlone() = runBlocking {
        val repo = buildCaptainRepo()
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        val frontUri = createJpegImageUri("id_front", Color.RED)
        val result = repo.uploadAndSaveIdFront(frontUri)

        assertTrue(result.isSuccess)
        assertEquals("captains/capt_uid_001/documents/id_front.jpg", result.getOrNull())

        val captain = repo.captainDao.getCaptain()!!
        assertEquals("captains/capt_uid_001/documents/id_front.jpg", captain.idFrontImagePath)
        assertEquals("", captain.idBackImagePath)
        assertFalse("National ID must NOT be marked complete with only front uploaded", captain.isNationalIdComplete)
        assertEquals(false, fakeCloud.captainDocs["capt_uid_001"]?.get("idDocumentsComplete"))
    }

    // -------------------------------------------------------------------------
    // 3. رفع بطاقة خلفية -> المسار captains/{captainId}/documents/id_back.jpg واكتمال الوجهين معاً
    // -------------------------------------------------------------------------
    @Test
    fun test03_uploadIdBack_savesExactPathAndCompletesNationalIdWhenBothUploaded() = runBlocking {
        val repo = buildCaptainRepo()
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        val frontUri = createJpegImageUri("id_front_both", Color.RED)
        val backUri = createJpegImageUri("id_back_both", Color.GREEN)

        assertTrue(repo.uploadAndSaveIdFront(frontUri).isSuccess)
        assertFalse(repo.captainDao.getCaptain()!!.isNationalIdComplete)

        val backResult = repo.uploadAndSaveIdBack(backUri)
        assertTrue(backResult.isSuccess)
        assertEquals("captains/capt_uid_001/documents/id_back.jpg", backResult.getOrNull())

        val captain = repo.captainDao.getCaptain()!!
        assertEquals("captains/capt_uid_001/documents/id_front.jpg", captain.idFrontImagePath)
        assertEquals("captains/capt_uid_001/documents/id_back.jpg", captain.idBackImagePath)
        assertTrue("National ID MUST be marked complete once both front and back succeed", captain.isNationalIdComplete)
        assertEquals(true, fakeCloud.captainDocs["capt_uid_001"]?.get("idDocumentsComplete"))
    }

    // -------------------------------------------------------------------------
    // 4. إغلاق التطبيق وإعادة فتحه والتأكد من بقاء الصور وظهورها
    // -------------------------------------------------------------------------
    @Test
    fun test04_closeAndReopenApp_persistsAndDisplaysUploadedImages() = runBlocking {
        val repoSession1 = buildCaptainRepo()
        seedLoggedInCaptain(repoSession1, uid = "capt_uid_001")

        repoSession1.uploadAndSaveProfilePhoto(createJpegImageUri("persist_profile", Color.CYAN))
        repoSession1.uploadAndSaveIdFront(createJpegImageUri("persist_front", Color.MAGENTA))
        repoSession1.uploadAndSaveIdBack(createJpegImageUri("persist_back", Color.YELLOW))

        // Simulate closing the app completely (closing Room DB & clearing local memory)
        database?.close()
        database = null

        // Reopen app with new DB instance & fresh Repository instance
        database = openPersistentRoomDb()
        val reopenedStorageRepo = buildStorageRepo()
        val repoSession2 = buildCaptainRepo(checkNotNull(database), reopenedStorageRepo)

        val restoredCaptain = repoSession2.captainDao.getCaptain()
        assertNotNull(restoredCaptain)
        assertEquals("captains/capt_uid_001/profile/profile.jpg", restoredCaptain?.profileImagePath)
        assertEquals("captains/capt_uid_001/documents/id_front.jpg", restoredCaptain?.idFrontImagePath)
        assertEquals("captains/capt_uid_001/documents/id_back.jpg", restoredCaptain?.idBackImagePath)
        assertTrue(restoredCaptain!!.isNationalIdComplete)

        // Verify resolved display model fetches valid image bytes for display in Profile & Dashboard
        val displayModelResult = reopenedStorageRepo.resolveDisplayModel(restoredCaptain.effectiveProfileImage)
        assertTrue(displayModelResult.isSuccess)
        val modelBytes = displayModelResult.getOrNull() as? ByteArray
        assertNotNull("Firebase Storage path must resolve to authenticated ByteArray", modelBytes)
        assertTrue(modelBytes!!.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // 5. استبدال الصورة الشخصية والتأكد من تحديثها فوراً
    // -------------------------------------------------------------------------
    @Test
    fun test05_replaceProfilePhoto_updatesBytesCacheRevisionAndPath() = runBlocking {
        val storageRepo = buildStorageRepo()
        val repo = buildCaptainRepo(storageRepo = storageRepo)
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        val uriV1 = createJpegImageUri("profile_v1", Color.BLUE, width = 200, height = 200)
        assertTrue(repo.uploadAndSaveProfilePhoto(uriV1).isSuccess)
        val bytesV1 = storageRepo.downloadStorageImageBytes("captains/capt_uid_001/profile/profile.jpg").getOrThrow()
        val revV1 = storageRepo.getPathRevision("captains/capt_uid_001/profile/profile.jpg")

        Thread.sleep(5)
        val uriV2 = createJpegImageUri("profile_v2", Color.rgb(240, 120, 15), width = 360, height = 280)
        val replaceRes = repo.uploadAndSaveProfilePhoto(uriV2)
        assertTrue(replaceRes.isSuccess)
        assertEquals("captains/capt_uid_001/profile/profile.jpg", replaceRes.getOrNull())

        val bytesV2 = storageRepo.downloadStorageImageBytes("captains/capt_uid_001/profile/profile.jpg").getOrThrow()
        val revV2 = storageRepo.getPathRevision("captains/capt_uid_001/profile/profile.jpg")

        assertNotEquals("Revision must update when replacing profile photo", revV1, revV2)
        assertFalse("Cached & stored bytes must reflect the newly replaced image", bytesV1.contentEquals(bytesV2))
        assertArrayEquals(fakeStorage.rawBytesAt("captains/capt_uid_001/profile/profile.jpg"), bytesV2)
    }

    // -------------------------------------------------------------------------
    // 6. رفع إيصال طلب حقيقي -> المسار orders/{orderId}/receipts/{uniqueFileName} وحفظ بيانات العميل
    // -------------------------------------------------------------------------
    @Test
    fun test06_uploadRealOrderReceipt_uploadsToOrderReceiptsFolderAndTransitionsToAwaitingMerchant() = runBlocking {
        val repo = buildCaptainRepo()
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        fakeCloud.registerOrder("ORD_9001", captainId = "capt_uid_001", merchantId = "merch_55", status = "picked_up")
        repo.orderDao.insertOrder(
            OrderEntity(
                id = 101L,
                orderNumber = "EXP-9001",
                firestoreDocId = "ORD_9001",
                customerName = "",
                customerPhone = "",
                storeName = "مطعم البطل",
                pickupAddress = "مدينة نصر",
                deliveryAddress = "التجمع الخامس",
                itemsSummary = "2 وجبة مشويات",
                itemsCount = 2,
                orderTotalAmount = 350.0,
                deliveryFee = 40.0,
                distanceKm = 5.4,
                status = OrderStatus.PICKED_UP,
                captainId = "capt_uid_001"
            )
        )

        val receiptUri = createJpegImageUri("order_receipt_9001", Color.WHITE)
        val states = mutableListOf<ImageUploadState>()

        val saveRes = repo.saveOrderCustomerData(
            orderId = 101L,
            customerName = "محمود حسن",
            customerPhone = "01122334455",
            notes = "الدور الثالث شقة 6",
            imageUri = receiptUri,
            onUploadStateChange = { states.add(it) }
        )

        assertTrue("Order receipt upload & submission must succeed", saveRes.isSuccess)
        val uploadedPath = saveRes.getOrThrow()
        assertTrue(
            "Receipt path must match orders/{orderId}/receipts/{uniqueFileName}",
            uploadedPath.startsWith("orders/ORD_9001/receipts/") && uploadedPath.endsWith(".jpg")
        )
        assertTrue(states.last() is ImageUploadState.Success)

        val updatedOrder = repo.allOrdersFlow.first().first { it.id == 101L }
        assertEquals(OrderStatus.AWAITING_MERCHANT_CONFIRMATION, updatedOrder.status)
        assertEquals("محمود حسن", updatedOrder.customerName)
        assertEquals("01122334455", updatedOrder.customerPhone)
        assertEquals("الدور الثالث شقة 6", updatedOrder.notes)
        assertEquals(uploadedPath, updatedOrder.receiptImagePath)
        assertEquals("capt_uid_001", updatedOrder.receiptUploadedBy)
        assertTrue(updatedOrder.receiptUploadedAt > 0L)

        // Verify Firestore / Cloud Function order metadata fields
        val cloudOrder = fakeCloud.orderDocs["ORD_9001"]!!
        assertEquals(uploadedPath, cloudOrder["receiptImagePath"])
        assertEquals("capt_uid_001", cloudOrder["receiptUploadedBy"])
        assertNotNull(cloudOrder["receiptUploadedAt"])
    }

    // -------------------------------------------------------------------------
    // 7. ربط الإيصال بالطلب الصحيح دون التأثير على الطلبات الأخرى
    // -------------------------------------------------------------------------
    @Test
    fun test07_linkReceiptToCorrectOrder_keepsOtherOrdersUntouched() = runBlocking {
        val repo = buildCaptainRepo()
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        fakeCloud.registerOrder("ORD_A", captainId = "capt_uid_001", merchantId = "merch_1", status = "picked_up")
        fakeCloud.registerOrder("ORD_B", captainId = "capt_uid_001", merchantId = "merch_2", status = "picked_up")

        repo.orderDao.insertOrder(
            OrderEntity(
                id = 1L,
                orderNumber = "EXP-A",
                firestoreDocId = "ORD_A",
                customerName = "",
                customerPhone = "",
                storeName = "متجر أ",
                pickupAddress = "عنوان أ",
                deliveryAddress = "توصيل أ",
                itemsSummary = "طلب أ",
                itemsCount = 1,
                orderTotalAmount = 120.0,
                deliveryFee = 25.0,
                distanceKm = 2.1,
                status = OrderStatus.PICKED_UP,
                captainId = "capt_uid_001"
            )
        )
        repo.orderDao.insertOrder(
            OrderEntity(
                id = 2L,
                orderNumber = "EXP-B",
                firestoreDocId = "ORD_B",
                customerName = "",
                customerPhone = "",
                storeName = "متجر ب",
                pickupAddress = "عنوان ب",
                deliveryAddress = "توصيل ب",
                itemsSummary = "طلب ب",
                itemsCount = 1,
                orderTotalAmount = 210.0,
                deliveryFee = 30.0,
                distanceKm = 3.5,
                status = OrderStatus.PICKED_UP,
                captainId = "capt_uid_001"
            )
        )

        val receiptForA = createJpegImageUri("receipt_A", Color.LTGRAY)
        val resA = repo.saveOrderCustomerData(1L, "عميل أ", "01099887766", "", receiptForA)
        assertTrue(resA.isSuccess)

        val allOrders = repo.allOrdersFlow.first()
        val orderA = allOrders.first { it.id == 1L }
        val orderB = allOrders.first { it.id == 2L }

        assertTrue(orderA.receiptImagePath.startsWith("orders/ORD_A/receipts/"))
        assertEquals("capt_uid_001", orderA.receiptUploadedBy)
        assertEquals("", orderB.receiptImagePath)
        assertEquals("", orderB.receiptUploadedBy)
        assertEquals(OrderStatus.PICKED_UP, orderB.status)
    }

    // -------------------------------------------------------------------------
    // 8. رفض رفع إيصال لطلب لا يخص الكابتن أو طلب مغلق
    // -------------------------------------------------------------------------
    @Test
    fun test08_rejectReceiptUploadForOrderNotAssignedToCaptainOrClosedOrder() = runBlocking {
        val repo = buildCaptainRepo()
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        // Case A: Order belongs to another captain ("capt_uid_999")
        fakeCloud.registerOrder("ORD_FOREIGN", captainId = "capt_uid_999", merchantId = "merch_1", status = "picked_up")
        repo.orderDao.insertOrder(
            OrderEntity(
                id = 50L,
                orderNumber = "EXP-FOREIGN",
                firestoreDocId = "ORD_FOREIGN",
                customerName = "",
                customerPhone = "",
                storeName = "متجر خارجي",
                pickupAddress = "القاهرة",
                deliveryAddress = "الجيزة",
                itemsSummary = "طلب",
                itemsCount = 1,
                orderTotalAmount = 100.0,
                deliveryFee = 20.0,
                distanceKm = 4.0,
                status = OrderStatus.PICKED_UP,
                captainId = "capt_uid_999"
            )
        )

        val receiptUri = createJpegImageUri("foreign_receipt", Color.YELLOW)
        val foreignRes = repo.saveOrderCustomerData(50L, "عميل", "01011112222", "", receiptUri)

        assertTrue("Uploading receipt to another captain's order MUST fail", foreignRes.isFailure)
        val foreignErr = foreignRes.exceptionOrNull() as? StorageUploadException
        assertEquals(UploadErrorKind.OWNERSHIP_VIOLATION, foreignErr?.kind)
        assertTrue(fakeStorage.listPathsWithPrefix("orders/ORD_FOREIGN/").isEmpty())

        // Case B: Order is already DELIVERED (closed)
        fakeCloud.registerOrder("ORD_CLOSED", captainId = "capt_uid_001", merchantId = "merch_1", status = "delivered")
        repo.orderDao.insertOrder(
            OrderEntity(
                id = 51L,
                orderNumber = "EXP-CLOSED",
                firestoreDocId = "ORD_CLOSED",
                customerName = "عميل سابق",
                customerPhone = "01011113333",
                storeName = "متجر مغلق",
                pickupAddress = "القاهرة",
                deliveryAddress = "المعادي",
                itemsSummary = "طلب مسلم",
                itemsCount = 1,
                orderTotalAmount = 150.0,
                deliveryFee = 25.0,
                distanceKm = 3.0,
                status = OrderStatus.DELIVERED,
                captainId = "capt_uid_001",
                receiptImagePath = "orders/ORD_CLOSED/receipts/original.jpg"
            )
        )

        val closedRes = repo.saveOrderCustomerData(51L, "عميل معدل", "01011113333", "", receiptUri)
        assertTrue("Replacing receipt on a closed/delivered order MUST fail", closedRes.isFailure)
        val closedErr = closedRes.exceptionOrNull() as? StorageUploadException
        assertEquals(UploadErrorKind.ORDER_CLOSED, closedErr?.kind)
    }

    // -------------------------------------------------------------------------
    // 9. منع كابتن من الوصول لصور بطاقة كابتن آخر (قراءة أو استبدال)
    // -------------------------------------------------------------------------
    @Test
    fun test09_preventCaptainFromViewingOrOverwritingAnotherCaptainIdCards() = runBlocking {
        // Captain B uploads their private National ID front & back
        activeAuthUid = "captain_B"
        val storageRepoB = buildStorageRepo()
        val repoB = buildCaptainRepo(storageRepo = storageRepoB)
        seedLoggedInCaptain(repoB, uid = "captain_B")

        val bFrontUri = createJpegImageUri("b_id_front", Color.RED)
        val bBackUri = createJpegImageUri("b_id_back", Color.GREEN)
        assertTrue(repoB.uploadAndSaveIdFront(bFrontUri).isSuccess)
        assertTrue(repoB.uploadAndSaveIdBack(bBackUri).isSuccess)

        // Switch session to Captain A ("captain_A")
        activeAuthUid = "captain_A"
        val storageRepoA = buildStorageRepo()

        // 1) Captain A tries to view/download Captain B's National ID front
        val readFrontAttempt = storageRepoA.downloadStorageImageBytes("captains/captain_B/documents/id_front.jpg")
        assertTrue("Captain A MUST NOT be able to view Captain B's front ID", readFrontAttempt.isFailure)
        assertEquals(
            UploadErrorKind.OWNERSHIP_VIOLATION,
            (readFrontAttempt.exceptionOrNull() as? StorageUploadException)?.kind
        )

        // 2) Captain A tries to view/download Captain B's National ID back
        val readBackAttempt = storageRepoA.downloadStorageImageBytes("captains/captain_B/documents/id_back.jpg")
        assertTrue("Captain A MUST NOT be able to view Captain B's back ID", readBackAttempt.isFailure)

        // 3) Captain A tries to overwrite Captain B's National ID document
        val maliciousUri = createJpegImageUri("attacker_doc", Color.BLACK)
        val overwriteAttempt = storageRepoA.uploadCaptainIdFrontImage("captain_B", maliciousUri)
        assertTrue("Captain A MUST NOT be able to overwrite Captain B's ID", overwriteAttempt.isFailure)
        assertEquals(
            UploadErrorKind.OWNERSHIP_VIOLATION,
            (overwriteAttempt.exceptionOrNull() as? StorageUploadException)?.kind
        )
    }

    // -------------------------------------------------------------------------
    // 10. التعامل مع انقطاع الإنترنت أثناء الرفع وعدم اعتبار الصورة محفوظة
    // -------------------------------------------------------------------------
    @Test
    fun test10_internetDisconnectionDuringUpload_reportsFailedStateAndDoesNotMarkSaved() = runBlocking {
        networkConnected = false
        val storageRepo = buildStorageRepo(maxRetries = 2)
        val repo = buildCaptainRepo(storageRepo = storageRepo)
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        val emittedStates = mutableListOf<ImageUploadState>()
        val uri = createJpegImageUri("offline_profile", Color.GRAY)

        val result = repo.uploadAndSaveProfilePhoto(uri) { emittedStates.add(it) }

        assertTrue("Upload while offline must fail cleanly", result.isFailure)
        assertTrue("Must attempt retry before failing", emittedStates.any { it is ImageUploadState.Retrying })
        val finalState = emittedStates.last()
        assertTrue("Final state must be Failed", finalState is ImageUploadState.Failed)
        val failed = finalState as ImageUploadState.Failed
        assertEquals(UploadErrorKind.NETWORK_DISCONNECTED, failed.kind)
        assertTrue("Network failure must be marked retryable", failed.isRetryable)

        // Image MUST NOT be marked saved in Room or Firestore
        val captain = repo.captainDao.getCaptain()!!
        assertEquals("", captain.profileImagePath)
        assertEquals(null, fakeCloud.captainDocs["capt_uid_001"]?.get("profileImagePath"))
    }

    // -------------------------------------------------------------------------
    // 11. إعادة المحاولة بعد فشل الرفع ونجاح الحفظ عند عودة الإنترنت
    // -------------------------------------------------------------------------
    @Test
    fun test11_retryUploadAfterFailure_succeedsWhenNetworkRestored() = runBlocking {
        networkConnected = false
        val storageRepo = buildStorageRepo(maxRetries = 2)
        val repo = buildCaptainRepo(storageRepo = storageRepo)
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        val uri = createJpegImageUri("retry_id_front", Color.DKGRAY)

        // First attempt fails due to disconnected network
        val firstAttempt = repo.uploadAndSaveIdFront(uri)
        assertTrue(firstAttempt.isFailure)
        assertEquals("", repo.captainDao.getCaptain()?.idFrontImagePath.orEmpty())

        // Restore internet connection and trigger manual retry
        networkConnected = true
        val retryStates = mutableListOf<ImageUploadState>()
        val retryAttempt = repo.uploadAndSaveIdFront(uri) { retryStates.add(it) }

        assertTrue("Retry after network restoration must succeed", retryAttempt.isSuccess)
        assertEquals("captains/capt_uid_001/documents/id_front.jpg", retryAttempt.getOrNull())
        assertTrue(retryStates.last() is ImageUploadState.Success)
        assertEquals(
            "captains/capt_uid_001/documents/id_front.jpg",
            repo.captainDao.getCaptain()?.idFrontImagePath
        )
    }

    // -------------------------------------------------------------------------
    // 12. تسجيل الخروج ثم تسجيل الدخول والتأكد من استمرار ظهور جميع الصور وعدم المساس بروابط ImgBB القديمة
    // -------------------------------------------------------------------------
    @Test
    fun test12_logoutAndRelogin_clearsLocalSessionThenRestoresAllImagesOnRelogin() = runBlocking {
        val repo = buildCaptainRepo()
        // Seed captain who also had a legacy ImgBB license URL in Firestore (must NOT be deleted)
        val legacyImgbbLicenseUrl = "https://i.ibb.co/legacy123/old_license.jpg"
        fakeCloud.captainDocs["capt_uid_001"] = mutableMapOf(
            "licenseImageUrl" to legacyImgbbLicenseUrl
        )
        seedLoggedInCaptain(repo, uid = "capt_uid_001")

        assertTrue(repo.uploadAndSaveProfilePhoto(createJpegImageUri("relogin_prof", Color.BLUE)).isSuccess)
        assertTrue(repo.uploadAndSaveIdFront(createJpegImageUri("relogin_front", Color.RED)).isSuccess)
        assertTrue(repo.uploadAndSaveIdBack(createJpegImageUri("relogin_back", Color.GREEN)).isSuccess)

        // Perform Logout
        repo.logout()
        assertEquals("Local Room captain profile must clear on logout", null, repo.captainDao.getCaptain())

        // Perform Re-login (rehydrating from Firestore)
        activeAuthUid = "capt_uid_001"
        val reloginRes = repo.rehydrateCaptainImagesFromCloud(
            uid = "capt_uid_001",
            fullNameFallback = "أحمد البطل",
            phoneFallback = "01012345678",
            emailFallback = "ahmed@elbatal.com"
        )
        assertTrue(reloginRes.isSuccess)

        val restored = repo.captainDao.getCaptain()!!
        assertEquals("captains/capt_uid_001/profile/profile.jpg", restored.profileImagePath)
        assertEquals("captains/capt_uid_001/documents/id_front.jpg", restored.idFrontImagePath)
        assertEquals("captains/capt_uid_001/documents/id_back.jpg", restored.idBackImagePath)
        assertTrue("National ID completion must remain true after re-login", restored.isNationalIdComplete)
        assertEquals(
            "Legacy ImgBB URL in Firestore must remain untouched",
            legacyImgbbLicenseUrl,
            restored.effectiveLicenseImage
        )
    }

    // -------------------------------------------------------------------------
    // 13. التحقق من قواعد الحماية في storage.rules وعدم فتح صلاحيات عامة
    // -------------------------------------------------------------------------
    @Test
    fun test13_storageRulesFile_enforcesZeroTrustPrivacyAndBlocksWildcardPublicReads() {
        val candidateFiles = listOf(
            File("storage.rules"),
            File("../storage.rules"),
            File("/app/applet/storage.rules")
        )
        val rulesFile = candidateFiles.firstOrNull { it.exists() }
        assertNotNull("storage.rules file must exist at project root", rulesFile)

        val content = rulesFile!!.readText()
        assertFalse("storage.rules MUST NOT allow public wildcard reads", content.contains("allow read: if true"))
        assertFalse("storage.rules MUST NOT allow open authenticated wildcard writes", content.contains("allow write: if request.auth != null;"))
        assertTrue(content.contains("match /captains/{captainId}/profile/profile.jpg"))
        assertTrue(content.contains("match /captains/{captainId}/documents/{docFile}"))
        assertTrue(content.contains("match /orders/{orderId}/receipts/{uniqueFileName}"))
        assertTrue(content.contains("isExplicitAllowedReceiptUploadStatus"))
        assertTrue(content.contains("allow read, write: if false;"))
    }

    // =========================================================================
    // Test Doubles: Rule-Enforcing Storage Gateway & Firestore/CF Metadata Port
    // =========================================================================

    private class FakeCaptainCloudBackend(
        private val currentUidSupplier: () -> String?
    ) : CaptainCloudMetadataPort {
        val captainDocs = mutableMapOf<String, MutableMap<String, Any>>()
        val orderDocs = mutableMapOf<String, MutableMap<String, Any>>()

        fun registerOrder(orderId: String, captainId: String, merchantId: String, status: String) {
            orderDocs[orderId] = mutableMapOf(
                "captainId" to captainId,
                "merchantId" to merchantId,
                "status" to status
            )
        }

        override fun currentAuthUid(): String? = currentUidSupplier()

        override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {
            val caller = currentUidSupplier()
            if (caller.isNullOrBlank() || caller != uid) {
                throw SecurityException("Firestore permission denied: caller=$caller != captain=$uid")
            }
            val doc = captainDocs.getOrPut(uid) { mutableMapOf() }
            doc.putAll(fields)
        }

        override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? {
            val doc = captainDocs[uid] ?: return null
            return doc.mapValues { (_, v) -> (v as? String).orEmpty() }
        }

        override suspend fun submitOrderReceiptDetails(
            orderDocId: String,
            customerName: String,
            customerPhone: String,
            receiptImagePath: String,
            notes: String
        ): Result<String> {
            val caller = currentUidSupplier()
                ?: return Result.failure(SecurityException("Unauthenticated"))
            val order = orderDocs[orderDocId]
                ?: return Result.failure(IllegalArgumentException("Order $orderDocId not found"))
            val assignedCaptain = order["captainId"] as? String ?: ""
            if (assignedCaptain != caller) {
                return Result.failure(SecurityException("Order $orderDocId is not assigned to captain $caller"))
            }
            val status = (order["status"] as? String).orEmpty()
            if (status in setOf("delivered", "completed", "cancelled", "expired", "closed")) {
                return Result.failure(IllegalStateException("Order $orderDocId is already closed"))
            }
            order["customerName"] = customerName
            order["customerPhone"] = customerPhone
            order["notes"] = notes
            order["receiptImagePath"] = receiptImagePath
            order["receiptUploadedBy"] = caller
            order["receiptUploadedAt"] = System.currentTimeMillis()
            order["status"] = "awaiting_merchant_confirmation"
            return Result.success("تم إرسال بيانات العميل والإيصال بنجاح")
        }
    }

    private class RuleEnforcingInMemoryStorageGateway(
        private val cloudBackend: FakeCaptainCloudBackend
    ) : StorageGateway {
        private val objects = mutableMapOf<String, ByteArray>()

        fun rawBytesAt(path: String): ByteArray? = objects[path]

        fun listPathsWithPrefix(prefix: String): List<String> =
            objects.keys.filter { it.startsWith(prefix) }

        override suspend fun putBytesWithProgress(
            storagePath: String,
            bytes: ByteArray,
            contentType: String,
            customMetadata: Map<String, String>,
            requesterUid: String,
            onProgress: (Int) -> Unit
        ) {
            enforceWriteRule(storagePath, bytes, contentType, customMetadata, requesterUid)
            onProgress(25)
            onProgress(70)
            objects[storagePath] = bytes.copyOf()
            onProgress(100)
        }

        override suspend fun getBytes(
            storagePath: String,
            maxDownloadBytes: Long,
            requesterUid: String
        ): ByteArray {
            enforceReadRule(storagePath, requesterUid)
            return objects[storagePath]?.copyOf()
                ?: throw SecurityException("Object not found at $storagePath")
        }

        private fun enforceWriteRule(
            path: String,
            bytes: ByteArray,
            contentType: String,
            metadata: Map<String, String>,
            uid: String
        ) {
            if (uid.isBlank()) throw SecurityException("Unauthenticated Storage write")
            if (bytes.isEmpty() || bytes.size > 10 * 1024 * 1024) {
                throw SecurityException("Invalid file size for $path")
            }
            if (!contentType.startsWith("image/")) {
                throw SecurityException("Invalid contentType $contentType")
            }

            val parts = path.split("/")
            when {
                parts.size == 4 && parts[0] == "captains" && parts[2] == "profile" && parts[3] == "profile.jpg" -> {
                    if (parts[1] != uid) throw SecurityException("Cannot write another captain's profile")
                }
                parts.size == 4 && parts[0] == "captains" && parts[2] == "documents" -> {
                    val docName = parts[3]
                    if (parts[1] != uid || docName !in setOf("id_front.jpg", "id_back.jpg", "license.jpg")) {
                        throw SecurityException("Cannot write private document $path")
                    }
                }
                parts.size == 4 && parts[0] == "orders" && parts[2] == "receipts" -> {
                    val orderId = parts[1]
                    val order = cloudBackend.orderDocs[orderId]
                        ?: throw SecurityException("Order $orderId does not exist")
                    if (order["captainId"] != uid) {
                        throw SecurityException("Order $orderId is not assigned to $uid")
                    }
                    val status = (order["status"] as? String).orEmpty()
                    if (status !in setOf("picked_up", "merchant_rejected", "awaiting_merchant_confirmation")) {
                        throw SecurityException("Order $orderId status '$status' is not in explicit receipt upload allowlist")
                    }
                    if (metadata["orderId"] != orderId || metadata["captainId"] != uid) {
                        throw SecurityException("Missing or mismatched receipt metadata")
                    }
                }
                else -> throw SecurityException("Write denied by default rule for $path")
            }
        }

        private fun enforceReadRule(path: String, uid: String) {
            if (uid.isBlank()) throw SecurityException("Unauthenticated Storage read")
            val parts = path.split("/")
            when {
                parts.size == 4 && parts[0] == "captains" && parts[2] == "profile" && parts[3] == "profile.jpg" -> {
                    // Allowed for owning captain (and merchants/admins in storage.rules)
                    if (parts[1] != uid) throw SecurityException("Profile read restricted")
                }
                parts.size == 4 && parts[0] == "captains" && parts[2] == "documents" -> {
                    if (parts[1] != uid) throw SecurityException("Private ID card read denied for $uid")
                }
                parts.size == 4 && parts[0] == "orders" && parts[2] == "receipts" -> {
                    val orderId = parts[1]
                    val order = cloudBackend.orderDocs[orderId]
                        ?: throw SecurityException("Order not found")
                    if (order["captainId"] != uid && order["merchantId"] != uid) {
                        throw SecurityException("Order receipt read denied")
                    }
                }
                else -> throw SecurityException("Read denied by default rule for $path")
            }
        }
    }
}
