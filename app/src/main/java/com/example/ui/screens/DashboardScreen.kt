package com.example.ui.screens

import android.location.Location
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.data.CaptainEntity
import com.example.data.OrderEntity
import com.example.data.ShiftEntity
import com.example.data.ShiftStatus
import com.example.data.location.GpsStatus
import com.example.data.storage.ImageUploadState
import com.example.ui.OrderActionError
import com.example.ui.components.ActiveOrderCard
import com.example.ui.components.AvailableTripCard
import com.example.ui.components.CaptainAvatarView
import com.example.ui.components.EnterOrderDataBottomSheet
import com.example.ui.components.IncomingOrderAlert
import com.example.ui.components.WeeklyScheduleBottomSheet
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    captain: CaptainEntity?,
    activeOrder: OrderEntity?,
    availableOrders: List<OrderEntity>,
    pendingIncomingOrder: OrderEntity?,
    completedCount: Int,
    totalEarnings: Double,
    shiftStatus: ShiftStatus = ShiftStatus.NoSchedule(),
    shifts: List<ShiftEntity> = emptyList(),
    currentCairoTime: String = "12:00:00 م",
    currentCairoDate: String = "الخميس 5 نوفمبر",
    dailyWorkHours: Double = 0.0,
    weeklyWorkHours: Double = 0.0,
    isShiftNetworkConnected: Boolean = true,
    isLoading: Boolean = false,
    gpsStatus: GpsStatus = GpsStatus.Inactive,
    currentLocation: Location? = null,
    onRequestLocationPermission: () -> Unit = {},
    onToggleOnline: () -> Unit,
    onAcceptOrder: (Long) -> Unit,
    onTimeoutOrder: (Long) -> Unit = {},
    onMarkArrivedAtMerchant: (Long) -> Unit = {},
    onConfirmPickup: (Long) -> Unit = {},
    onSaveOrderCustomerData: (Long, String, String, String, Uri, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit = { _, _, _, _, _, _, _ -> },
    receiptUploadState: ImageUploadState = ImageUploadState.Idle,
    onResetReceiptUploadState: () -> Unit = {},
    orderActionError: OrderActionError? = null,
    onDismissOrderActionError: () -> Unit = {},
    onAdvanceOrderStatus: (OrderEntity) -> Unit = {},
    onSimulateMerchantConfirm: ((Long, Boolean) -> Unit)? = null,
    onRefreshOrders: () -> Unit = {},
    onRefreshShifts: () -> Unit = {},
    onNavigateToShifts: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var orderForDataEntry by remember { mutableStateOf<OrderEntity?>(null) }
    var isUploadingOrderImage by remember { mutableStateOf(false) }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showWeeklyScheduleSheet by remember { mutableStateOf(false) }
    val weeklyScheduleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Section 1: Calculate clear Captain Readiness state:
    // - متصل وجاهز لاستقبال الطلبات (Green)
    // - خارج الشفت (Red)
    // - غير متصل (Gray)
    // - مشغول بطلب (Yellow)
    // - الموقع غير محدث (Orange)
    val readinessStatusText: String
    val readinessColor: Color

    when {
        captain?.status == "banned" || captain?.status == "suspended" -> {
            readinessStatusText = "الحساب موقوف / محظور"
            readinessColor = ExpressRed
        }
        captain?.status == "pending" -> {
            readinessStatusText = "الحساب بانتظار الاعتماد من الإدارة"
            readinessColor = HeroYellow
        }
        captain?.isOnline != true -> {
            readinessStatusText = "غير متصل"
            readinessColor = Color.Gray
        }
        activeOrder != null -> {
            readinessStatusText = "مشغول بطلب"
            readinessColor = HeroYellow
        }
        !shiftStatus.isAvailableForOrders -> {
            readinessStatusText = "خارج الشفت"
            readinessColor = ExpressRed
        }
        gpsStatus !is GpsStatus.Active -> {
            readinessStatusText = "الموقع غير محدث"
            readinessColor = Color(0xFFFF9800)
        }
        else -> {
            readinessStatusText = "متصل وجاهز لاستقبال الطلبات"
            readinessColor = StatusOnlineGreen
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(18.dp)
        ) {
            // Header Bar: Captain Info & Online Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        CaptainAvatarView(
                            presetIndex = captain?.avatarPresetIndex ?: 0,
                            sizeDp = 52,
                            avatarUri = captain?.effectiveProfileImage ?: ""
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = captain?.fullName ?: "الكابتن",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(readinessColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = readinessStatusText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = readinessColor
                                )
                            }
                        }
                    }

                    // Online/Offline Switch
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = captain?.isOnline ?: true,
                            onCheckedChange = { onToggleOnline() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = StatusOnlineGreen,
                                uncheckedThumbColor = Color.LightGray,
                                uncheckedTrackColor = DarkSurfaceVariant
                            )
                        )
                        Text(
                            text = if (captain?.isOnline == true) "متصل" else "غير متصل",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Daily Stats Section
            Text(
                text = "ملخص إحصائيات اليوم",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Today's Earnings
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachMoney,
                            contentDescription = null,
                            tint = StatusOnlineGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${totalEarnings.toInt()} ج.م",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                        Text(
                            text = "أرباح اليوم",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Completed Count
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = HeroYellow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "$completedCount طلبات",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                        Text(
                            text = "الطلبات المكتملة",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Rating Card
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = HeroYellow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${captain?.rating ?: 4.9} ★",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = Color.White
                        )
                        Text(
                            text = "تقييم الكابتن",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Server / Cloud Functions Error & Retry Banner (no silent local fallback)
            if (orderActionError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("order_action_error_banner"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.16f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ExpressRed)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "تنبيه خطأ الخادم",
                                    tint = ExpressRed,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = orderActionError.message,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                            IconButton(
                                onClick = onDismissOrderActionError,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "إغلاق التنبيه",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (orderActionError.isRetryable) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = orderActionError.onRetry,
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("retry_order_action_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ExpressRed,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = orderActionError.actionLabel,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        } else if (orderActionError.isNotFound) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    orderActionError.onDismissOrClear?.invoke()
                                    onDismissOrderActionError()
                                },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("dismiss_stale_order_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ExpressRed,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تحديث القائمة وإزالة الطلب غير المتاح",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 1. ACTIVE ORDER SECTION (If there is an active order)
            if (activeOrder != null) {
                Text(
                    text = "الطلب الحالي قيد التنفيذ",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = HeroYellow
                )
                Spacer(modifier = Modifier.height(10.dp))
                ActiveOrderCard(
                    order = activeOrder,
                    isLoading = isLoading,
                    onMarkArrivedAtMerchant = onMarkArrivedAtMerchant,
                    onConfirmPickup = onConfirmPickup,
                    onOpenCustomerDataEntry = {
                        onResetReceiptUploadState()
                        orderForDataEntry = it
                    },
                    onCompleteDelivery = onAdvanceOrderStatus,
                    onSimulateMerchantConfirm = onSimulateMerchantConfirm
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 2. AVAILABLE TRIPS & ORDERS SECTION
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DeliveryDining,
                        contentDescription = null,
                        tint = HeroYellow,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "الرحلات والطلبات المتاحة للاستلام",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (availableOrders.isNotEmpty() && shiftStatus.isAvailableForOrders) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(HeroYellow)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${availableOrders.size} متاح",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.Black
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (!shiftStatus.isAvailableForOrders) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ExpressRed.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "مغلق حالياً",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = ExpressRed
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    IconButton(
                        onClick = onRefreshOrders,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = HeroYellow,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "تحديث الطلبات من السيرفر",
                                tint = HeroYellow,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!shiftStatus.isAvailableForOrders) {
                // Outside working shift hours: Show clear notice
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(ExpressRed.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ExpressRed,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "استقبال الرحلات متوقف خارج ساعات العمل",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "بناءً على تعليمات الإدارة، لا يتم إسناد أو عرض أي طلبات جديدة للكابتن خارج ساعات الشفت المحددة له في لوحة التحكم.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = onNavigateToShifts,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = HeroYellow,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "مراجعة جدول شفتاتك 📅",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = HeroYellow
                            )
                        }
                    }
                }
            } else if (availableOrders.isEmpty() && activeOrder == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ElectricScooter,
                            contentDescription = null,
                            tint = ExpressRed,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (captain?.isOnline == true) "لا توجد رحلات جديدة حالياً" else "أنت في وضع غير متصل",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (captain?.isOnline == true)
                                "في انتظار إسناد أو وصول رحلات توصيل جديدة من المطاعم المسجلة"
                            else
                                "قم بتشغيل وضع الاتصال أعلاه لاستقبال وتصفح رحلات التوصيل المتاحة",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Refresh from Firestore button
                        OutlinedButton(
                            onClick = onRefreshOrders,
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = HeroYellow,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "تحديث الطلبات",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = HeroYellow
                            )
                        }
                    }
                }
            } else {
                // List of available trip cards
                availableOrders.forEach { availableOrder ->
                    AvailableTripCard(
                        order = availableOrder,
                        isLoading = isLoading,
                        onAcceptOrder = { onAcceptOrder(it) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Modal BottomSheet for Entering Customer Data & Uploading Receipt to Firebase Storage
        orderForDataEntry?.let { targetOrder ->
            EnterOrderDataBottomSheet(
                order = targetOrder,
                sheetState = bottomSheetState,
                isUploading = isUploadingOrderImage,
                receiptUploadState = receiptUploadState,
                onDismiss = {
                    if (!isUploadingOrderImage) {
                        onResetReceiptUploadState()
                        orderForDataEntry = null
                    }
                },
                onSaveDataAndImage = { name, phone, notes, uri ->
                    isUploadingOrderImage = true
                    onSaveOrderCustomerData(
                        targetOrder.id,
                        name,
                        phone,
                        notes,
                        uri,
                        {
                            isUploadingOrderImage = false
                            orderForDataEntry = null
                        },
                        { _ ->
                            isUploadingOrderImage = false
                        }
                    )
                }
            )
        }

        // Incoming Order Offer Alert (Single "قبول الطلب" button + 60s countdown timer)
        if (pendingIncomingOrder != null && activeOrder == null && availableOrders.none { it.id == pendingIncomingOrder.id }) {
            IncomingOrderAlert(
                order = pendingIncomingOrder,
                onAccept = { onAcceptOrder(pendingIncomingOrder.id) },
                onTimeout = { onTimeoutOrder(pendingIncomingOrder.id) }
            )
        }

        // Loading Overlay Indicator
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = HeroYellow)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "جاري تنفيذ العملية مع الخادم...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }

        // Weekly Schedule Bottom Sheet
        if (showWeeklyScheduleSheet) {
            WeeklyScheduleBottomSheet(
                shifts = shifts,
                sheetState = weeklyScheduleSheetState,
                onDismiss = { showWeeklyScheduleSheet = false }
            )
        }
    }
}
