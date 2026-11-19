package com.example.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ScheduledShiftTrigger(
    val dayOfWeek: Int,
    val isStart: Boolean,
    val triggerAtMillis: Long,
    val dayNameArabic: String,
    val startFormatted: String,
    val endFormatted: String,
    val dedupeKey: String
)

object ShiftNotificationScheduler {
    private const val TAG = "ShiftNotifScheduler"
    private const val REQUEST_CODE_START_BASE = 10_000
    private const val REQUEST_CODE_END_BASE = 20_000

    const val ACTION_SHIFT_START_ALERT = "com.example.action.SHIFT_START_ALERT"
    const val ACTION_SHIFT_END_ALERT = "com.example.action.SHIFT_END_ALERT"

    const val EXTRA_CAPTAIN_UID = "extra_captain_uid"
    const val EXTRA_DAY_OF_WEEK = "extra_day_of_week"
    const val EXTRA_DAY_NAME_ARABIC = "extra_day_name_arabic"
    const val EXTRA_START_FORMATTED = "extra_start_formatted"
    const val EXTRA_END_FORMATTED = "extra_end_formatted"
    const val EXTRA_DEDUPE_KEY = "extra_dedupe_key"

    fun computeNextCairoOccurrenceMillis(
        targetDayOfWeek: Int,
        hourOfDay: Int,
        minute: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val cal = Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE).apply {
            timeInMillis = nowMs
            val currentDay = get(Calendar.DAY_OF_WEEK)
            val daysUntil = (targetDayOfWeek - currentDay + 7) % 7
            add(Calendar.DAY_OF_YEAR, daysUntil)
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) {
                add(Calendar.DAY_OF_YEAR, 7)
            }
        }
        return cal.timeInMillis
    }

    fun computeTriggersForShifts(
        shifts: List<ShiftEntity>,
        nowMs: Long = System.currentTimeMillis()
    ): List<ScheduledShiftTrigger> {
        if (shifts.isEmpty()) return emptyList()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = ShiftManager.CAIRO_TIME_ZONE
        }

        val triggers = mutableListOf<ScheduledShiftTrigger>()
        for (shift in shifts) {
            if (shift.isDayOff) continue
            val startMin = ShiftManager.parseTimeToMinutes(shift.startTime)
            val endMin = ShiftManager.parseTimeToMinutes(shift.endTime)
            if (startMin == endMin) continue

            val startHour = startMin / 60
            val startMinute = startMin % 60
            val endHour = endMin / 60
            val endMinute = endMin % 60

            val startFormatted = ShiftManager.formatTimeArabic(shift.startTime)
            val endFormatted = ShiftManager.formatTimeArabic(shift.endTime)
            val dayNameArabic = shift.dayNameArabic.ifBlank {
                ShiftManager.ARABIC_DAYS_MAP[shift.dayOfWeek] ?: "اليوم"
            }

            val startTriggerMs = computeNextCairoOccurrenceMillis(
                targetDayOfWeek = shift.dayOfWeek,
                hourOfDay = startHour,
                minute = startMinute,
                nowMs = nowMs
            )
            triggers.add(
                ScheduledShiftTrigger(
                    dayOfWeek = shift.dayOfWeek,
                    isStart = true,
                    triggerAtMillis = startTriggerMs,
                    dayNameArabic = dayNameArabic,
                    startFormatted = startFormatted,
                    endFormatted = endFormatted,
                    dedupeKey = "${dateFmt.format(Date(startTriggerMs))}_${shift.dayOfWeek}_start"
                )
            )

            val isOvernight = endMin < startMin
            val endDayOfWeek = if (isOvernight) {
                if (shift.dayOfWeek == Calendar.SATURDAY) Calendar.SUNDAY else shift.dayOfWeek + 1
            } else {
                shift.dayOfWeek
            }
            val endTriggerMs = computeNextCairoOccurrenceMillis(
                targetDayOfWeek = endDayOfWeek,
                hourOfDay = endHour,
                minute = endMinute,
                nowMs = nowMs
            )
            triggers.add(
                ScheduledShiftTrigger(
                    dayOfWeek = shift.dayOfWeek,
                    isStart = false,
                    triggerAtMillis = endTriggerMs,
                    dayNameArabic = dayNameArabic,
                    startFormatted = startFormatted,
                    endFormatted = endFormatted,
                    dedupeKey = "${dateFmt.format(Date(endTriggerMs))}_${shift.dayOfWeek}_end"
                )
            )
        }
        return triggers
    }

    fun buildTodayDedupeKey(shift: ShiftEntity, isStart: Boolean, nowMs: Long = System.currentTimeMillis()): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = ShiftManager.CAIRO_TIME_ZONE
        }
        val suffix = if (isStart) "start" else "end"
        return "${dateFmt.format(Date(nowMs))}_${shift.dayOfWeek}_$suffix"
    }

    fun scheduleShiftNotifications(
        context: Context,
        shifts: List<ShiftEntity>,
        captainUid: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        CaptainNotificationManager.ensureNotificationChannels(context)

        // Cancel existing alarms before scheduling fresh ones from latest schedule
        cancelAllShiftAlarms(context)

        if (shifts.isEmpty() || captainUid.isBlank()) return

        val triggers = computeTriggersForShifts(shifts, nowMs)
        for (trigger in triggers) {
            val pendingIntent = buildAlarmPendingIntent(
                context = context,
                captainUid = captainUid,
                trigger = trigger
            )
            scheduleSingleAlarm(alarmManager, trigger.triggerAtMillis, pendingIntent)
        }
        Log.i(TAG, "Scheduled ${triggers.size} Cairo shift start/end notifications for captain $captainUid")
    }

    fun cancelAllShiftAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (day in Calendar.SUNDAY..Calendar.SATURDAY) {
            listOf(
                ACTION_SHIFT_START_ALERT to (REQUEST_CODE_START_BASE + day),
                ACTION_SHIFT_END_ALERT to (REQUEST_CODE_END_BASE + day)
            ).forEach { (action, reqCode) ->
                val intent = Intent(context, ShiftAlarmReceiver::class.java).apply {
                    this.action = action
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    reqCode,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    alarmManager.cancel(pi)
                    pi.cancel()
                }
            }
        }
    }

    private fun buildAlarmPendingIntent(
        context: Context,
        captainUid: String,
        trigger: ScheduledShiftTrigger
    ): PendingIntent {
        val action = if (trigger.isStart) ACTION_SHIFT_START_ALERT else ACTION_SHIFT_END_ALERT
        val requestCode = (if (trigger.isStart) REQUEST_CODE_START_BASE else REQUEST_CODE_END_BASE) + trigger.dayOfWeek

        val intent = Intent(context, ShiftAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_CAPTAIN_UID, captainUid)
            putExtra(EXTRA_DAY_OF_WEEK, trigger.dayOfWeek)
            putExtra(EXTRA_DAY_NAME_ARABIC, trigger.dayNameArabic)
            putExtra(EXTRA_START_FORMATTED, trigger.startFormatted)
            putExtra(EXTRA_END_FORMATTED, trigger.endFormatted)
            putExtra(EXTRA_DEDUPE_KEY, trigger.dedupeKey)
        }

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleSingleAlarm(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ) {
        try {
            val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact alarm fallback to setAndAllowWhileIdle: ${se.message}")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule shift alarm: ${e.message}")
        }
    }
}
