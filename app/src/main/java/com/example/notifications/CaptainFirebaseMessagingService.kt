package com.example.notifications

import android.util.Log
import com.example.data.AppDatabase
import com.example.data.ShiftManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class CaptainFirebaseMessagingService : FirebaseMessagingService() {

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
                        val storeName = data["storeName"]
                            ?: remoteMessage.notification?.title
                            ?: "مطعم مسجل"
                        val pickup = data["pickupAddress"].orEmpty()
                        val delivery = data["deliveryAddress"].orEmpty()
                        val fee = data["deliveryFee"]?.toDoubleOrNull() ?: 25.0
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
                            storePhone = data["storePhone"].orEmpty(),
                            storeImage = data["storeImage"].orEmpty(),
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
                            offerExpiresAt = effectiveExpiresAt
                        )
                        val savedOrderId = runCatching {
                            db.orderDao().upsertByFirestoreId(pushedOrder)
                        }.getOrDefault(numericId)

                        CaptainNotificationManager.showNewOrderNotification(
                            context = applicationContext,
                            orderKey = orderKey,
                            storeName = storeName,
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
                        val notifTitle = remoteMessage.notification?.title ?: data["title"] ?: return@launch
                        val notifBody = remoteMessage.notification?.body ?: data["body"] ?: ""
                        CaptainNotificationManager.showNewOrderNotification(
                            context = applicationContext,
                            orderKey = remoteMessage.messageId ?: System.currentTimeMillis().toString(),
                            storeName = notifTitle,
                            pickupAddress = notifBody,
                            deliveryFee = data["deliveryFee"]?.toDoubleOrNull() ?: 25.0
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing FCM message: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "CaptainFcmService"
    }
}
