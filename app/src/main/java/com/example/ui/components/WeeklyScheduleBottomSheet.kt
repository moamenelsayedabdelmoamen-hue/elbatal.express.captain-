package com.example.ui.components

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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
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
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ExpressRed
import com.example.ui.theme.HeroYellow
import com.example.ui.theme.StatusOnlineGreen
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyScheduleBottomSheet(
    shifts: List<ShiftEntity>,
    sheetState: SheetState,
    onDismiss: () -> Unit
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

    // Map existing shifts or empty entries
    val weekItems = standardWeekDays.map { (dayCode, dayArabic) ->
        val foundShift = shifts.find { it.dayOfWeek == dayCode }
        Triple(dayCode, dayArabic, foundShift)
    }

    val totalHours = ShiftManager.calculateWeeklyWorkHours(shifts)
    val workingDaysCount = shifts.count { !it.isDayOff }
    val daysOffCount = shifts.count { it.isDayOff }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(scrollState)
                .testTag("weekly_schedule_sheet")
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(HeroYellow.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = HeroYellow,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "جدول شفتات الأسبوع",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = "لوحة تحكم إدارة البطل إكسبريس (Africa/Cairo)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        tint = Color.LightGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Management Control Notice (Rule: Captain cannot modify schedule)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = HeroYellow,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "جدول العمل معتمد ومحدد من قبل الإدارة فقط، ولا يمكن تعديله بواسطة الكابتن.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = Color.LightGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Weekly Summary Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text("إجمالي الساعات", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(if (totalHours % 1.0 == 0.0) "${totalHours.toInt()} ساعة" else String.format(Locale.getDefault(), "%.1f ساعة", totalHours), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = HeroYellow)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text("أيام العمل", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text("$workingDaysCount أيام", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = StatusOnlineGreen)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text("أيام الإجازة", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text("$daysOffCount يوم", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = ExpressRed)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "مواعيد الشفتات حسب الأيام:",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 7 Days List
            weekItems.forEach { (dayCode, dayArabic, shift) ->
                val isToday = (dayCode == todayDayOfWeek)
                val isDayOff = shift?.isDayOff ?: (dayCode == Calendar.FRIDAY)

                val cardBorder = if (isToday) {
                    BorderStrokeModifier(1.5.dp, HeroYellow)
                } else {
                    BorderStrokeModifier(1.dp, Color.White.copy(alpha = 0.05f))
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .then(cardBorder),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isToday) DarkSurfaceVariant.copy(alpha = 0.9f) else DarkBackground
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

                        // Shift Details or Day Off pill
                        if (shift == null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Gray.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "غير محدد",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                            }
                        } else if (isDayOff) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ExpressRed.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.WbSunny,
                                        contentDescription = null,
                                        tint = ExpressRed,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "يوم إجازة / عطلة رسمية 🌴",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = ExpressRed
                                        )
                                    )
                                }
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AccessTime,
                                        contentDescription = null,
                                        tint = StatusOnlineGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${shift.startTime} إلى ${shift.endTime}  (${ShiftManager.formatTimeArabic(shift.startTime)} - ${ShiftManager.formatTimeArabic(shift.endTime)})",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                                val shiftDur = ShiftManager.calculateShiftDurationHours(shift)
                                Text(
                                    text = ShiftManager.formatShiftDurationArabic(shiftDur),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Done / Close Button
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HeroYellow)
            ) {
                Text(
                    text = "تم وفهم الجدول",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.Black
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun BorderStrokeModifier(width: androidx.compose.ui.unit.Dp, color: Color): Modifier {
    return Modifier.border(width, color, RoundedCornerShape(14.dp))
}
