package com.example.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class OrderHistoryTab {
    ALL,
    COMPLETED,
    CANCELLED
}

data class OrderHistorySummaryStats(
    val totalFinished: Int,
    val completedCount: Int,
    val cancelledCount: Int
)

/**
 * Pure domain logic for the Archived Order History screen ("سجل الطلبات"):
 * - Displays ONLY finished orders (DELIVERED or CANCELLED) belonging strictly to the logged-in captain.
 * - Never displays PENDING or any active/in-progress orders.
 * - Filters strictly by calendar day ("yyyy-MM-dd") in the app's local timezone (Africa/Cairo)
 *   using completion timestamp for DELIVERED and cancellation timestamp for CANCELLED.
 * - Sorts descending by finish timestamp (newest finished order first).
 * - Prevents selecting future dates.
 */
object OrderHistoryHelper {

    val LOCAL_TZ: TimeZone = TimeZone.getTimeZone("Africa/Cairo")
    private val UTC_TZ: TimeZone = TimeZone.getTimeZone("UTC")

    fun getTodayDateKey(nowMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(LOCAL_TZ)
        cal.timeInMillis = nowMs
        return formatDateKey(cal)
    }

    fun isFutureDateKey(dateKey: String, todayKey: String = getTodayDateKey()): Boolean {
        return dateKey > todayKey
    }

    fun clampToMaxToday(dateKey: String, todayKey: String = getTodayDateKey()): String {
        if (!dateKey.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return todayKey
        return if (dateKey > todayKey) todayKey else dateKey
    }

    fun getCalendarDayBoundsMs(dateKey: String): Pair<Long, Long> {
        val startCal = parseDateKeyToLocalCalendar(dateKey).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (startCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return Pair(startCal.timeInMillis, endCal.timeInMillis)
    }

    /**
     * Strictly returns true ONLY for archived finished orders (DELIVERED or CANCELLED)
     * that have a valid finish timestamp.
     * Never returns true for PENDING, ACCEPTED, AT_STORE, PICKED_UP, ORDER_DATA_ENTERED,
     * AWAITING_MERCHANT_CONFIRMATION, MERCHANT_REJECTED, EN_ROUTE, or EXPIRED.
     */
    fun isArchivedFinishedOrder(order: OrderEntity): Boolean {
        return when (order.status) {
            OrderStatus.DELIVERED -> getFinishTimestamp(order) > 0L
            OrderStatus.CANCELLED -> getFinishTimestamp(order) > 0L
            else -> false
        }
    }

    /**
     * Returns the exact finish timestamp of an archived order:
     * - For DELIVERED: completedAt (falling back to acceptedAt or createdAt for legacy records).
     * - For CANCELLED: cancelledAt (falling back to completedAt or createdAt for legacy records).
     * - For non-finished orders: 0L.
     */
    fun getFinishTimestamp(order: OrderEntity): Long {
        return when (order.status) {
            OrderStatus.DELIVERED -> when {
                order.completedAt > 0L -> order.completedAt
                order.acceptedAt > 0L -> order.acceptedAt
                else -> order.createdAt
            }
            OrderStatus.CANCELLED -> when {
                order.cancelledAt > 0L -> order.cancelledAt
                order.completedAt > 0L -> order.completedAt
                else -> order.createdAt
            }
            else -> 0L
        }
    }

    fun getFinishDateKey(order: OrderEntity): String {
        val finishTs = getFinishTimestamp(order)
        if (finishTs <= 0L) return ""
        val cal = Calendar.getInstance(LOCAL_TZ)
        cal.timeInMillis = finishTs
        return formatDateKey(cal)
    }

    /**
     * Enforces strict per-captain isolation:
     * - Requires a logged-in captain identifier (UID or phone).
     * - Rejects unassigned orders (blank/placeholder captainId) and any order assigned to another captain.
     */
    fun isStrictlyOwnedByLoggedInCaptain(
        order: OrderEntity,
        captainUid: String,
        captainPhone: String = ""
    ): Boolean {
        val cleanUid = captainUid.trim()
        val cleanPhone = captainPhone.trim()
        if (cleanUid.isBlank() && cleanPhone.isBlank()) return false

        val orderCapt = order.captainId.trim()
        if (orderCapt.isBlank() || isPlaceholderOrUnassigned(orderCapt)) {
            return false
        }
        if (cleanUid.isNotBlank() && orderCapt == cleanUid) return true
        if (cleanPhone.isNotBlank() && isSamePhone(orderCapt, cleanPhone)) return true
        return false
    }

    /**
     * Filters, deduplicates, and sorts archived finished orders for the given dateKey
     * strictly for the logged-in captain.
     */
    fun filterArchivedOrdersForDate(
        allOrders: List<OrderEntity>,
        captainUid: String,
        captainPhone: String = "",
        selectedDateKey: String
    ): List<OrderEntity> {
        val (startMs, endMs) = getCalendarDayBoundsMs(selectedDateKey)
        val deduplicated = LinkedHashMap<String, OrderEntity>()

        for (order in allOrders) {
            if (!isStrictlyOwnedByLoggedInCaptain(order, captainUid, captainPhone)) continue
            if (!isArchivedFinishedOrder(order)) continue

            val finishTs = getFinishTimestamp(order)
            if (finishTs !in startMs..endMs) continue

            val dedupKey = order.firestoreDocId.trim().ifBlank { "id_${order.id}_${order.orderNumber}" }
            val existing = deduplicated[dedupKey]
            if (existing == null || getFinishTimestamp(order) >= getFinishTimestamp(existing)) {
                deduplicated[dedupKey] = order
            }
        }

        return deduplicated.values.sortedWith(
            compareByDescending<OrderEntity> { getFinishTimestamp(it) }
                .thenByDescending { it.id }
        )
    }

    fun filterByTab(dateOrders: List<OrderEntity>, tab: OrderHistoryTab): List<OrderEntity> {
        return when (tab) {
            OrderHistoryTab.ALL -> dateOrders
            OrderHistoryTab.COMPLETED -> dateOrders.filter { it.status == OrderStatus.DELIVERED }
            OrderHistoryTab.CANCELLED -> dateOrders.filter { it.status == OrderStatus.CANCELLED }
        }
    }

    /**
     * Computes dynamic summary counts for the selected date and active category tab.
     * Changing either the date or the category tab updates the summary numbers.
     */
    fun computeSummaryStats(
        dateOrders: List<OrderEntity>,
        tab: OrderHistoryTab
    ): OrderHistorySummaryStats {
        val completedInDate = dateOrders.count { it.status == OrderStatus.DELIVERED }
        val cancelledInDate = dateOrders.count { it.status == OrderStatus.CANCELLED }
        return when (tab) {
            OrderHistoryTab.ALL -> OrderHistorySummaryStats(
                totalFinished = completedInDate + cancelledInDate,
                completedCount = completedInDate,
                cancelledCount = cancelledInDate
            )
            OrderHistoryTab.COMPLETED -> OrderHistorySummaryStats(
                totalFinished = completedInDate,
                completedCount = completedInDate,
                cancelledCount = 0
            )
            OrderHistoryTab.CANCELLED -> OrderHistorySummaryStats(
                totalFinished = cancelledInDate,
                completedCount = 0,
                cancelledCount = cancelledInDate
            )
        }
    }

    fun utcPickerMillisToDateKey(utcMillis: Long): String {
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

    fun dateKeyToUtcPickerMillis(dateKey: String): Long {
        val parts = dateKey.split("-")
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

    fun getTodayYear(todayKey: String = getTodayDateKey()): Int {
        return todayKey.substringBefore("-").toIntOrNull() ?: Calendar.getInstance(LOCAL_TZ).get(Calendar.YEAR)
    }

    fun shiftDateKey(dateKey: String, deltaDays: Int): String {
        val cal = parseDateKeyToLocalCalendar(dateKey)
        cal.add(Calendar.DAY_OF_YEAR, deltaDays)
        return formatDateKey(cal)
    }

    fun formatSelectedDateTitleArabic(dateKey: String, todayKey: String = getTodayDateKey()): String {
        val cal = parseDateKeyToLocalCalendar(dateKey)
        val arabicLocale = Locale.forLanguageTag("ar-EG")
        val yesterdayKey = shiftDateKey(todayKey, -1)
        val fullFmt = SimpleDateFormat("EEEE d MMMM yyyy", arabicLocale).apply { timeZone = LOCAL_TZ }
        return when (dateKey) {
            todayKey -> "اليوم • ${fullFmt.format(cal.time)}"
            yesterdayKey -> "أمس • ${fullFmt.format(cal.time)}"
            else -> fullFmt.format(cal.time)
        }
    }

    fun formatFinishDateTimeArabic(timestampMs: Long): String {
        if (timestampMs <= 0L) return "غير محدد"
        val fmt = SimpleDateFormat("yyyy/MM/dd • hh:mm a", Locale.forLanguageTag("ar-EG")).apply {
            timeZone = LOCAL_TZ
        }
        return fmt.format(Date(timestampMs))
    }

    private fun parseDateKeyToLocalCalendar(dateKey: String): Calendar {
        val cal = Calendar.getInstance(LOCAL_TZ)
        val parts = dateKey.split("-")
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull() ?: cal.get(Calendar.YEAR)
            val m = (parts[1].toIntOrNull() ?: (cal.get(Calendar.MONTH) + 1)) - 1
            val d = parts[2].toIntOrNull() ?: cal.get(Calendar.DAY_OF_MONTH)
            cal.set(y, m, d, 12, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        return cal
    }

    private fun formatDateKey(cal: Calendar): String {
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
