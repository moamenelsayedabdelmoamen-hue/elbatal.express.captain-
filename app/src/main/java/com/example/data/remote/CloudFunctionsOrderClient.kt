package com.example.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Typed exception representing a Cloud Functions HTTP or connectivity failure.
 * Never falls back to direct Firestore writes when server calls fail.
 */
class CloudFunctionException(
    message: String,
    val httpCode: Int = 0,
    val isRetryable: Boolean = true,
    val isNotFound: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Server-authoritative client for invoking secure Cloud Functions for the El-Batal Express Order Lifecycle.
 *
 * Security invariants:
 * - Zero direct Firestore mutations or fallback transactions on `orders` or financial subcollections.
 * - All sensitive order state transitions (accept, arrive, pickup, receipt details, complete delivery, expire/decline)
 *   execute exclusively through authenticated HTTPS Cloud Functions on `el-batal-express-956ae`.
 * - Network/server errors return `Result.failure(CloudFunctionException(..., isRetryable = true))` so the UI
 *   surfaces a clear Arabic error message with Retry without faking local success.
 */
class CloudFunctionsOrderClient(
    private val firebaseAuth: FirebaseAuth? = runCatching { FirebaseAuth.getInstance() }.getOrNull(),
    private val baseUrl: String = DEFAULT_FUNCTIONS_BASE_URL,
    private val httpClient: OkHttpClient = buildDefaultHttpClient(),
    private val authCredentialsProvider: (suspend () -> Pair<String, String>?)? = null
) {

    private suspend fun resolveAuthCredentials(): Result<Pair<String, String>> {
        if (authCredentialsProvider != null) {
            val custom = authCredentialsProvider.invoke()
            return if (custom != null && custom.first.isNotBlank() && custom.second.isNotBlank()) {
                Result.success(custom)
            } else {
                Result.failure(
                    CloudFunctionException(
                        message = "يجب تسجيل الدخول أولاً لتنفيذ هذه العملية.",
                        httpCode = 401,
                        isRetryable = false
                    )
                )
            }
        }

        val user = firebaseAuth?.currentUser
            ?: return Result.failure(
                CloudFunctionException(
                    message = "يجب تسجيل الدخول أولاً لتنفيذ هذه العملية.",
                    httpCode = 401,
                    isRetryable = false
                )
            )

        return try {
            val token = user.getIdToken(false).await()?.token
            if (token.isNullOrBlank()) {
                Result.failure(
                    CloudFunctionException(
                        message = "تعذر التحقق من صلاحية الجلسة مع الخادم. يرجى التحقق من الإنترنت وإعادة المحاولة.",
                        httpCode = 0,
                        isRetryable = true
                    )
                )
            } else {
                Result.success(user.uid to token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to obtain Firebase ID token for Cloud Function call", e)
            Result.failure(
                CloudFunctionException(
                    message = "تعذر الاتصال بالخادم للتحقق من الجلسة. يرجى التحقق من اتصال الإنترنت وإعادة المحاولة.",
                    httpCode = 0,
                    isRetryable = true,
                    cause = e
                )
            )
        }
    }

    private suspend fun postAuthenticatedFunction(
        functionName: String,
        defaultClientErrorMsg: String,
        payloadBuilder: JSONObject.(uid: String) -> Unit
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val (uid, idToken) = resolveAuthCredentials().getOrElse { err ->
            return@withContext Result.failure(err)
        }

        val normalizedBase = baseUrl.trimEnd('/')
        val endpoint = "$normalizedBase/$functionName"
        val payload = JSONObject().apply { payloadBuilder(uid) }

        try {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $idToken")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                val parsedJson = if (bodyStr.isNotBlank()) {
                    runCatching { JSONObject(bodyStr) }.getOrNull()
                } else {
                    null
                }

                if (response.isSuccessful) {
                    return@withContext Result.success(parsedJson ?: JSONObject())
                }

                val serverErrorMsg = parsedJson?.optString("error")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: parsedJson?.optString("message")?.trim()?.takeIf { it.isNotEmpty() }

                val code = response.code
                val notFound = code == 404 || code == 410 ||
                    serverErrorMsg?.contains("الطلب غير موجود") == true ||
                    serverErrorMsg?.contains("لم يعد متاحاً") == true
                val isRetryable = if (notFound) false else (code >= 500 || code == 408 || code == 429)
                val resolvedMessage = when {
                    !serverErrorMsg.isNullOrBlank() -> serverErrorMsg
                    notFound -> "الطلب غير موجود أو لم يعد متاحاً على الخادم."
                    isRetryable -> "تعذر إتمام العملية بسبب خطأ في الاتصال بالخادم ($code). يرجى إعادة المحاولة."
                    code == 401 || code == 403 -> "غير مصرح لك بتنفيذ هذه العملية على هذا الطلب."
                    code == 409 -> "تم تحديث حالة الطلب بالفعل ولم يعد متاحاً لهذه العملية."
                    else -> defaultClientErrorMsg
                }

                return@withContext Result.failure(
                    CloudFunctionException(
                        message = resolvedMessage,
                        httpCode = code,
                        isRetryable = isRetryable,
                        isNotFound = notFound
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Network failure calling Cloud Function $functionName", e)
            Result.failure(
                CloudFunctionException(
                    message = "تعذر الاتصال بالخادم لتنفيذ العملية. يرجى التحقق من اتصال الإنترنت وإعادة المحاولة.",
                    httpCode = 0,
                    isRetryable = true,
                    cause = e
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error calling Cloud Function $functionName", e)
            Result.failure(
                CloudFunctionException(
                    message = "حدث خطأ أثناء الاتصال بالخادم. يرجى إعادة المحاولة.",
                    httpCode = 0,
                    isRetryable = true,
                    cause = e
                )
            )
        }
    }

    /**
     * Sanitizes order IDs by removing leading hash `#` characters and extraneous whitespace.
     */
    fun sanitizeOrderId(raw: String): String = raw.trim().trimStart('#').trim()

    /**
     * 1. acceptDeliveryOffer (Server-only; zero direct Firestore fallback)
     */
    suspend fun acceptDeliveryOffer(orderDocId: String, offerId: String = ""): Result<String> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException("معرّف الطلب غير صالح", httpCode = 400, isRetryable = false)
            )
        }
        return postAuthenticatedFunction(
            functionName = "acceptDeliveryOffer",
            defaultClientErrorMsg = "تعذر قبول الطلب من الخادم"
        ) {
            put("orderId", cleanId)
            if (cleanId != orderDocId.trim()) {
                put("rawOrderId", orderDocId.trim())
            }
            if (offerId.isNotBlank()) {
                put("offerId", offerId)
            }
        }.map { json ->
            json.optString("message").takeIf { it.isNotBlank() } ?: "تم قبول الطلب بنجاح"
        }
    }

    /**
     * 2. captainArrivedAtMerchant (Server-only; zero direct Firestore fallback)
     */
    suspend fun captainArrivedAtMerchant(orderDocId: String): Result<String> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException("معرّف الطلب غير صالح", httpCode = 400, isRetryable = false)
            )
        }
        return postAuthenticatedFunction(
            functionName = "captainArrivedAtMerchant",
            defaultClientErrorMsg = "تعذر تسجيل الوصول إلى التاجر عبر الخادم"
        ) {
            put("orderId", cleanId)
            if (cleanId != orderDocId.trim()) {
                put("rawOrderId", orderDocId.trim())
            }
        }.map { json ->
            json.optString("message").takeIf { it.isNotBlank() } ?: "تم تسجيل الوصول إلى التاجر بنجاح"
        }
    }

    /**
     * 3. confirmPickup (Server-only; zero direct Firestore fallback)
     */
    suspend fun confirmPickup(orderDocId: String): Result<String> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException("معرّف الطلب غير صالح", httpCode = 400, isRetryable = false)
            )
        }
        return postAuthenticatedFunction(
            functionName = "confirmPickup",
            defaultClientErrorMsg = "تعذر تأكيد استلام الطلب من الخادم"
        ) {
            put("orderId", cleanId)
            if (cleanId != orderDocId.trim()) {
                put("rawOrderId", orderDocId.trim())
            }
        }.map { json ->
            json.optString("message").takeIf { it.isNotBlank() } ?: "تم تأكيد استلام الطلب من التاجر بنجاح"
        }
    }

    /**
     * 4. submitCustomerDeliveryDetails (Server-only; zero direct Firestore fallback)
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
                CloudFunctionException("بيانات الطلب أو مسار الإيصال غير مكتملة", httpCode = 400, isRetryable = false)
            )
        }
        return postAuthenticatedFunction(
            functionName = "submitCustomerDeliveryDetails",
            defaultClientErrorMsg = "رفض الخادم حفظ بيانات العميل والإيصال: تحقق من ملكية الطلب وحالته"
        ) { uid ->
            put("orderId", cleanId)
            if (cleanId != orderDocId.trim()) {
                put("rawOrderId", orderDocId.trim())
            }
            put("customerName", customerName)
            put("customerPhone", customerPhone)
            put("receiptImagePath", receiptImagePath)
            put("receiptUploadedBy", uid)
            put("receiptImageUrl", receiptImagePath)
            put("notes", notes)
        }.map { json ->
            json.optString("message").takeIf { it.isNotBlank() }
                ?: "تم إرسال بيانات العميل والإيصال وبانتظار تأكيد التاجر"
        }
    }

    /**
     * 5. confirmDelivery (Server-only; zero direct Firestore fallback)
     */
    suspend fun confirmDelivery(orderDocId: String): Result<Double> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException("معرّف الطلب غير صالح", httpCode = 400, isRetryable = false)
            )
        }
        return postAuthenticatedFunction(
            functionName = "confirmDelivery",
            defaultClientErrorMsg = "تعذر تأكيد تسليم الطلب عبر الخادم"
        ) {
            put("orderId", cleanId)
            if (cleanId != orderDocId.trim()) {
                put("rawOrderId", orderDocId.trim())
            }
        }.map { json ->
            json.optDouble("earnedAmount", 25.0)
        }
    }

    /**
     * 6. expireDeliveryOffer / decline offer (Server-only; never treats network failure as local success)
     */
    suspend fun expireDeliveryOffer(orderDocId: String, reason: String = "expired"): Result<Unit> {
        val cleanId = sanitizeOrderId(orderDocId)
        if (cleanId.isBlank()) {
            return Result.failure(
                CloudFunctionException("معرّف الطلب غير صالح", httpCode = 400, isRetryable = false)
            )
        }
        return postAuthenticatedFunction(
            functionName = "expireDeliveryOffer",
            defaultClientErrorMsg = "تعذر إبلاغ الخادم بانتهاء أو رفض عرض الطلب"
        ) { uid ->
            put("orderId", cleanId)
            if (cleanId != orderDocId.trim()) {
                put("rawOrderId", orderDocId.trim())
            }
            put("captainId", uid)
            put("reason", reason)
        }.map { }
    }

    /**
     * 7. resolveEmailByPhone (Read-only lookup helper for phone login when unauthenticated Firestore reads are blocked)
     */
    suspend fun resolveEmailByPhone(phone: String, variants: List<String> = emptyList()): String? =
        withContext(Dispatchers.IO) {
            val fastClient = httpClient.newBuilder()
                .connectTimeout(3500, TimeUnit.MILLISECONDS)
                .readTimeout(3500, TimeUnit.MILLISECONDS)
                .build()

            val normalizedBase = baseUrl.trimEnd('/')
            val endpoints = listOf("resolveCaptainEmailByPhone", "getCaptainEmailByPhone")
            for (fnName in endpoints) {
                try {
                    val payload = JSONObject().apply {
                        put("phone", phone)
                        put("phoneNumber", phone)
                        if (variants.isNotEmpty()) {
                            put("variants", JSONArray(variants))
                        }
                    }
                    val request = Request.Builder()
                        .url("$normalizedBase/$fnName")
                        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    fastClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            val json = JSONObject(body)
                            val em = json.optString("email").trim()
                            if (em.isNotBlank() && em.contains("@")) {
                                return@withContext em
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            null
        }

    companion object {
        private const val TAG = "CloudFunctionsClient"
        const val PROJECT_ID = "el-batal-express-956ae"
        const val DEFAULT_FUNCTIONS_BASE_URL = "https://us-central1-$PROJECT_ID.cloudfunctions.net"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun buildDefaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
