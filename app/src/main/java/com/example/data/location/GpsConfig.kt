package com.example.data.location

/**
 * Configurable parameters for Captain GPS Tracking in El-Batal Express.
 * Balances battery efficiency and Firestore write limits with real-time accuracy for Dispatch & Admin Dashboard.
 */
object GpsConfig {
    /** Target update interval for FusedLocationProviderClient (10 seconds) */
    const val UPDATE_INTERVAL_MS = 10_000L

    /** Fastest interval for rapid movement updates (5 seconds) */
    const val FASTEST_INTERVAL_MS = 5_000L

    /** Minimum displacement before triggering a location callback (10 meters) */
    const val MIN_DISPLACEMENT_METERS = 10f

    /** Max delay between Firestore updates when stationary to keep dashboard live status fresh (30 seconds) */
    const val FIRESTORE_HEARTBEAT_INTERVAL_MS = 30_000L

    /** Threshold to consider a GPS fix stale (60 seconds) */
    const val STALE_GPS_THRESHOLD_MS = 60_000L

    /** Notification constants */
    const val NOTIFICATION_CHANNEL_ID = "elbatal_captain_location_channel"
    const val NOTIFICATION_CHANNEL_NAME = "خدمة التتبع المباشر - البطل إكسبريس"
    const val NOTIFICATION_ID = 1001

    /** Intent Actions */
    const val ACTION_START_LOCATION_SERVICE = "com.example.action.START_LOCATION_SERVICE"
    const val ACTION_STOP_LOCATION_SERVICE = "com.example.action.STOP_LOCATION_SERVICE"
}
