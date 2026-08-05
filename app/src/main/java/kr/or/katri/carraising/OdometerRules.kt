package kr.or.katri.carraising

import java.util.Locale
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

    fun editableOdoText(currentOdoKm: Double?): String? {
        val odoKm = currentOdoKm ?: return null
        return String.format(Locale.US, "%.2f", odoKm).trimEnd('0').trimEnd('.')
    }
}
