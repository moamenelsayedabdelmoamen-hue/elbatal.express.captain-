package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import com.example.ui.components.ShiftStatusCard
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen
import java.util.Calendar
import java.util.Locale

@Composable
fun ShiftsScreen(
    shifts: List<ShiftEntity>,
    shiftStatus: ShiftStatus,
    currentCairoTime: String,
    currentCairoDate: String,
    dailyWorkHours: Double,
    weeklyWorkHours: Double,
    isShiftNetworkConnected: Boolean,
    isLoading: Boolean = false,
    onRefreshShifts: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val todayDayOfWeek = ShiftManager.getCurrentCairoCalendar().get(Calendar.DAY_OF_WEEK)

    // Standard week days in order: Saturday to Friday
    val standardWeekDays = listOf(
        Calendar.SATURDAY to "السبت",
        Calendar.SUNDAY to "الأحد",
        Calendar.MONDAY to "الإثنين",
        Calendar.TUESDAY to "الثلاثاء",
        Calendar.WEDNESDAY to "الأربعاء",
        Calendar.THURSDAY to "الخميس",
        Calendar.FRIDAY to "الجمعة"
    )

    val weekItems = standardWeekDays.map { (dayCode, dayArabic) ->
        val dayShifts = shifts.filter { it.dayOfWeek == dayCode }
            .sortedBy { ShiftManager.parseTimeToMinutes(it.startTime) }
        Triple(dayCode, dayArabic, dayShifts)
    }

    val totalHours = ShiftManager.calculateWeeklyWorkHours(shifts)
    val workingDaysCount = shifts.filter { !it.isDayOff }.map { it.dayOfWeek }.distinct().size
    val daysOffCount = shifts.filter { it.isDayOff }.map { it.dayOfWeek }.distinct().size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
            .testTag("shifts_screen")
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(HeroYellow.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = "شفتات الأسبوع",
                        tint = HeroYellow,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "شفتات العمل",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "جدول الأسبوع بتوقيت مصر (Africa/Cairo)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }
            }

            IconButton(
                onClick = onRefreshShifts,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant)
                    .testTag("refresh_shifts_button")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = HeroYellow,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "تحديث الشفتات",
                        tint = HeroYellow
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live Shift Status Card (Banner)
        ShiftStatusCard(
            shiftStatus = shiftStatus,
            currentCairoTime = currentCairoTime,
            currentCairoDate = currentCairoDate,
            dailyWorkHours = dailyWorkHours,
            weeklyWorkHours = weeklyWorkHours,
            isNetworkConnected = isShiftNetworkConnected,
            isLoading = isLoading,
            onOpenWeeklySchedule = null, // Already on shifts screen
            onRefreshShifts = onRefreshShifts
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Management Lock Notice
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = HeroYellow,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "جدول العمل معتمد ومحدد من قبل إدارة البطل إكسبريس فقط، ويتم تحديثه لحظياً عبر السحابة.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = Color.LightGray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Weekly Summary Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "إجمالي الساعات",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ShiftManager.formatShiftDurationArabic(totalHours),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = HeroYellow
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "أيام العمل",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$workingDaysCount أيام",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = StatusOnlineGreen
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "أيام الإجازة",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$daysOffCount يوم",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ExpressRed
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Weekdays Header
        Text(
            text = "جدول شفتات الأسبوع (السبت - الجمعة)",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 7 Days List
        weekItems.forEach { (dayCode, dayArabic, dayShifts) ->
            val isToday = (dayCode == todayDayOfWeek)
            val workingShifts = dayShifts.filter { !it.isDayOff }
            val isDayOff = workingShifts.isEmpty() && (dayShifts.any { it.isDayOff } || dayCode == Calendar.FRIDAY)

            val borderModifier = if (isToday) {
                Modifier.border(1.5.dp, HeroYellow, RoundedCornerShape(14.dp))
            } else {
                Modifier.border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .then(borderModifier),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isToday) DarkSurfaceVariant.copy(alpha = 0.9f) else DarkSurface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Day Name and Today badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dayArabic,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isToday) HeroYellow else Color.White
                        )

                        if (isToday) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(HeroYellow)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "اليوم 📍",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    ),
                                    color = Color.Black
                                )
                            }
                        }
                    }

                    // Render every shift assigned to the day; do not collapse multiple shifts into one.
                    if (workingShifts.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDayOff) ExpressRed.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.2f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isDayOff) "عطلة أسبوعية ☕" else "غير محدد",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isDayOff) ExpressRed else Color.LightGray
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            workingShifts.forEachIndexed { index, shift ->
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "الوردية ${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = HeroYellow
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AccessTime,
                                            contentDescription = null,
                                            tint = HeroYellow,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${ShiftManager.formatTimeArabic(shift.startTime)} إلى ${ShiftManager.formatTimeArabic(shift.endTime)}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White
                                        )
                                    }
                                    val startMinutes = ShiftManager.parseTimeToMinutes(shift.startTime)
                                    val endMinutes = ShiftManager.parseTimeToMinutes(shift.endTime)
                                    if (endMinutes <= startMinutes) {
                                        Text(
                                            text = "النهاية في اليوم التالي",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = HeroYellow.copy(alpha = 0.9f)
                                        )
                                    }
                                    val duration = ShiftManager.calculateShiftDurationHours(shift)
                                    Text(
                                        text = ShiftManager.formatShiftDurationArabic(duration),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
