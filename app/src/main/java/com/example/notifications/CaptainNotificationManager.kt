package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object CaptainNotificationManager {
    private const val TAG = "CaptainNotifManager"
    private const val PREFS_NAME = "elbatal_notifications_prefs"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val PREFIX_NOTIFIED = "notified_"

    const val CHANNEL_NEW_ORDERS_ID = "el_batal_new_orders_channel"
    const val CHANNEL_NEW_ORDERS_NAME = "الطلبات الجديدة"
    const val CHANNEL_NEW_ORDERS_BELL_ID = "el_batal_new_orders_bell_v2"
    const val CHANNEL_NEW_ORDERS_BELL_NAME = "جرس الطلبات الجديدة الواردة"
    const val CHANNEL_SHIFT_ALERTS_ID = "el_batal_shift_alerts_channel"
    const val CHANNEL_SHIFT_ALERTS_NAME = "تنبيهات الشفتات ومواعيد العمل"

    const val EXTRA_NAVIGATE_TARGET = "navigate_target"
    const val EXTRA_ACCEPT_ORDER_ID = "extra_accept_order_id"
    const val EXTRA_ORDER_KEY = "extra_order_key"
    const val TARGET_DASHBOARD = "dashboard"
    const val TARGET_SHIFTS = "shifts"

    fun computeOrderNotificationId(orderKey: String): Int {
        val clean = orderKey.trim().ifBlank { "default_order" }
        return 40_000 + (kotlin.math.abs(clean.hashCode()) % 10_000)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return@runCatching

            val soundUri = runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) }.getOrNull()
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val bellSoundUri = runCatching {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }.getOrNull() ?: soundUri
            val bellAudioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val ordersChannel = NotificationChannel(
                CHANNEL_NEW_ORDERS_ID,
                CHANNEL_NEW_ORDERS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيه فوري عند ظهور طلب توصيل جديد للكابتن"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 180, 350)
                enableLights(true)
                setShowBadge(true)
                if (soundUri != null) setSound(soundUri, audioAttrs)
            }

            val ordersBellChannel = NotificationChannel(
                CHANNEL_NEW_ORDERS_BELL_ID,
                CHANNEL_NEW_ORDERS_BELL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "جرس متواصل وعدّاد 60 ثانية للطلبات الجديدة عند التواجد خارج التطبيق"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 350, 600, 650)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                runCatching { setBypassDnd(true) }
                if (bellSoundUri != null) setSound(bellSoundUri, bellAudioAttrs)
            }

            val shiftsChannel = NotificationChannel(
                CHANNEL_SHIFT_ALERTS_ID,
                CHANNEL_SHIFT_ALERTS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات بداية ونهاية شفت العمل الرسمي للكابتن"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                enableLights(true)
                setShowBadge(true)
                if (soundUri != null) setSound(soundUri, audioAttrs)
            }

            manager.createNotificationChannel(ordersChannel)
            manager.createNotificationChannel(ordersBellChannel)
            manager.createNotificationChannel(shiftsChannel)
        }.onFailure { e ->
            Log.w(TAG, "Non-fatal error creating notification channels: ${e.message}")
        }
    }

    private fun markIfNotNotified(
        context: Context,
        uniqueKey: String,
        dedupeWindowMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (uniqueKey.isBlank()) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fullKey = "$PREFIX_NOTIFIED$uniqueKey"
        val prevTimestamp = prefs.getLong(fullKey, 0L)
        if (prevTimestamp > 0L && (nowMs - prevTimestamp) < dedupeWindowMs) {
            return false
        }

        val editor = prefs.edit()
        val staleCutoff = nowMs - (7L * 24L * 3600_000L)
        for ((k, v) in prefs.all) {
            if (k.startsWith(PREFIX_NOTIFIED) && v is Long && v < staleCutoff) {
                editor.remove(k)
            }
        }
        editor.putLong(fullKey, nowMs)
        editor.apply()
        return true
    }

    @SuppressLint("MissingPermission")
    fun showNewOrderNotification(
        context: Context,
        orderKey: String,
        storeName: String,
        pickupAddress: String = "",
        deliveryAddress: String = "",
        deliveryFee: Double = 25.0,
        orderCode: String = "",
        bypassDedupe: Boolean = false,
        orderId: Long = 0L,
        offerExpiresAtMs: Long = 0L,
        distanceKm: Double = 3.0,
        startContinuousBell: Boolean = true
    ): Boolean {
        ensureNotificationChannels(context)
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "Skipping new-order notification: POST_NOTIFICATIONS permission not granted")
            return false
        }

        val cleanKey = orderKey.trim().ifBlank { "${storeName}_${deliveryFee.toInt()}" }
        if (!bypassDedupe && !markIfNotNotified(context, "order_$cleanKey", 6L * 3600_000L)) {
            return false
        }

        val nowMs = System.currentTimeMillis()
        val effectiveExpiresAt = if (offerExpiresAtMs > nowMs) offerExpiresAtMs else nowMs + 60_000L
        val remainingSeconds = (((effectiveExpiresAt - nowMs) + 999L) / 1000L).toInt().coerceIn(1, 60)
        val requestCode = computeOrderNotificationId(cleanKey)

        val fullScreenIntent = IncomingOrderActivity.createIntent(
            context = context,
            orderKey = cleanKey,
            orderId = orderId,
            storeName = storeName,
            pickupAddress = pickupAddress,
            deliveryAddress = deliveryAddress,
            deliveryFee = deliveryFee,
            orderCode = orderCode,
            offerExpiresAtMs = effectiveExpiresAt
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            requestCode + 10_000,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAVIGATE_TARGET, TARGET_DASHBOARD)
            putExtra(EXTRA_ACCEPT_ORDER_ID, orderId)
            putExtra(EXTRA_ORDER_KEY, cleanKey)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            requestCode + 20_000,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeStore = storeName.ifBlank { "التاجر" }
        val feeText = "${deliveryFee.toInt()} ج.م"
        val cleanOrderCode = orderCode.trim().trimStart('#').trim()
        val summaryLine = if (cleanOrderCode.isNotBlank()) {
            "كود الطلب: $cleanOrderCode • من $safeStore • أرباح التوصيل $feeText"
        } else {
            "من $safeStore • أرباح التوصيل $feeText"
        }
        val details = buildString {
            append(summaryLine)
            if (pickupAddress.isNotBlank()) {
                append("\nالاستلام: ").append(pickupAddress)
            }
            if (deliveryAddress.isNotBlank()) {
                append("\nالتسليم: ").append(deliveryAddress)
            }
            append("\n⏱️ مهلة القبول: $remainingSeconds ثانية قبل تحويل الطلب لأقرب كابتن للتاجر")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_ORDERS_BELL_ID)
            .setSmallIcon(resolveNotificationIcon())
            .setContentTitle("طلب توصيل جديد متاح الآن! 🔔")
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setWhen(effectiveExpiresAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((effectiveExpiresAt - nowMs).coerceAtLeast(1000L))
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(0, "قبول الطلب الآن ($remainingSeconds ث)", acceptPendingIntent)
            .build()
            .apply {
                flags = flags or Notification.FLAG_INSISTENT or Notification.FLAG_ONGOING_EVENT
            }

        val posted = try {
            NotificationManagerCompat.from(context).notify(requestCode, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting order notification: ${e.message}")
            false
        }

        if (posted && startContinuousBell) {
            IncomingOrderBellService.start(
                context = context,
                notificationId = requestCode,
                orderKey = cleanKey,
                orderId = orderId,
                storeName = safeStore,
                pickupAddress = pickupAddress,
                deliveryAddress = deliveryAddress,
                deliveryFee = deliveryFee,
                orderCode = cleanOrderCode,
                offerExpiresAtMs = effectiveExpiresAt,
                distanceKm = distanceKm
            )
        }

        return posted
    }

    fun stopIncomingOrderBell(context: Context, orderKey: String = "") {
        val activeKey = IncomingOrderBellService.activeRingingOrderKey
        if (IncomingOrderBellService.isBellActive || orderKey.isNotBlank() || activeKey.isNotBlank()) {
            IncomingOrderBellService.stop(context, orderKey)
        }
        if (orderKey.isNotBlank()) {
            val notifId = computeOrderNotificationId(orderKey)
            runCatching { NotificationManagerCompat.from(context).cancel(notifId) }
        } else if (activeKey.isNotBlank()) {
            runCatching { NotificationManagerCompat.from(context).cancel(computeOrderNotificationId(activeKey)) }
        }
    }

    @SuppressLint("MissingPermission")
    fun showOfferExpiredTransferredNotification(
        context: Context,
        orderKey: String,
        storeName: String,
        orderCode: String = ""
    ): Boolean {
        ensureNotificationChannels(context)
        if (!hasNotificationPermission(context)) return false

        val cleanOrderCode = orderCode.trim().trimStart('#').trim()
        val label = if (cleanOrderCode.isNotBlank()) "طلب ($cleanOrderCode)" else "الطلب من $storeName"
        val body = "انتهت مهلة الـ 60 ثانية لـ $label دون قبول، وتم تحويله تلقائياً إلى أقرب كابتن للتاجر."
        val notifId = 50_000 + (kotlin.math.abs(orderKey.ifBlank { storeName }.hashCode()) % 1000)

        val notification = NotificationCompat.Builder(context, CHANNEL_NEW_ORDERS_ID)
            .setSmallIcon(resolveNotificationIcon())
            .setContentTitle("تم تحويل الطلب لكابتن آخر")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun showShiftStartNotification(
        context: Context,
        dayNameArabic: String,
        startFormatted: String,
        endFormatted: String,
        dedupeKey: String,
        bypassDedupe: Boolean = false
    ): Boolean {
        ensureNotificationChannels(context)
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "Skipping shift-start notification: POST_NOTIFICATIONS permission not granted")
            return false
        }

        if (!bypassDedupe && !markIfNotNotified(context, "shift_start_$dedupeKey", 20L * 3600_000L)) {
            return false
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAVIGATE_TARGET, TARGET_SHIFTS)
        }
        val notifId = 51_000 + (kotlin.math.abs(dedupeKey.hashCode()) % 5_000)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeDay = dayNameArabic.ifBlank { "اليوم" }
        val body = "بدأ شفت عملك ($safeDay) من $startFormatted إلى $endFormatted. يمكنك الآن استقبال الطلبات."

        val notification = NotificationCompat.Builder(context, CHANNEL_SHIFT_ALERTS_ID)
            .setSmallIcon(resolveNotificationIcon())
            .setContentTitle("بدأ شفت عملك الآن")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting shift-start notification: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun showShiftEndNotification(
        context: Context,
        dayNameArabic: String,
        endFormatted: String,
        dedupeKey: String,
        bypassDedupe: Boolean = false
    ): Boolean {
        ensureNotificationChannels(context)
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "Skipping shift-end notification: POST_NOTIFICATIONS permission not granted")
            return false
        }

        if (!bypassDedupe && !markIfNotNotified(context, "shift_end_$dedupeKey", 20L * 3600_000L)) {
            return false
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAVIGATE_TARGET, TARGET_SHIFTS)
        }
        val notifId = 56_000 + (kotlin.math.abs(dedupeKey.hashCode()) % 5_000)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeDay = dayNameArabic.ifBlank { "اليوم" }
        val body = "انتهى شفت عملك ($safeDay) الساعة $endFormatted. تم إيقاف إسناد الطلبات الجديدة تلقائياً."

        val notification = NotificationCompat.Builder(context, CHANNEL_SHIFT_ALERTS_ID)
            .setSmallIcon(resolveNotificationIcon())
            .setContentTitle("انتهى شفت عملك اليوم")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException posting shift-end notification: ${e.message}")
            false
        }
    }

    fun saveFcmToken(context: Context, token: String) {
        if (token.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
    }

    fun getSavedFcmToken(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun clearNotificationState(context: Context) {
        stopIncomingOrderBell(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (_: Exception) {}
    }

    private fun resolveNotificationIcon(): Int {
        return try {
            R.drawable.ic_notification_captain
        } catch (_: Exception) {
            android.R.drawable.ic_dialog_info
        }
    }
}
