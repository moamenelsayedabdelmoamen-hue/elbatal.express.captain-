package com.example.ui.components

import android.content.Intent
import android.location.Location
import android.provider.Settings
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
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.location.GpsStatus
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen

@Composable
fun GpsStatusCard(
    isOnline: Boolean,
    gpsStatus: GpsStatus,
    currentLocation: Location?,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isOnline) {
                    when (gpsStatus) {
                        is GpsStatus.Active -> StatusOnlineGreen.copy(alpha = 0.5f)
                        is GpsStatus.Connecting -> HeroYellow.copy(alpha = 0.5f)
                        is GpsStatus.GpsDisabled, is GpsStatus.PermissionDenied -> ExpressRed.copy(alpha = 0.8f)
                        else -> Color.Gray.copy(alpha = 0.3f)
                    }
                } else Color.Gray.copy(alpha = 0.2f),
                RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isOnline) {
                                    when (gpsStatus) {
                                        is GpsStatus.Active -> StatusOnlineGreen.copy(alpha = 0.2f)
                                        is GpsStatus.Connecting -> HeroYellow.copy(alpha = 0.2f)
                                        else -> ExpressRed.copy(alpha = 0.2f)
                                    }
                                } else DarkSurfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isOnline) {
                                when (gpsStatus) {
                                    is GpsStatus.Active -> Icons.Default.GpsFixed
                                    is GpsStatus.Connecting -> Icons.Default.GpsNotFixed
                                    else -> Icons.Default.GpsOff
                                }
                            } else Icons.Default.GpsOff,
                            contentDescription = "حالة الـ GPS",
                            tint = if (isOnline) {
                                when (gpsStatus) {
                                    is GpsStatus.Active -> StatusOnlineGreen
                                    is GpsStatus.Connecting -> HeroYellow
                                    else -> ExpressRed
                                }
                            } else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "نظام التتبع المباشر GPS",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = if (isOnline) {
                                when (gpsStatus) {
                                    is GpsStatus.Active -> "متصل بالقمر الصناعي ويتم الإرسال Live"
                                    is GpsStatus.Connecting -> "جاري تثبيت إشارة الـ GPS..."
                                    is GpsStatus.GpsDisabled -> "خدمة الموقع بالجهاز مغلقة!"
                                    is GpsStatus.PermissionDenied -> "إذن الموقع غير ممنوح!"
                                    is GpsStatus.NetworkOffline -> "غير متصل بالإنترنت (في انتظار الشبكة)"
                                    is GpsStatus.Stale -> "إشارة قديمة، جاري تجديد التتبع"
                                    is GpsStatus.Inactive -> "متوقف"
                                }
                            } else "متوقف (الكابتن غير متصل)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOnline) {
                                when (gpsStatus) {
                                    is GpsStatus.Active -> StatusOnlineGreen
                                    is GpsStatus.Connecting -> HeroYellow
                                    else -> ExpressRed
                                }
                            } else Color.Gray
                        )
                    }
                }

                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isOnline && gpsStatus is GpsStatus.Active) StatusOnlineGreen.copy(alpha = 0.15f)
                            else DarkSurfaceVariant
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isOnline && gpsStatus is GpsStatus.Connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = HeroYellow
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isOnline && gpsStatus is GpsStatus.Active) StatusOnlineGreen
                                        else if (isOnline && gpsStatus is GpsStatus.Connecting) HeroYellow
                                        else Color.Gray
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = if (isOnline) {
                                when (gpsStatus) {
                                    is GpsStatus.Active -> "LIVE"
                                    is GpsStatus.Connecting -> "WAIT"
                                    else -> "ALERT"
                                }
                            } else "OFFLINE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isOnline && gpsStatus is GpsStatus.Active) StatusOnlineGreen
                            else if (isOnline && gpsStatus is GpsStatus.Connecting) HeroYellow
                            else Color.LightGray
                        )
                    }
                }
            }

            // Detailed GPS stats or Alerts
            if (isOnline) {
                Spacer(modifier = Modifier.height(12.dp))

                when (gpsStatus) {
                    is GpsStatus.Active -> {
                        val loc = currentLocation ?: gpsStatus.location
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("خط العرض (Lat)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(
                                    String.format("%.5f", loc.latitude),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("خط الطول (Lng)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(
                                    String.format("%.5f", loc.longitude),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("الدقة (±متر)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(
                                    "${loc.accuracy.toInt()} م",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = HeroYellow
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = StatusOnlineGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "موقعك متاح للوحة تحكم المشرف ونظام توجيه أقرب كابتن تلقائياً",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.LightGray
                            )
                        }
                    }

                    is GpsStatus.GpsDisabled -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = ExpressRed, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "خدمة تحديد الموقع (GPS) مقفلة بجهازك",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "لن يتم إرسال أي رحلات إليك حتى تفعيل الـ GPS من إعدادات الهاتف.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ExpressRed),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("فتح إعدادات الموقع وتفعيل GPS", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    is GpsStatus.PermissionDenied -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = ExpressRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = ExpressRed, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "إذن استخدام الموقع الجغرافي غير مفعل",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "يتطلب تطبيق كابتن البطل إكسبريس إذن الموقع الدقيق للعمل بشكل سليم.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = onRequestPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = HeroYellow, contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("منح إذن الموقع الآن", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }

                    is GpsStatus.Connecting -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkSurfaceVariant, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = HeroYellow
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "جاري الحصول على إحداثيات دقيقة من الأقمار الصناعية...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                        }
                    }

                    else -> {}
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "قم بتفعيل وضع (متصل) بالأعلى لبدء بث موقعك واستقبال أقرب الطلبات والمطاعم إليك.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}
