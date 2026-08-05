package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingNotificationContentTest {
    @Test
    fun statusIncludesCurrentTargetScenarioInstructionAndDistance() {
        val step = KatriDrivingScenario.stepAt(1_350.0, PowertrainType.COMBUSTION)!!

        val status = DrivingNotificationContent.status(
            sessionState = SessionState.Running,
            speedKmh = 129.8,
            progressDistanceKm = 1_350.25,
            step = step,
            actionState = DrivingActionState.DECELERATING
        )

        assertEquals("현재 129.8km/h · 목표 130km/h · E-2", status.compactText)
        assertTrue(status.expandedLines.contains("현재 구간: E-2 · 가감속"))
        assertTrue(status.expandedLines.contains("주행 지시: 80km/h까지 감속하세요."))
        assertTrue(status.expandedLines.contains("시험 누적거리: 1350.25km"))
    }

    @Test
    fun completeStopAlertUsesExplicitZeroSpeedInstruction() {
        val step = KatriDrivingScenario.stepAt(150.0, PowertrainType.COMBUSTION)!!

        assertEquals(
            "0km/h까지 감속해 완전 정차하세요.",
            DrivingNotificationContent.brakeAlert(step)
        )
    }

    @Test
    fun pausedStatusOverridesDrivingInstruction() {
        val step = KatriDrivingScenario.stepAt(1_350.0, PowertrainType.COMBUSTION)!!

        val status = DrivingNotificationContent.status(
            sessionState = SessionState.Paused,
            speedKmh = 0.0,
            progressDistanceKm = 1_350.0,
            step = step,
            actionState = DrivingActionState.DECELERATING
        )

        assertTrue(status.expandedLines.contains("주행 지시: 주행 일시정지"))
    }

    @Test
    fun scenarioTransitionDescribesAccelerationMethod() {
        val step = KatriDrivingScenario.stepAt(2_150.0, PowertrainType.COMBUSTION)!!

        assertEquals(
            "145km/h 주행 · 시작선 통과 시 110km/h까지 감속 · WOT",
            DrivingNotificationContent.scenarioTransition(step)
        )
    }

    @Test
    fun constantScenarioTransitionVoiceExplainsTargetSpeed() {
        val step = KatriDrivingScenario.stepAt(350.0, PowertrainType.COMBUSTION)!!

        assertEquals(
            "B-1 정속 구간입니다. 규정속도 80킬로미터로 감속 없이 주행하세요.",
            DrivingNotificationContent.scenarioTransitionVoice(step)
        )
    }

    @Test
    fun completeStopScenarioTransitionVoiceExplainsSmoothAcceleration() {
        val step = KatriDrivingScenario.stepAt(150.0, PowertrainType.COMBUSTION)!!

        assertEquals(
            "A-2 가감속 구간입니다. 규정속도 60킬로미터. 제동 시작점 통과 후 완전 정차하고 완가속하세요.",
            DrivingNotificationContent.scenarioTransitionVoice(step)
        )
    }

    @Test
    fun wotScenarioTransitionVoicePronouncesAccelerationMethod() {
        val step = KatriDrivingScenario.stepAt(2_150.0, PowertrainType.COMBUSTION)!!

        assertEquals(
            "H-R1-2 가감속 구간입니다. 규정속도 145킬로미터. 제동 시작점 통과 후 시속 110킬로미터까지 일반감속하고 더블유 오 티로 가속하세요.",
            DrivingNotificationContent.scenarioTransitionVoice(step)
        )
    }

    @Test
    fun speedGuidanceOverridesNormalCruiseInstruction() {
        val step = KatriDrivingScenario.stepAt(1_250.0, PowertrainType.COMBUSTION)!!
        val guidance = TargetSpeedGuidanceRules.evaluate(
            targetSpeedKmh = 130,
            currentSpeedKmh = 133.2,
            toleranceKmh = 3.0
        )

        val status = DrivingNotificationContent.status(
            sessionState = SessionState.Running,
            speedKmh = 133.2,
            progressDistanceKm = 1_250.0,
            step = step,
            actionState = DrivingActionState.CRUISING,
            speedGuidance = guidance
        )

        assertTrue(
            status.expandedLines.contains(
                "주행 지시: 규정속도 이탈 · 속도를 3.2km/h 내리세요"
            )
        )
    }
}
