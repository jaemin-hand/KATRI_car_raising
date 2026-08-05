package kr.or.katri.carraising

internal class LapSuccessMonitor(
    private val toleranceKmh: Double,
    private val requiredConsecutiveSamples: Int = 2
) {
    init {
        require(toleranceKmh >= 0.0)
        require(requiredConsecutiveSamples > 0)
    }

    private var trackedStepId: String? = null
    private var consecutiveTargetSpeedSamples = 0

    var isTargetSpeedConfirmed: Boolean = false
        private set

    fun update(
        step: DrivingScenarioStep?,
        actionState: DrivingActionState,
        currentSpeedKmh: Double,
        hasValidSpeedSample: Boolean,
        isGpsSignalStale: Boolean
    ) {
        if (step == null) {
            trackedStepId = null
            clearConfirmation()
            return
        }
        if (step.id != trackedStepId) {
            trackedStepId = step.id
            clearConfirmation()
        }

        val isEligible = when (step.driveMode) {
            ScenarioDriveMode.CONSTANT -> true
            ScenarioDriveMode.ACCEL_DECEL ->
                actionState == DrivingActionState.ACCELERATING
        }
        if (!isEligible || !hasValidSpeedSample || isGpsSignalStale) {
            clearConfirmation()
            return
        }

        if (
            TargetSpeedGuidanceRules.isWithinTolerance(
                targetSpeedKmh = step.targetSpeedKmh,
                currentSpeedKmh = currentSpeedKmh,
                toleranceKmh = toleranceKmh
            )
        ) {
            consecutiveTargetSpeedSamples += 1
        } else {
            consecutiveTargetSpeedSamples = 0
        }
        isTargetSpeedConfirmed =
            consecutiveTargetSpeedSamples >= requiredConsecutiveSamples
    }

    fun reset() {
        trackedStepId = null
        clearConfirmation()
    }

    private fun clearConfirmation() {
        consecutiveTargetSpeedSamples = 0
        isTargetSpeedConfirmed = false
    }
}
