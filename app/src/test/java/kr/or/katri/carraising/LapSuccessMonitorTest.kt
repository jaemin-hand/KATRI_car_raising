package kr.or.katri.carraising

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapSuccessMonitorTest {
    private val constantStep = KatriDrivingScenario.stepAt(
        distanceKm = 50.0,
        powertrainType = PowertrainType.COMBUSTION
    )!!
    private val accelDecelStep = KatriDrivingScenario.stepAt(
        distanceKm = 150.0,
        powertrainType = PowertrainType.COMBUSTION
    )!!

    @Test
    fun constantSpeedRequiresTwoConsecutiveSamplesWithinTolerance() {
        val monitor = LapSuccessMonitor(toleranceKmh = 3.0)

        monitor.update(constantStep, DrivingActionState.CRUISING, 57.0, true, false)
        assertFalse(monitor.isTargetSpeedConfirmed)

        monitor.update(constantStep, DrivingActionState.CRUISING, 63.0, true, false)
        assertTrue(monitor.isTargetSpeedConfirmed)
    }

    @Test
    fun outOfRangeSampleResetsConfirmation() {
        val monitor = LapSuccessMonitor(toleranceKmh = 3.0)

        monitor.update(constantStep, DrivingActionState.CRUISING, 60.0, true, false)
        monitor.update(constantStep, DrivingActionState.CRUISING, 60.0, true, false)
        assertTrue(monitor.isTargetSpeedConfirmed)

        monitor.update(constantStep, DrivingActionState.CRUISING, 63.1, true, false)
        assertFalse(monitor.isTargetSpeedConfirmed)
    }

    @Test
    fun accelDecelOnlyConfirmsAfterDecelerationStateEnds() {
        val monitor = LapSuccessMonitor(toleranceKmh = 3.0)

        monitor.update(accelDecelStep, DrivingActionState.DECELERATING, 60.0, true, false)
        monitor.update(accelDecelStep, DrivingActionState.DECELERATING, 60.0, true, false)
        assertFalse(monitor.isTargetSpeedConfirmed)

        monitor.update(accelDecelStep, DrivingActionState.ACCELERATING, 58.0, true, false)
        monitor.update(accelDecelStep, DrivingActionState.ACCELERATING, 60.0, true, false)
        assertTrue(monitor.isTargetSpeedConfirmed)
    }

    @Test
    fun invalidOrStaleGpsSampleCannotConfirmTargetSpeed() {
        val monitor = LapSuccessMonitor(toleranceKmh = 3.0)

        monitor.update(constantStep, DrivingActionState.CRUISING, 60.0, false, false)
        monitor.update(constantStep, DrivingActionState.CRUISING, 60.0, true, true)

        assertFalse(monitor.isTargetSpeedConfirmed)
    }

    @Test
    fun scenarioChangeRequiresFreshConfirmation() {
        val monitor = LapSuccessMonitor(toleranceKmh = 3.0)

        monitor.update(constantStep, DrivingActionState.CRUISING, 60.0, true, false)
        monitor.update(constantStep, DrivingActionState.CRUISING, 60.0, true, false)
        assertTrue(monitor.isTargetSpeedConfirmed)

        monitor.update(accelDecelStep, DrivingActionState.ACCELERATING, 60.0, true, false)
        assertFalse(monitor.isTargetSpeedConfirmed)
    }
}
