package kr.or.katri.carraising

internal object BrakeLineGateRules {
    const val MINIMUM_REARM_DISTANCE_M = 4_000.0

    fun isEligible(
        traveledSinceLastGateM: Double,
        isInitialBrakeLinePassPending: Boolean,
        minimumRearmDistanceM: Double = MINIMUM_REARM_DISTANCE_M
    ): Boolean {
        require(minimumRearmDistanceM >= 0.0)
        if (!traveledSinceLastGateM.isFinite() || traveledSinceLastGateM < 0.0) {
            return false
        }
        return isInitialBrakeLinePassPending ||
            traveledSinceLastGateM >= minimumRearmDistanceM
    }
}
