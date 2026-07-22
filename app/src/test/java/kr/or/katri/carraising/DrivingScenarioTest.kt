package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingScenarioTest {
    @Test
    fun everyConfiguredBoundarySelectsTheNextStep() {
        val expected = listOf(
            0.0 to "A-1",
            100.0 to "A-2",
            300.0 to "B-1",
            500.0 to "B-2",
            700.0 to "C-1",
            800.0 to "C-2",
            1_000.0 to "D-1",
            1_100.0 to "D-2",
            1_200.0 to "E-1",
            1_300.0 to "E-2",
            1_400.0 to "F-1",
            1_500.0 to "F-2",
            1_600.0 to "G-1",
            1_700.0 to "G-2",
            1_800.0 to "H-1",
            1_900.0 to "H-2",
            2_000.0 to "H-R1-1",
            2_100.0 to "H-R1-2",
            2_200.0 to "H-R2-1",
            2_300.0 to "H-R2-2",
            2_400.0 to "H-R3-1",
            2_500.0 to "H-R3-2",
            2_600.0 to "H-R4-1",
            2_700.0 to "H-R4-2",
            2_800.0 to "H-R5-1",
            2_900.0 to "H-R5-2"
        )

        expected.forEach { (distanceKm, stepId) ->
            assertEquals(stepId, KatriDrivingScenario.stepAt(distanceKm)?.id)
        }
    }

    @Test
    fun configuredValuesMatchTheProvidedScenario() {
        val stopStep = KatriDrivingScenario.stepAt(150.0)!!
        assertEquals(60, stopStep.targetSpeedKmh)
        assertEquals(0, stopStep.decelTargetKmh)
        assertEquals(AccelerationMethod.SMOOTH, stopStep.accelerationMethod)

        val wotStep = KatriDrivingScenario.stepAt(2_150.0)!!
        assertEquals(145, wotStep.targetSpeedKmh)
        assertEquals(110, wotStep.decelTargetKmh)
        assertEquals(AccelerationMethod.WOT, wotStep.accelerationMethod)
    }

    @Test
    fun distanceOutsideConfiguredRangeHasNoStep() {
        assertNull(KatriDrivingScenario.stepAt(-0.1))
        assertNull(KatriDrivingScenario.stepAt(3_000.0))
        assertNull(KatriDrivingScenario.stepAt(Double.NaN))
    }

    @Test
    fun configuredStepsAreContinuousWithoutGaps() {
        val steps = KatriDrivingScenario.steps
        assertEquals(0.0, steps.first().startKm, 0.0)
        assertEquals(KatriDrivingScenario.configuredEndKm, steps.last().endKm, 0.0)
        steps.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endKm, next.startKm, 0.0)
            assertTrue(current.endKm > current.startKm)
        }
    }
}
