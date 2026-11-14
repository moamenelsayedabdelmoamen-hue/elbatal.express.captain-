package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a work shift assigned to a captain from the management dashboard.
 * Time calculations and days strictly adhere to the Africa/Cairo timezone.
 */
@Entity(tableName = "captain_shifts")
data class ShiftEntity(
    @PrimaryKey val id: String, // e.g. "${captainUid}_day_${dayOfWeek}" or Firestore doc id
    val captainUid: String = "",
    val dayOfWeek: Int = 1, // Calendar.SUNDAY = 1, MONDAY = 2, ..., SATURDAY = 7
    val dayNameArabic: String = "", // السبت، الأحد، الإثنين، الثلاثاء، الأربعاء، الخميس، الجمعة
    val startTime: String = "09:00", // 24-hour format "HH:mm" (e.g. "14:00")
    val endTime: String = "17:00",   // 24-hour format "HH:mm" (e.g. "22:00")
    val isDayOff: Boolean = false,   // True if this day is a designated day off / holiday
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
