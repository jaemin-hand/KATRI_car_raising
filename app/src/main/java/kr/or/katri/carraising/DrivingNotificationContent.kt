package kr.or.katri.carraising

import java.util.Locale

internal data class DrivingNotificationStatus(
    val compactText: String,
    val expandedLines: List<String>,
    val summaryText: String?
)

internal object DrivingNotificationContent {
    fun status(
        sessionState: SessionState,
        speedKmh: Double,
        progressDistanceKm: Double,
        step: DrivingScenarioStep?,
        actionState: DrivingActionState
    ): DrivingNotificationStatus {
        val targetSpeed = step?.let { "${it.targetSpeedKmh}km/h" } ?: "-"
        val stepLabel = step?.let { "${it.id} · ${it.driveMode.label}" } ?: "시나리오 없음"
        val instruction = if (sessionState == SessionState.Paused) {
            "주행 일시정지"
        } else {
            instruction(step, actionState)
        }
        val compactStep = step?.let { " · ${it.id}" }.orEmpty()

        return DrivingNotificationStatus(
            compactText = String.format(
                Locale.US,
                "현재 %.1fkm/h · 목표 %s%s",
                speedKmh,
                targetSpeed,
                compactStep
            ),
            expandedLines = listOf(
                String.format(Locale.US, "현재 속도: %.1fkm/h", speedKmh),
                "목표 속도: $targetSpeed",
                "현재 구간: $stepLabel",
                "주행 지시: $instruction",
                String.format(Locale.US, "시험 누적거리: %.2fkm", progressDistanceKm)
            ),
            summaryText = step?.let { "${it.section} · ${it.driveMode.label}" }
        )
    }

    fun instruction(
        step: DrivingScenarioStep?,
        actionState: DrivingActionState
    ): String {
        if (step == null) return "시험이 완료되었거나 적용할 시나리오가 없습니다."
        if (step.driveMode == ScenarioDriveMode.CONSTANT) {
            return "${step.targetSpeedKmh}km/h 정속 유지 · 감속 없음"
        }

        val decelTarget = step.decelTargetKmh ?: 0
        return when (actionState) {
            DrivingActionState.WAITING_FOR_BRAKE_LINE ->
                if (decelTarget == 0) {
                    "브레이크 시작선 통과 대기 · 통과 후 완전 정차"
                } else {
                    "브레이크 시작선 통과 대기 · 통과 후 ${decelTarget}km/h까지 감속"
                }
            DrivingActionState.DECELERATING -> brakeAlert(step)
            DrivingActionState.ACCELERATING ->
                "${step.accelerationMethod.label} · ${step.targetSpeedKmh}km/h까지 재가속"
            DrivingActionState.CRUISING -> "브레이크 시작선 통과 대기"
        }
    }

    fun brakeAlert(step: DrivingScenarioStep): String {
        val targetKmh = step.decelTargetKmh ?: return "규정 속도로 감속하세요."
        return if (targetKmh == 0) {
            "0km/h까지 감속해 완전 정차하세요."
        } else {
            "${targetKmh}km/h까지 감속하세요."
        }
    }

    fun scenarioTransition(step: DrivingScenarioStep): String {
        if (step.driveMode == ScenarioDriveMode.CONSTANT) {
            return "${step.targetSpeedKmh}km/h 정속 유지 · 감속 없음"
        }

        val targetKmh = step.decelTargetKmh ?: 0
        val deceleration = if (targetKmh == 0) {
            "시작선 통과 시 완전 정차"
        } else {
            "시작선 통과 시 ${targetKmh}km/h까지 감속"
        }
        return "${step.targetSpeedKmh}km/h 주행 · $deceleration · ${step.accelerationMethod.label}"
    }
}
