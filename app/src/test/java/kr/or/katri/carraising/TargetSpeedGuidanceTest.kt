package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetSpeedGuidanceTest {
    @Test
    fun speedAboveToleranceRequestsExactDecrease() {
        val guidance = TargetSpeedGuidanceRules.evaluate(
            targetSpeedKmh = 130,
            currentSpeedKmh = 133.2,
            toleranceKmh = 3.0
        )!!

        assertEquals(SpeedAdjustmentDirection.DECREASE, guidance.direction)
        assertEquals(3.2, guidance.adjustmentKmh, 0.001)
        assertEquals(
            "규정속도 이탈 · 속도를 3.2km/h 내리세요",
            TargetSpeedGuidanceRules.displayMessage(guidance)
        )
    }

    @Test
    fun speedBelowToleranceRequestsExactIncrease() {
        val guidance = TargetSpeedGuidanceRules.evaluate(
            targetSpeedKmh = 130,
            currentSpeedKmh = 126.8,
            toleranceKmh = 3.0
        )!!

        assertEquals(SpeedAdjustmentDirection.INCREASE, guidance.direction)
        assertEquals(3.2, guidance.adjustmentKmh, 0.001)
        assertEquals(
            "기준 속도보다 3.2킬로미터 낮습니다. 속도를 3.2킬로미터 올리세요.",
            TargetSpeedGuidanceRules.voiceMessage(guidance)
        )
    }

    @Test
    fun inclusiveToleranceDoesNotCreateGuidance() {
        assertNull(TargetSpeedGuidanceRules.evaluate(130, 133.0, 3.0))
        assertNull(TargetSpeedGuidanceRules.evaluate(130, 127.0, 3.0))
    }

    @Test
    fun monitorWaitsUntilTargetRangeHasBeenReached() {
        val monitor = TargetSpeedMonitor(toleranceKmh = 3.0)
        val step = KatriDrivingScenario.stepAt(1_250.0, PowertrainType.COMBUSTION)!!

        assertNull(
            monitor.update(
                sessionState = SessionState.Running,
                step = step,
                actionState = DrivingActionState.CRUISING,
                currentSpeedKmh = 90.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false
            )
        )
        assertNull(
            monitor.update(
                sessionState = SessionState.Running,
                step = step,
                actionState = DrivingActionState.CRUISING,
                currentSpeedKmh = 130.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false
            )
        )

        val guidance = monitor.update(
            sessionState = SessionState.Running,
            step = step,
            actionState = DrivingActionState.CRUISING,
            currentSpeedKmh = 133.2,
            hasValidSpeedSample = true,
            isGpsSignalStale = false
        )

        assertEquals(SpeedAdjustmentDirection.DECREASE, guidance?.direction)
    }

    @Test
    fun monitorSuspendsGuidanceDuringBrakeAndReacceleration() {
        val monitor = TargetSpeedMonitor(toleranceKmh = 3.0)
        val step = KatriDrivingScenario.stepAt(1_350.0, PowertrainType.COMBUSTION)!!
        monitor.update(
            SessionState.Running,
            step,
            DrivingActionState.WAITING_FOR_BRAKE_LINE,
            130.0,
            hasValidSpeedSample = true,
            isGpsSignalStale = false
        )

        assertNull(
            monitor.update(
                SessionState.Running,
                step,
                DrivingActionState.DECELERATING,
                100.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false
            )
        )
        assertNull(
            monitor.update(
                SessionState.Running,
                step,
                DrivingActionState.ACCELERATING,
                100.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false
            )
        )

        val guidance = monitor.update(
            SessionState.Running,
            step,
            DrivingActionState.WAITING_FOR_BRAKE_LINE,
            134.0,
            hasValidSpeedSample = true,
            isGpsSignalStale = false
        )
        assertEquals(SpeedAdjustmentDirection.DECREASE, guidance?.direction)
    }

    @Test
    fun monitorRemainsArmedAcrossSectionsWithSameTargetSpeed() {
        val monitor = TargetSpeedMonitor(toleranceKmh = 3.0)
        val constantStep =
            KatriDrivingScenario.stepAt(1_250.0, PowertrainType.COMBUSTION)!!
        val accelDecelStep =
            KatriDrivingScenario.stepAt(1_350.0, PowertrainType.COMBUSTION)!!
        monitor.update(
            SessionState.Running,
            constantStep,
            DrivingActionState.CRUISING,
            130.0,
            hasValidSpeedSample = true,
            isGpsSignalStale = false
        )

        val guidance = monitor.update(
            SessionState.Running,
            accelDecelStep,
            DrivingActionState.WAITING_FOR_BRAKE_LINE,
            133.2,
            hasValidSpeedSample = true,
            isGpsSignalStale = false
        )

        assertEquals(SpeedAdjustmentDirection.DECREASE, guidance?.direction)
    }
}
