package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class ShiftAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ShiftNotificationScheduler.ACTION_SHIFT_START_ALERT &&
            action != ShiftNotificationScheduler.ACTION_SHIFT_END_ALERT
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val expectedUid = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_CAPTAIN_UID).orEmpty()
        val dayNameArabic = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_DAY_NAME_ARABIC).orEmpty()
        val startFormatted = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_START_FORMATTED).orEmpty()
        val endFormatted = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_END_FORMATTED).orEmpty()
        val dedupeKey = intent.getStringExtra(ShiftNotificationScheduler.EXTRA_DEDUPE_KEY).orEmpty()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(appContext)
                val captain = db.captainDao().getCaptain()
                if (captain == null || !captain.isLoggedIn) {
                    ShiftNotificationScheduler.cancelAllShiftAlarms(appContext)
                    return@launch
                }
                if (expectedUid.isNotBlank() && captain.uid.isNotBlank() && expectedUid != captain.uid) {
                    return@launch
                }

                if (action == ShiftNotificationScheduler.ACTION_SHIFT_START_ALERT) {
                    CaptainNotificationManager.showShiftStartNotification(
                        context = appContext,
                        dayNameArabic = dayNameArabic,
                        startFormatted = startFormatted,
                        endFormatted = endFormatted,
                        dedupeKey = dedupeKey
                    )
                } else {
                    CaptainNotificationManager.showShiftEndNotification(
                        context = appContext,
                        dayNameArabic = dayNameArabic,
                        endFormatted = endFormatted,
                        dedupeKey = dedupeKey
                    )
                }

                // Reschedule upcoming week's shift start/end alarms automatically even if app stays closed
                val shifts = if (captain.uid.isNotBlank()) {
                    db.shiftDao().getShiftsForCaptainFlow(captain.uid).firstOrNull()
                        ?: db.shiftDao().getAllShiftsFlow().firstOrNull()
                        ?: emptyList()
                } else {
                    db.shiftDao().getAllShiftsFlow().firstOrNull() ?: emptyList()
                }
                if (shifts.isNotEmpty() && captain.uid.isNotBlank()) {
                    ShiftNotificationScheduler.scheduleShiftNotifications(
                        context = appContext,
                        shifts = shifts,
                        captainUid = captain.uid,
                        nowMs = System.currentTimeMillis() + 61_000L
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling shift alarm: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ShiftAlarmReceiver"
    }
}
