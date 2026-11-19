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

class ShiftBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(appContext)
                val captain = db.captainDao().getCaptain()
                if (captain == null || !captain.isLoggedIn || captain.uid.isBlank()) {
                    ShiftNotificationScheduler.cancelAllShiftAlarms(appContext)
                    return@launch
                }

                val shifts = db.shiftDao().getShiftsForCaptainFlow(captain.uid).firstOrNull()
                    ?: db.shiftDao().getAllShiftsFlow().firstOrNull()
                    ?: emptyList()

                if (shifts.isNotEmpty()) {
                    ShiftNotificationScheduler.scheduleShiftNotifications(
                        context = appContext,
                        shifts = shifts,
                        captainUid = captain.uid
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring shift alarms on boot/time change: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ShiftBootReceiver"
    }
}
