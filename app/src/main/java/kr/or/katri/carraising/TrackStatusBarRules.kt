package kr.or.katri.carraising

internal enum class TrackStatusBarState {
    INACTIVE,
    NORMAL,
    SPEED_WARNING,
    BRAKING,
    GPS_ISSUE,
    PAUSED
}

internal object TrackStatusBarRules {
    fun resolve(
        sessionState: SessionState,
        actionState: DrivingActionState,
        hasSpeedGuidance: Boolean,
        isGpsSignalStale: Boolean
    ): TrackStatusBarState {
        return when {
            sessionState == SessionState.Paused -> TrackStatusBarState.PAUSED
            sessionState != SessionState.Running -> TrackStatusBarState.INACTIVE
            actionState == DrivingActionState.DECELERATING ->
                TrackStatusBarState.BRAKING
            isGpsSignalStale -> TrackStatusBarState.GPS_ISSUE
            hasSpeedGuidance -> TrackStatusBarState.SPEED_WARNING
            else -> TrackStatusBarState.NORMAL
        }
    }
}
