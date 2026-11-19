package com.example.notifications

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.CaptainRepository
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.ui.components.IncomingOrderOfferCardContent
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.ElBatalTheme
import kotlinx.coroutines.launch

/**
 * Full-Screen Incoming Order Alert Activity launched via FullScreenIntent or notification tap
 * when a new order arrives while the captain is outside the app or screen-locked.
 * Displays the live 60-second countdown timer and keeps the bell ringing until accepted or expired.
 */
class IncomingOrderActivity : ComponentActivity() {

    private lateinit var repository: CaptainRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenAndWakeFlags()
        enableEdgeToEdge()

        val db = AppDatabase.getDatabase(applicationContext)
        repository = CaptainRepository(
            captainDao = db.captainDao(),
            orderDao = db.orderDao(),
            shiftDao = db.shiftDao(),
            context = applicationContext
        )

        val fallbackOrder = extractFallbackOrderFromIntent(intent)
        val targetOrderKey = intent.getStringExtra(EXTRA_ORDER_KEY).orEmpty()
        val targetOrderId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)

        setContent {
            ElBatalTheme {
                val allOrders by repository.allOrdersFlow.collectAsState(initial = emptyList())
                val matchedOrder = remember(allOrders, targetOrderId, targetOrderKey) {
                    allOrders.firstOrNull {
                        (targetOrderId > 0L && it.id == targetOrderId) ||
                            (targetOrderKey.isNotBlank() && it.firestoreDocId == targetOrderKey)
                    }
                }
                val displayOrder = matchedOrder ?: fallbackOrder
                var isAccepting by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                // If order transitioned away from PENDING (e.g. taken by another captain or accepted), stop bell and close
                LaunchedEffect(matchedOrder?.status) {
                    if (matchedOrder != null && matchedOrder.status != OrderStatus.PENDING && !isAccepting) {
                        CaptainNotificationManager.stopIncomingOrderBell(
                            applicationContext,
                            matchedOrder.firestoreDocId.ifBlank { matchedOrder.id.toString() }
                        )
                        finish()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground.copy(alpha = 0.96f))
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IncomingOrderOfferCardContent(
                        order = displayOrder,
                        isAccepting = isAccepting,
                        onAccept = {
                            if (isAccepting) return@IncomingOrderOfferCardContent
                            isAccepting = true
                            val orderKeyToStop = displayOrder.firestoreDocId.ifBlank { targetOrderKey }
                            CaptainNotificationManager.stopIncomingOrderBell(applicationContext, orderKeyToStop)

                            scope.launch {
                                val resolvedId = matchedOrder?.id ?: targetOrderId
                                val res = if (resolvedId > 0L) {
                                    repository.acceptOrder(resolvedId)
                                } else {
                                    Result.failure(Exception("تعذر تحديد معرف الطلب"))
                                }
                                if (res.isSuccess) {
                                    Toast.makeText(
                                        applicationContext,
                                        "تم قبول الطلب بنجاح!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    val dashIntent = Intent(this@IncomingOrderActivity, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        putExtra(
                                            CaptainNotificationManager.EXTRA_NAVIGATE_TARGET,
                                            CaptainNotificationManager.TARGET_DASHBOARD
                                        )
                                    }
                                    startActivity(dashIntent)
                                    finish()
                                } else {
                                    isAccepting = false
                                    val msg = res.exceptionOrNull()?.localizedMessage
                                        ?: "تعذر قبول الطلب، ربما تم قبوله بواسطة كابتن آخر"
                                    Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                                    if (matchedOrder == null) finish()
                                }
                            }
                        },
                        onTimeout = {
                            val orderKeyToStop = displayOrder.firestoreDocId.ifBlank { targetOrderKey }
                            CaptainNotificationManager.stopIncomingOrderBell(applicationContext, orderKeyToStop)
                            scope.launch {
                                val resolvedId = matchedOrder?.id ?: targetOrderId
                                if (resolvedId > 0L) {
                                    repository.expireDeliveryOffer(resolvedId)
                                }
                                finish()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun configureLockScreenAndWakeFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun extractFallbackOrderFromIntent(intent: Intent?): OrderEntity {
        val orderKey = intent?.getStringExtra(EXTRA_ORDER_KEY).orEmpty()
        val orderId = intent?.getLongExtra(EXTRA_ORDER_ID, 0L) ?: 0L
        val storeName = intent?.getStringExtra(EXTRA_STORE_NAME).orEmpty().ifBlank { "مطعم مسجل" }
        val pickup = intent?.getStringExtra(EXTRA_PICKUP_ADDRESS).orEmpty().ifBlank { "مقر التاجر" }
        val delivery = intent?.getStringExtra(EXTRA_DELIVERY_ADDRESS).orEmpty().ifBlank { "عنوان العميل" }
        val fee = intent?.getDoubleExtra(EXTRA_DELIVERY_FEE, 25.0) ?: 25.0
        val orderCode = intent?.getStringExtra(EXTRA_ORDER_CODE).orEmpty()
        val rawExpires = intent?.getLongExtra(EXTRA_OFFER_EXPIRES_AT_MS, 0L) ?: 0L
        val expiresAt = if (rawExpires > 0L) rawExpires else System.currentTimeMillis() + 60_000L

        return OrderEntity(
            id = if (orderId > 0L) orderId else 1L,
            orderNumber = orderKey.takeLast(6).uppercase().ifBlank { "NEW" },
            orderCode = orderCode,
            customerName = "عميل البطل",
            customerPhone = "",
            storeName = storeName,
            pickupAddress = pickup,
            deliveryAddress = delivery,
            itemsSummary = "طلب توصيل جديد",
            itemsCount = 1,
            orderTotalAmount = 0.0,
            deliveryFee = fee,
            distanceKm = 3.0,
            status = OrderStatus.PENDING,
            firestoreDocId = orderKey,
            offerExpiresAt = expiresAt
        )
    }

    companion object {
        const val EXTRA_ORDER_KEY = "extra_order_key"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_STORE_NAME = "extra_store_name"
        const val EXTRA_PICKUP_ADDRESS = "extra_pickup_address"
        const val EXTRA_DELIVERY_ADDRESS = "extra_delivery_address"
        const val EXTRA_DELIVERY_FEE = "extra_delivery_fee"
        const val EXTRA_ORDER_CODE = "extra_order_code"
        const val EXTRA_OFFER_EXPIRES_AT_MS = "extra_offer_expires_at_ms"

        fun createIntent(
            context: Context,
            orderKey: String,
            orderId: Long,
            storeName: String,
            pickupAddress: String,
            deliveryAddress: String,
            deliveryFee: Double,
            orderCode: String,
            offerExpiresAtMs: Long
        ): Intent {
            return Intent(context, IncomingOrderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ORDER_KEY, orderKey)
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_STORE_NAME, storeName)
                putExtra(EXTRA_PICKUP_ADDRESS, pickupAddress)
                putExtra(EXTRA_DELIVERY_ADDRESS, deliveryAddress)
                putExtra(EXTRA_DELIVERY_FEE, deliveryFee)
                putExtra(EXTRA_ORDER_CODE, orderCode)
                putExtra(EXTRA_OFFER_EXPIRES_AT_MS, offerExpiresAtMs)
            }
        }
    }
}
