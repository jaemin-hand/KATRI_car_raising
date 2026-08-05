package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceCorrectionRulesTest {
    @Test
    fun eachAdjustmentStepChangesCorrectionByPointOnePercent() {
        assertEquals(1, DistanceCorrectionRules.adjust(0, 1))
        assertEquals(-1, DistanceCorrectionRules.adjust(0, -1))
        assertEquals(0.1, DistanceCorrectionRules.percent(1), 0.0001)
        assertEquals(-0.1, DistanceCorrectionRules.percent(-1), 0.0001)
        assertEquals(
            100_100.0,
            DistanceCorrectionRules.correctedDistanceM(100_000.0, 1),
            0.001
        )
        assertEquals(
            99_900.0,
            DistanceCorrectionRules.correctedDistanceM(100_000.0, -1),
            0.001
        )
    }

    @Test
    fun negativeOnePointTwoPercentUsesPointNineEightEightFactor() {
        assertEquals(0.988, DistanceCorrectionRules.factor(-12), 0.000001)
        assertEquals(
            98_800.0,
            DistanceCorrectionRules.correctedDistanceM(100_000.0, -12),
            0.001
        )
    }

    @Test
    fun correctionIsLimitedToFivePercentInEitherDirection() {
        assertEquals(-50, DistanceCorrectionRules.adjust(-50, -1))
        assertEquals(50, DistanceCorrectionRules.adjust(50, 1))
        assertEquals(0.95, DistanceCorrectionRules.factor(-500), 0.000001)
        assertEquals(1.05, DistanceCorrectionRules.factor(500), 0.000001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRawDistanceIsRejected() {
        DistanceCorrectionRules.correctedDistanceM(-1.0, 0)
    }
}
