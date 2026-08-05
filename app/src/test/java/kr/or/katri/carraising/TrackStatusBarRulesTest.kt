package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackStatusBarRulesTest {
    @Test
    fun runningWithoutWarningsIsNormal() {
        assertEquals(
            TrackStatusBarState.NORMAL,
            resolve()
        )
    }

    @Test
    fun brakingHasHighestRunningPriority() {
        assertEquals(
            TrackStatusBarState.BRAKING,
            resolve(
                actionState = DrivingActionState.DECELERATING,
                hasSpeedGuidance = true,
                isGpsSignalStale = true
            )
        )
    }

    @Test
    fun gpsIssueOverridesSpeedWarning() {
        assertEquals(
            TrackStatusBarState.GPS_ISSUE,
            resolve(hasSpeedGuidance = true, isGpsSignalStale = true)
        )
    }

    @Test
    fun speedGuidanceUsesWarningState() {
        assertEquals(
            TrackStatusBarState.SPEED_WARNING,
            resolve(hasSpeedGuidance = true)
        )
    }

    @Test
    fun pausedAndInactiveSessionsDoNotShowRunningState() {
        assertEquals(
            TrackStatusBarState.PAUSED,
            resolve(sessionState = SessionState.Paused)
        )
        assertEquals(
            TrackStatusBarState.INACTIVE,
            resolve(sessionState = SessionState.Ready)
        )
    }

    private fun resolve(
        sessionState: SessionState = SessionState.Running,
        actionState: DrivingActionState = DrivingActionState.CRUISING,
        hasSpeedGuidance: Boolean = false,
        isGpsSignalStale: Boolean = false
    ): TrackStatusBarState {
        return TrackStatusBarRules.resolve(
            sessionState = sessionState,
            actionState = actionState,
            hasSpeedGuidance = hasSpeedGuidance,
            isGpsSignalStale = isGpsSignalStale
        )
    }
}
