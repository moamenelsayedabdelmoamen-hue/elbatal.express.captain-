package com.example.data.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.CaptainRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CaptainLocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var repository: CaptainRepository? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            val db = AppDatabase.getDatabase(applicationContext)
            repository = CaptainRepository(db.captainDao(), db.orderDao(), applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing repository in service", e)
        }

        setupLocationCallback()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            GpsConfig.ACTION_START_LOCATION_SERVICE -> startLocationTracking()
            GpsConfig.ACTION_STOP_LOCATION_SERVICE -> stopLocationTracking()
        }
        return START_STICKY
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onNewLocation(location)
            }
        }
    }

    private fun onNewLocation(location: Location) {
        Log.d(TAG, "New GPS location received: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m)")

        // Update Tracker state flow for UI
        CaptainLocationTracker.updateLocation(location)

        // Sync to Firestore via repository
        serviceScope.launch {
            try {
                repository?.updateCaptainGpsLocation(location)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing location from service to Firestore", e)
            }
        }

        // Update notification subtext if wanted
        updateNotification("تم تحديث موقعك: ${String.format("%.4f", location.latitude)}, ${String.format("%.4f", location.longitude)}")
    }

    private fun startLocationTracking() {
        if (!CaptainLocationTracker.hasLocationPermission(this)) {
            Log.w(TAG, "Location permission missing, stopping service")
            CaptainLocationTracker.updateStatus(GpsStatus.PermissionDenied)
            stopSelf()
            return
        }

        if (!CaptainLocationTracker.isGpsProviderEnabled(this)) {
            Log.w(TAG, "GPS Provider is disabled")
            CaptainLocationTracker.updateStatus(GpsStatus.GpsDisabled)
        } else {
            CaptainLocationTracker.updateStatus(GpsStatus.Connecting)
        }

        CaptainLocationTracker.setTracking(true)

        val notification = buildForegroundNotification("جاري تحديد موقعك عبر الأقمار الصناعية GPS...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    GpsConfig.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(
                    GpsConfig.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            }
        } else {
            startForeground(GpsConfig.NOTIFICATION_ID, notification)
        }

        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                GpsConfig.UPDATE_INTERVAL_MS
            ).apply {
                setMinUpdateIntervalMillis(GpsConfig.FASTEST_INTERVAL_MS)
                setMinUpdateDistanceMeters(GpsConfig.MIN_DISPLACEMENT_METERS)
                setWaitForAccurateLocation(false)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // Also request last known location immediately for instant startup
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                if (lastLoc != null) {
                    onNewLocation(lastLoc)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during requestLocationUpdates", e)
            CaptainLocationTracker.updateStatus(GpsStatus.PermissionDenied)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
        }
    }

    private fun stopLocationTracking() {
        Log.i(TAG, "Stopping location tracking service")
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository?.markCaptainOfflineInFirestore()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.w(TAG, "Error marking captain offline in Firestore: ${e.message}")
                }
            }
        }

        CaptainLocationTracker.setTracking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GpsConfig.NOTIFICATION_CHANNEL_ID,
                GpsConfig.NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار تشغيل خدمة بث وتتبع موقع الكابتن المباشر"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIconRes = try {
            R.drawable.ic_launcher_foreground
        } catch (_: Exception) {
            android.R.drawable.ic_menu_mylocation
        }

        return NotificationCompat.Builder(this, GpsConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("البطل إكسبريس - الكابتن متصل ⚡")
            .setContentText(statusText)
            .setSmallIcon(smallIconRes)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(GpsConfig.NOTIFICATION_ID, buildForegroundNotification(statusText))
        } catch (e: Exception) {
            Log.w(TAG, "Could not update notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {}
        serviceScope.cancel()
        CaptainLocationTracker.setTracking(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CaptainLocationService"
    }
}
