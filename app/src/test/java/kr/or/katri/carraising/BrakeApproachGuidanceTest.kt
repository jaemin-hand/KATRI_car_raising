package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrakeApproachGuidanceTest {
    @Test
    fun triggerDistanceUsesEightSecondsWithConfiguredLimits() {
        assertEquals(
            120.0,
            BrakeApproachGuidanceRules.triggerDistanceM(10.0),
            0.001
        )
        assertEquals(
            133.333,
            BrakeApproachGuidanceRules.triggerDistanceM(60.0),
            0.01
        )
        assertEquals(
            288.889,
            BrakeApproachGuidanceRules.triggerDistanceM(130.0),
            0.01
        )
        assertEquals(
            300.0,
            BrakeApproachGuidanceRules.triggerDistanceM(145.0),
            0.001
        )
    }

    @Test
    fun scenarioModeSelectsTheRequestedVoiceAndTone() {
        val constantStep =
            KatriDrivingScenario.stepAt(50.0, PowertrainType.COMBUSTION)!!
        val accelDecelStep =
            KatriDrivingScenario.stepAt(150.0, PowertrainType.COMBUSTION)!!

        val constantGuidance =
            BrakeApproachGuidanceRules.guidanceFor(constantStep)
        assertEquals("감속없이 주행하세요.", constantGuidance.message)
        assertFalse(constantGuidance.playsPreparationTone)

        val accelDecelGuidance =
            BrakeApproachGuidanceRules.guidanceFor(accelDecelStep)
        assertEquals(
            "비상 깜빡이를 켜고 감속을 준비하세요.",
            accelDecelGuidance.message
        )
        assertTrue(accelDecelGuidance.playsPreparationTone)
    }

    @Test
    fun monitorRequiresTwoSamplesAndAnnouncesOnlyOncePerLap() {
        val step = KatriDrivingScenario.stepAt(50.0, PowertrainType.COMBUSTION)!!
        val monitor = BrakeApproachMonitor()

        assertNull(
            monitor.update(
                sessionState = SessionState.Running,
                step = step,
                actionState = DrivingActionState.CRUISING,
                currentSpeedKmh = 60.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false,
                traveledSinceBrakeLineM = 4_500.0,
                remainingRouteDistanceM = 120.0,
                straightLineDistanceM = 125.0
            )
        )
        val guidance = monitor.update(
            sessionState = SessionState.Running,
            step = step,
            actionState = DrivingActionState.CRUISING,
            currentSpeedKmh = 60.0,
            hasValidSpeedSample = true,
            isGpsSignalStale = false,
            traveledSinceBrakeLineM = 4_510.0,
            remainingRouteDistanceM = 115.0,
            straightLineDistanceM = 120.0
        )
        assertEquals(BrakeApproachGuidanceType.KEEP_CONSTANT_SPEED, guidance?.type)

        assertNull(
            monitor.update(
                sessionState = SessionState.Running,
                step = step,
                actionState = DrivingActionState.CRUISING,
                currentSpeedKmh = 60.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false,
                traveledSinceBrakeLineM = 4_520.0,
                remainingRouteDistanceM = 100.0,
                straightLineDistanceM = 105.0
            )
        )

        monitor.resetForNextLap()
        assertNull(
            monitor.update(
                sessionState = SessionState.Running,
                step = step,
                actionState = DrivingActionState.CRUISING,
                currentSpeedKmh = 60.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false,
                traveledSinceBrakeLineM = 4_500.0,
                remainingRouteDistanceM = 120.0,
                straightLineDistanceM = 125.0
            )
        )
        assertEquals(
            BrakeApproachGuidanceType.KEEP_CONSTANT_SPEED,
            monitor.update(
                sessionState = SessionState.Running,
                step = step,
                actionState = DrivingActionState.CRUISING,
                currentSpeedKmh = 60.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false,
                traveledSinceBrakeLineM = 4_510.0,
                remainingRouteDistanceM = 115.0,
                straightLineDistanceM = 120.0
            )?.type
        )
    }

    @Test
    fun accelDecelGuidanceOnlyArmsWhileWaitingForBrakeLine() {
        val step = KatriDrivingScenario.stepAt(1_350.0, PowertrainType.COMBUSTION)!!
        val monitor = BrakeApproachMonitor(requiredConsecutiveSamples = 1)

        assertNull(
            monitor.update(
                sessionState = SessionState.Running,
                step = step,
                actionState = DrivingActionState.ACCELERATING,
                currentSpeedKmh = 130.0,
                hasValidSpeedSample = true,
                isGpsSignalStale = false,
                traveledSinceBrakeLineM = 4_700.0,
                remainingRouteDistanceM = 250.0,
                straightLineDistanceM = 260.0
            )
        )

        val guidance = monitor.update(
            sessionState = SessionState.Running,
            step = step,
            actionState = DrivingActionState.WAITING_FOR_BRAKE_LINE,
            currentSpeedKmh = 130.0,
            hasValidSpeedSample = true,
            isGpsSignalStale = false,
            traveledSinceBrakeLineM = 4_710.0,
            remainingRouteDistanceM = 245.0,
            straightLineDistanceM = 255.0
        )
        assertEquals(
            BrakeApproachGuidanceType.PREPARE_TO_DECELERATE,
            guidance?.type
        )
    }

    @Test
    fun routeDistanceFollowsTheRecordedDrivingDirection() {
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.01),
            GeoPoint(0.01, 0.01),
            GeoPoint(0.01, 0.0)
        )
        val brakeLine = GeoPoint(0.0, 0.0)

        val beforeLineDistance =
            BrakeApproachGuidanceRules.remainingRouteDistanceM(
                route = route,
                currentLocation = GeoPoint(0.001, 0.0),
                brakeLineLocation = brakeLine
            )
        assertTrue(beforeLineDistance != null && beforeLineDistance in 100.0..125.0)

        val afterLineDistance =
            BrakeApproachGuidanceRules.remainingRouteDistanceM(
                route = route,
                currentLocation = GeoPoint(0.0, 0.001),
                brakeLineLocation = brakeLine
            )
        assertTrue(afterLineDistance != null && afterLineDistance > 4_000.0)
    }
}
