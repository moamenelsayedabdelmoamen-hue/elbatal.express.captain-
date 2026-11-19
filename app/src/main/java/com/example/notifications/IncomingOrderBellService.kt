package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.CaptainRepository
import com.example.data.OrderStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Foreground Service (`mediaPlayback`) that rings a continuous bell / ringtone when a new
 * delivery order arrives while the captain is outside the app (or inside), displays a live
 * 60-second countdown notification + Full-Screen Incoming Order popup, and automatically stops
 * ringing & transfers the order to the nearest captain if 60 seconds elapse without acceptance.
 */
class IncomingOrderBellService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var countdownJob: Job? = null

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    private var currentOrderKey: String = ""
    private var currentOrderId: Long = 0L
    private var currentNotificationId: Int = DEFAULT_NOTIFICATION_ID

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_BELL -> {
                val targetKey = intent.getStringExtra(EXTRA_ORDER_KEY).orEmpty()
                if (targetKey.isBlank() || currentOrderKey.isBlank() || targetKey == currentOrderKey) {
                    stopBellAndFinish(removeNotification = true)
                }
                return START_NOT_STICKY
            }

            ACTION_START_BELL -> {
                val orderKey = intent.getStringExtra(EXTRA_ORDER_KEY).orEmpty()
                val orderId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
                val storeName = intent.getStringExtra(EXTRA_STORE_NAME).orEmpty()
                val pickupAddress = intent.getStringExtra(EXTRA_PICKUP_ADDRESS).orEmpty()
                val deliveryAddress = intent.getStringExtra(EXTRA_DELIVERY_ADDRESS).orEmpty()
                val deliveryFee = intent.getDoubleExtra(EXTRA_DELIVERY_FEE, 25.0)
                val orderCode = intent.getStringExtra(EXTRA_ORDER_CODE).orEmpty()
                val distanceKm = intent.getDoubleExtra(EXTRA_DISTANCE_KM, 3.0)
                val rawExpiresAt = intent.getLongExtra(EXTRA_OFFER_EXPIRES_AT_MS, 0L)
                val notificationId = intent.getIntExtra(
                    EXTRA_NOTIFICATION_ID,
                    CaptainNotificationManager.computeOrderNotificationId(orderKey)
                )

                val nowMs = System.currentTimeMillis()
                val expiresAtMs = if (rawExpiresAt > nowMs) rawExpiresAt else nowMs + 60_000L

                if (orderKey.isNotBlank() && orderKey == currentOrderKey && countdownJob?.isActive == true) {
                    // Already ringing for this exact order
                    return START_NOT_STICKY
                }

                currentOrderKey = orderKey
                currentOrderId = orderId
                currentNotificationId = notificationId
                activeRingingOrderKey = orderKey
                isBellActive = true

                CaptainNotificationManager.ensureNotificationChannels(this)
                val initialRemaining = (((expiresAtMs - System.currentTimeMillis()) + 999L) / 1000L)
                    .toInt()
                    .coerceIn(1, 60)

                val foregroundNotification = buildRingingNotification(
                    notificationId = notificationId,
                    orderKey = orderKey,
                    orderId = orderId,
                    storeName = storeName,
                    pickupAddress = pickupAddress,
                    deliveryAddress = deliveryAddress,
                    deliveryFee = deliveryFee,
                    orderCode = orderCode,
                    expiresAtMs = expiresAtMs,
                    remainingSeconds = initialRemaining
                )

                val fgStarted = startAsForegroundService(notificationId, foregroundNotification)
                if (!fgStarted) {
                    Log.w(TAG, "Unable to promote IncomingOrderBellService to foreground; stopping fallback service safely")
                    stopBellAndFinish(removeNotification = false)
                    return START_NOT_STICKY
                }

                acquireShortWakeLock()
                startContinuousBellSound()
                startContinuousVibration()
                startCountdownAndMonitor(
                    notificationId = notificationId,
                    orderKey = orderKey,
                    orderId = orderId,
                    storeName = storeName,
                    pickupAddress = pickupAddress,
                    deliveryAddress = deliveryAddress,
                    deliveryFee = deliveryFee,
                    orderCode = orderCode,
                    distanceKm = distanceKm,
                    expiresAtMs = expiresAtMs
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForegroundService(notificationId: Int, notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(notificationId, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed for IncomingOrderBellService: ${e.message}", e)
            false
        }
    }

    private fun buildRingingNotification(
        notificationId: Int,
        orderKey: String,
        orderId: Long,
        storeName: String,
        pickupAddress: String,
        deliveryAddress: String,
        deliveryFee: Double,
        orderCode: String,
        expiresAtMs: Long,
        remainingSeconds: Int
    ): Notification {
        val fullScreenIntent = IncomingOrderActivity.createIntent(
            context = this,
            orderKey = orderKey,
            orderId = orderId,
            storeName = storeName,
            pickupAddress = pickupAddress,
            deliveryAddress = deliveryAddress,
            deliveryFee = deliveryFee,
            orderCode = orderCode,
            offerExpiresAtMs = expiresAtMs
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            notificationId + 10_000,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CaptainNotificationManager.EXTRA_NAVIGATE_TARGET, CaptainNotificationManager.TARGET_DASHBOARD)
            putExtra(CaptainNotificationManager.EXTRA_ACCEPT_ORDER_ID, orderId)
            putExtra(CaptainNotificationManager.EXTRA_ORDER_KEY, orderKey)
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            this,
            notificationId + 20_000,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeStore = storeName.ifBlank { "التاجر" }
        val feeText = "${deliveryFee.toInt()} ج.م"
        val cleanCode = orderCode.trim().trimStart('#').trim()
        val codePrefix = if (cleanCode.isNotBlank()) "كود الطلب: $cleanCode • " else ""
        val formattedTime = String.format(java.util.Locale.US, "%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)

        val contentLine = "${codePrefix}$safeStore • أرباح $feeText • متبقي $formattedTime"
        val bigText = buildString {
            append(codePrefix).append("من ").append(safeStore).append(" • أرباح التوصيل ").append(feeText)
            if (pickupAddress.isNotBlank()) append("\nالاستلام: ").append(pickupAddress)
            if (deliveryAddress.isNotBlank()) append("\nالتسليم: ").append(deliveryAddress)
            append("\n⏱️ متبقي ").append(remainingSeconds).append(" ثانية قبل تحويل الطلب لأقرب كابتن للتاجر")
        }

        val builder = NotificationCompat.Builder(this, CaptainNotificationManager.CHANNEL_NEW_ORDERS_BELL_ID)
            .setSmallIcon(resolveSmallIcon())
            .setContentTitle("طلب توصيل جديد! 🔔 ($formattedTime)")
            .setContentText(contentLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setWhen(expiresAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(
                0,
                "قبول الطلب الآن ($remainingSeconds ث)",
                acceptPendingIntent
            )

        val built = builder.build()
        built.flags = built.flags or Notification.FLAG_INSISTENT or Notification.FLAG_ONGOING_EVENT
        return built
    }

    private fun startCountdownAndMonitor(
        notificationId: Int,
        orderKey: String,
        orderId: Long,
        storeName: String,
        pickupAddress: String,
        deliveryAddress: String,
        deliveryFee: Double,
        orderCode: String,
        distanceKm: Double,
        expiresAtMs: Long
    ) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            val db = runCatching { AppDatabase.getDatabase(applicationContext) }.getOrNull()
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager

            try {
                while (true) {
                    val nowMs = System.currentTimeMillis()
                    val remainingMs = expiresAtMs - nowMs
                    val remainingSecs = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)

                    // Stop immediately if captain went offline, logged out, or accepted/removed the order
                    if (db != null) {
                        val captain = runCatching { db.captainDao().getCaptain() }.getOrNull()
                        if (captain == null || !captain.isLoggedIn || !captain.isOnline) {
                            Log.i(TAG, "Captain offline or logged out; stopping incoming order bell")
                            stopBellAndFinish(removeNotification = true)
                            return@launch
                        }

                        val trackedOrder = findTrackedOrder(db, orderId, orderKey)
                        if (trackedOrder != null && trackedOrder.status != OrderStatus.PENDING) {
                            Log.i(TAG, "Order status transitioned to ${trackedOrder.status}; stopping incoming order bell")
                            stopBellAndFinish(removeNotification = true)
                            return@launch
                        }
                    }

                    if (remainingSecs <= 0) {
                        Log.i(TAG, "60-second timer elapsed without acceptance; stopping bell & transferring to nearest captain")
                        stopSoundAndVibrationOnly()
                        handleOfferTimeoutTransfer(
                            db = db,
                            orderId = orderId,
                            orderKey = orderKey,
                            storeName = storeName,
                            orderCode = orderCode
                        )
                        stopBellAndFinish(removeNotification = true)
                        return@launch
                    }

                    // Refresh notification countdown text cleanly without re-alerting
                    if (CaptainNotificationManager.hasNotificationPermission(applicationContext)) {
                        val updated = buildRingingNotification(
                            notificationId = notificationId,
                            orderKey = orderKey,
                            orderId = orderId,
                            storeName = storeName,
                            pickupAddress = pickupAddress,
                            deliveryAddress = deliveryAddress,
                            deliveryFee = deliveryFee,
                            orderCode = orderCode,
                            expiresAtMs = expiresAtMs,
                            remainingSeconds = remainingSecs.coerceIn(1, 60)
                        )
                        runCatching { notifManager?.notify(notificationId, updated) }
                    }

                    delay(1000L)
                }
            } catch (_: CancellationException) {
                // Normal cancellation on stop
            } catch (e: Exception) {
                Log.w(TAG, "Countdown monitor notice: ${e.message}")
            }
        }
    }

    private suspend fun findTrackedOrder(db: AppDatabase, orderId: Long, orderKey: String): com.example.data.OrderEntity? {
        if (orderKey.isNotBlank()) {
            val byDoc = runCatching { db.orderDao().findByFirestoreDocId(orderKey) }.getOrNull()
            if (byDoc != null) return byDoc
        }
        val all = runCatching { db.orderDao().getAllOrdersFlow().firstOrNull() }.getOrNull() ?: return null
        return all.find {
            (orderId > 0L && it.id == orderId) ||
                (orderKey.isNotBlank() && (it.firestoreDocId == orderKey || it.cleanOrderNumber == orderKey))
        }
    }

    private suspend fun handleOfferTimeoutTransfer(
        db: AppDatabase?,
        orderId: Long,
        orderKey: String,
        storeName: String,
        orderCode: String
    ) {
        try {
            if (db != null) {
                val repo = CaptainRepository(
                    captainDao = db.captainDao(),
                    orderDao = db.orderDao(),
                    shiftDao = db.shiftDao(),
                    context = applicationContext
                )
                val targetOrder = findTrackedOrder(db, orderId, orderKey)
                val resolvedId = targetOrder?.id ?: orderId
                if (resolvedId > 0L) {
                    val result = repo.expireDeliveryOffer(resolvedId)
                    Log.i(TAG, "Auto-expire transfer result for order $resolvedId: ${result.isSuccess}")
                } else if (orderKey.isNotBlank()) {
                    val client = com.example.data.remote.CloudFunctionsOrderClient()
                    client.expireDeliveryOffer(orderKey, reason = "expired")
                }
            } else if (orderKey.isNotBlank()) {
                val client = com.example.data.remote.CloudFunctionsOrderClient()
                client.expireDeliveryOffer(orderKey, reason = "expired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error transferring timed-out order to nearest captain: ${e.message}")
        } finally {
            CaptainNotificationManager.showOfferExpiredTransferredNotification(
                context = applicationContext,
                orderKey = orderKey,
                storeName = storeName,
                orderCode = orderCode
            )
        }
    }

    private fun startContinuousBellSound() {
        stopSoundAndVibrationOnly()
        val ringtoneUri = resolveBellUri() ?: return

        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = focusReq
                runCatching { audioManager?.requestAudioFocus(focusReq) }
            }

            val player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(applicationContext, ringtoneUri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
            Log.i(TAG, "Started continuous looping bell MediaPlayer")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer bell failed (${e.message}), falling back to Ringtone API")
            runCatching {
                val ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = true
                    }
                    ringtone.play()
                    fallbackRingtone = ringtone
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousVibration() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator = vib
            val pattern = longArrayOf(0, 600, 350, 600, 650)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(pattern, 0)
            }
        }
    }

    private fun resolveBellUri(): Uri? {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    private fun acquireShortWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ElBatal::IncomingOrderBellWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(65_000L)
            }
        }
    }

    private fun stopSoundAndVibrationOnly() {
        runCatching {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        }
        mediaPlayer = null

        runCatching {
            fallbackRingtone?.stop()
        }
        fallbackRingtone = null

        runCatching {
            vibrator?.cancel()
        }
        vibrator = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { req ->
                runCatching { audioManager?.abandonAudioFocusRequest(req) }
            }
            audioFocusRequest = null
        }

        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun stopBellAndFinish(removeNotification: Boolean) {
        countdownJob?.cancel()
        countdownJob = null
        stopSoundAndVibrationOnly()

        isBellActive = false
        activeRingingOrderKey = ""
        currentOrderKey = ""

        if (removeNotification) {
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                nm?.cancel(currentNotificationId)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
        stopSelf()
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        stopSoundAndVibrationOnly()
        isBellActive = false
        activeRingingOrderKey = ""
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun resolveSmallIcon(): Int {
        return try {
            R.drawable.ic_notification_captain
        } catch (_: Exception) {
            android.R.drawable.ic_dialog_info
        }
    }

    companion object {
        private const val TAG = "IncomingOrderBellSvc"
        private const val DEFAULT_NOTIFICATION_ID = 49_999

        const val ACTION_START_BELL = "com.example.action.START_ORDER_BELL"
        const val ACTION_STOP_BELL = "com.example.action.STOP_ORDER_BELL"

        const val EXTRA_ORDER_KEY = "extra_order_key"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_STORE_NAME = "extra_store_name"
        const val EXTRA_PICKUP_ADDRESS = "extra_pickup_address"
        const val EXTRA_DELIVERY_ADDRESS = "extra_delivery_address"
        const val EXTRA_DELIVERY_FEE = "extra_delivery_fee"
        const val EXTRA_ORDER_CODE = "extra_order_code"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
        const val EXTRA_OFFER_EXPIRES_AT_MS = "extra_offer_expires_at_ms"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        @Volatile
        var isBellActive: Boolean = false
            private set

        @Volatile
        var activeRingingOrderKey: String = ""
            private set

        fun start(
            context: Context,
            notificationId: Int,
            orderKey: String,
            orderId: Long,
            storeName: String,
            pickupAddress: String,
            deliveryAddress: String,
            deliveryFee: Double,
            orderCode: String,
            offerExpiresAtMs: Long,
            distanceKm: Double = 3.0
        ) {
            val intent = Intent(context, IncomingOrderBellService::class.java).apply {
                action = ACTION_START_BELL
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_ORDER_KEY, orderKey)
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_STORE_NAME, storeName)
                putExtra(EXTRA_PICKUP_ADDRESS, pickupAddress)
                putExtra(EXTRA_DELIVERY_ADDRESS, deliveryAddress)
                putExtra(EXTRA_DELIVERY_FEE, deliveryFee)
                putExtra(EXTRA_ORDER_CODE, orderCode)
                putExtra(EXTRA_DISTANCE_KM, distanceKm)
                putExtra(EXTRA_OFFER_EXPIRES_AT_MS, offerExpiresAtMs)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start IncomingOrderBellService from current state: ${e.message}")
            }
        }

        fun stop(context: Context, orderKey: String = "") {
            isBellActive = false
            activeRingingOrderKey = ""
            val intent = Intent(context, IncomingOrderBellService::class.java).apply {
                action = ACTION_STOP_BELL
                putExtra(EXTRA_ORDER_KEY, orderKey)
            }
            runCatching {
                context.stopService(intent)
            }
        }
    }
}
