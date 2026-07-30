package kr.or.katri.carraising

internal object DrivingSpeedRules {
    fun hasReachedDecelerationTarget(
        targetKmh: Int,
        displayedSpeedKmh: Double,
        latestRawSpeedKmh: Double?,
        toleranceKmh: Double,
        hasStationaryEvidence: Boolean
    ): Boolean {
        if (targetKmh <= 0) {
            if (displayedSpeedKmh <= 0.0) return true
            val decisionSpeedKmh = latestRawSpeedKmh ?: return false
            return hasStationaryEvidence && decisionSpeedKmh <= toleranceKmh
        }
        val decisionSpeedKmh = latestRawSpeedKmh ?: return false
        return decisionSpeedKmh <= targetKmh + toleranceKmh
    }
}
