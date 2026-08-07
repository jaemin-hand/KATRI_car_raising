package kr.or.katri.carraising

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrakeLineGateRulesTest {
    @Test
    fun initialEntryAllowsTheFirstBrakeLineBeforeFourKilometers() {
        assertTrue(
            BrakeLineGateRules.isEligible(
                traveledSinceLastGateM = 2_500.0,
                isInitialBrakeLinePassPending = true
            )
        )
    }

    @Test
    fun subsequentBrakeLineRequiresTheRearmDistance() {
        assertFalse(
            BrakeLineGateRules.isEligible(
                traveledSinceLastGateM = 2_500.0,
                isInitialBrakeLinePassPending = false
            )
        )
        assertTrue(
            BrakeLineGateRules.isEligible(
                traveledSinceLastGateM = 4_000.0,
                isInitialBrakeLinePassPending = false
            )
        )
    }

    @Test
    fun invalidDistanceCannotTriggerTheBrakeLine() {
        assertFalse(
            BrakeLineGateRules.isEligible(
                traveledSinceLastGateM = Double.NaN,
                isInitialBrakeLinePassPending = true
            )
        )
    }
}
