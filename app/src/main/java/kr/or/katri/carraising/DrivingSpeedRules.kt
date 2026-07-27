package kr.or.katri.carraising

internal object DrivingSpeedRules {
    fun hasReachedDecelerationTarget(
        targetKmh: Int,
        displayedSpeedKmh: Double,
        latestRawSpeedKmh: Double?,
        toleranceKmh: Double
    ): Boolean {
        if (targetKmh <= 0) return displayedSpeedKmh <= 0.0
        val decisionSpeedKmh = latestRawSpeedKmh ?: return false
        return decisionSpeedKmh <= targetKmh + toleranceKmh
    }
}
