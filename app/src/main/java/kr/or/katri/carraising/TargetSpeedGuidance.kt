package kr.or.katri.carraising

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SpeedAdjustmentDirection {
    INCREASE,
    DECREASE
}

data class SpeedGuidance(
    val targetSpeedKmh: Int,
    val currentSpeedKmh: Double,
    val adjustmentKmh: Double,
    val direction: SpeedAdjustmentDirection
)

internal object TargetSpeedGuidanceRules {
    fun isWithinTolerance(
        targetSpeedKmh: Int,
        currentSpeedKmh: Double,
        toleranceKmh: Double
    ): Boolean {
        val displayedSpeedKmh = displayedSpeed(currentSpeedKmh)
        return abs(displayedSpeedKmh - targetSpeedKmh) <= toleranceKmh
    }

    fun evaluate(
        targetSpeedKmh: Int,
        currentSpeedKmh: Double,
        toleranceKmh: Double
    ): SpeedGuidance? {
        val displayedSpeedKmh = displayedSpeed(currentSpeedKmh)
        val differenceKmh = displayedSpeedKmh - targetSpeedKmh
        if (abs(differenceKmh) <= toleranceKmh) return null

        return SpeedGuidance(
            targetSpeedKmh = targetSpeedKmh,
            currentSpeedKmh = displayedSpeedKmh,
            adjustmentKmh = displayedSpeed(abs(differenceKmh)),
            direction = if (differenceKmh < 0.0) {
                SpeedAdjustmentDirection.INCREASE
            } else {
                SpeedAdjustmentDirection.DECREASE
            }
        )
    }

    fun displayMessage(guidance: SpeedGuidance): String {
        val direction = when (guidance.direction) {
            SpeedAdjustmentDirection.INCREASE -> "올리세요"
            SpeedAdjustmentDirection.DECREASE -> "내리세요"
        }
        return String.format(
            Locale.US,
            "규정속도 이탈 · 속도를 %.1fkm/h %s",
            guidance.adjustmentKmh,
            direction
        )
    }

    fun voiceMessage(guidance: SpeedGuidance): String {
        val state = when (guidance.direction) {
            SpeedAdjustmentDirection.INCREASE -> "낮습니다"
            SpeedAdjustmentDirection.DECREASE -> "높습니다"
        }
        val direction = when (guidance.direction) {
            SpeedAdjustmentDirection.INCREASE -> "올리세요"
            SpeedAdjustmentDirection.DECREASE -> "내리세요"
        }
        val adjustment = String.format(Locale.US, "%.1f", guidance.adjustmentKmh)
        return "기준 속도보다 ${adjustment}킬로미터 $state. " +
            "속도를 ${adjustment}킬로미터 $direction."
    }

    private fun displayedSpeed(speedKmh: Double): Double {
        return (speedKmh * 10.0).roundToInt() / 10.0
    }
}

internal class TargetSpeedMonitor(
    private val toleranceKmh: Double
) {
    var armedTargetSpeedKmh: Int? = null
        private set

    var guidance: SpeedGuidance? = null
        private set

    fun update(
        sessionState: SessionState,
        step: DrivingScenarioStep?,
        actionState: DrivingActionState,
        currentSpeedKmh: Double,
        hasValidSpeedSample: Boolean,
        isGpsSignalStale: Boolean
    ): SpeedGuidance? {
        val isEligibleAction =
            actionState == DrivingActionState.CRUISING ||
                actionState == DrivingActionState.WAITING_FOR_BRAKE_LINE
        if (
            sessionState != SessionState.Running ||
            step == null ||
            !isEligibleAction ||
            !hasValidSpeedSample ||
            isGpsSignalStale
        ) {
            guidance = null
            return null
        }

        if (armedTargetSpeedKmh != step.targetSpeedKmh) {
            armedTargetSpeedKmh = if (
                TargetSpeedGuidanceRules.isWithinTolerance(
                    targetSpeedKmh = step.targetSpeedKmh,
                    currentSpeedKmh = currentSpeedKmh,
                    toleranceKmh = toleranceKmh
                )
            ) {
                step.targetSpeedKmh
            } else {
                null
            }
            guidance = null
            return null
        }

        guidance = TargetSpeedGuidanceRules.evaluate(
            targetSpeedKmh = step.targetSpeedKmh,
            currentSpeedKmh = currentSpeedKmh,
            toleranceKmh = toleranceKmh
        )
        return guidance
    }

    fun suspendGuidance() {
        guidance = null
    }

    fun reset() {
        armedTargetSpeedKmh = null
        guidance = null
    }

    fun restoreArmedTargetSpeed(targetSpeedKmh: Int?) {
        armedTargetSpeedKmh = targetSpeedKmh
        guidance = null
    }
}
