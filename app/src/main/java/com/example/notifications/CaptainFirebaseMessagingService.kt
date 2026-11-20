package com.example.notifications

import android.net.Uri
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.ShiftManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class CaptainFirebaseMessagingService : FirebaseMessagingService() {

    /**
     * Resolve a server-supplied merchant image without creating a Firebase public
     * download URL. Storage paths are fetched through the authenticated Firebase SDK
     * and cached in the app's private files directory for Coil/AsyncImage.
     */
    private suspend fun resolveMerchantImageUrl(
        rawUrl: String,
        rawPath: String,
        merchantId: String
    ): String {
        val url = rawUrl.trim()
        val path = rawPath.trim()
        if (merchantId.isBlank()) return ""

        // A Storage path from an order is accepted only when it is inside the
        // exact merchant folder that owns this order.
        val merchantPrefix = "merchants/$merchantId/"
        val bucketRelativePath = if (path.startsWith("gs://", ignoreCase = true)) {
            path.replaceFirst(Regex("^gs://[^/]+/"), "")
        } else {
            path
        }
        val scopedPath = bucketRelativePath.startsWith(merchantPrefix)

        if (scopedPath) {
            val cachedImage = runCatching {
                val storage = FirebaseStorage.getInstance()
                val reference = if (path.startsWith("gs://", ignoreCase = true)) {
                    storage.getReferenceFromUrl(path)
                } else {
                    storage.reference.child(path)
                }

                val imageBytes = reference.getBytes(MAX_MERCHANT_IMAGE_BYTES).await()
                if (imageBytes.isEmpty()) return@runCatching ""

                val imageDirectory = File(applicationContext.filesDir, MERCHANT_IMAGE_CACHE_DIR)
                if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
                    throw IllegalStateException("Could not create private merchant image cache")
                }
                val safeName = merchantId.hashCode().toUInt().toString(16)
                val imageFile = File(imageDirectory, "$safeName.webp")
                FileOutputStream(imageFile).use { output ->
                    output.write(imageBytes)
                    output.flush()
                }
                Uri.fromFile(imageFile).toString()
            }.onFailure { error ->
                Log.w(TAG, "Could not securely download merchant profile image from Firebase Storage", error)
            }.getOrDefault("")

            if (cachedImage.isNotBlank()) return cachedImage
        }

        // Legacy HTTPS URLs are used only when no valid, merchant-scoped Storage path
        // could be resolved. New uploads store both fields, so the private path wins.
        return url.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }.orEmpty()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Refreshed FCM token received")
        CaptainNotificationManager.saveFcmToken(applicationContext, token)

        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (!uid.isNullOrBlank()) {
            val payload = mapOf<String, Any>(
                "fcmToken" to token,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            runCatching {
                FirebaseFirestore.getInstance().collection("captains").document(uid).update(payload)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val type = data["type"].orEmpty().trim().lowercase()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                val captain = db.captainDao().getCaptain()
                if (captain == null || !captain.isLoggedIn) {
                    Log.d(TAG, "Ignoring FCM notification because captain is logged out")
                    return@launch
                }

                when (type) {
                    "new_order", "order_offer" -> {
                        // Strict rule: never notify/assign orders if offline, account inactive, or outside shift hours
                        val accountStatus = captain.status.trim().lowercase()
                        val accountActive = accountStatus !in listOf(
                            "banned", "suspended", "disabled", "blocked", "pending", "inactive"
                        )
                        if (!captain.isOnline || !accountActive) {
                            Log.i(TAG, "Blocked new_order push: captain is offline or inactive")
                            return@launch
                        }

                        val shifts = if (captain.uid.isNotBlank()) {
                            db.shiftDao().getShiftsForCaptainFlow(captain.uid).firstOrNull()
                                ?: db.shiftDao().getAllShiftsFlow().firstOrNull()
                                ?: emptyList()
                        } else {
                            db.shiftDao().getAllShiftsFlow().firstOrNull() ?: emptyList()
                        }

                        if (!ShiftManager.calculateShiftStatus(shifts).isAvailableForOrders) {
                            Log.i(TAG, "Blocked new_order push: captain is outside working shift hours")
                            return@launch
                        }

                        val orderKey = data["orderId"]
                            ?: data["firestoreDocId"]
                            ?: remoteMessage.messageId
                            ?: System.currentTimeMillis().toString()
                        val storeName = data["storeName"].orEmpty().trim()
                        val storePhone = data["storePhone"].orEmpty().trim()
                        val merchantId = data["merchantId"].orEmpty().trim()
                        val storeImage = resolveMerchantImageUrl(
                            rawUrl = data["storeImage"].orEmpty(),
                            rawPath = data["storeImagePath"].orEmpty(),
                            merchantId = merchantId
                        )
                        val pickup = data["pickupAddress"].orEmpty()
                        val delivery = data["deliveryAddress"].orEmpty()
                        val fee = data["deliveryFee"]?.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it > 0.0 }
                        if (merchantId.isBlank() || storeName.isBlank() || storePhone.isBlank() || storeImage.isBlank() || fee == null) {
                            Log.e(
                                TAG,
                                "Offer omitted: trusted merchant name, phone, image, or merchant-entered delivery fee is missing (order=$orderKey)"
                            )
                            return@launch
                        }
                        val distanceKm = data["distanceKm"]?.toDoubleOrNull() ?: 3.0
                        val orderCode = data["orderCode"]
                            ?: data["orderCodes"]
                            ?: data["Ordar codes"]
                            ?: data["code"]
                            ?: ""
                        val nowMs = System.currentTimeMillis()
                        val parsedExpiresAt = data["offerExpiresAt"]?.toLongOrNull() ?: 0L
                        val effectiveExpiresAt = if (parsedExpiresAt > nowMs) parsedExpiresAt else nowMs + 60_000L

                        val numericId = orderKey.hashCode().toLong().let { if (it < 0) -it else it }.let {
                            if (it == 0L) 1L else it
                        }
                        val pushedOrder = com.example.data.OrderEntity(
                            id = numericId,
                            orderNumber = orderKey.trim().trimStart('#').takeLast(6).uppercase().ifBlank { "NEW" },
                            orderCode = orderCode.trim().trimStart('#').trim(),
                            customerName = data["customerName"].orEmpty().ifBlank { "عميل البطل" },
                            customerPhone = data["customerPhone"].orEmpty(),
                            storeName = storeName,
                            storePhone = storePhone,
                            storeImage = storeImage,
                            pickupAddress = pickup.ifBlank { "مقر التاجر" },
                            deliveryAddress = delivery.ifBlank { "عنوان العميل" },
                            storeLocationUrl = data["storeLocationUrl"].orEmpty(),
                            itemsSummary = data["itemsSummary"].orEmpty().ifBlank { "طلب توصيل جديد" },
                            itemsCount = data["itemsCount"]?.toIntOrNull() ?: 1,
                            orderTotalAmount = data["orderTotalAmount"]?.toDoubleOrNull() ?: 0.0,
                            deliveryFee = fee,
                            distanceKm = distanceKm,
                            status = com.example.data.OrderStatus.PENDING,
                            firestoreDocId = orderKey,
                            merchantId = merchantId,
                            offerExpiresAt = effectiveExpiresAt
                        )
                        val savedOrderId = runCatching {
                            db.orderDao().upsertByFirestoreId(pushedOrder)
                        }.getOrDefault(numericId)

                        CaptainNotificationManager.showNewOrderNotification(
                            context = applicationContext,
                            orderKey = orderKey,
                            storeName = storeName,
                            storePhone = storePhone,
                            storeImage = storeImage,
                            merchantId = merchantId,
                            pickupAddress = pickup,
                            deliveryAddress = delivery,
                            deliveryFee = fee,
                            orderCode = orderCode,
                            orderId = savedOrderId,
                            offerExpiresAtMs = effectiveExpiresAt,
                            distanceKm = distanceKm,
                            startContinuousBell = true
                        )
                    }

                    "shift_start" -> {
                        CaptainNotificationManager.showShiftStartNotification(
                            context = applicationContext,
                            dayNameArabic = data["dayNameArabic"].orEmpty().ifBlank { "اليوم" },
                            startFormatted = data["startFormatted"].orEmpty(),
                            endFormatted = data["endFormatted"].orEmpty(),
                            dedupeKey = data["dedupeKey"].orEmpty().ifBlank { "fcm_start_${System.currentTimeMillis() / 3600_000L}" }
                        )
                    }

                    "shift_end" -> {
                        CaptainNotificationManager.showShiftEndNotification(
                            context = applicationContext,
                            dayNameArabic = data["dayNameArabic"].orEmpty().ifBlank { "اليوم" },
                            endFormatted = data["endFormatted"].orEmpty(),
                            dedupeKey = data["dedupeKey"].orEmpty().ifBlank { "fcm_end_${System.currentTimeMillis() / 3600_000L}" }
                        )
                    }

                    else -> {
                        // Unknown FCM event types must never be displayed as delivery offers:
                        // doing so could invent merchant identity or a delivery fee.
                        Log.d(TAG, "Ignoring unsupported FCM event type: $type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing FCM message: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "CaptainFcmService"
        private const val MERCHANT_IMAGE_CACHE_DIR = "merchant_images"
        private const val MAX_MERCHANT_IMAGE_BYTES = 10L * 1024L * 1024L
    }
}
