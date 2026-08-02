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
        assertNull(KatriDrivingScenario.stepAt(5_500.0, PowertrainType.ELECTRIC))
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
    fun hybridRepeatsFullThreeThousandKilometerScenarioBlocks() {
        val firstRepeatedA = KatriDrivingScenario.stepAt(3_000.0, PowertrainType.HYBRID)!!
        assertEquals("A-1", firstRepeatedA.id)
        assertEquals(60, firstRepeatedA.targetSpeedKmh)
        assertEquals(3_100.0, firstRepeatedA.endKm, 0.0)

        val firstRepeatedWot = KatriDrivingScenario.stepAt(5_150.0, PowertrainType.HYBRID)!!
        assertEquals("H-R1-2", firstRepeatedWot.id)
        assertEquals(145, firstRepeatedWot.targetSpeedKmh)
        assertEquals(110, firstRepeatedWot.decelTargetKmh)
        assertEquals(AccelerationMethod.WOT, firstRepeatedWot.accelerationMethod)

        val secondRepeatedA = KatriDrivingScenario.stepAt(6_000.0, PowertrainType.HYBRID)!!
        assertEquals("A-1", secondRepeatedA.id)
        assertEquals(60, secondRepeatedA.targetSpeedKmh)
        assertEquals(6_100.0, secondRepeatedA.endKm, 0.0)

        val secondRepeatedWot = KatriDrivingScenario.stepAt(8_950.0, PowertrainType.HYBRID)!!
        assertEquals("H-R5-2", secondRepeatedWot.id)
        assertEquals(AccelerationMethod.WOT, secondRepeatedWot.accelerationMethod)
    }

    @Test
    fun electricMatchesCombustionScenarioUntilItsTargetDistance() {
        listOf(0.0, 150.0, 3_000.0, 3_150.0, 5_350.0, 5_499.9).forEach { distanceKm ->
            val combustion = KatriDrivingScenario.stepAt(distanceKm, PowertrainType.COMBUSTION)
            val electric = KatriDrivingScenario.stepAt(distanceKm, PowertrainType.ELECTRIC)
            assertEquals(combustion, electric)
        }
    }

    @Test
    fun electricScenarioEndsAtFiveThousandFiveHundredKm() {
        assertEquals(
            5_500.0,
            KatriDrivingScenario.targetDistanceKm(PowertrainType.ELECTRIC),
            0.0
        )
        assertTrue(KatriDrivingScenario.stepAt(5_499.9, PowertrainType.ELECTRIC) != null)
        assertNull(KatriDrivingScenario.stepAt(5_500.0, PowertrainType.ELECTRIC))
    }
}
