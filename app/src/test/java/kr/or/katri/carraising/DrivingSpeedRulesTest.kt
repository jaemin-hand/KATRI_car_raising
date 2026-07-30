package kr.or.katri.carraising

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingSpeedRulesTest {
    @Test
    fun rawGpsSpeedCompletesDecelerationWithoutWaitingForSmoothedDisplay() {
        assertTrue(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 80,
                displayedSpeedKmh = 112.5,
                latestRawSpeedKmh = 80.0,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
    }

    @Test
    fun nonzeroTargetDoesNotUseLaggingDisplayedSpeedForDecision() {
        assertFalse(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 80,
                displayedSpeedKmh = 80.0,
                latestRawSpeedKmh = 100.0,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
    }

    @Test
    fun nonzeroTargetWaitsUntilRawGpsSampleExists() {
        assertFalse(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 80,
                displayedSpeedKmh = 80.0,
                latestRawSpeedKmh = null,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
    }

    @Test
    fun completeStopStillUsesDisplayedZeroRule() {
        assertTrue(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 0,
                displayedSpeedKmh = 0.0,
                latestRawSpeedKmh = 6.0,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
        assertFalse(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 0,
                displayedSpeedKmh = 0.1,
                latestRawSpeedKmh = 0.0,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
    }

    @Test
    fun nonzeroTargetUsesSixKmhTolerance() {
        assertTrue(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 80,
                displayedSpeedKmh = 95.0,
                latestRawSpeedKmh = 86.0,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
        assertFalse(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 80,
                displayedSpeedKmh = 95.0,
                latestRawSpeedKmh = 86.1,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
    }

    @Test
    fun completeStopAcceptsLowResidualGpsSpeedOnlyWithStationaryEvidence() {
        assertTrue(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 0,
                displayedSpeedKmh = 4.5,
                latestRawSpeedKmh = 6.0,
                toleranceKmh = 6.0,
                hasStationaryEvidence = true
            )
        )
        assertFalse(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 0,
                displayedSpeedKmh = 4.5,
                latestRawSpeedKmh = 6.0,
                toleranceKmh = 6.0,
                hasStationaryEvidence = false
            )
        )
    }
}
