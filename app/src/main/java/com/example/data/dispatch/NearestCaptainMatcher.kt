package com.example.data.dispatch

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Matches an unaccepted or timed-out order to the nearest eligible online captain
 * relative to the merchant's pickup coordinates using the Haversine formula.
 */
object NearestCaptainMatcher {

    private const val EARTH_RADIUS_KM = 6371.0

    data class CaptainCandidate(
        val uid: String,
        val latitude: Double?,
        val longitude: Double?,
        val isOnline: Boolean,
        val accountActive: Boolean,
        val inActiveShift: Boolean,
        val hasOngoingOrder: Boolean
    )

    data class RankedCaptainMatch(
        val uid: String,
        val distanceKm: Double?
    )

    fun hasValidCoordinates(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (!lat.isFinite() || !lng.isFinite()) return false
        if (lat == 0.0 && lng == 0.0) return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }

    fun calculateDistanceKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2.0).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Filters out excluded, offline, inactive, out-of-shift, or busy captains,
     * then ranks the remaining captains by distance to the merchant (closest first).
     */
    fun rankEligibleCaptains(
        merchantLat: Double?,
        merchantLng: Double?,
        candidates: List<CaptainCandidate>,
        excludedCaptainIds: Set<String>
    ): List<RankedCaptainMatch> {
        val merchantHasCoords = hasValidCoordinates(merchantLat, merchantLng)
        val eligible = candidates.filter { c ->
            c.uid.isNotBlank() &&
                c.uid !in excludedCaptainIds &&
                c.isOnline &&
                c.accountActive &&
                c.inActiveShift &&
                !c.hasOngoingOrder
        }

        val withDistances = eligible.map { c ->
            val dist = if (merchantHasCoords && hasValidCoordinates(c.latitude, c.longitude)) {
                calculateDistanceKm(merchantLat!!, merchantLng!!, c.latitude!!, c.longitude!!)
            } else {
                null
            }
            RankedCaptainMatch(uid = c.uid, distanceKm = dist)
        }

        return withDistances.sortedWith(
            compareBy<RankedCaptainMatch>(
                { it.distanceKm == null },
                { it.distanceKm ?: Double.MAX_VALUE },
                { it.uid }
            )
        )
    }

    fun findNearestEligibleCaptain(
        merchantLat: Double?,
        merchantLng: Double?,
        candidates: List<CaptainCandidate>,
        excludedCaptainIds: Set<String>
    ): RankedCaptainMatch? {
        return rankEligibleCaptains(
            merchantLat = merchantLat,
            merchantLng = merchantLng,
            candidates = candidates,
            excludedCaptainIds = excludedCaptainIds
        ).firstOrNull()
    }
}
