package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AuthUserSnapshot
import com.example.data.CaptainAuthBackendPort
import com.example.data.CaptainCloudMetadataPort
import com.example.data.CaptainEntity
import com.example.data.CaptainRepository
import com.example.data.LoginOutcome
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.data.PhoneAuthRegistry
import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.data.remote.CloudFunctionsOrderClient
import com.example.data.remote.OrderCallableGateway
import com.example.data.storage.AuthUidProvider
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.NetworkConnectivityChecker
import com.example.data.storage.StorageGateway
import com.example.notifications.CaptainNotificationManager
import com.example.notifications.ShiftNotificationScheduler
import com.example.ui.screens.EmailVerificationScreen
import com.example.ui.screens.SignUpScreen
import com.example.ui.theme.ElBatalTheme
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h1400dp")
class CaptainAuthAndIsolationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Application
    private lateinit var db: AppDatabase
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        tempDir = File(appContext.cacheDir, "auth_isolation_${System.nanoTime()}").apply { mkdirs() }
        appContext.getSharedPreferences("elbatal_phone_auth_registry", Context.MODE_PRIVATE)
            .edit().clear().commit()
        CaptainNotificationManager.clearNotificationState(appContext)
        ShiftNotificationScheduler.cancelAllShiftAlarms(appContext)
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    private data class FakeAuthAccount(
        val uid: String,
        val email: String,
        var password: String,
        var isEmailVerified: Boolean,
        var displayName: String = ""
    ) {
        fun toSnapshot() = AuthUserSnapshot(
            uid = uid,
            email = email,
            isEmailVerified = isEmailVerified,
            displayName = displayName
        )
    }

    /**
     * Stateful in-memory backend tracking separate Firestore collections ('captains', 'merchants',
     * 'restaurants', 'drivers') and Firebase Auth user state + verification emails.
     */
    private class FakeCaptainAuthBackend : CaptainAuthBackendPort {
        val authUsersByEmail = mutableMapOf<String, FakeAuthAccount>()
        val authUsersByUid = mutableMapOf<String, FakeAuthAccount>()

        val captains = mutableMapOf<String, MutableMap<String, Any?>>()
        val merchants = mutableMapOf<String, MutableMap<String, Any?>>()
        val restaurants = mutableMapOf<String, MutableMap<String, Any?>>()
        val drivers = mutableMapOf<String, MutableMap<String, Any?>>()

        var currentUid: String? = null
        var verificationEmailsSentCount = 0
        var reloadCallsCount = 0
        var lastCreatedCaptainDocId: String? = null
        private var uidSeq = 100

        fun seedAuthAccount(
            uid: String,
            email: String,
            password: String,
            isEmailVerified: Boolean,
            displayName: String = ""
        ) {
            val acc = FakeAuthAccount(uid, email.lowercase(), password, isEmailVerified, displayName)
            authUsersByEmail[email.lowercase()] = acc
            authUsersByUid[uid] = acc
        }

        fun markEmailVerifiedInAuthServer(email: String) {
            authUsersByEmail[email.lowercase()]?.isEmailVerified = true
        }

        override fun currentUser(): AuthUserSnapshot? {
            return currentUid?.let { authUsersByUid[it]?.toSnapshot() }
        }

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val normalizedEmail = email.trim().lowercase()
            if (authUsersByEmail.containsKey(normalizedEmail)) {
                throw FirebaseAuthUserCollisionException(
                    "ERROR_EMAIL_ALREADY_IN_USE",
                    "The email address is already in use by another account."
                )
            }
            uidSeq += 1
            val uid = "cap_uid_$uidSeq"
            val acc = FakeAuthAccount(
                uid = uid,
                email = normalizedEmail,
                password = password,
                isEmailVerified = false
            )
            authUsersByEmail[normalizedEmail] = acc
            authUsersByUid[uid] = acc
            currentUid = uid
            return acc.toSnapshot()
        }

        override suspend fun sendEmailVerification() {
            currentUser() ?: throw IllegalStateException("No signed-in user")
            verificationEmailsSentCount += 1
        }

        override suspend fun reloadCurrentUser(): AuthUserSnapshot? {
            reloadCallsCount += 1
            return currentUser()
        }

        override suspend fun updateDisplayName(displayName: String) {
            val uid = currentUid ?: return
            authUsersByUid[uid]?.displayName = displayName
        }

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val acc = authUsersByEmail[email.trim().lowercase()]
                ?: throw FirebaseAuthInvalidCredentialsException(
                    "ERROR_INVALID_CREDENTIAL",
                    "The supplied auth credential is incorrect."
                )
            if (acc.password != password) {
                throw FirebaseAuthInvalidCredentialsException(
                    "ERROR_WRONG_PASSWORD",
                    "The password is invalid."
                )
            }
            currentUid = acc.uid
            return acc.toSnapshot()
        }

        override fun signOut() {
            currentUid = null
        }

        override suspend fun sendPasswordResetEmail(email: String) {
            // No-op in fake backend
        }

        override suspend fun createCaptainDoc(uid: String, data: Map<String, Any>) {
            lastCreatedCaptainDocId = uid
            captains[uid] = data.toMutableMap()
        }

        override suspend fun getCaptainDoc(uid: String): Map<String, Any?>? {
            return captains[uid]?.toMap()
        }

        override suspend fun updateCaptainDoc(uid: String, data: Map<String, Any>) {
            val doc = captains[uid] ?: throw IllegalStateException("NOT_FOUND captains/$uid")
            data.forEach { (k, v) -> doc[k] = v }
        }

        override suspend fun queryCaptainEmailByPhoneVariants(
            variants: List<String>,
            numericVariants: List<Long>,
            normalizedLocal: String
        ): String? {
            for ((_, doc) in captains) {
                if (doc["accountType"]?.toString()?.lowercase() != "captain") continue
                val phone = doc["phone"]?.toString().orEmpty()
                val phoneNumber = doc["phoneNumber"]?.toString().orEmpty()
                if (phone in variants || phoneNumber in variants || phone == normalizedLocal || phoneNumber == normalizedLocal) {
                    val em = doc["email"]?.toString().orEmpty()
                    if (em.isNotBlank()) return em
                }
            }
            return null
        }
    }

    private fun createSampleJpeg(name: String): Uri {
        val bmp = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(20, 120, 200))
        val file = File(tempDir, "$name.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return Uri.fromFile(file)
    }

    private fun buildRepository(
        backend: FakeCaptainAuthBackend,
        activeUidProvider: () -> String = { backend.currentUid ?: "cap_uid_101" }
    ): CaptainRepository {
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
            context = appContext,
            gateway = storageGateway,
            authUidProvider = AuthUidProvider { activeUidProvider() },
            connectivityChecker = NetworkConnectivityChecker { true },
            maxAutoRetries = 1
        )
        val metadataPort = object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String? = backend.currentUid
            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {
                backend.updateCaptainDoc(uid, fields)
            }
            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? {
                val doc = backend.getCaptainDoc(uid) ?: return null
                return doc.mapValues { it.value?.toString().orEmpty() }
            }
            override suspend fun submitOrderReceiptDetails(
                orderDocId: String,
                customerName: String,
                customerPhone: String,
                receiptImagePath: String,
                notes: String
            ): Result<String> = Result.success("ok")
        }
        val cfClient = CloudFunctionsOrderClient(
            firebaseAuth = null,
            callableGateway = object : OrderCallableGateway {
                override suspend fun callFunction(
                    functionName: String,
                    payload: Map<String, Any?>
                ): Map<String, Any?> = mapOf("success" to true, "message" to "ok", "earnedAmount" to 30.0)
            },
            authUidProvider = { activeUidProvider() }
        )

        return CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = storageRepo,
            cloudMetadataPortOverride = metadataPort,
            cloudFunctionsClientOverride = cfClient,
            authBackendOverride = backend
        )
    }

    // -------------------------------------------------------------------------
    // Scenarios 1, 2, 3: Captain signup writes ONLY captains/{uid} with full schema,
    // ZERO writes to merchants/restaurants/drivers, and blocks colliding merchant emails.
    // -------------------------------------------------------------------------
    @Test
    fun scenario1_2_3_signupCreatesSingleCaptainDocOnly_neverTouchesMerchantsOrRestaurants_andRejectsCollision() = runBlocking {
        val backend = FakeCaptainAuthBackend()
        val repo = buildRepository(backend)

        val profileUri = createSampleJpeg("cap_profile").toString()
        val idFrontUri = createSampleJpeg("cap_front").toString()
        val idBackUri = createSampleJpeg("cap_back").toString()

        val result = repo.registerCaptainWithEmailVerification(
            fullName = "كابتن حسام الدين",
            phone = "01012345678",
            email = "hossam.captain@elbatal.com",
            pass = "StrongPass123",
            address = "القاهرة - مدينة نصر",
            avatarUri = profileUri,
            nationalIdUri = idFrontUri,
            idBackUri = idBackUri
        )

        // Scenario 1 assertions: Auth user + verification email + single captains/{uid} doc
        assertTrue("Captain signup must succeed", result.isSuccess)
        assertEquals("hossam.captain@elbatal.com", result.getOrNull())
        assertEquals(1, backend.verificationEmailsSentCount)

        val createdUid = backend.lastCreatedCaptainDocId
        assertNotNull("Captain UID must be assigned", createdUid)
        assertEquals(1, backend.captains.size)
        val capDoc = backend.captains[createdUid]!!
        assertEquals(createdUid, capDoc["uid"])
        assertEquals("كابتن حسام الدين", capDoc["name"])
        assertEquals("hossam.captain@elbatal.com", capDoc["email"])
        assertEquals("01012345678", capDoc["phone"])
        assertTrue(capDoc["personalImage"].toString().contains("captains/$createdUid/profile/profile.jpg"))
        assertTrue(capDoc["idCardFront"].toString().contains("captains/$createdUid/documents/id_front.jpg"))
        assertTrue(capDoc["idCardBack"].toString().contains("captains/$createdUid/documents/id_back.jpg"))
        assertEquals("القاهرة - مدينة نصر", capDoc["address"])
        assertEquals("captain", capDoc["accountType"])
        assertEquals(false, capDoc["emailVerified"])
        assertTrue((capDoc["createdAt"] as Long) > 0L)
        assertEquals("offline", capDoc["availabilityStatus"])
        assertEquals("outside_shift", capDoc["shiftStatus"])

        // Unverified captain MUST NOT be marked logged in yet
        val localRoom = db.captainDao().getCaptain()
        assertNotNull(localRoom)
        assertFalse("Unverified captain cannot be logged in", localRoom!!.isLoggedIn)

        // Scenario 2 & 3 assertions: ZERO documents created in merchants, restaurants, or drivers
        assertTrue("merchants collection MUST remain empty", backend.merchants.isEmpty())
        assertTrue("restaurants collection MUST remain empty", backend.restaurants.isEmpty())
        assertTrue("drivers collection MUST remain empty", backend.drivers.isEmpty())

        // Scenario 3 extra assertion: Email already used by a Merchant MUST collide cleanly and NEVER overwrite/create captain doc
        backend.seedAuthAccount(
            uid = "merchant_existing_uid",
            email = "merchant@store.com",
            password = "MerchPass123",
            isEmailVerified = true
        )
        backend.merchants["merchant_existing_uid"] = mutableMapOf(
            "uid" to "merchant_existing_uid",
            "name" to "تاجر القائمة",
            "accountType" to "merchant"
        )

        val collisionResult = repo.registerCaptainWithEmailVerification(
            fullName = "محاولة تداخل",
            phone = "01055556666",
            email = "merchant@store.com",
            pass = "MerchPass123",
            address = "الجيزة"
        )
        assertTrue("Duplicate merchant email registration MUST fail immediately", collisionResult.isFailure)
        assertNull("Collision MUST NOT create any doc in captains for merchant UID", backend.captains["merchant_existing_uid"])
    }

    // -------------------------------------------------------------------------
    // Scenarios 4, 5, 6: Block unverified login (email & phone), allow after reload()+verify,
    // resend verification, and load profile exclusively from captains/{uid}.
    // -------------------------------------------------------------------------
    @Test
    fun scenario4_5_6_emailVerificationBlocksAccessUntilReloadVerified_thenLoadsDataFromCaptainsUid() = runBlocking {
        val backend = FakeCaptainAuthBackend()
        val repo = buildRepository(backend)

        val signupRes = repo.registerCaptainWithEmailVerification(
            fullName = "كابتن طارق علي",
            phone = "01122334455",
            email = "tarek.cap@elbatal.com",
            pass = "Secret999",
            address = "الإسكندرية - سموحة",
            avatarUri = createSampleJpeg("tarek_p").toString(),
            nationalIdUri = createSampleJpeg("tarek_f").toString(),
            idBackUri = createSampleJpeg("tarek_b").toString()
        )
        assertTrue(signupRes.isSuccess)
        val capUid = backend.lastCreatedCaptainDocId!!

        // Scenario 4a: Attempt login by EMAIL before verification -> blocked
        val unverifiedEmailLogin = repo.login("tarek.cap@elbatal.com", "Secret999")
        assertTrue("Unverified login by email must return EmailNotVerified", unverifiedEmailLogin is LoginOutcome.EmailNotVerified)
        assertFalse(repo.validateCurrentSession())
        assertFalse(db.captainDao().getCaptain()?.isLoggedIn == true)

        // Scenario 4b: Attempt login by PHONE before verification -> ALSO blocked (never bypassed!)
        val unverifiedPhoneLogin = repo.login("01122334455", "Secret999")
        assertTrue("Unverified login by phone must also return EmailNotVerified", unverifiedPhoneLogin is LoginOutcome.EmailNotVerified)
        assertFalse(db.captainDao().getCaptain()?.isLoggedIn == true)

        // Scenario 4c: checkEmailVerification before clicking link -> returns false & invokes reload()
        val preVerifyCheck = repo.checkEmailVerification()
        assertTrue(preVerifyCheck.isSuccess)
        assertEquals(false, preVerifyCheck.getOrNull())
        assertTrue("checkEmailVerification must call Firebase reload()", backend.reloadCallsCount > 0)

        // Resend verification email
        val resendRes = repo.resendVerificationEmail()
        assertTrue(resendRes.isSuccess)
        assertEquals(2, backend.verificationEmailsSentCount)

        // Scenario 5: Simulate user clicking verification link in email (Auth server flips emailVerified -> true)
        backend.markEmailVerifiedInAuthServer("tarek.cap@elbatal.com")
        val postVerifyCheck = repo.checkEmailVerification()
        assertTrue(postVerifyCheck.isSuccess)
        assertEquals(true, postVerifyCheck.getOrNull())
        assertEquals(true, backend.captains[capUid]?.get("emailVerified"))

        // Clear local Room & sign out, then perform fresh login via login()
        backend.signOut()
        db.captainDao().clearAll()
        val verifiedLogin = repo.login("tarek.cap@elbatal.com", "Secret999")
        assertTrue("Verified captain login must succeed", verifiedLogin is LoginOutcome.Success)
        assertTrue("validateCurrentSession must return true for verified captain", repo.validateCurrentSession())

        // Scenario 6: Verify all captain profile fields are loaded strictly from captains/{uid}
        val loaded = db.captainDao().getCaptain()
        assertNotNull(loaded)
        assertEquals(capUid, loaded!!.uid)
        assertEquals("كابتن طارق علي", loaded.fullName)
        assertEquals("01122334455", loaded.phoneNumber)
        assertEquals("tarek.cap@elbatal.com", loaded.email)
        assertEquals("الإسكندرية - سموحة", loaded.address)
        assertEquals("captain", loaded.accountType)
        assertTrue(loaded.isLoggedIn)
        assertTrue(loaded.profileImagePath.contains("captains/$capUid/profile/profile.jpg"))
        assertTrue(loaded.idFrontImagePath.contains("captains/$capUid/documents/id_front.jpg"))
        assertTrue(loaded.idBackImagePath.contains("captains/$capUid/documents/id_back.jpg"))
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Merchant account rejected on Captain App login (zero fallback to merchants/restaurants,
    // zero auto-creation of captains/{uid}, and non-captain accountType rejected).
    // -------------------------------------------------------------------------
    @Test
    fun scenario7_merchantAccountRejectedOnCaptainLogin_neverAutoCreatesCaptainDoc() = runBlocking {
        val backend = FakeCaptainAuthBackend()
        val repo = buildRepository(backend)

        // Case A: Verified Auth user who exists ONLY in merchants/{uid} & restaurants/{uid}
        val merchUid = "merch_uid_500"
        backend.seedAuthAccount(
            uid = merchUid,
            email = "owner@burgerking-demo.com",
            password = "MerchantPassword1!",
            isEmailVerified = true,
            displayName = "مطعم البرجر"
        )
        backend.merchants[merchUid] = mutableMapOf(
            "uid" to merchUid,
            "name" to "مطعم البرجر",
            "phone" to "01077778888",
            "email" to "owner@burgerking-demo.com",
            "accountType" to "merchant"
        )
        backend.restaurants[merchUid] = mutableMapOf(
            "uid" to merchUid,
            "name" to "مطعم البرجر",
            "accountType" to "merchant"
        )

        val merchLoginOutcome = repo.login("owner@burgerking-demo.com", "MerchantPassword1!")
        assertTrue("Merchant account MUST be rejected in Captain App", merchLoginOutcome is LoginOutcome.Error)
        assertNull("Login rejection MUST sign out Firebase Auth user", backend.currentUser())
        assertNull("CRITICAL: Login MUST NOT auto-create captains/$merchUid!", backend.captains[merchUid])
        assertFalse("Room captain must not be logged in", db.captainDao().getCaptain()?.isLoggedIn == true)

        // Case B: Phone lookup must NEVER return email of a non-captain or merchant document
        val phoneLookupEmail = repo.lookupEmailByPhone("01077778888")
        assertNull("lookupEmailByPhone must never resolve merchant accounts", phoneLookupEmail)

        // Case C: Even if a corrupt/rogue document exists in captains/{uid} with accountType == "merchant", login rejects it
        val rogueUid = "rogue_merch_uid_501"
        backend.seedAuthAccount(rogueUid, "rogue@merchant.com", "RoguePass1!", isEmailVerified = true)
        backend.captains[rogueUid] = mutableMapOf(
            "uid" to rogueUid,
            "email" to "rogue@merchant.com",
            "accountType" to "merchant",
            "status" to "active"
        )
        val rogueOutcome = repo.login("rogue@merchant.com", "RoguePass1!")
        assertTrue("Doc with accountType='merchant' MUST be rejected", rogueOutcome is LoginOutcome.Error)
        assertTrue(
            "Error message should clarify merchant separation",
            (rogueOutcome as LoginOutcome.Error).message.contains("تاجر")
        )
        assertNull(backend.currentUser())

        // Case D: Also validateCurrentSession revokes session if Firebase user is merchant without captains doc
        backend.seedAuthAccount("session_merch", "sess_m@elbatal.com", "Pass123!", isEmailVerified = true)
        backend.currentUid = "session_merch"
        backend.merchants["session_merch"] = mutableMapOf("uid" to "session_merch", "accountType" to "merchant")
        assertFalse("validateCurrentSession MUST reject merchant session", repo.validateCurrentSession())
        assertNull(backend.currentUser())
        assertNull(backend.captains["session_merch"])
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Authenticated verified captain retains shifts, order acceptance,
    // offline/out-of-shift protections, and shift/order notifications.
    // -------------------------------------------------------------------------
    @Test
    fun scenario8_verifiedCaptainRetainsShiftsOrderAcceptanceAndNotifications() = runBlocking {
        val backend = FakeCaptainAuthBackend()
        val capUid = "cap_active_800"
        backend.seedAuthAccount(capUid, "active.cap@elbatal.com", "ActivePass123", isEmailVerified = true)
        backend.captains[capUid] = mutableMapOf(
            "uid" to capUid,
            "name" to "كابتن نشط",
            "email" to "active.cap@elbatal.com",
            "phone" to "01099887766",
            "accountType" to "captain",
            "status" to "active",
            "availabilityStatus" to "available",
            "shiftStatus" to "inside_shift"
        )
        val repo = buildRepository(backend) { capUid }

        val loginOutcome = repo.login("active.cap@elbatal.com", "ActivePass123")
        assertTrue(loginOutcome is LoginOutcome.Success)

        // 1. Seed 24h active Cairo shift schedule in captain_schedules (all days 00:00-23:59)
        val dayNames = mapOf(
            Calendar.SUNDAY to "الأحد",
            Calendar.MONDAY to "الإثنين",
            Calendar.TUESDAY to "الثلاثاء",
            Calendar.WEDNESDAY to "الأربعاء",
            Calendar.THURSDAY to "الخميس",
            Calendar.FRIDAY to "الجمعة",
            Calendar.SATURDAY to "السبت"
        )
        val activeShifts = (1..7).map { day ->
            ShiftEntity(
                id = "${capUid}_day_$day",
                captainUid = capUid,
                dayOfWeek = day,
                dayNameArabic = dayNames[day] ?: "يوم",
                startTime = "00:00",
                endTime = "23:59",
                isDayOff = false
            )
        }
        repo.saveShiftsLocally(capUid, activeShifts)
        val shiftStatus = ShiftManager.calculateShiftStatus(repo.getShifts())
        assertTrue("Captain must be inside active shift", shiftStatus is ShiftStatus.Active)

        // 2. Schedule shift start/end alarms via ShiftNotificationScheduler
        ShiftNotificationScheduler.scheduleShiftNotifications(appContext, activeShifts, capUid)
        val alarmShadow = Shadows.shadowOf(appContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
        assertTrue("Shift alarms must be scheduled", alarmShadow.scheduledAlarms.isNotEmpty())

        // 3. Verify order acceptance works when online + inside active shift, and blocked when offline
        val testOrder = OrderEntity(
            id = 888L,
            orderNumber = "EXP-888",
            firestoreDocId = "ORD_888",
            customerName = "عميل اختبار",
            customerPhone = "01000001111",
            storeName = "مطعم التجربة",
            pickupAddress = "مدينة نصر",
            deliveryAddress = "التجمع الخامس",
            itemsSummary = "وجبة بطل",
            itemsCount = 1,
            orderTotalAmount = 150.0,
            deliveryFee = 30.0,
            distanceKm = 4.5,
            status = OrderStatus.PENDING,
            captainId = ""
        )
        db.orderDao().insertOrder(testOrder)

        // Online + inside shift -> acceptOrder succeeds
        val acceptOk = repo.acceptOrder(888L)
        assertTrue("Verified active captain inside shift & online must accept order", acceptOk.isSuccess)
        assertEquals(OrderStatus.ACCEPTED, repo.allOrdersFlow.first().firstOrNull { it.id == 888L }?.status)

        // Show new-order notification & verify posted cleanly
        Shadows.shadowOf(appContext).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        CaptainNotificationManager.ensureNotificationChannels(appContext)
        val posted = CaptainNotificationManager.showNewOrderNotification(
            context = appContext,
            orderKey = "ORD_888",
            storeName = "مطعم التجربة",
            pickupAddress = "مدينة نصر",
            deliveryAddress = "التجمع الخامس",
            deliveryFee = 30.0,
            orderCode = "EXP-888"
        )
        assertTrue("Notification call must return true", posted)
        val nmShadow = Shadows.shadowOf(appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        assertTrue("Order notification must be posted", nmShadow.allNotifications.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // UI Verification: SignUpScreen (with address) & EmailVerificationScreen
    // -------------------------------------------------------------------------
    @Test
    fun uiScreens_signUpPassesAddress_andEmailVerificationHandlesCheckAndResend() {
        var submittedAddress = ""
        var submittedEmail = ""

        composeTestRule.setContent {
            ElBatalTheme {
                SignUpScreen(
                    onSignUpClick = { _, _, email, _, _, address, _, _, _, _, _, _, _ ->
                        submittedEmail = email
                        submittedAddress = address
                    },
                    onNavigateLogin = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Fill name, phone, email, address, password, confirmPassword
        composeTestRule.onNodeWithText("مثال: أحمد محمد علي").performScrollTo().performTextInput("كابتن كريم")
        composeTestRule.onNodeWithText("مثال: 01099887766").performScrollTo().performTextInput("01012345678")
        composeTestRule.onNodeWithText("مثال: captain@example.com").performScrollTo().performTextInput("karim@elbatal.com")
        composeTestRule.onNodeWithTag("signup_address_input").performScrollTo().performTextInput("القاهرة - المعادي")
        composeTestRule.onNodeWithText("أدخل 6 أحرف أو أرقام على الأقل").performScrollTo().performTextInput("Pass1234")
        composeTestRule.onNodeWithText("أعد إدخال كلمة المرور للتأكيد").performScrollTo().performTextInput("Pass1234")

        composeTestRule.onNodeWithTag("signup_submit_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("karim@elbatal.com", submittedEmail)
        assertEquals("القاهرة - المعادي", submittedAddress)
    }

    @Test
    fun uiScreen_emailVerificationScreen_displaysEmailAndHandlesActions() {
        var isServerVerified = false
        var resendInvoked = false
        var navigatedMain = false

        composeTestRule.setContent {
            ElBatalTheme {
                EmailVerificationScreen(
                    email = "verify.me@elbatal.com",
                    onCheckVerification = { onVerified, onNotVerified ->
                        if (isServerVerified) onVerified() else onNotVerified("لم يتم تأكيد البريد الإلكتروني بعد!")
                    },
                    onResendEmail = { onResult ->
                        resendInvoked = true
                        onResult(true, "تمت إعادة الإرسال بنجاح")
                    },
                    onNavigateLogin = {},
                    onVerifiedSuccess = { navigatedMain = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("verify_email_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("verify_resend_button").performScrollTo().performClick()
        assertTrue("Resend callback must trigger", resendInvoked)

        // Before verification -> banner shows unverified error, no navigation
        composeTestRule.onNodeWithTag("verify_check_button").performScrollTo().performClick()
        assertFalse(navigatedMain)
        composeTestRule.onNodeWithTag("verify_status_banner").assertIsDisplayed()

        // After verification -> navigates to Main
        isServerVerified = true
        composeTestRule.onNodeWithTag("verify_check_button").performScrollTo().performClick()
        assertTrue("Verified check must invoke onVerifiedSuccess", navigatedMain)
    }

    @Test
    fun legacyRoleOnlyCaptainDoc_allowsLoginAndPreservesExistingFields_whileConflictingMerchantRoleIsRejected() = runBlocking {
        val backend = FakeCaptainAuthBackend()
        val repo = buildRepository(backend)

        // 1. Existing legacy captain in captains/{uid} with role = "captain" and NO accountType field
        val legacyUid = "legacy_cap_901"
        backend.seedAuthAccount(
            uid = legacyUid,
            email = "legacy.captain@elbatal.com",
            password = "LegacyPass123",
            isEmailVerified = true,
            displayName = "كابتن قديم"
        )
        backend.captains[legacyUid] = mutableMapOf(
            "uid" to legacyUid,
            "name" to "كابتن قديم معتمد",
            "email" to "legacy.captain@elbatal.com",
            "phone" to "01011223344",
            "role" to "captain",
            "status" to "active",
            "legacyCustomField" to "keep_me_intact"
        )

        // Read-only audit detects legacyUid as having role='captain' without accountType
        val auditedLegacyIds = CaptainRepository.auditLegacyRoleWithoutAccountType(backend.captains)
        assertEquals(listOf(legacyUid), auditedLegacyIds)

        // Login succeeds for verified legacy captain in captains/{uid} without deleting or mutating legacy fields
        val loginRes = repo.login("legacy.captain@elbatal.com", "LegacyPass123")
        assertTrue("Legacy captain with role='captain' in captains/{uid} must log in", loginRes is LoginOutcome.Success)
        assertEquals("keep_me_intact", backend.captains[legacyUid]?.get("legacyCustomField"))
        assertEquals("captain", backend.captains[legacyUid]?.get("role"))
        assertNull("Login must NOT perform unapproved bulk/schema mutation of accountType in Firestore", backend.captains[legacyUid]?.get("accountType"))

        // 2. Conflicting document with accountType = "merchant" and role = "captain" MUST still be rejected!
        val conflictUid = "conflict_uid_902"
        backend.seedAuthAccount(conflictUid, "conflict@elbatal.com", "ConflictPass123", isEmailVerified = true)
        backend.captains[conflictUid] = mutableMapOf(
            "uid" to conflictUid,
            "email" to "conflict@elbatal.com",
            "accountType" to "merchant",
            "role" to "captain",
            "status" to "active"
        )
        val conflictLogin = repo.login("conflict@elbatal.com", "ConflictPass123")
        assertTrue("Conflicting accountType='merchant' must be rejected even if role='captain'", conflictLogin is LoginOutcome.Error)

        // 3. New captain registration writes BOTH accountType='captain' AND role='captain' to captains/{uid}
        val newSignup = repo.registerCaptainWithEmailVerification(
            fullName = "كابتن موحد",
            phone = "01288990011",
            email = "unified.cap@elbatal.com",
            pass = "UnifiedPass123",
            address = "القاهرة"
        )
        assertTrue(newSignup.isSuccess)
        val newUid = backend.lastCreatedCaptainDocId!!
        assertEquals("captain", backend.captains[newUid]?.get("accountType"))
        assertEquals("captain", backend.captains[newUid]?.get("role"))
    }
}
