package com.example.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Represents the distinct work states for a captain based on Cairo working hours.
 */
sealed class ShiftStatus {
    abstract val isAvailableForOrders: Boolean

    /**
     * Active shift (أثناء ساعات العمل):
     * Green indicator, "أنت تعمل الآن", "أنت متاح لاستقبال الطلبات",
     * Start/End time, and remaining countdown until shift ends.
     */
    data class Active(
        val shift: ShiftEntity,
        val remainingMillis: Long,
        val remainingFormatted: String,
        val startFormatted: String,
        val endFormatted: String
    ) : ShiftStatus() {
        override val isAvailableForOrders: Boolean = true
    }

    /**
     * Outside working hours (خارج ساعات العمل):
     * Red indicator, "أنت خارج ساعات العمل", "لا تستقبل طلبات جديدة حالياً",
     * Next scheduled shift info.
     */
    data class OutsideShift(
        val nextShift: ShiftEntity?,
        val nextShiftDescription: String,
        val isDayOffToday: Boolean
    ) : ShiftStatus() {
        override val isAvailableForOrders: Boolean = false
    }

    /**
     * Before shift starts (قبل بداية الشفت):
     * Orange/Amber indicator, "لم يبدأ شفت العمل بعد",
     * Time remaining until shift starts.
     */
    data class UpcomingSoon(
        val shift: ShiftEntity,
        val millisUntilStart: Long,
        val countdownFormatted: String,
        val startFormatted: String,
        val endFormatted: String
    ) : ShiftStatus() {
        override val isAvailableForOrders: Boolean = false
    }

    /**
     * No schedule assigned (في حالة عدم وجود جدول):
     * Gray indicator, "لا يوجد شفت عمل محدد لك حالياً".
     */
    data class NoSchedule(
        val message: String = "لا يوجد شفت عمل محدد لك حالياً"
    ) : ShiftStatus() {
        override val isAvailableForOrders: Boolean = false
    }
}

/**
 * ShiftManager handles all timezone calculations, time conversions,
 * shift status determination, and weekly hour aggregations in Africa/Cairo.
 */
object ShiftManager {
    val CAIRO_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Africa/Cairo")

    val ARABIC_DAYS_MAP = mapOf(
        Calendar.SATURDAY to "السبت",
        Calendar.SUNDAY to "الأحد",
        Calendar.MONDAY to "الإثنين",
        Calendar.TUESDAY to "الثلاثاء",
        Calendar.WEDNESDAY to "الأربعاء",
        Calendar.THURSDAY to "الخميس",
        Calendar.FRIDAY to "الجمعة"
    )

    fun getCurrentCairoCalendar(): Calendar {
        return Calendar.getInstance(CAIRO_TIME_ZONE)
    }

    fun getCurrentCairoTimeFormatted(): String {
        val cal = getCurrentCairoCalendar()
        val hour = cal.get(Calendar.HOUR)
        val displayHour = if (hour == 0) 12 else hour
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val isAm = cal.get(Calendar.AM_PM) == Calendar.AM
        val amPmArabic = if (isAm) "ص" else "م"
        return String.format(Locale.getDefault(), "%02d:%02d:%02d %s", displayHour, minute, second, amPmArabic)
    }

    fun getCurrentCairoDateFormatted(): String {
        val cal = getCurrentCairoCalendar()
        val dayName = ARABIC_DAYS_MAP[cal.get(Calendar.DAY_OF_WEEK)] ?: "اليوم"
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val monthNames = arrayOf(
            "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
        )
        val monthName = monthNames.getOrElse(cal.get(Calendar.MONTH)) { "" }
        val year = cal.get(Calendar.YEAR)
        return "$dayName $dayOfMonth $monthName $year"
    }

    /**
     * Parses "HH:mm" or "hh:mm a" into total minutes from start of day (0..1439).
     */
    fun parseTimeToMinutes(timeStr: String): Int {
        val clean = normalizeArabicDigits(timeStr).trim()
        if (clean.isBlank()) return 0

        // Check if AM/PM is present
        val isPm = clean.contains("PM", ignoreCase = true) || clean.contains("م")
        val isAm = clean.contains("AM", ignoreCase = true) || clean.contains("ص")
        val digits = clean.replace(Regex("[^0-9:]"), "").split(":")

        if (digits.isEmpty()) return 0
        var hour = digits[0].toIntOrNull() ?: 0
        val minute = if (digits.size > 1) digits[1].toIntOrNull() ?: 0 else 0

        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        return (hour * 60 + minute).coerceIn(0, 1439)
    }

    /**
     * Formats a 24-hour time string like "14:00" to Arabic representation "02:00 م".
     */
    fun formatTimeArabic(timeStr: String): String {
        val clean = normalizeArabicDigits(timeStr).trim()
        if (clean.isBlank()) return "--:--"
        val minutes = parseTimeToMinutes(clean)
        val hour24 = minutes / 60
        val min = minutes % 60
        val isAm = hour24 < 12
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val amPm = if (isAm) "ص" else "م"
        return String.format(Locale.getDefault(), "%02d:%02d %s", hour12, min, amPm)
    }

    /**
     * Formats duration in milliseconds to "X ساعة و Y دقيقة و Z ثانية" or "HH:mm:ss".
     */
    fun formatDurationDetailed(millis: Long): String {
        if (millis <= 0) return "0 ثانية"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "$hours س و $minutes د و $seconds ث"
            minutes > 0 -> "$minutes د و $seconds ث"
            else -> "$seconds ثانية"
        }
    }

    /**
     * Calculates work duration for a shift in hours.
     */
    fun calculateShiftDurationHours(shift: ShiftEntity): Double {
        if (shift.isDayOff) return 0.0
        val startMin = parseTimeToMinutes(shift.startTime)
        val endMin = parseTimeToMinutes(shift.endTime)
        val diffMin = if (endMin > startMin) {
            endMin - startMin
        } else {
            // End at or before start means the shift finishes on the following day.
            (1440 - startMin) + endMin
        }
        return diffMin / 60.0
    }

    /**
     * Formats shift duration in hours to grammatically accurate Arabic string.
     * Handles integers and fractions (e.g. 8.0 -> "8 ساعات عمل", 7.5 -> "7.5 ساعة عمل", 1.0 -> "ساعة عمل واحدة")
     */
    fun formatShiftDurationArabic(hours: Double): String {
        if (hours <= 0.0) return "0 دقيقة عمل"
        val totalMinutes = Math.round(hours * 60.0).toInt()
        val wholeHours = totalMinutes / 60
        val remainingMinutes = totalMinutes % 60
        val hoursLabel = when (wholeHours) {
            1 -> "ساعة"
            2 -> "ساعتين"
            in 3..10 -> "ساعات"
            else -> "ساعة"
        }
        val minutesLabel = when (remainingMinutes) {
            1 -> "دقيقة"
            2 -> "دقيقتين"
            in 3..10 -> "دقائق"
            else -> "دقيقة"
        }
        return when {
            wholeHours == 0 -> "$remainingMinutes $minutesLabel عمل"
            remainingMinutes == 0 && wholeHours == 1 -> "ساعة عمل واحدة"
            remainingMinutes == 0 && wholeHours == 2 -> "ساعتين عمل"
            remainingMinutes == 0 -> "$wholeHours $hoursLabel عمل"
            wholeHours == 1 -> "ساعة و $remainingMinutes $minutesLabel عمل"
            wholeHours == 2 -> "ساعتين و $remainingMinutes $minutesLabel عمل"
            else -> "$wholeHours $hoursLabel و $remainingMinutes $minutesLabel عمل"
        }
    }

    /**
     * Calculates total weekly work hours for all assigned shifts.
     */
    fun calculateWeeklyWorkHours(shifts: List<ShiftEntity>): Double {
        return shifts.filter { !it.isDayOff }.sumOf { calculateShiftDurationHours(it) }
    }

    /**
     * Calculates current ShiftStatus based on the captain's weekly shifts and Cairo current time.
     */
    fun calculateShiftStatus(shifts: List<ShiftEntity>, nowCal: Calendar = getCurrentCairoCalendar()): ShiftStatus {
        if (shifts.isEmpty()) return ShiftStatus.NoSchedule()
        val todayDayOfWeek = nowCal.get(Calendar.DAY_OF_WEEK)
        val currentMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
        val currentSeconds = nowCal.get(Calendar.SECOND)
        val workingShifts = shifts.filter { !it.isDayOff }
        val yesterdayDayOfWeek = if (todayDayOfWeek == Calendar.SUNDAY) Calendar.SATURDAY else todayDayOfWeek - 1

        val activeYesterday = workingShifts
            .filter { it.dayOfWeek == yesterdayDayOfWeek }
            .sortedBy { parseTimeToMinutes(it.startTime) }
            .firstOrNull { shift ->
                val start = parseTimeToMinutes(shift.startTime)
                val end = parseTimeToMinutes(shift.endTime)
                end <= start && currentMinutes < end
            }
        if (activeYesterday != null) {
            val remainingMillis = ((parseTimeToMinutes(activeYesterday.endTime) - currentMinutes) * 60L - currentSeconds) * 1000L
            val safeRemaining = remainingMillis.coerceAtLeast(1000L)
            return ShiftStatus.Active(
                shift = activeYesterday, remainingMillis = safeRemaining,
                remainingFormatted = formatDurationDetailed(safeRemaining),
                startFormatted = formatTimeArabic(activeYesterday.startTime),
                endFormatted = formatTimeArabic(activeYesterday.endTime)
            )
        }

        val todayShifts = workingShifts
            .filter { it.dayOfWeek == todayDayOfWeek }
            .sortedBy { parseTimeToMinutes(it.startTime) }
        val activeToday = todayShifts.firstOrNull { shift ->
            val start = parseTimeToMinutes(shift.startTime)
            val end = parseTimeToMinutes(shift.endTime)
            if (end > start) currentMinutes in start until end else currentMinutes >= start
        }
        if (activeToday != null) {
            val start = parseTimeToMinutes(activeToday.startTime)
            val end = parseTimeToMinutes(activeToday.endTime)
            val remainingMin = if (end > start) end - currentMinutes else (1440 - currentMinutes) + end
            val remainingMillis = (remainingMin * 60L - currentSeconds) * 1000L
            val safeRemaining = remainingMillis.coerceAtLeast(1000L)
            return ShiftStatus.Active(
                shift = activeToday, remainingMillis = safeRemaining,
                remainingFormatted = formatDurationDetailed(safeRemaining),
                startFormatted = formatTimeArabic(activeToday.startTime),
                endFormatted = formatTimeArabic(activeToday.endTime)
            )
        }

        val nextToday = todayShifts.firstOrNull { parseTimeToMinutes(it.startTime) > currentMinutes }
        if (nextToday != null) {
            val minUntilStart = parseTimeToMinutes(nextToday.startTime) - currentMinutes
            val millisUntilStart = (minUntilStart * 60L - currentSeconds) * 1000L
            return if (minUntilStart <= 180) {
                ShiftStatus.UpcomingSoon(
                    shift = nextToday,
                    millisUntilStart = millisUntilStart.coerceAtLeast(1000L),
                    countdownFormatted = formatDurationDetailed(millisUntilStart.coerceAtLeast(1000L)),
                    startFormatted = formatTimeArabic(nextToday.startTime),
                    endFormatted = formatTimeArabic(nextToday.endTime)
                )
            } else {
                ShiftStatus.OutsideShift(
                    nextShift = nextToday,
                    nextShiftDescription = "يبدأ شفت اليوم الساعة ${formatTimeArabic(nextToday.startTime)} (متبقي ${minUntilStart / 60} ساعة)",
                    isDayOffToday = false
                )
            }
        }

        val nextShift = findNextUpcomingShift(shifts, todayDayOfWeek, currentMinutes)
        val isDayOffToday = todayShifts.isEmpty()
        val nextText = if (nextShift != null) {
            "الشفت القادم: ${nextShift.dayNameArabic} الساعة ${formatTimeArabic(nextShift.startTime)}"
        } else "لا توجد شفتات قادمة محددة"
        return ShiftStatus.OutsideShift(
            nextShift = nextShift,
            nextShiftDescription = if (isDayOffToday) "اليوم عطلة أو لا توجد وردية محددة. $nextText" else "انتهت ورديات اليوم. $nextText",
            isDayOffToday = isDayOffToday
        )
    }

    /**
     * Finds the next working shift occurring after today/now.
     */
    private fun findNextUpcomingShift(
        shifts: List<ShiftEntity>,
        currentDayOfWeek: Int,
        currentMinutes: Int
    ): ShiftEntity? {
        val workingShifts = shifts.filter { !it.isDayOff }
        if (workingShifts.isEmpty()) return null
        val laterToday = workingShifts
            .filter { it.dayOfWeek == currentDayOfWeek && parseTimeToMinutes(it.startTime) > currentMinutes }
            .minByOrNull { parseTimeToMinutes(it.startTime) }
        if (laterToday != null) return laterToday
        for (offset in 1..7) {
            val targetDay = ((currentDayOfWeek - 1 + offset) % 7) + 1
            val nextForDay = workingShifts
                .filter { it.dayOfWeek == targetDay }
                .minByOrNull { parseTimeToMinutes(it.startTime) }
            if (nextForDay != null) return nextForDay
        }
        return null
    }

    /**
     * Standard 7-day schedule (Saturday to Thursday active, Friday off)
     * used as fallback or template when setting up new captain schedule.
     */
    fun generateDefaultWeeklyShifts(captainUid: String): List<ShiftEntity> {
        val days = listOf(
            Calendar.SATURDAY to "السبت",
            Calendar.SUNDAY to "الأحد",
            Calendar.MONDAY to "الإثنين",
            Calendar.TUESDAY to "الثلاثاء",
            Calendar.WEDNESDAY to "الأربعاء",
            Calendar.THURSDAY to "الخميس",
            Calendar.FRIDAY to "الجمعة"
        )

        return days.map { (dayCode, dayArabic) ->
            val isFriday = (dayCode == Calendar.FRIDAY)
            ShiftEntity(
                id = "${captainUid}_day_$dayCode",
                captainUid = captainUid,
                dayOfWeek = dayCode,
                dayNameArabic = dayArabic,
                startTime = if (isFriday) "00:00" else "12:00",
                endTime = if (isFriday) "00:00" else "20:00",
                isDayOff = isFriday,
                notes = if (isFriday) "عطلة أسبوعية رسمية" else "شفت مسائي معتمد من الإدارة"
            )
        }
    }
}

fun parseTimeRangeString(str: String): Pair<String, String>? {
    val clean = normalizeArabicDigits(str).trim()
    if (clean.isBlank()) return null
    val separators = listOf(
        " - ", " – ", " — ",
        " إلى ", " الى ", " إلى", " الى ", "إلى", "الى",
        " to ", " to", "to ",
        " حتى ", "حتى",
        " till ", " until ",
        "-", "–", "—"
    )
    for (sep in separators) {
        if (clean.contains(sep)) {
            val parts = clean.split(sep, limit = 2)
            if (parts.size == 2) {
                val p1 = parts[0].trim().removePrefix("من").removePrefix("from").trim()
                val p2 = parts[1].trim().removePrefix("إلى").removePrefix("الى").removePrefix("to").trim()
                if (p1.isNotBlank() && p2.isNotBlank()) {
                    return Pair(p1, p2)
                }
            }
        }
    }
    return null
}

fun extractRawStartTime(data: Map<String, Any?>): Any? {
    // 1. Direct explicit start time keys
    data["startTime"]?.let { return it }
    data["start_time"]?.let { return it }
    data["shiftStart"]?.let { return it }
    data["shift_start"]?.let { return it }
    data["shiftStartTime"]?.let { return it }
    data["shift_start_time"]?.let { return it }
    data["shift_starts_at"]?.let { return it }
    data["shiftFrom"]?.let { return it }
    data["shift_from"]?.let { return it }
    data["startShift"]?.let { return it }
    data["start_shift"]?.let { return it }
    data["duty_start"]?.let { return it }
    data["dutyStart"]?.let { return it }
    data["dutyStartTime"]?.let { return it }
    data["duty_start_time"]?.let { return it }
    data["work_start"]?.let { return it }
    data["workStart"]?.let { return it }
    data["workStartTime"]?.let { return it }
    data["work_start_time"]?.let { return it }
    data["work_from"]?.let { return it }
    data["workFrom"]?.let { return it }
    data["fromTime"]?.let { return it }
    data["from_time"]?.let { return it }
    data["time_from"]?.let { return it }
    data["timeFrom"]?.let { return it }
    data["start"]?.let { return it }
    data["from"]?.let { return it }
    data["open"]?.let { return it }
    data["openTime"]?.let { return it }
    data["checkIn"]?.let { return it }
    data["check_in"]?.let { return it }
    data["begin"]?.let { return it }
    data["beginning"]?.let { return it }
    data["start_at"]?.let { return it }
    data["startAt"]?.let { return it }
    data["starts_at"]?.let { return it }
    data["startsAt"]?.let { return it }
    data["startHour"]?.let { return it }
    data["start_hour"]?.let { return it }
    data["time_start"]?.let { return it }
    data["timeStart"]?.let { return it }

    // Arabic field variations from Egyptian admin dashboards
    data["وقت_البدء"]?.let { return it }
    data["وقت_البداية"]?.let { return it }
    data["بداية_الشفت"]?.let { return it }
    data["بداية"]?.let { return it }
    data["بدء_الشفت"]?.let { return it }
    data["بدء"]?.let { return it }
    data["من_الساعة"]?.let { return it }
    data["وقت_بداية_الشفت"]?.let { return it }
    data["وقت_بدء_الشفت"]?.let { return it }
    data["بداية_الدوام"]?.let { return it }
    data["بدء_الدوام"]?.let { return it }
    data["ساعة_البدء"]?.let { return it }
    data["ساعة_البداية"]?.let { return it }
    data["دوام_من"]?.let { return it }
    data["من"]?.let { return it }
    data["ميعاد_البدء"]?.let { return it }
    data["موعد_البدء"]?.let { return it }
    data["موعد_البداية"]?.let { return it }

    // 2. Check string range fields (e.g. shift = "10:00 - 18:00")
    val candidateRangeKeys = listOf(
        "shift", "shift_hours", "shiftHours", "workingHours", "working_hours",
        "workHours", "work_hours", "shift_time", "shiftTime", "shift_times",
        "shift_range", "shiftRange", "schedule_time", "scheduleTime", "hours",
        "time", "times", "timing", "مواعيد_العمل", "ساعات_العمل", "وقت_العمل",
        "فترة_العمل", "الدوام", "أوقات_العمل", "جدول_العمل"
    )
    for (k in candidateRangeKeys) {
        val v = data[k]
        if (v is String) {
            val range = parseTimeRangeString(v)
            if (range != null) return range.first
        }
    }

    // 3. Submaps fallback
    val subMapKeys = listOf(
        "shift", "currentShift", "workingHours", "workHours", "work_hours",
        "hours", "time", "timing", "schedule", "workSchedule"
    )
    for (k in subMapKeys) {
        val map = data[k] as? Map<*, *>
        if (map != null) {
            @Suppress("UNCHECKED_CAST")
            val extracted = extractRawStartTime(map as Map<String, Any?>)
            if (extracted != null) return extracted
        }
    }

    return null
}

fun extractRawEndTime(data: Map<String, Any?>): Any? {
    // 1. Direct explicit end time keys
    data["endTime"]?.let { return it }
    data["end_time"]?.let { return it }
    data["shiftEnd"]?.let { return it }
    data["shift_end"]?.let { return it }
    data["shiftEndTime"]?.let { return it }
    data["shift_end_time"]?.let { return it }
    data["shift_ends_at"]?.let { return it }
    data["shiftTo"]?.let { return it }
    data["shift_to"]?.let { return it }
    data["endShift"]?.let { return it }
    data["end_shift"]?.let { return it }
    data["duty_end"]?.let { return it }
    data["dutyEnd"]?.let { return it }
    data["dutyEndTime"]?.let { return it }
    data["duty_end_time"]?.let { return it }
    data["work_end"]?.let { return it }
    data["workEnd"]?.let { return it }
    data["workEndTime"]?.let { return it }
    data["work_end_time"]?.let { return it }
    data["work_to"]?.let { return it }
    data["workTo"]?.let { return it }
    data["toTime"]?.let { return it }
    data["to_time"]?.let { return it }
    data["time_to"]?.let { return it }
    data["timeTo"]?.let { return it }
    data["end"]?.let { return it }
    data["to"]?.let { return it }
    data["close"]?.let { return it }
    data["closeTime"]?.let { return it }
    data["checkOut"]?.let { return it }
    data["check_out"]?.let { return it }
    data["finish"]?.let { return it }
    data["end_at"]?.let { return it }
    data["endAt"]?.let { return it }
    data["ends_at"]?.let { return it }
    data["endsAt"]?.let { return it }
    data["endHour"]?.let { return it }
    data["end_hour"]?.let { return it }
    data["time_end"]?.let { return it }
    data["timeEnd"]?.let { return it }

    // Arabic field variations from Egyptian admin dashboards
    data["وقت_الانتهاء"]?.let { return it }
    data["وقت_النهاية"]?.let { return it }
    data["نهاية_الشفت"]?.let { return it }
    data["نهاية"]?.let { return it }
    data["انتهاء_الشفت"]?.let { return it }
    data["انتهاء"]?.let { return it }
    data["الى_الساعة"]?.let { return it }
    data["إلى_الساعة"]?.let { return it }
    data["وقت_نهاية_الشفت"]?.let { return it }
    data["وقت_انتهاء_الشفت"]?.let { return it }
    data["نهاية_الدوام"]?.let { return it }
    data["انتهاء_الدوام"]?.let { return it }
    data["ساعة_الانتهاء"]?.let { return it }
    data["ساعة_النهاية"]?.let { return it }
    data["دوام_الى"]?.let { return it }
    data["دوام_إلى"]?.let { return it }
    data["الى"]?.let { return it }
    data["إلى"]?.let { return it }
    data["ميعاد_الانتهاء"]?.let { return it }
    data["ميعاد_النهاية"]?.let { return it }
    data["موعد_الانتهاء"]?.let { return it }
    data["موعد_النهاية"]?.let { return it }

    // 2. Check string range fields (e.g. shift = "10:00 - 18:00")
    val candidateRangeKeys = listOf(
        "shift", "shift_hours", "shiftHours", "workingHours", "working_hours",
        "workHours", "work_hours", "shift_time", "shiftTime", "shift_times",
        "shift_range", "shiftRange", "schedule_time", "scheduleTime", "hours",
        "time", "times", "timing", "مواعيد_العمل", "ساعات_العمل", "وقت_العمل",
        "فترة_العمل", "الدوام", "أوقات_العمل", "جدول_العمل"
    )
    for (k in candidateRangeKeys) {
        val v = data[k]
        if (v is String) {
            val range = parseTimeRangeString(v)
            if (range != null) return range.second
        }
    }

    // 3. Submaps fallback
    val subMapKeys = listOf(
        "shift", "currentShift", "workingHours", "workHours", "work_hours",
        "hours", "time", "timing", "schedule", "workSchedule"
    )
    for (k in subMapKeys) {
        val map = data[k] as? Map<*, *>
        if (map != null) {
            @Suppress("UNCHECKED_CAST")
            val extracted = extractRawEndTime(map as Map<String, Any?>)
            if (extracted != null) return extracted
        }
    }

    return null
}

fun extractRawHours(data: Map<String, Any?>): Double? {
    val candidateHoursKeys = listOf(
        "shift_hours", "shiftHours", "hours", "work_hours", "workHours",
        "working_hours", "workingHours", "duration", "shift_duration", "shiftDuration",
        "duration_hours", "durationHours", "total_hours", "totalHours",
        "daily_hours", "dailyHours", "shift_length", "length", "hours_per_day",
        "ساعات_العمل", "ساعات_الشفت", "عدد_الساعات", "عدد_ساعات_العمل",
        "عدد_ساعات_الشفت", "مدة_الشفت", "ساعات", "ساعات_الدوام", "دوام_ساعات"
    )
    for (k in candidateHoursKeys) {
        val v = data[k] ?: continue
        if (v is Number) {
            val d = v.toDouble()
            if (d in 0.5..24.0) return d
        }
        if (v is String) {
            val clean = normalizeArabicDigits(v).trim().lowercase()
            if (!clean.contains("-") && !clean.contains(":") && !clean.contains("إلى") && !clean.contains("الى") && !clean.contains("to")) {
                val numStr = clean.replace(Regex("[^0-9.]"), "").trim()
                val d = numStr.toDoubleOrNull()
                if (d != null && d in 0.5..24.0) return d
            }
        }
    }
    // Submap check
    val subMapKeys = listOf(
        "shift", "currentShift", "workingHours", "workHours", "work_hours",
        "hours", "schedule", "workSchedule"
    )
    for (k in subMapKeys) {
        val map = data[k] as? Map<*, *>
        if (map != null) {
            @Suppress("UNCHECKED_CAST")
            val extracted = extractRawHours(map as Map<String, Any?>)
            if (extracted != null) return extracted
        }
    }
    return null
}

fun addHoursToTime(timeStr: String, hours: Double): String {
    val clean = normalizeArabicDigits(timeStr).trim()
    val startMin = ShiftManager.parseTimeToMinutes(clean)
    val durationMin = (hours * 60).toInt()
    val endMin = (startMin + durationMin) % 1440
    val h = endMin / 60
    val m = endMin % 60
    return String.format(Locale.getDefault(), "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))
}

fun subtractHoursFromTime(timeStr: String, hours: Double): String {
    val clean = normalizeArabicDigits(timeStr).trim()
    val endMin = ShiftManager.parseTimeToMinutes(clean)
    val durationMin = (hours * 60).toInt()
    var startMin = (endMin - durationMin) % 1440
    if (startMin < 0) startMin += 1440
    val h = startMin / 60
    val m = startMin % 60
    return String.format(Locale.getDefault(), "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))
}

fun formatRawTimeToHHmm(raw: Any?, fallback: String = "09:00"): String {
    if (raw == null) return fallback
    if (raw is Timestamp) {
        val cal = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE)
        cal.time = raw.toDate()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return String.format(Locale.getDefault(), "%02d:%02d", h, m)
    }
    if (raw is Map<*, *>) {
        val hourVal = raw["hour"] ?: raw["hours"] ?: raw["h"] ?: raw["Hour"] ?: raw["ساعة"] ?: raw["الساعة"]
        val minVal = raw["minute"] ?: raw["minutes"] ?: raw["min"] ?: raw["m"] ?: raw["Minute"] ?: raw["دقيقة"] ?: 0
        if (hourVal != null) {
            val h = (hourVal as? Number)?.toInt() ?: hourVal.toString().trim().toIntOrNull() ?: 0
            val m = (minVal as? Number)?.toInt() ?: minVal.toString().trim().toIntOrNull() ?: 0
            return String.format(Locale.getDefault(), "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))
        }
        val secVal = raw["seconds"] ?: raw["_seconds"]
        if (secVal is Number) {
            val cal = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE)
            cal.timeInMillis = secVal.toLong() * 1000L
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            return String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }
        val strVal = raw["value"] ?: raw["time"] ?: raw["formatted"] ?: raw["text"] ?: raw["str"]
        if (strVal != null) {
            return formatRawTimeToHHmm(strVal, fallback)
        }
    }
    if (raw is Number) {
        val d = raw.toDouble()
        val num = raw.toInt()
        // Epoch seconds or milliseconds
        if (num > 100_000_000) {
            val cal = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE)
            cal.timeInMillis = if (raw.toLong() < 10_000_000_000L) raw.toLong() * 1000L else raw.toLong()
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            return String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }
        // If 0..24, it's an HOUR of day! (e.g. 10 -> 10:00, 14 -> 14:00, 22 -> 22:00, 9.5 -> 09:30)
        if (num in 0..24 && d <= 24.0) {
            val h = num.coerceIn(0, 23)
            val frac = d - num
            val m = (frac * 60).toInt().coerceIn(0, 59)
            return String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }
        // If 25..1440, it's minutes from midnight! (e.g. 540 -> 09:00, 870 -> 14:30)
        if (num in 25..1440) {
            val h = (num / 60).coerceIn(0, 23)
            val m = (num % 60).coerceIn(0, 59)
            return String.format(Locale.getDefault(), "%02d:%02d", h, m)
        }
    }
    val rawStr = raw.toString().trim()
    if (rawStr.isBlank()) return fallback

    val clean = normalizeArabicDigits(rawStr).trim()
    if (clean.isBlank()) return fallback

    if (clean.matches(Regex("^[0-9]{1,2}$"))) {
        val h = clean.toInt().coerceIn(0, 23)
        return String.format(Locale.getDefault(), "%02d:00", h)
    }

    if (clean.contains("T") && clean.contains(":")) {
        val timePart = clean.substringAfter("T").substringBefore("Z").substringBefore("+")
        val parts = timePart.split(":")
        if (parts.size >= 2) {
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            return String.format(Locale.getDefault(), "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))
        }
    }

    val minutes = ShiftManager.parseTimeToMinutes(clean)
    val h = minutes / 60
    val m = minutes % 60
    return String.format(Locale.getDefault(), "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))
}

private fun parseDayScheduleMap(
    dayMap: Map<String, Any?>, fallbackId: String, captainUid: String, dayKeyHint: String
): List<ShiftEntity> {
    val dayBase = parseShiftMap(dayMap, fallbackId, captainUid, dayKeyHint = dayKeyHint) ?: return emptyList()
    if (dayBase.isDayOff) {
        return listOf(dayBase.copy(id = "${captainUid}_${dayKeyHint}_off", startTime = "00:00", endTime = "00:00", isDayOff = true))
    }
    val nestedShifts = dayMap["shifts"] as? List<*>
    if (nestedShifts != null) {
        return nestedShifts.mapIndexedNotNull { index, item ->
            val shiftMap = item as? Map<*, *> ?: return@mapIndexedNotNull null
            val normalizedMap = shiftMap.entries.associate { it.key.toString() to it.value }
            if (extractRawStartTime(normalizedMap) == null || extractRawEndTime(normalizedMap) == null) return@mapIndexedNotNull null
            val sourceId = (normalizedMap["id"] ?: normalizedMap["shiftId"] ?: normalizedMap["shift_id"])?.toString()?.trim().orEmpty()
            val fallbackShiftId = "${captainUid}_${dayKeyHint}_shift_${index + 1}"
            val parsed = parseShiftMap(normalizedMap, sourceId.ifBlank { fallbackShiftId }, captainUid,
                dayKeyHint = dayKeyHint, defaultDayOfWeek = dayBase.dayOfWeek) ?: return@mapIndexedNotNull null
            parsed.copy(
                id = sourceId.ifBlank { fallbackShiftId },
                dayOfWeek = dayBase.dayOfWeek,
                dayNameArabic = dayBase.dayNameArabic,
                isDayOff = false
            )
        }
    }
    // Never infer a noon start from a duration-only totalHours field.
    if (extractRawStartTime(dayMap) != null || extractRawEndTime(dayMap) != null) {
        val sourceId = dayMap["id"]?.toString()?.trim().orEmpty()
        return listOf(dayBase.copy(id = sourceId.ifBlank { fallbackId }))
    }
    return emptyList()
}

fun extractShiftsFromMap(docData: Map<String, Any?>, fallbackId: String, captainUid: String): List<ShiftEntity> {
    val containerField = docData["weeklySchedule"] ?: docData["weekly_schedule"] ?: docData["shifts"] ?: docData["schedule"]
        ?: docData["workShifts"] ?: docData["work_shifts"] ?: docData["days"] ?: docData["schedules"]
        ?: docData["workingHours"] ?: docData["working_hours"] ?: docData["shiftsSchedule"] ?: docData["shift_schedule"]
        ?: docData["shiftSchedule"] ?: docData["shifts_schedule"] ?: docData["shiftList"] ?: docData["shiftsList"]
        ?: docData["weekShifts"] ?: docData["week_shifts"] ?: docData["timetable"] ?: docData["data"]
        ?: docData["captainSchedule"] ?: docData["captain_schedule"]

    val parsed = mutableListOf<ShiftEntity>()
    if (containerField is Map<*, *>) {
        for ((key, value) in containerField) {
            val dayKey = key?.toString().orEmpty()
            val dayMap = value as? Map<*, *> ?: continue
            val normalized = dayMap.entries.associate { it.key.toString() to it.value }
            parsed.addAll(parseDayScheduleMap(normalized, "${fallbackId}_$dayKey", captainUid, dayKey))
        }
    } else if (containerField is List<*>) {
        containerField.forEachIndexed { index, item ->
            val shiftMap = item as? Map<*, *> ?: return@forEachIndexed
            val normalized = shiftMap.entries.associate { it.key.toString() to it.value }
            val dayHint = normalized["day"]?.toString() ?: normalized["dayOfWeek"]?.toString()
                ?: normalized["dayName"]?.toString() ?: ""
            if (normalized["shifts"] is List<*>) {
                parsed.addAll(parseDayScheduleMap(normalized, "${fallbackId}_$index", captainUid, dayHint))
            } else {
                parseShiftMap(normalized, "${fallbackId}_shift_$index", captainUid)?.let { parsed.add(it) }
            }
        }
    }

    val rootDayNames = listOf(
        "saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday",
        "sat", "sun", "mon", "tue", "wed", "thu", "fri",
        "السبت", "الأحد", "الاحد", "الإثنين", "الاثنين", "الثلاثاء", "الأربعاء", "الاربعاء", "الخميس", "الجمعة",
        "day_0", "day_1", "day_2", "day_3", "day_4", "day_5", "day_6",
        "day0", "day1", "day2", "day3", "day4", "day5", "day6", "0", "1", "2", "3", "4", "5", "6"
    )
    for (dayKey in rootDayNames) {
        val dayMap = docData[dayKey] as? Map<*, *> ?: continue
        val normalized = dayMap.entries.associate { it.key.toString() to it.value }
        parsed.addAll(parseDayScheduleMap(normalized, "${fallbackId}_$dayKey", captainUid, dayKey))
    }
    if (parsed.isNotEmpty()) {
        return parsed.distinctBy { it.id }.sortedWith(
            compareBy<ShiftEntity>({ it.dayOfWeek }, { ShiftManager.parseTimeToMinutes(it.startTime) }, { it.id })
        )
    }

    // Backward compatibility for legacy documents with one global shift.
    val rootStartRaw = extractRawStartTime(docData)
    val rootEndRaw = extractRawEndTime(docData)
    val rootHours = extractRawHours(docData)
    val rootStart = rootStartRaw?.let { formatRawTimeToHHmm(it, "12:00") }
    val rootEnd = when {
        rootEndRaw != null -> formatRawTimeToHHmm(rootEndRaw, "20:00")
        rootStart != null && rootHours != null -> addHoursToTime(rootStart, rootHours)
        else -> null
    }
    if (rootStart != null || rootEnd != null) {
        val days = listOf(
            Calendar.SATURDAY to "السبت", Calendar.SUNDAY to "الأحد", Calendar.MONDAY to "الإثنين",
            Calendar.TUESDAY to "الثلاثاء", Calendar.WEDNESDAY to "الأربعاء",
            Calendar.THURSDAY to "الخميس", Calendar.FRIDAY to "الجمعة"
        )
        return days.map { (day, name) ->
            val fridayOff = day == Calendar.FRIDAY
            ShiftEntity(
                id = "${captainUid}_day_$day", captainUid = captainUid, dayOfWeek = day,
                dayNameArabic = name,
                startTime = if (fridayOff) "00:00" else (rootStart ?: subtractHoursFromTime(rootEnd!!, rootHours ?: 0.0)),
                endTime = if (fridayOff) "00:00" else (rootEnd ?: addHoursToTime(rootStart!!, rootHours ?: 0.0)),
                isDayOff = fridayOff,
                notes = if (fridayOff) "عطلة أسبوعية رسمية" else "شفت عمل معتمد من لوحة التحكم"
            )
        }
    }
    return emptyList()
}

fun DocumentSnapshot.extractAllShifts(captainUid: String): List<ShiftEntity> {
    if (!exists()) return emptyList()
    val docData = data ?: return emptyList()
    return extractShiftsFromMap(docData, id, captainUid)
}

fun DocumentSnapshot.toShiftEntity(captainUid: String): ShiftEntity? {
    if (!exists()) return null
    val docData = data ?: return null
    return parseShiftMap(docData, id, captainUid)
}

fun DocumentSnapshot.extractEmbeddedShifts(captainUid: String): List<ShiftEntity> {
    return extractAllShifts(captainUid)
}

fun parseShiftMap(
    data: Map<String, Any?>,
    fallbackId: String,
    captainUid: String,
    dayKeyHint: String = "",
    defaultDayOfWeek: Int = Calendar.SUNDAY
): ShiftEntity? {
    val rawDay = data["dayOfWeek"]
        ?: data["day_of_week"]
        ?: data["day"]
        ?: data["dayName"]
        ?: data["day_name"]
        ?: data["weekday"]
        ?: data["week_day"]
        ?: data["name"]
        ?: dayKeyHint

    val dayOfWeek = when (rawDay) {
        is Number -> {
            when (rawDay.toInt()) {
                0 -> Calendar.SATURDAY
                1 -> Calendar.SUNDAY
                2 -> Calendar.MONDAY
                3 -> Calendar.TUESDAY
                4 -> Calendar.WEDNESDAY
                5 -> Calendar.THURSDAY
                6 -> Calendar.FRIDAY
                7 -> Calendar.SATURDAY
                else -> defaultDayOfWeek
            }
        }
        is String -> {
            val lower = rawDay.trim().lowercase()
            when {
                lower.contains("sat") || lower.contains("سبت") -> Calendar.SATURDAY
                lower.contains("sun") || lower.contains("أحد") || lower.contains("احد") -> Calendar.SUNDAY
                lower.contains("mon") || lower.contains("اثنين") || lower.contains("إثنين") -> Calendar.MONDAY
                lower.contains("tue") || lower.contains("ثلاثاء") -> Calendar.TUESDAY
                lower.contains("wed") || lower.contains("أربعاء") || lower.contains("اربعاء") -> Calendar.WEDNESDAY
                lower.contains("thu") || lower.contains("خميس") -> Calendar.THURSDAY
                lower.contains("fri") || lower.contains("جمعة") -> Calendar.FRIDAY
                lower == "0" || lower == "day_0" || lower == "day0" -> Calendar.SATURDAY
                lower == "1" || lower == "day_1" || lower == "day1" -> Calendar.SUNDAY
                lower == "2" || lower == "day_2" || lower == "day2" -> Calendar.MONDAY
                lower == "3" || lower == "day_3" || lower == "day3" -> Calendar.TUESDAY
                lower == "4" || lower == "day_4" || lower == "day4" -> Calendar.WEDNESDAY
                lower == "5" || lower == "day_5" || lower == "day5" -> Calendar.THURSDAY
                lower == "6" || lower == "day_6" || lower == "day6" -> Calendar.FRIDAY
                lower == "7" || lower == "day_7" || lower == "day7" -> Calendar.SATURDAY
                else -> defaultDayOfWeek
            }
        }
        else -> defaultDayOfWeek
    }

    val dayNameArabic = ShiftManager.ARABIC_DAYS_MAP[dayOfWeek] ?: "اليوم"
    val rawStart = extractRawStartTime(data)
    val rawEnd = extractRawEndTime(data)
    val rawHours = extractRawHours(data)

    val startTime: String
    val endTime: String

    if (rawStart != null && rawEnd != null) {
        startTime = formatRawTimeToHHmm(rawStart, "12:00")
        endTime = formatRawTimeToHHmm(rawEnd, "20:00")
    } else if (rawStart != null && rawHours != null) {
        startTime = formatRawTimeToHHmm(rawStart, "12:00")
        endTime = addHoursToTime(startTime, rawHours)
    } else if (rawEnd != null && rawHours != null) {
        endTime = formatRawTimeToHHmm(rawEnd, "20:00")
        startTime = subtractHoursFromTime(endTime, rawHours)
    } else if (rawHours != null) {
        startTime = "12:00"
        endTime = addHoursToTime("12:00", rawHours)
    } else {
        startTime = formatRawTimeToHHmm(rawStart, "12:00")
        endTime = formatRawTimeToHHmm(rawEnd, "20:00")
    }

    val isDayOff = when (val rawOff = data["isDayOff"]
        ?: data["is_day_off"]
        ?: data["isOff"]
        ?: data["is_off"]
        ?: data["dayOff"]
        ?: data["day_off"]
        ?: data["off"]
        ?: data["isHoliday"]
        ?: data["is_holiday"]
        ?: data["holiday"]
        ?: data["vacation"]
        ?: data["isVacation"]
        ?: data["is_vacation"]) {
        is Boolean -> rawOff
        is String -> rawOff.equals("true", ignoreCase = true) || rawOff == "1" || rawOff.contains("إجازة") || rawOff.contains("عطلة") || rawOff.equals("off", ignoreCase = true)
        is Number -> rawOff.toInt() == 1
        else -> {
            val status = data["status"]?.toString()?.lowercase() ?: ""
            val active = data["active"] as? Boolean
                ?: data["isActive"] as? Boolean
                ?: data["is_active"] as? Boolean
                ?: data["enabled"] as? Boolean
                ?: true
            !active || status in listOf("off", "day_off", "holiday", "inactive", "vacation", "closed")
        }
    }

    val notes = (data["notes"] ?: data["note"] ?: "").toString().trim()
    val updated = (data["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()

    val shiftCanonicalId = (data["id"] ?: data["shiftId"] ?: data["shift_id"])
        ?.toString()?.trim()?.takeIf { it.isNotBlank() }
        ?: fallbackId.ifBlank { "${captainUid}_day_${dayOfWeek}" }

    return ShiftEntity(
        id = shiftCanonicalId,
        captainUid = captainUid,
        dayOfWeek = dayOfWeek,
        dayNameArabic = dayNameArabic,
        startTime = startTime,
        endTime = endTime,
        isDayOff = isDayOff,
        notes = notes,
        updatedAt = updated
    )
}
