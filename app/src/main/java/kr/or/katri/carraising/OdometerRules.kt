package kr.or.katri.carraising

import kotlin.math.max

internal object OdometerRules {
    fun currentOdoKm(
        confirmedOdoKm: Double?,
        trackedDistanceM: Double,
        confirmationDistanceM: Double
    ): Double? {
        val baseOdoKm = confirmedOdoKm ?: return null
        val distanceSinceConfirmationKm =
            max(0.0, trackedDistanceM - confirmationDistanceM) / 1000.0
        return baseOdoKm + distanceSinceConfirmationKm
    }
}
