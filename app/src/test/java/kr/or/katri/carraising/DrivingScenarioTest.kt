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
            assertEquals(stepId, KatriDrivingScenario.commonStepAt(distanceKm)?.id)
        }
    }

    @Test
    fun configuredValuesMatchTheProvidedScenario() {
        val stopStep = KatriDrivingScenario.commonStepAt(150.0)!!
        assertEquals(60, stopStep.targetSpeedKmh)
        assertEquals(0, stopStep.decelTargetKmh)
        assertEquals(AccelerationMethod.SMOOTH, stopStep.accelerationMethod)

        val wotStep = KatriDrivingScenario.commonStepAt(2_150.0)!!
        assertEquals(145, wotStep.targetSpeedKmh)
        assertEquals(110, wotStep.decelTargetKmh)
        assertEquals(AccelerationMethod.WOT, wotStep.accelerationMethod)
    }

    @Test
    fun distanceOutsideConfiguredRangeHasNoStep() {
        assertNull(KatriDrivingScenario.commonStepAt(-0.1))
        assertNull(KatriDrivingScenario.commonStepAt(3_000.0))
        assertNull(KatriDrivingScenario.commonStepAt(Double.NaN))
        assertNull(KatriDrivingScenario.stepAt(9_000.0, PowertrainType.COMBUSTION))
        assertNull(KatriDrivingScenario.stepAt(9_000.0, PowertrainType.HYBRID))
    }

    @Test
    fun configuredStepsAreContinuousWithoutGaps() {
        val steps = KatriDrivingScenario.commonSteps
        assertEquals(0.0, steps.first().startKm, 0.0)
        assertEquals(KatriDrivingScenario.commonConfiguredEndKm, steps.last().endKm, 0.0)
        steps.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endKm, next.startKm, 0.0)
            assertTrue(current.endKm > current.startKm)
        }
    }

    @Test
    fun combustionRepeatsHPatternAfterThreeThousandKm() {
        val constant = KatriDrivingScenario.stepAt(3_000.0, PowertrainType.COMBUSTION)!!
        assertEquals(145, constant.targetSpeedKmh)
        assertEquals(ScenarioDriveMode.CONSTANT, constant.driveMode)

        val accelDecel = KatriDrivingScenario.stepAt(3_150.0, PowertrainType.COMBUSTION)!!
        assertEquals(145, accelDecel.targetSpeedKmh)
        assertEquals(110, accelDecel.decelTargetKmh)
        assertEquals(AccelerationMethod.WOT, accelDecel.accelerationMethod)
    }

    @Test
    fun hybridRestartsFromAAfterThreeThousandKm() {
        val restartedA = KatriDrivingScenario.stepAt(3_000.0, PowertrainType.HYBRID)!!
        assertEquals("A-1", restartedA.id)
        assertEquals(60, restartedA.targetSpeedKmh)
        assertEquals(3_100.0, restartedA.endKm, 0.0)

        val secondCycleA = KatriDrivingScenario.stepAt(5_000.0, PowertrainType.HYBRID)!!
        assertEquals("A-1", secondCycleA.id)
        assertEquals(5_100.0, secondCycleA.endKm, 0.0)
    }
}
