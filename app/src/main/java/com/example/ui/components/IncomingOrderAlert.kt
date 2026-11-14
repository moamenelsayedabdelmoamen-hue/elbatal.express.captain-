package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.OrderEntity
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Incoming Order Offer Card Dialog with 60-second server-synchronized countdown timer.
 *
 * Rules:
 * - Exactly ONE action button: "قبول الطلب" (Accept Order).
 * - NO reject button is displayed under any condition.
 * - Countdown starts from 01:00 based on server expiration.
 * - If 60 seconds expire:
 *   - "انتهت مهلة قبول الطلب وتم تحويله إلى كابتن آخر" message is shown.
 *   - Accept button is disabled.
 */
@Composable
fun IncomingOrderAlert(
    order: OrderEntity,
    onAccept: () -> Unit,
    onTimeout: () -> Unit = {}
) {
    val totalSeconds = 60
    val initialRemaining = remember(order.id, order.offerExpiresAt) {
        val now = System.currentTimeMillis()
        if (order.offerExpiresAt > now) {
            val secs = ((order.offerExpiresAt - now) / 1000).toInt()
            secs.coerceIn(0, totalSeconds)
        } else if (order.offerExpiresAt == 0L) {
            60
        } else {
            0
        }
    }

    var remainingSeconds by remember(order.id) { mutableIntStateOf(initialRemaining) }
    var isExpired by remember(order.id) { mutableStateOf(initialRemaining <= 0) }

    LaunchedEffect(order.id) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
        isExpired = true
        delay(2000)
        onTimeout()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val countdownFormatted = String.format(Locale.US, "%02d:%02d", minutes, seconds)
    val progress = (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)

    Dialog(
        onDismissRequest = { /* Modal offer card remains active until accepted or timeout */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = DarkSurface,
            tonalElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, if (isExpired) Color.Gray else ExpressRed, RoundedCornerShape(26.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Pulsing Notification Badge
                Box(
                    modifier = Modifier
                        .scale(if (isExpired) 1f else pulseScale)
                        .size(60.dp)
                        .background(if (isExpired) Color.Gray else ExpressRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "عرض طلب جديد",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isExpired) "انتهت مهلة العرض" else "عرض طلب توصيل جديد! ⚡",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    ),
                    color = if (isExpired) Color.LightGray else ExpressRed
                )

                Text(
                    text = "منظومة البطل إكسبريس الذكية للإسناد",
                    style = MaterialTheme.typography.bodySmall,
                    color = HeroYellow
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 60-Second Countdown Timer Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isExpired) ExpressRed.copy(alpha = 0.15f) else DarkSurfaceVariant
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = if (isExpired) ExpressRed else HeroYellow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isExpired) "حالة العرض:" else "الوقت المتبقي للقبول:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray
                                )
                            }
                            Text(
                                text = countdownFormatted,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                ),
                                color = if (isExpired || remainingSeconds <= 10) ExpressRed else HeroYellow
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (remainingSeconds <= 10) ExpressRed else StatusOnlineGreen,
                            trackColor = Color.DarkGray
                        )
                    }
                }

                if (isExpired) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = ExpressRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "انتهت مهلة قبول الطلب وتم تحويله إلى كابتن آخر",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = ExpressRed
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Financial & Distance Banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = StatusOnlineGreen.copy(alpha = 0.15f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusOnlineGreen.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("أجرة التوصيل", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                            Text(
                                text = "${order.deliveryFee.toInt()} ج.م",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = StatusOnlineGreen
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = HeroYellow.copy(alpha = 0.15f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HeroYellow.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("المسافة والوقت", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                            Text(
                                text = "${order.distanceKm} كم • ${order.etaMinutes} د",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = HeroYellow
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Merchant & Customer Location Details
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
                        // Merchant Info
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (order.storeImage.isNotBlank()) {
                                AsyncImage(
                                    model = order.storeImage,
                                    contentDescription = "شعار التاجر",
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(HeroYellow.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Restaurant,
                                        contentDescription = null,
                                        tint = HeroYellow,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "التاجر: ${order.storeName}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                if (order.storePhone.isNotBlank()) {
                                    Text(
                                        text = "الهاتف: ${order.storePhone}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = HeroYellow
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "📍 موقع الاستلام: ${order.pickupAddress.ifBlank { "مقر التاجر" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Customer Info
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = ExpressRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "منطقة التسليم: ${order.deliveryAddress}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Text(
                                    text = "العميل: ${order.customerName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        if (order.itemsSummary.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "محتوى الطلب: ${order.itemsSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = HeroYellow
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // STRICT REQUIREMENT: Only ONE button: "قبول الطلب" (NO reject button)
                Button(
                    onClick = {
                        if (!isExpired) {
                            onAccept()
                        }
                    },
                    enabled = !isExpired,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ExpressRed,
                        disabledContainerColor = Color.DarkGray
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isExpired) "انتهت صلاحية العرض" else "قبول الطلب 🚀",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = Color.White
                    )
                }
            }
        }
    }
}
