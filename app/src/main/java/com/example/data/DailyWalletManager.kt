package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Stores daily wallet balance snapshots rolled over every day at 1:00 PM (13:00 Cairo Time).
 * - Before 1:00 PM, operations belong to the previous day's 1:00 PM cycle.
 * - At 1:00 PM (13:00), the previous cycle is closed and archived (isClosed = true),
 *   and the new operational day starts with walletBalance = 0.0 until new orders are delivered.
 */
@Entity(tableName = "daily_wallet_records")
data class DailyWalletEntity(
    @PrimaryKey val dayKey: String, // Format: "yyyy-MM-dd" representing cycle starting at 13:00 on dayKey
    val captainUid: String = "",
    val periodStartMs: Long = 0L,
    val periodEndMs: Long = 0L,
    val walletBalance: Double = 0.0,
    val deliveredOrdersCount: Int = 0,
    val totalOrdersCount: Int = 0,
    val isClosed: Boolean = false,
    val closedAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface DailyWalletDao {
    @Query("SELECT * FROM daily_wallet_records ORDER BY dayKey DESC")
    fun getAllDailyWalletsFlow(): Flow<List<DailyWalletEntity>>

    @Query("SELECT * FROM daily_wallet_records ORDER BY dayKey DESC")
    suspend fun getAllDailyWallets(): List<DailyWalletEntity>

    @Query("SELECT * FROM daily_wallet_records WHERE dayKey = :dayKey LIMIT 1")
    suspend fun getDailyWalletByDay(dayKey: String): DailyWalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DailyWalletEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<DailyWalletEntity>)

    @Query("DELETE FROM daily_wallet_records")
    suspend fun clearAll()
}

object DailyWalletManager {
    /**
     * Daily accounting rollover hour: 1:00 PM (13:00) in Africa/Cairo timezone.
     */
    const val ROLLOVER_HOUR_CAIRO = 13

    val CAIRO_TZ: TimeZone = TimeZone.getTimeZone("Africa/Cairo")
    private val UTC_TZ: TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * Returns the operational day key ("yyyy-MM-dd") for a given timestamp in Cairo time.
     * Any timestamp strictly before 1:00 PM (hour < 13) belongs to the cycle that started
     * at 1:00 PM on the previous calendar date.
     * At or after 1:00 PM (hour >= 13), a new operational day begins!
     */
    fun getOperationalDayKey(timestampMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(CAIRO_TZ)
        cal.timeInMillis = timestampMs
        if (cal.get(Calendar.HOUR_OF_DAY) < ROLLOVER_HOUR_CAIRO) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return formatCalendarKey(cal)
    }

    /**
     * Returns the calendar date key ("yyyy-MM-dd") in Cairo time without the 13:00 shift
     * (useful when mapping explicit calendar picker selections).
     */
    fun getCalendarDateKey(timestampMs: Long): String {
        val cal = Calendar.getInstance(CAIRO_TZ)
        cal.timeInMillis = timestampMs
        return formatCalendarKey(cal)
    }

    /**
     * Converts Material3 DatePicker UTC midnight millis into the selected "yyyy-MM-dd" key.
     */
    fun utcPickerMillisToDayKey(utcMillis: Long): String {
        val cal = Calendar.getInstance(UTC_TZ)
        cal.timeInMillis = utcMillis
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Converts a "yyyy-MM-dd" dayKey into UTC midnight millis for initializing Material3 DatePicker.
     */
    fun dayKeyToUtcPickerMillis(dayKey: String): Long {
        val parts = dayKey.split("-")
        val cal = Calendar.getInstance(UTC_TZ)
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull() ?: 2026
            val m = (parts[1].toIntOrNull() ?: 1) - 1
            val d = parts[2].toIntOrNull() ?: 1
            cal.set(y, m, d, 12, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun shiftDayKey(dayKey: String, deltaDays: Int): String {
        val cal = parseDayKeyToCairoCalendar(dayKey)
        cal.add(Calendar.DAY_OF_YEAR, deltaDays)
        return formatCalendarKey(cal)
    }

    fun getPreviousOperationalDayKey(dayKey: String): String = shiftDayKey(dayKey, -1)

    fun getNextOperationalDayKey(dayKey: String): String = shiftDayKey(dayKey, 1)

    /**
     * Returns [startMs, endMs] for an operational day:
     * - startMs: dayKey at 13:00:00.000 (1:00 PM Cairo time)
     * - endMs: (dayKey + 1 day) at 12:59:59.999 (12:59:59 PM Cairo time)
     */
    fun getOperationalDayBounds(dayKey: String): Pair<Long, Long> {
        val startCal = parseDayKeyToCairoCalendar(dayKey).apply {
            set(Calendar.HOUR_OF_DAY, ROLLOVER_HOUR_CAIRO)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (startCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            add(Calendar.MILLISECOND, -1)
        }
        return Pair(startCal.timeInMillis, endCal.timeInMillis)
    }

    /**
     * Effective timestamp of an order for daily accounting:
     * - For DELIVERED orders: uses completedAt if available (> 0), otherwise createdAt.
     * - For other orders: uses acceptedAt if available (> 0), otherwise createdAt.
     */
    fun getOrderEffectiveTimestamp(order: OrderEntity): Long {
        return when (order.status) {
            OrderStatus.DELIVERED -> if (order.completedAt > 0L) order.completedAt else order.createdAt
            else -> if (order.acceptedAt > 0L) order.acceptedAt else order.createdAt
        }
    }

    fun getOrderOperationalDayKey(order: OrderEntity): String {
        return getOperationalDayKey(getOrderEffectiveTimestamp(order))
    }

    /**
     * Determines whether an order belongs to the current captain's personal order history
     * (filtering out unassigned PENDING offers and any foreign orders).
     */
    fun isCaptainOwnOrder(order: OrderEntity, captainUid: String, captainPhone: String): Boolean {
        if (order.status == OrderStatus.PENDING) return false
        val orderCapt = order.captainId.trim()
        if (orderCapt.isBlank() || isPlaceholderOrUnassigned(orderCapt)) {
            // Locally accepted/processed order without explicit remote captainId string
            return order.status != OrderStatus.CANCELLED && order.status != OrderStatus.EXPIRED
        }
        if (captainUid.isNotBlank() && orderCapt == captainUid) return true
        if (captainPhone.isNotBlank() && isSamePhone(orderCapt, captainPhone)) return true
        return false
    }

    /**
     * Builds deterministic daily wallet records for all past and current operational days:
     * - Past operational days (< currentDayKey) are marked isClosed = true (archived at 1:00 PM).
     * - Current operational day (== currentDayKey) starts at 0.0 and accumulates only DELIVERED
     *   orders completed during the current 1:00 PM cycle!
     */
    fun buildDailyRecords(
        captainUid: String,
        captainOrders: List<OrderEntity>,
        existingRecords: List<DailyWalletEntity> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): List<DailyWalletEntity> {
        val currentDayKey = getOperationalDayKey(nowMs)
        val yesterdayKey = getPreviousOperationalDayKey(currentDayKey)

        val existingMap = existingRecords.associateBy { it.dayKey }
        val groupedByDay = captainOrders.groupBy { getOrderOperationalDayKey(it) }

        val allKeys = linkedSetOf<String>()
        allKeys.add(currentDayKey)
        allKeys.add(yesterdayKey)
        allKeys.addAll(groupedByDay.keys)
        allKeys.addAll(existingMap.keys)

        return allKeys.map { key ->
            val (startMs, endMs) = getOperationalDayBounds(key)
            val ordersInDay = groupedByDay[key].orEmpty()
            val deliveredOrders = ordersInDay.filter { it.status == OrderStatus.DELIVERED }
            val calculatedBalance = deliveredOrders.sumOf { it.deliveryFee }
            val prevRecord = existingMap[key]

            val isPastDay = key < currentDayKey
            // For past days: if we had a persisted closed balance and orders weren't all kept locally, preserve max
            val finalBalance = if (isPastDay && ordersInDay.isEmpty() && prevRecord != null) {
                prevRecord.walletBalance
            } else {
                calculatedBalance
            }
            val finalDeliveredCount = if (isPastDay && ordersInDay.isEmpty() && prevRecord != null) {
                prevRecord.deliveredOrdersCount
            } else {
                deliveredOrders.size
            }
            val finalTotalCount = if (isPastDay && ordersInDay.isEmpty() && prevRecord != null) {
                prevRecord.totalOrdersCount
            } else {
                ordersInDay.size
            }

            DailyWalletEntity(
                dayKey = key,
                captainUid = captainUid,
                periodStartMs = startMs,
                periodEndMs = endMs,
                walletBalance = finalBalance,
                deliveredOrdersCount = finalDeliveredCount,
                totalOrdersCount = finalTotalCount,
                isClosed = isPastDay,
                closedAt = if (isPastDay) (prevRecord?.closedAt?.takeIf { it > 0L } ?: (endMs + 1L)) else 0L,
                updatedAt = nowMs
            )
        }.sortedByDescending { it.dayKey }
    }

    /**
     * Returns the last N operational day keys starting from currentDayKey backwards.
     */
    fun getRecentOperationalDayKeys(count: Int = 7, currentDayKey: String = getOperationalDayKey()): List<String> {
        return (0 until count).map { offset -> shiftDayKey(currentDayKey, -offset) }
    }

    /**
     * Formats dayKey into readable Arabic label, e.g.:
     * - "اليوم الحالي • الثلاثاء 10 نوفمبر"
     * - "اليوم السابق • الإثنين 9 نوفمبر"
     * - "الأحد 8 نوفمبر 2026"
     */
    fun formatOperationalDayArabic(dayKey: String, currentDayKey: String = getOperationalDayKey()): String {
        val cal = parseDayKeyToCairoCalendar(dayKey)
        val arabicLocale = Locale.forLanguageTag("ar-EG")
        val yesterdayKey = getPreviousOperationalDayKey(currentDayKey)
        return when (dayKey) {
            currentDayKey -> {
                val fmt = SimpleDateFormat("EEEE d MMMM", arabicLocale).apply { timeZone = CAIRO_TZ }
                "اليوم الحالي • ${fmt.format(cal.time)}"
            }
            yesterdayKey -> {
                val fmt = SimpleDateFormat("EEEE d MMMM", arabicLocale).apply { timeZone = CAIRO_TZ }
                "اليوم السابق • ${fmt.format(cal.time)}"
            }
            else -> {
                val fmt = SimpleDateFormat("EEEE d MMMM yyyy", arabicLocale).apply { timeZone = CAIRO_TZ }
                fmt.format(cal.time)
            }
        }
    }

    /**
     * Short chip label for horizontal day strip.
     */
    fun formatShortChipLabel(dayKey: String, currentDayKey: String = getOperationalDayKey()): String {
        val cal = parseDayKeyToCairoCalendar(dayKey)
        val arabicLocale = Locale.forLanguageTag("ar-EG")
        val yesterdayKey = getPreviousOperationalDayKey(currentDayKey)
        val dayMonthFmt = SimpleDateFormat("d MMM", arabicLocale).apply { timeZone = CAIRO_TZ }
        val dayNameFmt = SimpleDateFormat("EEEE", arabicLocale).apply { timeZone = CAIRO_TZ }
        return when (dayKey) {
            currentDayKey -> "اليوم (${dayMonthFmt.format(cal.time)})"
            yesterdayKey -> "أمس (${dayMonthFmt.format(cal.time)})"
            else -> "${dayNameFmt.format(cal.time)} ${dayMonthFmt.format(cal.time)}"
        }
    }

    /**
     * Formats the exact 1:00 PM -> 1:00 PM window of a dayKey in Arabic.
     */
    fun formatOperationalWindowArabic(dayKey: String): String {
        val (startMs, endMs) = getOperationalDayBounds(dayKey)
        val fmt = SimpleDateFormat("dd/MM", Locale.US).apply { timeZone = CAIRO_TZ }
        val startDateStr = fmt.format(Date(startMs))
        val endDateStr = fmt.format(Date(endMs + 1000L))
        return "من $startDateStr (01:00 م) إلى $endDateStr (01:00 م)"
    }

    private fun parseDayKeyToCairoCalendar(dayKey: String): Calendar {
        val cal = Calendar.getInstance(CAIRO_TZ)
        val parts = dayKey.split("-")
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull() ?: cal.get(Calendar.YEAR)
            val m = (parts[1].toIntOrNull() ?: (cal.get(Calendar.MONTH) + 1)) - 1
            val d = parts[2].toIntOrNull() ?: cal.get(Calendar.DAY_OF_MONTH)
            cal.set(y, m, d, ROLLOVER_HOUR_CAIRO, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        return cal
    }

    private fun formatCalendarKey(cal: Calendar): String {
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
