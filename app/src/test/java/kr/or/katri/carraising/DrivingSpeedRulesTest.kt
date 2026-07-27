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
                toleranceKmh = 3.0
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
                toleranceKmh = 3.0
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
                toleranceKmh = 3.0
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
                toleranceKmh = 3.0
            )
        )
        assertFalse(
            DrivingSpeedRules.hasReachedDecelerationTarget(
                targetKmh = 0,
                displayedSpeedKmh = 0.1,
                latestRawSpeedKmh = 0.0,
                toleranceKmh = 3.0
            )
        )
    }
}
