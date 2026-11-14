package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class OrderStatus {
    PENDING,            // عرض الطلب - بانتظار قبول الكابتن (مؤقت 60 ثانية)
    ACCEPTED,           // تم قبول الطلب - في الطريق إلى التاجر
    AT_STORE,           // تم الوصول إلى التاجر (بانتظار استلام الطلب)
    PICKED_UP,          // تم استلام الطلب من التاجر
    ORDER_DATA_ENTERED, // تم إدخال بيانات العميل وصورة الإيصال
    AWAITING_MERCHANT_CONFIRMATION, // بانتظار مراجعة وتأكيد التاجر
    MERCHANT_REJECTED,  // تم رفض البيانات من التاجر (يتطلب التعديل)
    EN_ROUTE,           // تأكيد التاجر تم - في الطريق لتسليم العميل
    DELIVERED,          // تم تسليم الطلب بنجاح وإغلاق المستحقات
    CANCELLED,          // ملغي
    EXPIRED             // انتهت مهلة العرض (60 ثانية)
}

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNumber: String,
    val customerName: String,
    val customerPhone: String,
    val storeName: String,
    val storePhone: String = "",
    val storeImage: String = "",
    val pickupAddress: String,
    val deliveryAddress: String,
    val restaurantLatitude: Double? = null,
    val restaurantLongitude: Double? = null,
    val itemsSummary: String,
    val itemsCount: Int,
    val orderTotalAmount: Double,
    val deliveryFee: Double,
    val distanceKm: Double,
    val status: OrderStatus = OrderStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    val deliveryPin: String = "1234",
    val firestoreDocId: String = "",
    val captainId: String = "",
    val acceptedAt: Long = 0L,
    val orderImageUrl: String = "",
    val receiptImagePath: String = "",
    val receiptUploadedAt: Long = 0L,
    val receiptUploadedBy: String = "",
    val orderDataEnteredAt: Long = 0L,
    val offerExpiresAt: Long = 0L,
    val merchantConfirmed: Boolean = false,
    val rejectionReason: String = "",
    val etaMinutes: Int = 15,
    val completedAt: Long = 0L,
    val cancelledAt: Long = 0L,
    val cancellationReason: String = ""
) {
    val effectiveReceiptImage: String
        get() = receiptImagePath.ifBlank { orderImageUrl }

    val effectiveCancellationReason: String
        get() = cancellationReason.ifBlank { if (status == OrderStatus.CANCELLED) rejectionReason else "" }

    val cleanOrderNumber: String
        get() = orderNumber.trim().trimStart('#').trim()
}
