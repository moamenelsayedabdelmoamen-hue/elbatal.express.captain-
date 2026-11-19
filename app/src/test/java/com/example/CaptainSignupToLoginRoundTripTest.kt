package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.AuthUserSnapshot
import com.example.data.CaptainAuthBackendPort
import com.example.data.CaptainCloudMetadataPort
import com.example.data.CaptainRepository
import com.example.data.LoginOutcome
import com.example.data.OrderEntity
import com.example.data.PhoneAuthRegistry
import com.example.data.ShiftStatus
import com.example.data.remote.CloudFunctionsOrderClient
import com.example.data.remote.OrderCallableGateway
import com.example.data.storage.AuthUidProvider
import com.example.data.storage.FirebaseStorageRepository
import com.example.data.storage.NetworkConnectivityChecker
import com.example.data.storage.StorageGateway
import com.example.data.storage.StorageUploadException
import com.example.data.storage.UploadErrorKind
import com.example.ui.components.LocalStorageRepository
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.theme.ElBatalTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h1400dp")
class CaptainSignupToLoginRoundTripTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var appContext: Application
    private lateinit var db: AppDatabase
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        tempDir = File(appContext.cacheDir, "signup_roundtrip_${System.nanoTime()}").apply { mkdirs() }
        File(appContext.cacheDir, "fb_storage_cache").deleteRecursively()
        appContext.getSharedPreferences("elbatal_phone_auth_registry", Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
        File(appContext.cacheDir, "fb_storage_cache").deleteRecursively()
    }

    private data class FakeAuthUser(
        val uid: String,
        val email: String,
        val password: String,
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

    private class InMemoryCaptainBackend : CaptainAuthBackendPort {
        val usersByEmail = mutableMapOf<String, FakeAuthUser>()
        val usersByUid = mutableMapOf<String, FakeAuthUser>()
        val captains = mutableMapOf<String, MutableMap<String, Any?>>()
        var currentUid: String? = null
        var deletedUsersCount = 0
        private var seq = 500

        override fun currentUser(): AuthUserSnapshot? = currentUid?.let { usersByUid[it]?.toSnapshot() }

        override suspend fun createUserWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val normalized = email.trim().lowercase()
            if (usersByEmail.containsKey(normalized)) {
                throw IllegalStateException("EMAIL_ALREADY_IN_USE: $normalized")
            }
            seq += 1
            val uid = "roundtrip_uid_$seq"
            val u = FakeAuthUser(uid = uid, email = normalized, password = password, isEmailVerified = false)
            usersByEmail[normalized] = u
            usersByUid[uid] = u
            currentUid = uid
            return u.toSnapshot()
        }

        override suspend fun sendEmailVerification() {
            currentUser() ?: throw IllegalStateException("No current user")
        }

        override suspend fun reloadCurrentUser(): AuthUserSnapshot? = currentUser()

        override suspend fun updateDisplayName(displayName: String) {
            currentUid?.let { usersByUid[it]?.displayName = displayName }
        }

        override suspend fun signInWithEmailAndPassword(email: String, password: String): AuthUserSnapshot {
            val u = usersByEmail[email.trim().lowercase()]
                ?: throw IllegalArgumentException("User not found")
            if (u.password != password) throw IllegalArgumentException("Wrong password")
            currentUid = u.uid
            return u.toSnapshot()
        }

        override fun signOut() {
            currentUid = null
        }

        override suspend fun deleteCurrentUser() {
            val uid = currentUid ?: return
            val removed = usersByUid.remove(uid)
            if (removed != null) {
                usersByEmail.remove(removed.email)
            }
            currentUid = null
            deletedUsersCount += 1
        }

        override suspend fun sendPasswordResetEmail(email: String) {}

        override suspend fun createCaptainDoc(uid: String, data: Map<String, Any>) {
            captains[uid] = data.toMutableMap()
        }

        override suspend fun getCaptainDoc(uid: String): Map<String, Any?>? = captains[uid]?.toMap()

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
                val p1 = doc["phone"]?.toString().orEmpty()
                val p2 = doc["phoneNumber"]?.toString().orEmpty()
                if (p1 in variants || p2 in variants || p1 == normalizedLocal || p2 == normalizedLocal) {
                    return doc["email"]?.toString()
                }
            }
            return null
        }
    }

    private class InMemoryStorageGateway : StorageGateway {
        val objects = mutableMapOf<String, ByteArray>()
        val contentTypes = mutableMapOf<String, String>()

        override suspend fun putBytesWithProgress(
            storagePath: String,
            bytes: ByteArray,
            contentType: String,
            customMetadata: Map<String, String>,
            requesterUid: String,
            onProgress: (Int) -> Unit
        ) {
            if (requesterUid.isBlank()) throw SecurityException("Unauthenticated upload")
            if (!storagePath.startsWith("captains/$requesterUid/")) {
                throw SecurityException("Permission denied for $storagePath")
            }
            onProgress(50)
            objects[storagePath] = bytes.copyOf()
            contentTypes[storagePath] = contentType
            onProgress(100)
        }

        override suspend fun getBytes(
            storagePath: String,
            maxDownloadBytes: Long,
            requesterUid: String
        ): ByteArray {
            if (requesterUid.isBlank()) throw SecurityException("Unauthenticated download")
            return objects[storagePath]?.copyOf()
                ?: throw IllegalStateException("Missing object at $storagePath")
        }
    }

    private fun createSampleJpegFile(fileName: String, color: Int): File {
        val bmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val file = File(tempDir, fileName)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return file
    }

    private fun buildEnv(
        backend: InMemoryCaptainBackend,
        gateway: InMemoryStorageGateway,
        isConnectedProvider: () -> Boolean = { true }
    ): Pair<CaptainRepository, FirebaseStorageRepository> {
        val storageRepo = FirebaseStorageRepository(
            context = appContext,
            gateway = gateway,
            authUidProvider = AuthUidProvider { backend.currentUid },
            connectivityChecker = NetworkConnectivityChecker { isConnectedProvider() },
            maxAutoRetries = 1,
            retryDelayMs = 5L
        )
        val metaPort = object : CaptainCloudMetadataPort {
            override fun currentAuthUid(): String? = backend.currentUid
            override suspend fun saveCaptainImageMetadata(uid: String, fields: Map<String, Any>) {
                backend.updateCaptainDoc(uid, fields)
            }
            override suspend fun fetchCaptainImageMetadata(uid: String): Map<String, String>? {
                return backend.getCaptainDoc(uid)?.mapValues { it.value?.toString().orEmpty() }
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
                ): Map<String, Any?> = mapOf("success" to true, "earnedAmount" to 25.0)
            },
            authUidProvider = { backend.currentUid ?: "anon" }
        )
        val repo = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = appContext,
            storageRepository = storageRepo,
            cloudMetadataPortOverride = metaPort,
            cloudFunctionsClientOverride = cfClient,
            authBackendOverride = backend
        )
        return repo to storageRepo
    }

    @Test
    fun fullSignup_savesAllFieldsAndAll4PhotosInFirebaseStorageAndFirestore_thenRestoresOnFreshReloginAndUi() = runBlocking<Unit> {
        val backend = InMemoryCaptainBackend()
        val gateway = InMemoryStorageGateway()
        val (repo, storageRepo) = buildEnv(backend, gateway)

        val profileUri = Uri.fromFile(createSampleJpegFile("sig_profile.jpg", Color.BLUE)).toString()
        val idFrontUri = Uri.fromFile(createSampleJpegFile("sig_id_front.jpg", Color.RED)).toString()
        val idBackUri = Uri.fromFile(createSampleJpegFile("sig_id_back.jpg", Color.GREEN)).toString()
        val licenseUri = Uri.fromFile(createSampleJpegFile("sig_license.jpg", Color.YELLOW)).toString()

        // 1. Register new captain with complete info + all 4 photos
        val regResult = repo.registerCaptainWithEmailVerification(
            fullName = "محمود حسن إبراهيم",
            phone = "01066778899",
            email = "mahmoud.captain@elbatal.com",
            pass = "Batal@2026",
            address = "الجيزة - الدقي شارع التحرير",
            vehicleType = "موتوسيكل هوجن 150",
            avatarUri = profileUri,
            nationalIdUri = idFrontUri,
            idBackUri = idBackUri,
            licenseUri = licenseUri
        )
        assertTrue("Registration must succeed", regResult.isSuccess)
        val uid = backend.usersByEmail["mahmoud.captain@elbatal.com"]!!.uid

        // 2. Assert Firebase Storage received compressed non-empty image bytes for ALL 4 photos
        val expectedProfilePath = "captains/$uid/profile/profile.jpg"
        val expectedFrontPath = "captains/$uid/documents/id_front.jpg"
        val expectedBackPath = "captains/$uid/documents/id_back.jpg"
        val expectedLicensePath = "captains/$uid/documents/license.jpg"

        val storedProfileBytes = gateway.objects[expectedProfilePath]
        val storedFrontBytes = gateway.objects[expectedFrontPath]
        val storedBackBytes = gateway.objects[expectedBackPath]
        val storedLicenseBytes = gateway.objects[expectedLicensePath]

        assertNotNull("Profile image bytes must exist in Firebase Storage", storedProfileBytes)
        assertNotNull("ID front bytes must exist in Firebase Storage", storedFrontBytes)
        assertNotNull("ID back bytes must exist in Firebase Storage", storedBackBytes)
        assertNotNull("License bytes must exist in Firebase Storage", storedLicenseBytes)
        assertTrue(storedProfileBytes!!.isNotEmpty())
        assertTrue(storedFrontBytes!!.isNotEmpty())
        assertTrue(storedBackBytes!!.isNotEmpty())
        assertTrue(storedLicenseBytes!!.isNotEmpty())

        // 3. Assert Firestore captains/{uid} contains ALL registered fields & zero local URIs
        val doc = backend.captains[uid]!!
        assertEquals("محمود حسن إبراهيم", doc["fullName"])
        assertEquals("محمود حسن إبراهيم", doc["name"])
        assertEquals("01066778899", doc["phone"])
        assertEquals("01066778899", doc["phoneNumber"])
        assertEquals("mahmoud.captain@elbatal.com", doc["email"])
        assertEquals("الجيزة - الدقي شارع التحرير", doc["address"])
        assertEquals("موتوسيكل هوجن 150", doc["vehicleType"])
        assertEquals(expectedProfilePath, doc["profileImagePath"])
        assertEquals(expectedProfilePath, doc["personalImage"])
        assertEquals(expectedFrontPath, doc["idFrontImagePath"])
        assertEquals(expectedFrontPath, doc["idCardFront"])
        assertEquals(expectedBackPath, doc["idBackImagePath"])
        assertEquals(expectedBackPath, doc["idCardBack"])
        assertEquals(expectedLicensePath, doc["licenseImagePath"])
        assertEquals(true, doc["idDocumentsComplete"])
        doc.values.forEach { v ->
            val str = v?.toString().orEmpty()
            assertFalse("Firestore must never store local file:// URI: $str", str.startsWith("file://"))
            assertFalse("Firestore must never store local content:// URI: $str", str.startsWith("content://"))
        }

        // 4. Verify email & logout cleanly (wiping Room DB + local image cache + prefs)
        backend.usersByUid[uid]!!.isEmailVerified = true
        assertEquals(true, repo.checkEmailVerification().getOrNull())
        repo.logout()
        assertNull("Room DB captain must be wiped after logout", db.captainDao().getCaptain())
        val cacheDir = File(appContext.cacheDir, "fb_storage_cache")
        assertFalse("Local Storage cache folder must be wiped on logout", cacheDir.exists() && (cacheDir.listFiles()?.isNotEmpty() == true))

        // Delete source local temp photos so any display MUST come from Firebase Storage!
        tempDir.listFiles()?.forEach { it.delete() }

        // 5. Log back in via EMAIL on fresh device/session state
        val emailLoginOutcome = repo.login("mahmoud.captain@elbatal.com", "Batal@2026")
        assertTrue("Verified captain email login must succeed", emailLoginOutcome is LoginOutcome.Success)

        val restoredAfterEmailLogin = db.captainDao().getCaptain()!!
        assertEquals(uid, restoredAfterEmailLogin.uid)
        assertEquals("محمود حسن إبراهيم", restoredAfterEmailLogin.fullName)
        assertEquals("01066778899", restoredAfterEmailLogin.phoneNumber)
        assertEquals("mahmoud.captain@elbatal.com", restoredAfterEmailLogin.email)
        assertEquals("الجيزة - الدقي شارع التحرير", restoredAfterEmailLogin.address)
        assertEquals("موتوسيكل هوجن 150", restoredAfterEmailLogin.vehicleType)
        assertEquals(expectedProfilePath, restoredAfterEmailLogin.profileImagePath)
        assertEquals(expectedFrontPath, restoredAfterEmailLogin.idFrontImagePath)
        assertEquals(expectedBackPath, restoredAfterEmailLogin.idBackImagePath)
        assertEquals(expectedLicensePath, restoredAfterEmailLogin.licenseImagePath)
        assertTrue(restoredAfterEmailLogin.isNationalIdComplete)

        // 6. Verify StorageAsyncImage resolution re-downloads exact bytes from Firebase Storage on fresh session
        val resolvedProfile = storageRepo.resolveDisplayModel(restoredAfterEmailLogin.effectiveProfileImage).getOrThrow() as ByteArray
        val resolvedFront = storageRepo.resolveDisplayModel(restoredAfterEmailLogin.effectiveIdFrontImage).getOrThrow() as ByteArray
        val resolvedBack = storageRepo.resolveDisplayModel(restoredAfterEmailLogin.effectiveIdBackImage).getOrThrow() as ByteArray
        val resolvedLicense = storageRepo.resolveDisplayModel(restoredAfterEmailLogin.effectiveLicenseImage).getOrThrow() as ByteArray
        assertArrayEquals(storedProfileBytes, resolvedProfile)
        assertArrayEquals(storedFrontBytes, resolvedFront)
        assertArrayEquals(storedBackBytes, resolvedBack)
        assertArrayEquals(storedLicenseBytes, resolvedLicense)

        // 7. Also verify logging back in via PHONE number restores identical captain data & images
        repo.logout()
        appContext.getSharedPreferences("elbatal_phone_auth_registry", Context.MODE_PRIVATE).edit().clear().commit()
        val phoneLoginOutcome = repo.login("01066778899", "Batal@2026")
        assertTrue("Verified captain phone login must succeed via Firestore captains phone lookup", phoneLoginOutcome is LoginOutcome.Success)
        val restoredAfterPhoneLogin = db.captainDao().getCaptain()!!
        assertEquals("محمود حسن إبراهيم", restoredAfterPhoneLogin.fullName)
        assertEquals("الجيزة - الدقي شارع التحرير", restoredAfterPhoneLogin.address)
        assertEquals(expectedLicensePath, restoredAfterPhoneLogin.licenseImagePath)

        // 8. Verify ProfileScreen & DashboardScreen render all restored data and photos
        val showProfile = androidx.compose.runtime.mutableStateOf(true)
        composeTestRule.setContent {
            ElBatalTheme {
                CompositionLocalProvider(LocalStorageRepository provides storageRepo) {
                    if (showProfile.value) {
                        ProfileScreen(
                            captain = restoredAfterPhoneLogin,
                            onSaveProfileDetails = { _, _, _, _, _, _, _ -> },
                            onLogout = {}
                        )
                    } else {
                        DashboardScreen(
                            captain = restoredAfterPhoneLogin,
                            activeOrder = null,
                            availableOrders = emptyList(),
                            pendingIncomingOrder = null,
                            completedCount = 0,
                            totalEarnings = 0.0,
                            shiftStatus = ShiftStatus.NoSchedule(),
                            onToggleOnline = {},
                            onAcceptOrder = {}
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("profile_fullname_input").performScrollTo().assertTextContains("محمود حسن إبراهيم")
        composeTestRule.onNodeWithTag("profile_phone_input").performScrollTo().assertTextContains("01066778899")
        composeTestRule.onNodeWithTag("profile_email_input").performScrollTo().assertTextContains("mahmoud.captain@elbatal.com")
        composeTestRule.onNodeWithTag("profile_address_input").performScrollTo().assertTextContains("الجيزة - الدقي شارع التحرير")
        composeTestRule.onNodeWithTag("profile_vehicle_input").performScrollTo().assertTextContains("موتوسيكل هوجن 150")
        composeTestRule.onNodeWithTag("national_id_section_card").performScrollTo()
        composeTestRule.onAllNodesWithText("تم الحفظ والتوثيق بنجاح 🔒").fetchSemanticsNodes().let {
            assertEquals("Both ID front and back must show verified saved badge", 2, it.size)
        }
        composeTestRule.onNodeWithTag("profile_saved_license_image").performScrollTo().assertIsDisplayed()

        // 9. Switch to DashboardScreen and verify captain name renders from the same restored entity
        composeTestRule.runOnUiThread { showProfile.value = false }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("محمود حسن إبراهيم").assertIsDisplayed()
    }

    @Test
    fun updateProfile_persistsAddressVehiclePlateAndSurvivesLogoutRelogin() = runBlocking<Unit> {
        val backend = InMemoryCaptainBackend()
        val gateway = InMemoryStorageGateway()
        val (repo, _) = buildEnv(backend, gateway)

        assertTrue(
            repo.registerCaptainWithEmailVerification(
                fullName = "كابتن ياسر",
                phone = "01234567890",
                email = "yasser@elbatal.com",
                pass = "Pass1234",
                address = "العنوان القديم"
            ).isSuccess
        )
        val uid = backend.usersByEmail["yasser@elbatal.com"]!!.uid
        backend.usersByUid[uid]!!.isEmailVerified = true
        assertTrue(repo.login("yasser@elbatal.com", "Pass1234") is LoginOutcome.Success)

        val updateRes = repo.updateProfile(
            fullName = "كابتن ياسر المحدث",
            phoneNumber = "01234567891",
            email = "yasser@elbatal.com",
            vehicleType = "تروسيكل",
            licensePlate = "ج هـ و 7 8 9",
            address = "مدينة نصر - عباس العقاد"
        )
        assertTrue(updateRes.isSuccess)

        repo.logout()
        assertTrue(repo.login("yasser@elbatal.com", "Pass1234") is LoginOutcome.Success)

        val reloaded = db.captainDao().getCaptain()!!
        assertEquals("كابتن ياسر المحدث", reloaded.fullName)
        assertEquals("01234567891", reloaded.phoneNumber)
        assertEquals("مدينة نصر - عباس العقاد", reloaded.address)
        assertEquals("تروسيكل", reloaded.vehicleType)
        assertEquals("ج هـ و 7 8 9", reloaded.licensePlate)
    }

    @Test
    fun signup_rollsBackAuthUserOnStorageFailure_allowingImmediateRetryWithSameEmail() = runBlocking<Unit> {
        val backend = InMemoryCaptainBackend()
        val gateway = InMemoryStorageGateway()
        var online = false
        val (repo, _) = buildEnv(backend, gateway) { online }

        val photoUri = Uri.fromFile(createSampleJpegFile("offline_sig.jpg", Color.CYAN)).toString()

        // Attempt 1 while offline -> Storage fails, Auth user must be rolled back!
        val firstAttempt = repo.registerCaptainWithEmailVerification(
            fullName = "كابتن سامي",
            phone = "01555667788",
            email = "sami@elbatal.com",
            pass = "SamiPass123",
            address = "شبرا",
            avatarUri = photoUri
        )
        assertTrue("Signup MUST fail when Storage upload fails", firstAttempt.isFailure)
        assertEquals(1, backend.deletedUsersCount)
        assertFalse("Orphaned Auth user must be deleted on rollback", backend.usersByEmail.containsKey("sami@elbatal.com"))
        assertTrue("Firestore captains doc must NOT be left orphaned", backend.captains.isEmpty())

        // Attempt 2 once online -> succeeds cleanly with the EXACT same email!
        online = true
        val secondAttempt = repo.registerCaptainWithEmailVerification(
            fullName = "كابتن سامي",
            phone = "01555667788",
            email = "sami@elbatal.com",
            pass = "SamiPass123",
            address = "شبرا",
            avatarUri = photoUri
        )
        assertTrue("Retry signup after network restoration must succeed without email collision", secondAttempt.isSuccess)
        val uid = backend.usersByEmail["sami@elbatal.com"]!!.uid
        assertTrue(gateway.objects.containsKey("captains/$uid/profile/profile.jpg"))
        assertNotNull(backend.captains[uid])
    }

    @Test
    fun storageRepo_acceptsExtensionlessJpegFile_andRejectsTextFile() = runBlocking<Unit> {
        val backend = InMemoryCaptainBackend()
        val gateway = InMemoryStorageGateway()
        backend.currentUid = "cap_mime_1"
        val (_, storageRepo) = buildEnv(backend, gateway)

        // Extensionless file containing valid JPEG header & pixels (like many Android Photo Picker URIs)
        val extensionlessFile = createSampleJpegFile("picker_image_no_ext", Color.MAGENTA)
        val okRes = storageRepo.uploadCaptainProfileImage("cap_mime_1", Uri.fromFile(extensionlessFile))
        assertTrue("Valid extensionless JPEG photo must be accepted via header inspection", okRes.isSuccess)

        // Invalid non-image text file MUST still be rejected with VALIDATION
        val textFile = File(tempDir, "not_an_image.txt").apply { writeText("hello world") }
        val failRes = storageRepo.uploadCaptainProfileImage("cap_mime_1", Uri.fromFile(textFile))
        assertTrue("Text file must be rejected", failRes.isFailure)
        assertEquals(
            UploadErrorKind.VALIDATION,
            (failRes.exceptionOrNull() as? StorageUploadException)?.kind
        )
    }
}
