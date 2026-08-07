package kr.or.katri.carraising

import kotlin.math.cos
import kotlin.math.sqrt

internal enum class BrakeApproachGuidanceType {
    PREPARE_TO_DECELERATE,
    KEEP_CONSTANT_SPEED
}

internal data class BrakeApproachGuidance(
    val stepId: String,
    val type: BrakeApproachGuidanceType,
    val message: String,
    val playsPreparationTone: Boolean
)

internal object BrakeApproachGuidanceRules {
    const val LEAD_TIME_SECONDS = 8.0
    const val MIN_TRIGGER_DISTANCE_M = 120.0
    const val MAX_TRIGGER_DISTANCE_M = 300.0

    fun triggerDistanceM(speedKmh: Double): Double {
        if (!speedKmh.isFinite()) return MIN_TRIGGER_DISTANCE_M
        return (speedKmh.coerceAtLeast(0.0) / 3.6 * LEAD_TIME_SECONDS)
            .coerceIn(MIN_TRIGGER_DISTANCE_M, MAX_TRIGGER_DISTANCE_M)
    }

    fun guidanceFor(step: DrivingScenarioStep): BrakeApproachGuidance {
        return when (step.driveMode) {
            ScenarioDriveMode.ACCEL_DECEL -> BrakeApproachGuidance(
                stepId = step.id,
                type = BrakeApproachGuidanceType.PREPARE_TO_DECELERATE,
                message = "비상 깜빡이를 켜고 감속을 준비하세요.",
                playsPreparationTone = true
            )
            ScenarioDriveMode.CONSTANT -> BrakeApproachGuidance(
                stepId = step.id,
                type = BrakeApproachGuidanceType.KEEP_CONSTANT_SPEED,
                message = "감속없이 주행하세요.",
                playsPreparationTone = false
            )
        }
    }

    fun remainingRouteDistanceM(
        route: List<GeoPoint>,
        currentLocation: GeoPoint,
        brakeLineLocation: GeoPoint
    ): Double? {
        if (route.size < 4) return null

        val baseLatitude = route.first().latitude
        val longitudeScale = 111_320.0 * cos(Math.toRadians(baseLatitude))
        if (longitudeScale <= 0.0) return null

        val origin = route.first()
        fun project(point: GeoPoint): PlanarPoint {
            return PlanarPoint(
                x = (point.longitude - origin.longitude) * longitudeScale,
                y = (point.latitude - origin.latitude) * 110_540.0
            )
        }

        val projectedRoute = route.map(::project)
        val segmentLengths = projectedRoute.indices.map { index ->
            val start = projectedRoute[index]
            val end = projectedRoute[(index + 1) % projectedRoute.size]
            distance(start, end)
        }
        val routeLengthM = segmentLengths.sum()
        if (routeLengthM < MIN_USABLE_ROUTE_LENGTH_M) return null

        fun progressAt(point: GeoPoint): Double? {
            val projectedPoint = project(point)
            var cumulativeDistanceM = 0.0
            var nearestDistanceSquaredM = Double.POSITIVE_INFINITY
            var nearestProgressM: Double? = null

            projectedRoute.indices.forEach { index ->
                val start = projectedRoute[index]
                val end = projectedRoute[(index + 1) % projectedRoute.size]
                val segmentLengthM = segmentLengths[index]
                if (segmentLengthM >= MIN_USABLE_SEGMENT_LENGTH_M) {
                    val dx = end.x - start.x
                    val dy = end.y - start.y
                    val progress = (
                        (projectedPoint.x - start.x) * dx +
                            (projectedPoint.y - start.y) * dy
                        ) / (segmentLengthM * segmentLengthM)
                    val clampedProgress = progress.coerceIn(0.0, 1.0)
                    val nearestX = start.x + dx * clampedProgress
                    val nearestY = start.y + dy * clampedProgress
                    val offsetX = projectedPoint.x - nearestX
                    val offsetY = projectedPoint.y - nearestY
                    val distanceSquaredM = offsetX * offsetX + offsetY * offsetY
                    if (distanceSquaredM < nearestDistanceSquaredM) {
                        nearestDistanceSquaredM = distanceSquaredM
                        nearestProgressM =
                            cumulativeDistanceM + segmentLengthM * clampedProgress
                    }
                }
                cumulativeDistanceM += segmentLengthM
            }
            return nearestProgressM
        }

        val currentProgressM = progressAt(currentLocation) ?: return null
        val brakeLineProgressM = progressAt(brakeLineLocation) ?: return null
        val remainingDistanceM = brakeLineProgressM - currentProgressM
        return if (remainingDistanceM >= 0.0) {
            remainingDistanceM
        } else {
            remainingDistanceM + routeLengthM
        }
    }

    private fun distance(first: PlanarPoint, second: PlanarPoint): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        return sqrt(dx * dx + dy * dy)
    }

    private data class PlanarPoint(
        val x: Double,
        val y: Double
    )

    private const val MIN_USABLE_ROUTE_LENGTH_M = 1_000.0
    private const val MIN_USABLE_SEGMENT_LENGTH_M = 0.5
}

internal class BrakeApproachMonitor(
    private val minimumLapTravelDistanceM: Double = BrakeLineGateRules.MINIMUM_REARM_DISTANCE_M,
    private val requiredConsecutiveSamples: Int = 2
) {
    var announcedStepId: String? = null
        private set

    private var qualifyingSampleCount = 0

    init {
        require(minimumLapTravelDistanceM >= 0.0)
        require(requiredConsecutiveSamples > 0)
    }

    fun restoreAnnouncedStepId(stepId: String?) {
        announcedStepId = stepId
        qualifyingSampleCount = 0
    }

    fun resetForNextLap() {
        announcedStepId = null
        qualifyingSampleCount = 0
    }

    fun cancelCandidate() {
        qualifyingSampleCount = 0
    }

    fun reset() {
        resetForNextLap()
    }

    fun update(
        sessionState: SessionState,
        step: DrivingScenarioStep?,
        actionState: DrivingActionState,
        currentSpeedKmh: Double,
        hasValidSpeedSample: Boolean,
        isGpsSignalStale: Boolean,
        traveledSinceBrakeLineM: Double,
        remainingRouteDistanceM: Double?,
        straightLineDistanceM: Double?,
        isInitialBrakeLinePassPending: Boolean = false
    ): BrakeApproachGuidance? {
        if (
            sessionState != SessionState.Running ||
            step == null ||
            !hasValidSpeedSample ||
            isGpsSignalStale ||
            !currentSpeedKmh.isFinite() ||
            currentSpeedKmh < MINIMUM_GUIDANCE_SPEED_KMH ||
            !BrakeLineGateRules.isEligible(
                traveledSinceLastGateM = traveledSinceBrakeLineM,
                isInitialBrakeLinePassPending = isInitialBrakeLinePassPending,
                minimumRearmDistanceM = minimumLapTravelDistanceM
            ) ||
            announcedStepId == step.id ||
            !isEligibleAction(step, actionState)
        ) {
            qualifyingSampleCount = 0
            return null
        }

        val remainingDistanceM = listOfNotNull(
            remainingRouteDistanceM?.takeIf { it.isFinite() && it >= 0.0 },
            straightLineDistanceM?.takeIf { it.isFinite() && it >= 0.0 }
        ).minOrNull()
        val triggerDistanceM = BrakeApproachGuidanceRules.triggerDistanceM(currentSpeedKmh)
        if (remainingDistanceM == null || remainingDistanceM > triggerDistanceM) {
            qualifyingSampleCount = 0
            return null
        }

        qualifyingSampleCount += 1
        if (qualifyingSampleCount < requiredConsecutiveSamples) return null

        qualifyingSampleCount = 0
        announcedStepId = step.id
        return BrakeApproachGuidanceRules.guidanceFor(step)
    }

    private fun isEligibleAction(
        step: DrivingScenarioStep,
        actionState: DrivingActionState
    ): Boolean {
        return when (step.driveMode) {
            ScenarioDriveMode.CONSTANT -> actionState == DrivingActionState.CRUISING
            ScenarioDriveMode.ACCEL_DECEL ->
                actionState == DrivingActionState.WAITING_FOR_BRAKE_LINE ||
                    actionState == DrivingActionState.ACCELERATING
        }
    }

    private companion object {
        const val MINIMUM_GUIDANCE_SPEED_KMH = 10.0
    }
}
