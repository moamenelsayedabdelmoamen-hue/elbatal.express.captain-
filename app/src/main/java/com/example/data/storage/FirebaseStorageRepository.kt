package com.example.data.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.data.OrderStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class UploadErrorKind {
    VALIDATION,
    NETWORK_DISCONNECTED,
    PERMISSION_DENIED,
    OWNERSHIP_VIOLATION,
    ORDER_CLOSED,
    UNKNOWN
}

class StorageUploadException(
    message: String,
    val kind: UploadErrorKind,
    val isRetryable: Boolean,
    cause: Throwable? = null
) : Exception(message, cause)

sealed class ImageUploadState {
    data object Idle : ImageUploadState()
    data object Validating : ImageUploadState()
    data object Compressing : ImageUploadState()
    data class Uploading(val progressPercent: Int) : ImageUploadState()
    data class Retrying(val attempt: Int, val maxAttempts: Int, val reason: String) : ImageUploadState()
    data class Success(val storagePath: String, val uploadedAtMs: Long = System.currentTimeMillis()) : ImageUploadState()
    data class Failed(
        val errorMessage: String,
        val kind: UploadErrorKind = UploadErrorKind.UNKNOWN,
        val isRetryable: Boolean = true
    ) : ImageUploadState()

    val isBusy: Boolean
        get() = this is Validating || this is Compressing || this is Uploading || this is Retrying
}

/**
 * Abstraction over Firebase Storage SDK operations so production executes directly against
 * official Firebase Storage Android SDK while unit/Robolectric tests can verify offline disconnects,
 * retries, progress emissions, and access-control rules deterministically.
 */
interface StorageGateway {
    suspend fun putBytesWithProgress(
        storagePath: String,
        bytes: ByteArray,
        contentType: String,
        customMetadata: Map<String, String>,
        requesterUid: String,
        onProgress: (Int) -> Unit
    )

    suspend fun getBytes(
        storagePath: String,
        maxDownloadBytes: Long,
        requesterUid: String
    ): ByteArray
}

fun interface NetworkConnectivityChecker {
    fun isNetworkConnected(): Boolean
}

fun interface AuthUidProvider {
    fun currentUid(): String?
}

class OfficialFirebaseStorageGateway(
    private val firebaseStorageProvider: () -> FirebaseStorage = {
        runCatching { FirebaseStorage.getInstance(FirebaseStorageRepository.PROJECT_STORAGE_BUCKET) }
            .getOrElse { FirebaseStorage.getInstance() }
    }
) : StorageGateway {

    override suspend fun putBytesWithProgress(
        storagePath: String,
        bytes: ByteArray,
        contentType: String,
        customMetadata: Map<String, String>,
        requesterUid: String,
        onProgress: (Int) -> Unit
    ) {
        val storage = firebaseStorageProvider().apply {
            maxUploadRetryTimeMillis = 15_000L
            maxOperationRetryTimeMillis = 15_000L
        }
        val ref = storage.reference.child(storagePath)
        val metadataBuilder = StorageMetadata.Builder()
            .setContentType(contentType)
        customMetadata.forEach { (k, v) ->
            metadataBuilder.setCustomMetadata(k, v)
        }

        onProgress(0)
        val uploadTask = ref.putBytes(bytes, metadataBuilder.build())
        uploadTask.addOnProgressListener { snap ->
            val total = snap.totalByteCount
            if (total > 0L) {
                val pct = ((snap.bytesTransferred * 100L) / total).toInt().coerceIn(0, 99)
                onProgress(pct)
            }
        }
        uploadTask.await()
        onProgress(100)
    }

    override suspend fun getBytes(
        storagePath: String,
        maxDownloadBytes: Long,
        requesterUid: String
    ): ByteArray {
        val ref = firebaseStorageProvider().reference.child(storagePath)
        return ref.getBytes(maxDownloadBytes).await()
    }
}

/**
 * Standalone service responsible for all Captain image operations in El-Batal Express:
 * 1. Camera & Gallery image preparation (`createCameraCaptureUri`)
 * 2. File MIME type & maximum size validation
 * 3. Smart EXIF-aware JPEG compression (`ImageCompressor`)
 * 4. Uploading to official Firebase Storage paths with live `%` progress
 * 5. Upload states (Validating / Compressing / Uploading / Retrying / Success / Failed)
 * 6. Automatic + manual retry on internet disconnection
 * 7. Returning confirmed Firebase Storage path ONLY after upload succeeds
 * 8. Permission & connectivity error mapping + strict privacy enforcement (no open public ID links)
 */
class FirebaseStorageRepository(
    private val context: Context?,
    private val gateway: StorageGateway = activeGatewayOverride ?: OfficialFirebaseStorageGateway(),
    private val authUidProvider: AuthUidProvider = AuthUidProvider { FirebaseAuth.getInstance().currentUser?.uid },
    private val connectivityChecker: NetworkConnectivityChecker = DefaultNetworkConnectivityChecker(context),
    private val maxAutoRetries: Int = 3,
    private val retryDelayMs: Long = 600L
) {

    class DefaultNetworkConnectivityChecker(private val context: Context?) : NetworkConnectivityChecker {
        override fun isNetworkConnected(): Boolean {
            val ctx = context ?: return true
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    // Tracks cache busting version per storage path when an image is replaced at the same path (e.g. profile.jpg)
    private val pathVersionCounter = ConcurrentHashMap<String, Long>()

    fun getPathRevision(storagePath: String): Long = pathVersionCounter[normalizeStoragePath(storagePath)] ?: 0L

    /**
     * Creates a secure temporary Uri via FileProvider for capturing a photo with the device camera.
     */
    fun createCameraCaptureUri(targetContext: Context, prefix: String = "capture"): Uri {
        val capturesDir = File(targetContext.cacheDir, "camera_captures").apply { mkdirs() }
        val tempFile = File(capturesDir, "${prefix}_${System.currentTimeMillis()}.jpg")
        val authority = "${targetContext.packageName}.fileprovider"
        return FileProvider.getUriForFile(targetContext, authority, tempFile)
    }

    // --- 1. Captain Profile Photo: "captains/{captainId}/profile/profile.jpg" ---
    suspend fun uploadCaptainProfileImage(
        captainId: String,
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val cleanCaptainId = captainId.trim()
        val authFailure = verifyCaptainOwnership(cleanCaptainId)
        if (authFailure != null) {
            onStateChange(ImageUploadState.Failed(authFailure.message ?: "غير مصرح", authFailure.kind, false))
            return Result.failure(authFailure)
        }
        val path = captainProfilePath(cleanCaptainId)
        return performValidatedUpload(
            imageUri = imageUri,
            storagePath = path,
            customMetadata = mapOf(
                "captainId" to cleanCaptainId,
                "category" to "captain_profile"
            ),
            onStateChange = onStateChange
        )
    }

    // --- 2. National ID Front: "captains/{captainId}/documents/id_front.jpg" ---
    suspend fun uploadCaptainIdFrontImage(
        captainId: String,
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val cleanCaptainId = captainId.trim()
        val authFailure = verifyCaptainOwnership(cleanCaptainId)
        if (authFailure != null) {
            onStateChange(ImageUploadState.Failed(authFailure.message ?: "غير مصرح", authFailure.kind, false))
            return Result.failure(authFailure)
        }
        val path = captainIdFrontPath(cleanCaptainId)
        return performValidatedUpload(
            imageUri = imageUri,
            storagePath = path,
            customMetadata = mapOf(
                "captainId" to cleanCaptainId,
                "documentType" to "id_front",
                "visibility" to "private"
            ),
            onStateChange = onStateChange
        )
    }

    // --- 3. National ID Back: "captains/{captainId}/documents/id_back.jpg" ---
    suspend fun uploadCaptainIdBackImage(
        captainId: String,
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val cleanCaptainId = captainId.trim()
        val authFailure = verifyCaptainOwnership(cleanCaptainId)
        if (authFailure != null) {
            onStateChange(ImageUploadState.Failed(authFailure.message ?: "غير مصرح", authFailure.kind, false))
            return Result.failure(authFailure)
        }
        val path = captainIdBackPath(cleanCaptainId)
        return performValidatedUpload(
            imageUri = imageUri,
            storagePath = path,
            customMetadata = mapOf(
                "captainId" to cleanCaptainId,
                "documentType" to "id_back",
                "visibility" to "private"
            ),
            onStateChange = onStateChange
        )
    }

    // --- Optional Driving License: "captains/{captainId}/documents/license.jpg" ---
    suspend fun uploadCaptainLicenseImage(
        captainId: String,
        imageUri: Uri,
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val cleanCaptainId = captainId.trim()
        val authFailure = verifyCaptainOwnership(cleanCaptainId)
        if (authFailure != null) {
            onStateChange(ImageUploadState.Failed(authFailure.message ?: "غير مصرح", authFailure.kind, false))
            return Result.failure(authFailure)
        }
        val path = captainLicensePath(cleanCaptainId)
        return performValidatedUpload(
            imageUri = imageUri,
            storagePath = path,
            customMetadata = mapOf(
                "captainId" to cleanCaptainId,
                "documentType" to "driving_license",
                "visibility" to "private"
            ),
            onStateChange = onStateChange
        )
    }

    // --- 4. Order Receipt: "orders/{orderId}/receipts/{uniqueFileName}" ---
    suspend fun uploadOrderReceiptImage(
        orderId: String,
        assignedCaptainId: String,
        orderStatus: OrderStatus,
        imageUri: Uri,
        uniqueFileName: String = generateUniqueReceiptFileName(assignedCaptainId),
        onStateChange: (ImageUploadState) -> Unit = {}
    ): Result<String> {
        val cleanOrderId = orderId.trim()
        if (cleanOrderId.isBlank()) {
            val ex = StorageUploadException("معرّف الطلب غير صالح", UploadErrorKind.VALIDATION, false)
            onStateChange(ImageUploadExceptionToFailedState(ex))
            return Result.failure(ex)
        }

        val currentUid = authUidProvider.currentUid()?.trim().orEmpty()
        if (currentUid.isBlank()) {
            val ex = StorageUploadException(
                "يرجى تسجيل الدخول أولاً قبل رفع إيصال الطلب",
                UploadErrorKind.PERMISSION_DENIED,
                false
            )
            onStateChange(ImageUploadExceptionToFailedState(ex))
            return Result.failure(ex)
        }

        // Block uploading receipt to any order NOT assigned to this captain
        if (assignedCaptainId.isBlank() || assignedCaptainId.trim() != currentUid) {
            val ex = StorageUploadException(
                "غير مصرح: لا يمكنك رفع إيصال لطلب غير مسند إليك",
                UploadErrorKind.OWNERSHIP_VIOLATION,
                false
            )
            onStateChange(ImageUploadExceptionToFailedState(ex))
            return Result.failure(ex)
        }

        // Explicit allowlist of order statuses permitted for receipt upload (matches storage.rules 1:1).
        // Any unknown, early (PENDING, ACCEPTED, AT_STORE), EN_ROUTE, or closed (DELIVERED, CANCELLED, EXPIRED) status is rejected.
        if (orderStatus !in ALLOWED_RECEIPT_UPLOAD_STATUSES) {
            val ex = StorageUploadException(
                "لا يمكن رفع أو استبدال إيصال الطلب في الحالة الحالية. يُسمح بالرفع فقط بعد استلام الطلب من التاجر وقبل إغلاق الطلب.",
                UploadErrorKind.ORDER_CLOSED,
                false
            )
            onStateChange(ImageUploadExceptionToFailedState(ex))
            return Result.failure(ex)
        }

        val path = orderReceiptPath(cleanOrderId, uniqueFileName)
        return performValidatedUpload(
            imageUri = imageUri,
            storagePath = path,
            customMetadata = mapOf(
                "orderId" to cleanOrderId,
                "captainId" to currentUid,
                "uploadedBy" to currentUid,
                "category" to "order_receipt"
            ),
            onStateChange = onStateChange
        )
    }

    /**
     * Reads private bytes for a Firebase Storage path while enforcing strict access control:
     * - Never uses open public URLs for private National ID documents.
     * - Blocks reading another captain's `/captains/{otherCaptainId}/documents/...`.
     * - Caches bytes inside a UID-isolated private cache folder (`cacheDir/fb_storage_cache/{uid}/`).
     */
    suspend fun downloadStorageImageBytes(
        storagePath: String,
        forceRefresh: Boolean = false
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val cleanPath = normalizeStoragePath(storagePath)
        val currentUid = authUidProvider.currentUid()?.trim().orEmpty()
        if (currentUid.isBlank()) {
            return@withContext Result.failure(
                StorageUploadException("يجب تسجيل الدخول لعرض هذه الصورة المحمية", UploadErrorKind.PERMISSION_DENIED, false)
            )
        }

        // Enforce client-side privacy boundary matching storage.rules for private documents
        if (cleanPath.startsWith("captains/")) {
            val segments = cleanPath.split("/")
            val targetCaptainId = segments.getOrNull(1).orEmpty()
            val subFolder = segments.getOrNull(2).orEmpty()
            if (subFolder == "documents" && targetCaptainId != currentUid) {
                return@withContext Result.failure(
                    StorageUploadException(
                        "غير مصرح: لا يمكنك الاطلاع على مستندات أو بطاقة كابتن آخر",
                        UploadErrorKind.OWNERSHIP_VIOLATION,
                        false
                    )
                )
            }
        }

        val cacheFile = getUidScopedCacheFile(currentUid, cleanPath)
        if (!forceRefresh && cacheFile != null && cacheFile.exists() && cacheFile.length() > 0L) {
            return@withContext Result.success(cacheFile.readBytes())
        }

        try {
            val bytes = gateway.getBytes(cleanPath, MAX_DOWNLOAD_BYTES, currentUid)
            if (bytes.isEmpty()) {
                return@withContext Result.failure(
                    StorageUploadException("بيانات الصورة المسترجعة فارغة", UploadErrorKind.UNKNOWN, true)
                )
            }
            cacheFile?.apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            Result.success(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapToStorageUploadException(e))
        }
    }

    /**
     * Resolves any image reference (legacy https ImgBB URL, local content/file preview Uri,
     * or private Firebase Storage path) into a model ready for Coil rendering.
     */
    suspend fun resolveDisplayModel(pathOrUrl: String): Result<Any> {
        val trimmed = pathOrUrl.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("مسار الصورة فارغ"))
        }
        if (isLegacyOrLocalDirectUri(trimmed)) {
            return Result.success(trimmed)
        }
        return downloadStorageImageBytes(trimmed).map { it as Any }
    }

    fun clearSessionMemoryCache() {
        // Keeps persistent Firestore paths intact while wiping in-memory state and private cached bytes on logout
        pathVersionCounter.clear()
        context?.cacheDir?.let { cacheRoot ->
            runCatching { File(cacheRoot, "fb_storage_cache").deleteRecursively() }
            runCatching { File(cacheRoot, "camera_captures").deleteRecursively() }
        }
    }

    private fun verifyCaptainOwnership(captainId: String): StorageUploadException? {
        if (captainId.isBlank()) {
            return StorageUploadException("معرّف الكابتن غير صالح", UploadErrorKind.VALIDATION, false)
        }
        val currentUid = authUidProvider.currentUid()?.trim().orEmpty()
        if (currentUid.isBlank()) {
            return StorageUploadException("يرجى تسجيل الدخول أولاً قبل رفع الصور", UploadErrorKind.PERMISSION_DENIED, false)
        }
        if (currentUid != captainId) {
            return StorageUploadException(
                "غير مصرح: لا يمكنك رفع أو تعديل صور تخص كابتن آخر",
                UploadErrorKind.OWNERSHIP_VIOLATION,
                false
            )
        }
        return null
    }

    private suspend fun performValidatedUpload(
        imageUri: Uri,
        storagePath: String,
        customMetadata: Map<String, String>,
        onStateChange: (ImageUploadState) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext Result.failure(
            StorageUploadException("سياق التطبيق غير متوفر لمعالجة الصورة", UploadErrorKind.UNKNOWN, false)
        )
        val requesterUid = authUidProvider.currentUid()?.trim().orEmpty()
        if (requesterUid.isBlank()) {
            val ex = StorageUploadException("يرجى تسجيل الدخول أولاً", UploadErrorKind.PERMISSION_DENIED, false)
            onStateChange(ImageUploadExceptionToFailedState(ex))
            return@withContext Result.failure(ex)
        }

        try {
            // 1. Validate MIME type & file size
            onStateChange(ImageUploadState.Validating)
            val (mimeType, rawSize) = validateAndInspectUri(ctx, imageUri)

            // 2. Compress image if needed
            onStateChange(ImageUploadState.Compressing)
            val uploadBytes = ImageCompressor.compressIfNeeded(
                context = ctx,
                uri = imageUri,
                mimeType = mimeType,
                rawSizeBytes = rawSize
            )
            if (uploadBytes.size > MAX_UPLOAD_COMPRESSED_BYTES) {
                val tooLargeEx = StorageUploadException(
                    "حجم الصورة بعد الضغط لا يزال كبيراً جداً (الحد الأقصى 10 ميجابايت)",
                    UploadErrorKind.VALIDATION,
                    false
                )
                onStateChange(ImageUploadExceptionToFailedState(tooLargeEx))
                return@withContext Result.failure(tooLargeEx)
            }

            // 3. Upload with automatic retry on transient network failure
            val cleanPath = normalizeStoragePath(storagePath)
            var attempt = 1
            var lastUploadError: StorageUploadException? = null

            while (attempt <= maxAutoRetries) {
                if (!connectivityChecker.isNetworkConnected()) {
                    val offlineEx = StorageUploadException(
                        "انقطع الاتصال بالإنترنت. يرجى التحقق من الشبكة وإعادة المحاولة.",
                        UploadErrorKind.NETWORK_DISCONNECTED,
                        true
                    )
                    lastUploadError = offlineEx
                    if (attempt < maxAutoRetries) {
                        onStateChange(
                            ImageUploadState.Retrying(
                                attempt = attempt,
                                maxAttempts = maxAutoRetries,
                                reason = "في انتظار استعادة الاتصال بالإنترنت..."
                            )
                        )
                        delay(retryDelayMs * attempt)
                        attempt++
                        continue
                    } else {
                        break
                    }
                }

                try {
                    onStateChange(ImageUploadState.Uploading(0))
                    gateway.putBytesWithProgress(
                        storagePath = cleanPath,
                        bytes = uploadBytes,
                        contentType = "image/jpeg",
                        customMetadata = customMetadata,
                        requesterUid = requesterUid,
                        onProgress = { pct ->
                            onStateChange(ImageUploadState.Uploading(pct.coerceIn(0, 100)))
                        }
                    )

                    // Atomically overwrite UID-scoped private cache so replaced photos update immediately without leaking old bytes
                    getUidScopedCacheFile(requesterUid, cleanPath)?.let { targetCache ->
                        targetCache.parentFile?.mkdirs()
                        val tempCache = File(targetCache.parentFile, "${targetCache.name}.tmp")
                        tempCache.writeBytes(uploadBytes)
                        if (!tempCache.renameTo(targetCache)) {
                            targetCache.writeBytes(uploadBytes)
                            tempCache.delete()
                        }
                    }
                    // Clean up temporary camera capture file if source was captured inside cacheDir/camera_captures
                    runCatching {
                        val rawPath = imageUri.path.orEmpty()
                        if (rawPath.contains("/camera_captures/")) {
                            File(rawPath).takeIf { it.exists() }?.delete()
                        }
                    }
                    pathVersionCounter[cleanPath] = System.currentTimeMillis()

                    val now = System.currentTimeMillis()
                    onStateChange(ImageUploadState.Success(cleanPath, now))
                    return@withContext Result.success(cleanPath)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val mapped = mapToStorageUploadException(e)
                    lastUploadError = mapped
                    if (mapped.isRetryable && attempt < maxAutoRetries) {
                        onStateChange(
                            ImageUploadState.Retrying(
                                attempt = attempt,
                                maxAttempts = maxAutoRetries,
                                reason = mapped.message ?: "جاري إعادة المحاولة..."
                            )
                        )
                        delay(retryDelayMs * attempt)
                        attempt++
                    } else {
                        break
                    }
                }
            }

            val finalError = lastUploadError ?: StorageUploadException(
                "تعذر رفع الصورة إلى Firebase Storage. يرجى المحاولة مرة أخرى.",
                UploadErrorKind.UNKNOWN,
                true
            )
            onStateChange(ImageUploadExceptionToFailedState(finalError))
            Result.failure(finalError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageUploadException) {
            onStateChange(ImageUploadExceptionToFailedState(e))
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            val valEx = StorageUploadException(
                e.message ?: "ملف الصورة غير صالح",
                UploadErrorKind.VALIDATION,
                false,
                e
            )
            onStateChange(ImageUploadExceptionToFailedState(valEx))
            Result.failure(valEx)
        } catch (e: Exception) {
            val mapped = mapToStorageUploadException(e)
            onStateChange(ImageUploadExceptionToFailedState(mapped))
            Result.failure(mapped)
        }
    }

    private fun validateAndInspectUri(context: Context, uri: Uri): Pair<String, Long> {
        val detectedMime = resolveMimeType(context, uri)
        if (detectedMime !in ALLOWED_IMAGE_MIME_TYPES) {
            throw StorageUploadException(
                "نوع الملف غير مدعوم ($detectedMime). يرجى اختيار صورة بصيغة JPG أو PNG أو WEBP.",
                UploadErrorKind.VALIDATION,
                false
            )
        }

        val sizeBytes = resolveFileSizeBytes(context, uri)
        if (sizeBytes <= 0L) {
            throw StorageUploadException(
                "ملف الصورة المختار فارغ أو تعذر الوصول إليه.",
                UploadErrorKind.VALIDATION,
                false
            )
        }
        if (sizeBytes > MAX_RAW_FILE_BYTES) {
            throw StorageUploadException(
                "حجم الصورة كبير جداً (الحد الأقصى المسموح 15 ميجابايت).",
                UploadErrorKind.VALIDATION,
                false
            )
        }
        return detectedMime to sizeBytes
    }

    private fun resolveMimeType(context: Context, uri: Uri): String {
        val resolverType = context.contentResolver.getType(uri)?.lowercase()?.trim()
        if (!resolverType.isNullOrBlank()) return resolverType

        val path = uri.path.orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.lowercase() ?: "application/octet-stream"
        }
    }

    private fun resolveFileSizeBytes(context: Context, uri: Uri): Long {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val f = uri.path?.let { File(it) }
            if (f != null && f.exists()) return f.length()
        }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val s = cursor.getLong(idx)
                        if (s > 0L) return s
                    }
                }
            }
        } catch (_: Exception) {}

        return ImageCompressor.openUriInputStream(context, uri)?.use { stream ->
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val r = stream.read(buf)
                if (r <= 0) break
                total += r
                if (total > MAX_RAW_FILE_BYTES + 1) break
            }
            total
        } ?: 0L
    }

    private fun getUidScopedCacheFile(uid: String, cleanStoragePath: String): File? {
        val ctx = context ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cleanStoragePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val safeUid = uid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(File(ctx.cacheDir, "fb_storage_cache/$safeUid"), "$digest.jpg")
    }

    private fun mapToStorageUploadException(error: Throwable): StorageUploadException {
        if (error is StorageUploadException) return error
        if (error is SecurityException) {
            return StorageUploadException(
                error.message ?: "غير مصرح بالوصول إلى هذه الصورة المحمية",
                UploadErrorKind.PERMISSION_DENIED,
                false,
                error
            )
        }
        if (error is StorageException) {
            return when (error.errorCode) {
                StorageException.ERROR_NOT_AUTHENTICATED -> StorageUploadException(
                    "انتهت صلاحية الجلسة، يرجى إعادة تسجيل الدخول والمحاولة مرة أخرى.",
                    UploadErrorKind.PERMISSION_DENIED,
                    false,
                    error
                )
                StorageException.ERROR_NOT_AUTHORIZED -> StorageUploadException(
                    "ليس لديك صلاحية للوصول إلى هذا المسار أو تعديله في Firebase Storage.",
                    UploadErrorKind.PERMISSION_DENIED,
                    false,
                    error
                )
                StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> StorageUploadException(
                    "انتهت مهلة الاتصال بالخادم بسبب ضعف الإنترنت. يرجى المحاولة مجدداً.",
                    UploadErrorKind.NETWORK_DISCONNECTED,
                    true,
                    error
                )
                StorageException.ERROR_QUOTA_EXCEEDED -> StorageUploadException(
                    "تم تجاوز الحصة التخزينية المسموحة مؤقتاً.",
                    UploadErrorKind.UNKNOWN,
                    false,
                    error
                )
                else -> StorageUploadException(
                    "حدث خطأ في خدمة التخزين السحابي: ${error.localizedMessage ?: "يرجى المحاولة لاحقاً"}",
                    UploadErrorKind.NETWORK_DISCONNECTED,
                    true,
                    error
                )
            }
        }
        if (error is IOException) {
            return StorageUploadException(
                "انقطع الاتصال بالإنترنت أثناء نقل الصورة. يرجى التحقق من الشبكة وإعادة المحاولة.",
                UploadErrorKind.NETWORK_DISCONNECTED,
                true,
                error
            )
        }
        val msgLower = (error.message ?: "").lowercase()
        if (msgLower.contains("network") || msgLower.contains("offline") || msgLower.contains("timeout") || msgLower.contains("unreachable")) {
            return StorageUploadException(
                "تعذر الاتصال بالإنترنت أثناء رفع الصورة. يرجى إعادة المحاولة عند استقرار الشبكة.",
                UploadErrorKind.NETWORK_DISCONNECTED,
                true,
                error
            )
        }
        return StorageUploadException(
            error.localizedMessage ?: "فشل رفع الصورة، يرجى المحاولة مرة أخرى.",
            UploadErrorKind.UNKNOWN,
            true,
            error
        )
    }

    private fun ImageUploadExceptionToFailedState(ex: StorageUploadException): ImageUploadState.Failed {
        return ImageUploadState.Failed(
            errorMessage = ex.message ?: "فشل رفع الصورة",
            kind = ex.kind,
            isRetryable = ex.isRetryable
        )
    }

    companion object {
        const val PROJECT_ID = "el-batal-express-956ae"
        const val PROJECT_STORAGE_BUCKET = "gs://$PROJECT_ID.firebasestorage.app"
        const val MAX_RAW_FILE_BYTES = 15L * 1024L * 1024L // 15 MB raw selection limit
        const val MAX_UPLOAD_COMPRESSED_BYTES = 10 * 1024 * 1024 // 10 MB Storage rule limit
        const val MAX_DOWNLOAD_BYTES = 10L * 1024L * 1024L

        val ALLOWED_RECEIPT_UPLOAD_STATUSES = setOf(
            OrderStatus.PICKED_UP,
            OrderStatus.MERCHANT_REJECTED,
            OrderStatus.AWAITING_MERCHANT_CONFIRMATION
        )

        val ALLOWED_IMAGE_MIME_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
        )

        @Volatile
        var activeGatewayOverride: StorageGateway? = null

        @Volatile
        private var sharedInstance: FirebaseStorageRepository? = null

        fun getInstance(context: Context?): FirebaseStorageRepository {
            val current = sharedInstance
            if (current != null && activeGatewayOverride == null) {
                return current
            }
            if (activeGatewayOverride != null) {
                return FirebaseStorageRepository(context?.applicationContext ?: context)
            }
            return synchronized(this) {
                sharedInstance ?: FirebaseStorageRepository(context?.applicationContext ?: context).also {
                    if (context != null) sharedInstance = it
                }
            }
        }

        fun captainProfilePath(captainId: String): String =
            "captains/${captainId.trim()}/profile/profile.jpg"

        fun captainIdFrontPath(captainId: String): String =
            "captains/${captainId.trim()}/documents/id_front.jpg"

        fun captainIdBackPath(captainId: String): String =
            "captains/${captainId.trim()}/documents/id_back.jpg"

        fun captainLicensePath(captainId: String): String =
            "captains/${captainId.trim()}/documents/license.jpg"

        fun orderReceiptPath(orderId: String, uniqueFileName: String): String =
            "orders/${orderId.trim()}/receipts/${uniqueFileName.trim()}"

        fun generateUniqueReceiptFileName(captainId: String): String {
            val shortUid = captainId.trim().takeLast(6).ifBlank { "capt" }
            val shortRand = UUID.randomUUID().toString().replace("-", "").take(8)
            return "receipt_${shortUid}_${System.currentTimeMillis()}_$shortRand.jpg"
        }

        fun isLegacyOrLocalDirectUri(pathOrUrl: String): Boolean {
            val lower = pathOrUrl.trim().lowercase()
            return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("content://") ||
                lower.startsWith("file://") ||
                lower.startsWith("android.resource://")
        }

        fun isFirebaseStoragePath(pathOrUrl: String): Boolean {
            val trimmed = pathOrUrl.trim()
            return trimmed.isNotBlank() && !isLegacyOrLocalDirectUri(trimmed)
        }

        fun normalizeStoragePath(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.startsWith("gs://")) {
                val withoutScheme = trimmed.removePrefix("gs://")
                val slashIdx = withoutScheme.indexOf('/')
                return if (slashIdx >= 0) withoutScheme.substring(slashIdx + 1).trimStart('/') else ""
            }
            return trimmed.trimStart('/')
        }
    }
}
