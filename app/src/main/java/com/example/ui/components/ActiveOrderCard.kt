package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.OrderEntity
import com.example.data.OrderStatus
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen

@Composable
fun ActiveOrderCard(
    order: OrderEntity,
    isLoading: Boolean = false,
    onMarkArrivedAtMerchant: (Long) -> Unit,
    onConfirmPickup: (Long) -> Unit,
    onOpenCustomerDataEntry: (OrderEntity) -> Unit,
    onCompleteDelivery: (OrderEntity) -> Unit,
    onSimulateMerchantConfirm: ((Long, Boolean) -> Unit)? = null
) {
    val context = LocalContext.current

    val statusTitle = when (order.status) {
        OrderStatus.ACCEPTED -> "١. في الطريق إلى مقر التاجر"
        OrderStatus.AT_STORE -> "٢. تم الوصول للتاجر - قم باستلام الطلب"
        OrderStatus.PICKED_UP -> "٣. تم الاستلام - أدخل بيانات العميل والإيصال"
        OrderStatus.ORDER_DATA_ENTERED,
        OrderStatus.AWAITING_MERCHANT_CONFIRMATION -> "٤. بانتظار تأكيد التاجر ومراجعته للبيانات"
        OrderStatus.MERCHANT_REJECTED -> "⚠️ تم رفض البيانات من التاجر - يرجى تصحيحها"
        OrderStatus.EN_ROUTE -> "٥. أكد التاجر الطلب - في الطريق لتسليم العميل"
        OrderStatus.DELIVERED -> "تم تسليم الطلب بنجاح وإغلاق المستحقات"
        else -> "الطلب قيد التنفيذ"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, ExpressRed, RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header: Active Order Label & Fee
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(StatusOnlineGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "طلب نشط #${order.cleanOrderNumber}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }

                Text(
                    text = "${order.deliveryFee.toInt()} ج.م",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    ),
                    color = HeroYellow
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stepper (5 phases):
            // 1. القبول والتوجه | 2. الوصول والاستلام | 3. بيانات العميل | 4. تأكيد التاجر | 5. التسليم
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val step1Done = true
                val step2Done = order.status != OrderStatus.ACCEPTED && order.status != OrderStatus.PENDING
                val step3Done = order.status in listOf(
                    OrderStatus.ORDER_DATA_ENTERED,
                    OrderStatus.AWAITING_MERCHANT_CONFIRMATION,
                    OrderStatus.EN_ROUTE,
                    OrderStatus.DELIVERED
                )
                val step4Done = order.merchantConfirmed || order.status == OrderStatus.EN_ROUTE || order.status == OrderStatus.DELIVERED
                val step5Done = order.status == OrderStatus.DELIVERED

                StepBadgeMini("التاجر", step1Done)
                StepBadgeMini("الاستلام", step2Done)
                StepBadgeMini("البيانات", step3Done)
                StepBadgeMini("تأكيد التاجر", step4Done)
                StepBadgeMini("التسليم", step5Done)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Current Stage Status Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (order.status) {
                        OrderStatus.MERCHANT_REJECTED -> ExpressRed.copy(alpha = 0.2f)
                        OrderStatus.EN_ROUTE, OrderStatus.DELIVERED -> StatusOnlineGreen.copy(alpha = 0.2f)
                        else -> HeroYellow.copy(alpha = 0.15f)
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (order.status) {
                            OrderStatus.MERCHANT_REJECTED -> Icons.Default.Warning
                            OrderStatus.EN_ROUTE, OrderStatus.DELIVERED -> Icons.Default.CheckCircle
                            OrderStatus.AWAITING_MERCHANT_CONFIRMATION -> Icons.Default.HourglassTop
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = when (order.status) {
                            OrderStatus.MERCHANT_REJECTED -> ExpressRed
                            OrderStatus.EN_ROUTE, OrderStatus.DELIVERED -> StatusOnlineGreen
                            else -> HeroYellow
                        },
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Merchant / Location Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    // Merchant line
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Storefront,
                                contentDescription = null,
                                tint = HeroYellow,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "التاجر: ${order.storeName}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }

                        if (order.storePhone.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.storePhone}"))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "اتصال بالتاجر", tint = StatusOnlineGreen)
                            }
                        }
                    }

                    Text(
                        text = "العنوان: ${order.pickupAddress.ifBlank { "مقر التاجر" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Customer line
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = ExpressRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "العميل: ${order.customerName}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }

                        if (order.customerPhone.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.customerPhone}"))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "اتصال بالعميل", tint = StatusOnlineGreen)
                            }
                        }
                    }

                    Text(
                        text = "عنوان التسليم: ${order.deliveryAddress}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic Action Button Sequence based on server lifecycle:
            when (order.status) {
                // 1. ACCEPTED: Show Merchant Map, Directions, Call, and "تم الوصول إلى التاجر"
                OrderStatus.ACCEPTED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Open Map Directions
                        OutlinedButton(
                            onClick = {
                                openDirections(context, order.restaurantLatitude, order.restaurantLongitude, order.pickupAddress.ifBlank { order.storeName })
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HeroYellow)
                        ) {
                            Icon(Icons.Default.Directions, contentDescription = null, tint = HeroYellow)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("الاتجاهات", color = HeroYellow)
                        }

                        // Button: "تم الوصول إلى التاجر"
                        Button(
                            onClick = { onMarkArrivedAtMerchant(order.id) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1.5f)
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ExpressRed),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تم الوصول للتاجر 📍", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                // 2. AT_STORE: "استلام الطلب"
                OrderStatus.AT_STORE -> {
                    Button(
                        onClick = { onConfirmPickup(order.id) },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HeroYellow, contentColor = Color.Black),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "استلام الطلب من التاجر 📦",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                // 3. PICKED_UP: "إدخال بيانات العميل وصورة الإيصال"
                OrderStatus.PICKED_UP -> {
                    Button(
                        onClick = { onOpenCustomerDataEntry(order) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ExpressRed),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تسجيل بيانات العميل والإيصال 📷",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }

                // 4. AWAITING_MERCHANT_CONFIRMATION:
                // Cannot click delivered yet! Waiting for merchant to confirm on server
                OrderStatus.ORDER_DATA_ENTERED,
                OrderStatus.AWAITING_MERCHANT_CONFIRMATION -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = HeroYellow, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "تم إرسال البيانات للتاجر، وبانتظار المراجعة والتأكيد",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "لا يمكن تسليم الطلب قبل اعتماد التاجر لبيانات الإيصال من لوحة التحكم.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Simulation button for testing merchant confirmation if enabled
                        if (onSimulateMerchantConfirm != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onSimulateMerchantConfirm(order.id, true) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusOnlineGreen)
                                ) {
                                    Text("محاكاة: تأكيد التاجر ✓", color = StatusOnlineGreen, fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = { onSimulateMerchantConfirm(order.id, false) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, ExpressRed)
                                ) {
                                    Text("محاكاة: رفض التاجر ✕", color = ExpressRed, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // 5. MERCHANT_REJECTED: Rejection reason shown, button to correct and re-submit
                OrderStatus.MERCHANT_REJECTED -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "سبب الرفض: ${order.rejectionReason.ifBlank { "بيانات الإيصال غير مطابقة" }}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = ExpressRed
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { onOpenCustomerDataEntry(order) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ExpressRed),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تعديل وإعادة إرسال البيانات 📝", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 6. EN_ROUTE: Merchant confirmed! Direction to customer + "تم التوصيل"
                OrderStatus.EN_ROUTE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                openDirections(context, null, null, order.deliveryAddress)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, HeroYellow)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = HeroYellow)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("خريطة العميل", color = HeroYellow)
                        }

                        Button(
                            onClick = { onCompleteDelivery(order) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1.5f)
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StatusOnlineGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تم التوصيل 🏁", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                // 7. DELIVERED
                OrderStatus.DELIVERED -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = StatusOnlineGreen.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusOnlineGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "تم تسليم الطلب بنجاح وإضافة المستحقات لمحفظتك",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = StatusOnlineGreen
                            )
                        }
                    }
                }

                else -> { /* Pending or Expired */ }
            }
        }
    }
}

@Composable
private fun StepBadgeMini(label: String, isDone: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (isDone) StatusOnlineGreen else Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isDone) FontWeight.Bold else FontWeight.Normal,
                fontSize = 10.sp
            ),
            color = if (isDone) Color.White else Color.Gray
        )
    }
}

private fun openDirections(context: Context, lat: Double?, lng: Double?, addressQuery: String) {
    try {
        val mapUri = if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            Uri.parse("google.navigation:q=$lat,$lng")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(addressQuery)}")
        }
        val intent = Intent(Intent.ACTION_VIEW, mapUri)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val fallbackUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(addressQuery)}")
            context.startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
        } catch (e2: Exception) {
            Toast.makeText(context, "تعذر فتح تطبيق الخرائط", Toast.LENGTH_SHORT).show()
        }
    }
}
