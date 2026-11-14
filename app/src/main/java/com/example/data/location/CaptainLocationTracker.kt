package com.example.data.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CaptainLocationTracker {
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _gpsStatus = MutableStateFlow<GpsStatus>(GpsStatus.Inactive)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    fun updateLocation(location: Location) {
        _currentLocation.value = location
        _gpsStatus.value = GpsStatus.Active(location)
    }

    fun updateStatus(status: GpsStatus) {
        _gpsStatus.value = status
        if (status is GpsStatus.Inactive || status is GpsStatus.PermissionDenied || status is GpsStatus.GpsDisabled) {
            _currentLocation.value = null
        }
    }

    fun setTracking(tracking: Boolean) {
        _isTracking.value = tracking
        if (!tracking) {
            _gpsStatus.value = GpsStatus.Inactive
            _currentLocation.value = null
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    fun isGpsProviderEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun startService(context: Context) {
        val intent = Intent(context, CaptainLocationService::class.java).apply {
            action = GpsConfig.ACTION_START_LOCATION_SERVICE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService(context: Context) {
        val intent = Intent(context, CaptainLocationService::class.java).apply {
            action = GpsConfig.ACTION_STOP_LOCATION_SERVICE
        }
        context.startService(intent)
        setTracking(false)
    }
}
