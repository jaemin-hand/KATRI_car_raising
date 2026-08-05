package kr.or.katri.carraising

import kotlin.math.max

internal enum class DistanceAnchorAction {
    HOLD,
    ACCEPT,
    RESET
}

internal object GpsDistanceRules {
    private const val MAX_DISTANCE_ACCURACY_M = 15f
    private const val MIN_SEGMENT_DISTANCE_M = 30.0
    private const val MAX_PLAUSIBLE_SPEED_MPS = 220.0 / 3.6
    private const val GPS_SPEED_DISTANCE_MULTIPLIER = 1.5
    private const val OUTLIER_SLACK_M = 10.0
    private const val MIN_SPEED_BASED_LIMIT_M = 35.0

    fun hasUsableAccuracy(accuracyM: Float?): Boolean {
        return accuracyM != null &&
            accuracyM.isFinite() &&
            accuracyM >= 0f &&
            accuracyM <= MAX_DISTANCE_ACCURACY_M
    }

    fun evaluateSegment(
        segmentDistanceM: Double,
        elapsedSeconds: Double,
        previousAccuracyM: Float?,
        currentAccuracyM: Float?,
        movementSpeedKmh: Double,
        currentGpsSpeedMps: Double?,
        minimumMovingSpeedKmh: Double
    ): DistanceAnchorAction {
        if (!hasUsableAccuracy(currentAccuracyM)) return DistanceAnchorAction.HOLD
        if (!hasUsableAccuracy(previousAccuracyM)) return DistanceAnchorAction.RESET
        if (
            !segmentDistanceM.isFinite() || segmentDistanceM < 0.0 ||
            !elapsedSeconds.isFinite() || elapsedSeconds <= 0.0
        ) {
            return DistanceAnchorAction.RESET
        }
        if (segmentDistanceM < MIN_SEGMENT_DISTANCE_M) return DistanceAnchorAction.HOLD
        if (movementSpeedKmh < minimumMovingSpeedKmh) return DistanceAnchorAction.RESET

        val absoluteDistanceLimitM =
            MAX_PLAUSIBLE_SPEED_MPS * elapsedSeconds + OUTLIER_SLACK_M
        val gpsSpeedDistanceLimitM = currentGpsSpeedMps
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { speedMps ->
                max(
                    MIN_SPEED_BASED_LIMIT_M,
                    speedMps * elapsedSeconds * GPS_SPEED_DISTANCE_MULTIPLIER +
                        OUTLIER_SLACK_M
                )
            }
        val allowedDistanceM = gpsSpeedDistanceLimitM
            ?.let { minOf(it, absoluteDistanceLimitM) }
            ?: absoluteDistanceLimitM

        return if (segmentDistanceM <= allowedDistanceM) {
            DistanceAnchorAction.ACCEPT
        } else {
            DistanceAnchorAction.RESET
        }
    }
}
