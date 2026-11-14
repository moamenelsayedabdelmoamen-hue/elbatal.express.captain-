package com.example.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.OrderEntity
import com.example.data.OrderHistoryHelper
import com.example.data.OrderHistoryTab
import com.example.data.OrderStatus
import com.example.ui.components.StorageAsyncImage
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen
import com.example.ui.theme.TextSecondaryDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    orders: List<OrderEntity>,
    captainUid: String = "",
    captainPhone: String = "",
    selectedDateKey: String = OrderHistoryHelper.getTodayDateKey(),
    todayDateKey: String = OrderHistoryHelper.getTodayDateKey(),
    selectedTab: OrderHistoryTab? = null,
    isSyncing: Boolean = false,
    syncError: String? = null,
    onSelectDateKey: (String) -> Unit = {},
    onReturnToToday: () -> Unit = { onSelectDateKey(todayDateKey) },
    onSelectTab: ((OrderHistoryTab) -> Unit)? = null,
    onSyncHistory: () -> Unit = {},
    @DrawableRes emptyIconRes: Int = R.drawable.ic_nav_clipboard_list
) {
    var internalDateKey by remember(todayDateKey) { mutableStateOf(selectedDateKey) }
    val effectiveDateKey = if (onSelectDateKey !== {}) {
        OrderHistoryHelper.clampToMaxToday(selectedDateKey, todayDateKey)
    } else {
        OrderHistoryHelper.clampToMaxToday(internalDateKey, todayDateKey)
    }

    var internalTab by remember { mutableStateOf(OrderHistoryTab.ALL) }
    val activeTab = selectedTab ?: internalTab

    val handleDateChange: (String) -> Unit = { candidateKey ->
        if (!OrderHistoryHelper.isFutureDateKey(candidateKey, todayDateKey)) {
            internalDateKey = candidateKey
            onSelectDateKey(candidateKey)
        }
    }

    val handleTabChange: (OrderHistoryTab) -> Unit = { tab ->
        internalTab = tab
        onSelectTab?.invoke(tab)
    }

    var showCalendarDialog by remember { mutableStateOf(false) }
    var selectedOrderForDetails by remember { mutableStateOf<OrderEntity?>(null) }

    // Enforce strict archive rules: ONLY finished (DELIVERED or CANCELLED) orders for the selected date,
    // strictly owned by the logged-in captain when captainUid/captainPhone is supplied, sorted newest first.
    val archivedDateOrders = remember(orders, captainUid, captainPhone, effectiveDateKey) {
        if (captainUid.isNotBlank() || captainPhone.isNotBlank()) {
            OrderHistoryHelper.filterArchivedOrdersForDate(
                allOrders = orders,
                captainUid = captainUid,
                captainPhone = captainPhone,
                selectedDateKey = effectiveDateKey
            )
        } else {
            val (startMs, endMs) = OrderHistoryHelper.getCalendarDayBoundsMs(effectiveDateKey)
            orders
                .filter { OrderHistoryHelper.isArchivedFinishedOrder(it) }
                .filter { OrderHistoryHelper.getFinishTimestamp(it) in startMs..endMs }
                .distinctBy { it.firestoreDocId.trim().ifBlank { "id_${it.id}_${it.orderNumber}" } }
                .sortedWith(
                    compareByDescending<OrderEntity> { OrderHistoryHelper.getFinishTimestamp(it) }
                        .thenByDescending { it.id }
                )
        }
    }

    val allFinishedCount = archivedDateOrders.size
    val completedDayCount = remember(archivedDateOrders) {
        archivedDateOrders.count { it.status == OrderStatus.DELIVERED }
    }
    val cancelledDayCount = remember(archivedDateOrders) {
        archivedDateOrders.count { it.status == OrderStatus.CANCELLED }
    }

    val visibleOrders = remember(archivedDateOrders, activeTab) {
        OrderHistoryHelper.filterByTab(archivedDateOrders, activeTab)
    }

    val summaryStats = remember(archivedDateOrders, activeTab) {
        OrderHistoryHelper.computeSummaryStats(archivedDateOrders, activeTab)
    }

    val isTodaySelected = effectiveDateKey == todayDateKey

    if (showCalendarDialog) {
        val initialMillis = OrderHistoryHelper.dateKeyToUtcPickerMillis(effectiveDateKey)
        val maxYear = OrderHistoryHelper.getTodayYear(todayDateKey)
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            yearRange = 2023..maxYear,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val candidateKey = OrderHistoryHelper.utcPickerMillisToDateKey(utcTimeMillis)
                    return !OrderHistoryHelper.isFutureDateKey(candidateKey, todayDateKey)
                }

                override fun isSelectableYear(year: Int): Boolean {
                    return year <= maxYear
                }
            }
        )

        DatePickerDialog(
            modifier = Modifier.testTag("history_date_picker_dialog"),
            onDismissRequest = { showCalendarDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pickedMillis = datePickerState.selectedDateMillis
                        if (pickedMillis != null) {
                            val pickedKey = OrderHistoryHelper.utcPickerMillisToDateKey(pickedMillis)
                            if (!OrderHistoryHelper.isFutureDateKey(pickedKey, todayDateKey)) {
                                handleDateChange(pickedKey)
                            }
                        }
                        showCalendarDialog = false
                    },
                    modifier = Modifier.testTag("confirm_calendar_date_button")
                ) {
                    Text("عرض السجل", color = ExpressRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isTodaySelected) {
                        TextButton(
                            onClick = {
                                internalDateKey = todayDateKey
                                onReturnToToday()
                                showCalendarDialog = false
                            },
                            modifier = Modifier.testTag("dialog_jump_today_button")
                        ) {
                            Text("اليوم", color = HeroYellow, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { showCalendarDialog = false }) {
                        Text("إلغاء", color = Color.Gray)
                    }
                }
            },
            colors = DatePickerDefaults.colors(containerColor = DarkSurface)
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (selectedOrderForDetails != null) {
        ArchivedOrderDetailsBottomSheet(
            order = selectedOrderForDetails!!,
            onDismiss = { selectedOrderForDetails = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("orders_history_screen")
    ) {
        // 1. Header + Calendar Button + One-Tap Return to Today
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "سجل الطلبات",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 21.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = OrderHistoryHelper.formatSelectedDateTitleArabic(effectiveDateKey, todayDateKey),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isTodaySelected) StatusOnlineGreen else HeroYellow,
                    modifier = Modifier.testTag("selected_history_date_title")
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onSyncHistory,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
                        .testTag("sync_orders_history_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = ExpressRed,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "تحديث سجل الطلبات",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Button(
                    onClick = { showCalendarDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ExpressRed,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("open_calendar_picker_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "فتح التقويم لاختيار تاريخ سابق",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "التقويم",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Quick Date Bar with One-Tap Return to Today when viewing any previous date (or quick status chip on today)
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, if (isTodaySelected) DarkCardBorder else ExpressRed.copy(alpha = 0.55f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = ExpressRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isTodaySelected) "أرشيف طلبات اليوم المنتهية" else "أرشيف تاريخ سابق ($effectiveDateKey)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "يعرض الطلبات المكتملة والملغاة فقط",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondaryDark,
                            fontSize = 10.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        internalDateKey = todayDateKey
                        onReturnToToday()
                    },
                    enabled = !isTodaySelected,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isTodaySelected) StatusOnlineGreen.copy(alpha = 0.35f) else ExpressRed
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isTodaySelected) StatusOnlineGreen.copy(alpha = 0.10f) else ExpressRed.copy(alpha = 0.16f),
                        contentColor = if (isTodaySelected) StatusOnlineGreen else Color.White,
                        disabledContainerColor = StatusOnlineGreen.copy(alpha = 0.10f),
                        disabledContentColor = StatusOnlineGreen
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("return_to_today_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Today,
                        contentDescription = "الرجوع إلى تاريخ اليوم",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isTodaySelected) "اليوم الحالي" else "العودة لليوم",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Connection / Sync Error Banner
        if (!syncError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_sync_error_banner"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.14f)),
                border = BorderStroke(1.dp, ExpressRed.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = ExpressRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = syncError,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = onSyncHistory,
                        modifier = Modifier.testTag("history_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = ExpressRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إعادة المحاولة", color = ExpressRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Three Filter Tabs: الكل | مكتملة | ملغاة
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurface)
                .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HistoryFilterTabButton(
                title = "الكل",
                count = allFinishedCount,
                selected = activeTab == OrderHistoryTab.ALL,
                accentColor = ExpressRed,
                testTag = "history_tab_all",
                modifier = Modifier.weight(1f),
                onClick = { handleTabChange(OrderHistoryTab.ALL) }
            )
            HistoryFilterTabButton(
                title = "مكتملة",
                count = completedDayCount,
                selected = activeTab == OrderHistoryTab.COMPLETED,
                accentColor = StatusOnlineGreen,
                testTag = "history_tab_completed",
                modifier = Modifier.weight(1f),
                onClick = { handleTabChange(OrderHistoryTab.COMPLETED) }
            )
            HistoryFilterTabButton(
                title = "ملغاة",
                count = cancelledDayCount,
                selected = activeTab == OrderHistoryTab.CANCELLED,
                accentColor = ExpressRed,
                testTag = "history_tab_cancelled",
                modifier = Modifier.weight(1f),
                onClick = { handleTabChange(OrderHistoryTab.CANCELLED) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Statistical Summary for Selected Day & Category
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("history_stats_summary_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkCardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryStatColumn(
                    label = "إجمالي المنتهية",
                    value = summaryStats.totalFinished.toString(),
                    valueColor = Color.White,
                    valueTestTag = "stat_total_finished_value",
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(DarkCardBorder)
                )
                SummaryStatColumn(
                    label = "الطلبات المكتملة",
                    value = summaryStats.completedCount.toString(),
                    valueColor = StatusOnlineGreen,
                    valueTestTag = "stat_completed_count_value",
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(DarkCardBorder)
                )
                SummaryStatColumn(
                    label = "الطلبات الملغاة",
                    value = summaryStats.cancelledCount.toString(),
                    valueColor = ExpressRed,
                    valueTestTag = "stat_cancelled_count_value",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Orders List (Sorted Newest First) or Empty State
        if (visibleOrders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkCardBorder, RoundedCornerShape(20.dp))
                    .padding(24.dp)
                    .testTag("orders_empty_state"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(ExpressRed.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = emptyIconRes),
                            contentDescription = "سجل الطلبات فارغ",
                            tint = ExpressRed,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "لا توجد طلبات منتهية في هذا اليوم",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isTodaySelected) {
                            "ستظهر هنا تلقائياً الطلبات المكتملة والملغاة فور انتهائها خلال اليوم"
                        } else {
                            "يمكنك اختيار تاريخ آخر من التقويم أو الضغط على العودة لليوم"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .testTag("archived_orders_lazy_list"),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visibleOrders, key = { "${it.firestoreDocId}_${it.id}" }) { order ->
                    ArchivedOrderHistoryCard(
                        order = order,
                        onOpenDetails = { selectedOrderForDetails = order }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryFilterTabButton(
    title: String,
    count: Int,
    selected: Boolean,
    accentColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accentColor else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                ),
                color = if (selected) Color.White else Color.Gray,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) Color.Black.copy(alpha = 0.25f) else DarkSurfaceVariant
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) Color.White else Color.LightGray,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SummaryStatColumn(
    label: String,
    value: String,
    valueColor: Color,
    valueTestTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            ),
            color = valueColor,
            modifier = Modifier.testTag(valueTestTag)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondaryDark,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArchivedOrderHistoryCard(
    order: OrderEntity,
    onOpenDetails: () -> Unit
) {
    val isDelivered = order.status == OrderStatus.DELIVERED
    val statusLabel = if (isDelivered) "مكتمل" else "ملغي"
    val statusColor = if (isDelivered) StatusOnlineGreen else ExpressRed
    val finishTimestamp = OrderHistoryHelper.getFinishTimestamp(order)
    val finishTimeLabel = if (isDelivered) "وقت الإكمال" else "وقت الإلغاء"
    val formattedFinishTime = OrderHistoryHelper.formatFinishDateTimeArabic(finishTimestamp)
    val cancelReason = order.effectiveCancellationReason.trim()
    val hasCustomerAddress = order.deliveryAddress.isNotBlank() && order.deliveryAddress != "عنوان العميل"
    val hasCustomerInfo = (order.customerName.isNotBlank() && order.customerName != "عميل البطل") ||
        (order.customerPhone.isNotBlank() && order.customerPhone != "01000000000")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails)
            .testTag("history_order_card_${order.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top Row: Merchant Logo + Merchant Name & Order Number + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    MerchantLogoAvatar(
                        storeImage = order.storeImage,
                        storeName = order.storeName,
                        accentColor = statusColor
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = order.storeName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "طلب #${order.cleanOrderNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            color = HeroYellow
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isDelivered) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = statusColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = DarkCardBorder.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(10.dp))

            // Finish Timestamp + Actual Captain Fee (for Completed Orders)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = TextSecondaryDark,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "$finishTimeLabel: $formattedFinishTime",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        fontSize = 11.sp
                    )
                }

                if (isDelivered) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StatusOnlineGreen.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Payments,
                                contentDescription = null,
                                tint = StatusOnlineGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "أجر الكابتن: ${order.deliveryFee.toInt()} ج.م",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = StatusOnlineGreen
                            )
                        }
                    }
                }
            }

            // Customer Info & Delivery Address when available
            if (hasCustomerInfo || hasCustomerAddress) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant.copy(alpha = 0.65f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (hasCustomerInfo) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = HeroYellow,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val phoneSuffix = if (order.customerPhone.isNotBlank() && order.customerPhone != "01000000000") {
                                " • ${order.customerPhone}"
                            } else {
                                ""
                            }
                            Text(
                                text = "${order.customerName}$phoneSuffix",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (hasCustomerAddress) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = ExpressRed,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = order.deliveryAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Cancellation Reason when available
            if (!isDelivered && cancelReason.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ExpressRed.copy(alpha = 0.12f))
                        .border(1.dp, ExpressRed.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = ExpressRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "سبب الإلغاء: $cancelReason",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Full Order Details Action Button
            OutlinedButton(
                onClick = onOpenDetails,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("view_order_details_button_${order.id}"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, DarkCardBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = DarkSurfaceVariant.copy(alpha = 0.45f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = ExpressRed,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "عرض تفاصيل الطلب كاملة",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun MerchantLogoAvatar(
    storeImage: String,
    storeName: String,
    accentColor: Color
) {
    val fallbackBox: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.14f))
                .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Storefront,
                contentDescription = storeName,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }

    if (storeImage.isBlank()) {
        fallbackBox()
    } else {
        StorageAsyncImage(
            pathOrUrl = storeImage,
            contentDescription = "شعار التاجر $storeName",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp)),
            fallback = fallbackBox
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedOrderDetailsBottomSheet(
    order: OrderEntity,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val isDelivered = order.status == OrderStatus.DELIVERED
    val statusColor = if (isDelivered) StatusOnlineGreen else ExpressRed
    val statusText = if (isDelivered) "مكتمل" else "ملغي"
    val finishTime = OrderHistoryHelper.formatFinishDateTimeArabic(OrderHistoryHelper.getFinishTimestamp(order))
    val cancelReason = order.effectiveCancellationReason.trim()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        contentColor = Color.White,
        modifier = Modifier.testTag("archived_order_details_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    MerchantLogoAvatar(
                        storeImage = order.storeImage,
                        storeName = order.storeName,
                        accentColor = statusColor
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "تفاصيل الطلب #${order.cleanOrderNumber}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = Color.White
                        )
                        Text(
                            text = order.storeName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HeroYellow
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusColor.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Financial & Timing Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRowItem(
                        label = if (isDelivered) "وقت وتاريخ الإكمال" else "وقت وتاريخ الإلغاء",
                        value = finishTime,
                        valueColor = Color.White
                    )
                    if (isDelivered) {
                        DetailRowItem(
                            label = "أجر الكابتن الفعلي",
                            value = "${order.deliveryFee.toInt()} ج.م",
                            valueColor = StatusOnlineGreen
                        )
                    }
                    if (order.orderTotalAmount > 0.0) {
                        DetailRowItem(
                            label = "إجمالي قيمة الطلب",
                            value = "${order.orderTotalAmount.toInt()} ج.م",
                            valueColor = HeroYellow
                        )
                    }
                    DetailRowItem(
                        label = "المسافة التقريبية",
                        value = "${order.distanceKm} كم",
                        valueColor = Color.LightGray
                    )
                }
            }

            if (!isDelivered && cancelReason.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.14f)),
                    border = BorderStroke(1.dp, ExpressRed.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "سبب إلغاء الطلب",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = ExpressRed
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = cancelReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Merchant & Customer Addresses Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailIconBlock(
                        icon = Icons.Default.Storefront,
                        iconTint = HeroYellow,
                        title = "التاجر وعنوان الاستلام",
                        subtitle = "${order.storeName} • ${order.pickupAddress}"
                    )
                    if (order.storePhone.isNotBlank()) {
                        DetailIconBlock(
                            icon = Icons.Default.Phone,
                            iconTint = HeroYellow,
                            title = "هاتف التاجر",
                            subtitle = order.storePhone
                        )
                    }
                    HorizontalDivider(color = DarkCardBorder)
                    DetailIconBlock(
                        icon = Icons.Default.Person,
                        iconTint = StatusOnlineGreen,
                        title = "بيانات العميل",
                        subtitle = "${order.customerName} • ${order.customerPhone}"
                    )
                    DetailIconBlock(
                        icon = Icons.Default.LocationOn,
                        iconTint = ExpressRed,
                        title = "عنوان التوصيل",
                        subtitle = order.deliveryAddress
                    )
                    if (order.itemsSummary.isNotBlank()) {
                        HorizontalDivider(color = DarkCardBorder)
                        DetailIconBlock(
                            icon = Icons.Default.ShoppingBag,
                            iconTint = Color.LightGray,
                            title = "ملخص الأصناف (${order.itemsCount})",
                            subtitle = order.itemsSummary
                        )
                    }
                    if (order.notes.isNotBlank()) {
                        DetailIconBlock(
                            icon = Icons.Default.Info,
                            iconTint = HeroYellow,
                            title = "ملاحظات الطلب",
                            subtitle = order.notes
                        )
                    }
                }
            }

            if (order.effectiveReceiptImage.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "صورة إيصال الطلب المرفقة",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                StorageAsyncImage(
                    pathOrUrl = order.effectiveReceiptImage,
                    contentDescription = "صورة إيصال الطلب",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(14.dp))
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("close_order_details_sheet_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ExpressRed)
            ) {
                Text("إغلاق التفاصيل", fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRowItem(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondaryDark
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = valueColor
        )
    }
}

@Composable
private fun DetailIconBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondaryDark
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color.White
            )
        }
    }
}
