package com.example.data.remote

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Typed exception representing a Firebase Cloud Functions callable or connectivity failure.
 * Never falls back to direct Firestore writes when server calls fail.
 */
class CloudFunctionException(
    message: String,
    val httpCode: Int = 0,
    val functionCode: String = "",
    val isRetryable: Boolean = true,
    val isNotFound: Boolean = false,
    val isEndpointNotFound: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Injectable gateway for invoking Firebase `onCall` Cloud Functions via the official
 * Firebase Functions SDK (`FirebaseFunctions.getHttpsCallable(...)`).
 */
interface OrderCallableGateway {
    suspend fun callFunction(
        functionName: String,
        payload: Map<String, Any?>
    ): Map<String, Any?>
}

private class DefaultFirebaseFunctionsGateway(
    private val region: String = CloudFunctionsOrderClient.FUNCTIONS_REGION
) : OrderCallableGateway {
    private val functions: FirebaseFunctions? by lazy {
        runCatching {
            val app = FirebaseApp.getInstance()
            FirebaseFunctions.getInstance(app, region)
        }.getOrNull()
    }

    override suspend fun callFunction(
        functionName: String,
        payload: Map<String, Any?>
    ): Map<String, Any?> {
        val instance = functions ?: throw IllegalStateException(
            "Firebase Functions SDK is not initialized"
        )
        val result = instance.getHttpsCallable(functionName).call(payload).await()
        @Suppress("UNCHECKED_CAST")
        return (result.getData() as? Map<String, Any?>).orEmpty()
    }
}

/**
 * Server-authoritative client for invoking Firebase `onCall` Cloud Functions
 * on project `el-batal-express-956ae` (region `us-central1`).
 *
 * Security invariants:
 * - Uses official Firebase Functions SDK (`getHttpsCallable`) which attaches the verified
 *   Firebase Auth ID Token automatically. Zero raw HTTP/OkHttp calls to callable endpoints.
 * - Zero direct Firestore mutations or fallback writes on `orders` or financial subcollections.
 * - Never trusts client-submitted `captainId`, delivery fees, commissions, or timestamps.
 */
class CloudFunctionsOrderClient(
    private val firebaseAuth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull(),
    private val callableGateway: OrderCallableGateway = DefaultFirebaseFunctionsGateway(),
    private val authUidProvider: (() -> String?)? = null
) {

    private fun resolveAuthenticatedUid(): Result<String> {
        val uid = authUidProvider?.invoke()?.trim()
            ?: runCatching { firebaseAuth?.currentUser?.uid?.trim() }.getOrNull()
        return if (!uid.isNullOrBlank()) {
            Result.success(uid)
        } else {
            Result.failure(
                CloudFunctionException(
                    message = "يجب تسجيل الدخول أولاً لتنفيذ هذه العملية.",
                    httpCode = 401,
                    functionCode = "UNAUTHENTICATED",
                    isRetryable = false
                )
            )
        }
    }

    private suspend fun invokeAuthenticatedCallable(
        functionName: String,
        orderDocIdForLog: String,
        defaultClientErrorMsg: String,
        payload: Map<String, Any?>
    ): Result<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val uid = resolveAuthenticatedUid().getOrElse { err ->
            return@withContext Result.failure(err)
        }

        try {
            val responseData = callableGateway.callFunction(functionName, payload)
            Result.success(responseData)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudFunctionException) {
            Log.w(
                TAG,
                "Callable '$functionName' failed [uid=$uid, orderId=$orderDocIdForLog, code=${e.functionCode}]"
            )
            Result.failure(e)
        } catch (e: FirebaseFunctionsException) {
            Log.w(
                TAG,
                "Callable '$functionName' returned ${e.code.name} [uid=$uid, orderId=$orderDocIdForLog]"
            )
            Result.failure(mapFirebaseFunctionsException(e, defaultClientErrorMsg))
        } catch (e: IOException) {
            Log.w(
                TAG,
                "Network error calling '$functionName' [uid=$uid, orderId=$orderDocIdForLog]",
                e
            )
            Result.failure(
                CloudFunctionException(
                    message = "تعذر الاتصال بالخادم لتنفيذ العملية. يرجى التحقق من اتصال الإنترنت وإعادة المحاولة.",
                    httpCode = 0,
                    functionCode = "UNAVAILABLE",
                    isRetryable = true,
                    cause = e
                )
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unexpected failure calling '$functionName' [uid=$uid, orderId=$orderDocIdForLog]",
                e
            )
            Result.failure(
                CloudFunctionException(
                    message = e.message?.takeIf { it.isNotBlank() } ?: defaultClientErrorMsg,
                    httpCode = 500,
                    functionCode = "INTERNAL",
                    isRetryable = true,
                    cause = e
                )
            )
        }
    }

    private fun mapFirebaseFunctionsException(
        e: FirebaseFunctionsException,
        defaultClientErrorMsg: String
    ): CloudFunctionException {
        val rawServerMsg = e.message?.trim().orEmpty()
        val serverMsg = rawServerMsg.takeIf {
            it.isNotBlank() &&
                !it.equals(e.code.name, ignoreCase = true) &&
                !it.startsWith("INTERNAL", ignoreCase = true)
        }

        val isOrderGone = e.code == FirebaseFunctionsException.Code.NOT_FOUND ||
            e.code == FirebaseFunctionsException.Code.ALREADY_EXISTS ||
            (e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
                (rawServerMsg.contains("لم يعد متاحاً") ||
                    rawServerMsg.contains("غير موجود") ||
                    rawServerMsg.contains("تم حذفه") ||
                    rawServerMsg.contains("بواسطة كابتن آخر") ||
                    rawServerMsg.contains("انتهت مهلة")))

        val isUnimplemented = e.code == FirebaseFunctionsException.Code.UNIMPLEMENTED

        val isRetryable = when (e.code) {
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.INTERNAL,
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            FirebaseFunctionsException.Code.ABORTED,
            FirebaseFunctionsException.Code.UNKNOWN,
            FirebaseFunctionsException.Code.UNIMPLEMENTED -> true
            else -> false
        }

        val httpCode = when (e.code) {
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> 401
            FirebaseFunctionsException.Code.PERMISSION_DENIED -> 403
            FirebaseFunctionsException.Code.NOT_FOUND -> 404
            FirebaseFunctionsException.Code.ALREADY_EXISTS,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.ABORTED -> 409
            FirebaseFunctionsException.Code.INVALID_ARGUMENT -> 400
            FirebaseFunctionsException.Code.UNIMPLEMENTED -> 404
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> 504
            FirebaseFunctionsException.Code.UNAVAILABLE -> 503
            else -> 500
        }

        val localizedMessage = when {
            isUnimplemented ->
                "خدمة الخادم لقبول وتنفيذ الطلبات غير منشورة حالياً. يرجى نشر Cloud Functions على المشروع ثم إعادة المحاولة."
            !serverMsg.isNullOrBlank() -> serverMsg
            e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                "يجب تسجيل الدخول أولاً لتنفيذ هذه العملية."
            e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                "غير مصرح لك بتنفيذ هذه العملية على هذا الطلب."
            e.code == FirebaseFunctionsException.Code.NOT_FOUND ->
                "الطلب غير موجود أو لم يعد متاحاً على الخادم."
            e.code == FirebaseFunctionsException.Code.ALREADY_EXISTS ->
                "عذراً، تم قبول هذا الطلب بالفعل بواسطة كابتن آخر."
            e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                "لا يمكن تنفيذ العملية على الطلب في حالته الحالية."
            e.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                "تعذر الاتصال بخدمة الطلبات على الخادم. يرجى التحقق من الإنترنت وإعادة المحاولة."
            else -> defaultClientErrorMsg
        }

        return CloudFunctionException(
            message = localizedMessage,
            httpCode = httpCode,
            functionCode = e.code.name,
            isRetryable = isRetryable,
            isNotFound = isOrderGone && !isUnimplemented,
            isEndpointNotFound = isUnimplemented,
            cause = e
        )
    }

    /**
     * Sanitizes order IDs by removing leading hash `#` characters and extraneous whitespace.
     */
    fun sanitizeOrderId(raw: String): String = raw.trim().trimStart('#').trim()

    /**
     * 1. acceptOrder callable (Server-only; zero direct Firestore fallback)
     */
    suspend fun acceptDeliveryOffer(orderDocId: String, offerId: String = ""): Result<String> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException(
                    message = "معرّف الطلب غير صالح",
                    httpCode = 400,
                    functionCode = "INVALID_ARGUMENT",
                    isRetryable = false
                )
            )
        }
        val payload = buildMap<String, Any?> {
            put("orderId", cleanId)
            if (offerId.isNotBlank()) {
                put("offerId", offerId)
            }
        }
        return invokeAuthenticatedCallable(
            functionName = "acceptOrder",
            orderDocIdForLog = cleanId,
            defaultClientErrorMsg = "تعذر قبول الطلب من الخادم",
            payload = payload
        ).map { data ->
            (data["message"] as? String)?.takeIf { it.isNotBlank() } ?: "تم قبول الطلب بنجاح"
        }
    }

    /**
     * 2. captainArrivedAtMerchant callable (Server-only; zero direct Firestore fallback)
     */
    suspend fun captainArrivedAtMerchant(orderDocId: String): Result<String> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException(
                    message = "معرّف الطلب غير صالح",
                    httpCode = 400,
                    functionCode = "INVALID_ARGUMENT",
                    isRetryable = false
                )
            )
        }
        return invokeAuthenticatedCallable(
            functionName = "captainArrivedAtMerchant",
            orderDocIdForLog = cleanId,
            defaultClientErrorMsg = "تعذر تسجيل الوصول إلى التاجر عبر الخادم",
            payload = mapOf("orderId" to cleanId)
        ).map { data ->
            (data["message"] as? String)?.takeIf { it.isNotBlank() }
                ?: "تم تسجيل الوصول إلى التاجر بنجاح"
        }
    }

    /**
     * 3. confirmPickup callable (Server-only; zero direct Firestore fallback)
     */
    suspend fun confirmPickup(orderDocId: String): Result<String> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException(
                    message = "معرّف الطلب غير صالح",
                    httpCode = 400,
                    functionCode = "INVALID_ARGUMENT",
                    isRetryable = false
                )
            )
        }
        return invokeAuthenticatedCallable(
            functionName = "confirmPickup",
            orderDocIdForLog = cleanId,
            defaultClientErrorMsg = "تعذر تأكيد استلام الطلب من الخادم",
            payload = mapOf("orderId" to cleanId)
        ).map { data ->
            (data["message"] as? String)?.takeIf { it.isNotBlank() }
                ?: "تم تأكيد استلام الطلب من التاجر بنجاح"
        }
    }

    /**
     * 4. submitCustomerDeliveryDetails callable (Server-only; zero direct Firestore fallback)
     */
    suspend fun submitCustomerDeliveryDetails(
        orderDocId: String,
        customerName: String,
        customerPhone: String,
        receiptImagePath: String,
        notes: String
    ): Result<String> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank() || receiptImagePath.isBlank()) {
            return Result.failure(
                CloudFunctionException(
                    message = "بيانات الطلب أو مسار الإيصال غير مكتملة",
                    httpCode = 400,
                    functionCode = "INVALID_ARGUMENT",
                    isRetryable = false
                )
            )
        }
        val payload = mapOf(
            "orderId" to cleanId,
            "customerName" to customerName,
            "customerPhone" to customerPhone,
            "receiptImagePath" to receiptImagePath,
            "notes" to notes
        )
        return invokeAuthenticatedCallable(
            functionName = "submitCustomerDeliveryDetails",
            orderDocIdForLog = cleanId,
            defaultClientErrorMsg = "رفض الخادم حفظ بيانات العميل والإيصال: تحقق من ملكية الطلب وحالته",
            payload = payload
        ).map { data ->
            (data["message"] as? String)?.takeIf { it.isNotBlank() }
                ?: "تم إرسال بيانات العميل والإيصال وبانتظار تأكيد التاجر"
        }
    }

    /**
     * 5. confirmDelivery callable (Server-only; server calculates earnings & updates wallet atomically)
     */
    suspend fun confirmDelivery(orderDocId: String): Result<Double> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException(
                    message = "معرّف الطلب غير صالح",
                    httpCode = 400,
                    functionCode = "INVALID_ARGUMENT",
                    isRetryable = false
                )
            )
        }
        return invokeAuthenticatedCallable(
            functionName = "confirmDelivery",
            orderDocIdForLog = cleanId,
            defaultClientErrorMsg = "تعذر تأكيد تسليم الطلب عبر الخادم",
            payload = mapOf("orderId" to cleanId)
        ).map { data ->
            val earned = (data["earnedAmount"] as? Number)?.toDouble()
            earned?.takeIf { it > 0.0 } ?: 0.0
        }
    }

    /**
     * 6. expireDeliveryOffer / decline offer (Server-verified deadline & reassignment)
     */
    suspend fun expireDeliveryOffer(orderDocId: String, reason: String = "expired"): Result<Unit> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException(
                    message = "معرّف الطلب غير صالح",
                    httpCode = 400,
                    functionCode = "INVALID_ARGUMENT",
                    isRetryable = false
                )
            )
        }
        return invokeAuthenticatedCallable(
            functionName = "expireDeliveryOffer",
            orderDocIdForLog = cleanId,
            defaultClientErrorMsg = "تعذر إبلاغ الخادم بانتهاء عرض الطلب",
            payload = mapOf(
                "orderId" to cleanId,
                "reason" to reason
            )
        ).map { }
    }

    /**
     * 7. resolveCaptainEmailByPhone callable (Read-only pre-login phone lookup)
     */
    suspend fun resolveEmailByPhone(phone: String, variants: List<String> = emptyList()): String? =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildMap<String, Any?> {
                    put("phone", phone)
                    if (variants.isNotEmpty()) {
                        put("variants", variants)
                    }
                }
                val data = callableGateway.callFunction("resolveCaptainEmailByPhone", payload)
                val em = (data["email"] as? String)?.trim()
                if (!em.isNullOrBlank() && em.contains("@")) em else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Phone lookup callable notice: ${e.message}")
                null
            }
        }

    companion object {
        private const val TAG = "CloudFunctionsClient"
        const val PROJECT_ID = "el-batal-express-956ae"
        const val FUNCTIONS_REGION = "us-central1"
    }
}
