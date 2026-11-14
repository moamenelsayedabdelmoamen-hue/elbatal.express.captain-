package com.example.data.location

import android.location.Location

sealed interface GpsStatus {
    data object Inactive : GpsStatus
    data object Connecting : GpsStatus
    data class Active(
        val location: Location,
        val updatedAt: Long = System.currentTimeMillis()
    ) : GpsStatus
    data object PermissionDenied : GpsStatus
    data object GpsDisabled : GpsStatus
    data object NetworkOffline : GpsStatus
    data object Stale : GpsStatus
}
