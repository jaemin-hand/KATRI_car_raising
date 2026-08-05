package kr.or.katri.carraising

enum class SessionState {
    Idle,
    Ready,
    Running,
    Paused,
    Finished
}

enum class DrivingActionState {
    CRUISING,
    WAITING_FOR_BRAKE_LINE,
    DECELERATING,
    ACCELERATING
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

internal data class BrakeLineFrame(
    val tangentEast: Double,
    val tangentNorth: Double
)

internal data class SpeedSample(
    val speedKmh: Double,
    val isValidForControl: Boolean,
    val hasStationaryEvidence: Boolean
)

data class TrackingSnapshot(
    val sessionState: SessionState,
    val selectedPowertrain: PowertrainType?,
    val selectedStartOdo: String,
    val selectedTrackOdo: String,
    val currentLocation: GeoPoint?,
    val currentLocationProvider: String?,
    val currentLocationAccuracyM: Float?,
    val currentLocationBearingDegrees: Float?,
    val currentSpeedKmh: Double,
    val latestRawSpeedKmh: Double?,
    val isGpsSignalStale: Boolean,
    val totalDistanceM: Double,
    val testProgressDistanceKm: Double,
    val currentOdoKm: Double?,
    val odoConfirmedAtSessionDistanceM: Double,
    val insideTrackArea: Boolean,
    val brakeLineLocation: GeoPoint?,
    val activeScenarioStepId: String?,
    val drivingActionState: DrivingActionState,
    val speedGuidance: SpeedGuidance?,
    val currentRoutePoints: List<GeoPoint>,
    val referenceRoutePoints: List<GeoPoint>,
    val hasReferenceRoute: Boolean,
    val noticeSequence: Long,
    val noticeMessage: String?
) {
    val scenarioStep: DrivingScenarioStep?
        get() = selectedPowertrain?.let {
            KatriDrivingScenario.stepAt(testProgressDistanceKm, it)
        }

    val isSessionActive: Boolean
        get() = sessionState == SessionState.Running || sessionState == SessionState.Paused
}

data class TrackingCommandResult(
    val success: Boolean,
    val message: String? = null
)

fun interface TrackingListener {
    fun onTrackingSnapshot(snapshot: TrackingSnapshot)
}
