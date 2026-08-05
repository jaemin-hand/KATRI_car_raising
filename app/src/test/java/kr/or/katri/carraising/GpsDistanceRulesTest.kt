package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GpsDistanceRulesTest {
    @Test
    fun distanceAccuracyRequiresAReportedValueWithinFifteenMeters() {
        assertTrue(GpsDistanceRules.hasUsableAccuracy(6f))
        assertTrue(GpsDistanceRules.hasUsableAccuracy(15f))
        assertFalse(GpsDistanceRules.hasUsableAccuracy(null))
        assertFalse(GpsDistanceRules.hasUsableAccuracy(15.1f))
    }

    @Test
    fun shortSegmentsHoldTheAnchorToReduceGpsZigzag() {
        assertEquals(
            DistanceAnchorAction.HOLD,
            evaluate(segmentDistanceM = 18.0, elapsedSeconds = 0.5, speedKmh = 130.0)
        )
    }

    @Test
    fun accumulatedNormalDrivingSegmentIsAccepted() {
        assertEquals(
            DistanceAnchorAction.ACCEPT,
            evaluate(segmentDistanceM = 40.0, elapsedSeconds = 1.0, speedKmh = 145.0)
        )
    }

    @Test
    fun lowAccuracyCurrentPointDoesNotReplaceTheGoodAnchor() {
        assertEquals(
            DistanceAnchorAction.HOLD,
            evaluate(
                segmentDistanceM = 45.0,
                elapsedSeconds = 1.0,
                speedKmh = 130.0,
                currentAccuracyM = 20f
            )
        )
    }

    @Test
    fun implausibleGpsJumpResetsWithoutAddingDistance() {
        assertEquals(
            DistanceAnchorAction.RESET,
            evaluate(segmentDistanceM = 180.0, elapsedSeconds = 1.0, speedKmh = 60.0)
        )
    }

    @Test
    fun stationaryDriftIsDiscardedOnceItReachesTheSegmentThreshold() {
        assertEquals(
            DistanceAnchorAction.RESET,
            evaluate(segmentDistanceM = 32.0, elapsedSeconds = 10.0, speedKmh = 0.0)
        )
    }

    @Test
    fun aggregatedSegmentsReduceAlternatingGpsZigzagOvercount() {
        val points = (0..12).map { index ->
            index * 10.0 to if (index % 2 == 0) 2.0 else -2.0
        }
        val rawDistanceM = points.zipWithNext().sumOf { (from, to) ->
            hypot(to.first - from.first, to.second - from.second)
        }

        var anchorIndex = 0
        var filteredDistanceM = 0.0
        points.indices.drop(1).forEach { index ->
            val anchor = points[anchorIndex]
            val current = points[index]
            val segmentDistanceM =
                hypot(current.first - anchor.first, current.second - anchor.second)
            val action = evaluate(
                segmentDistanceM = segmentDistanceM,
                elapsedSeconds = (index - anchorIndex).toDouble(),
                speedKmh = 36.0
            )
            if (action != DistanceAnchorAction.HOLD) {
                if (action == DistanceAnchorAction.ACCEPT) {
                    filteredDistanceM += segmentDistanceM
                }
                anchorIndex = index
            }
        }

        assertTrue(filteredDistanceM < rawDistanceM)
        assertTrue(filteredDistanceM <= 120.0 * 1.02)
    }

    private fun evaluate(
        segmentDistanceM: Double,
        elapsedSeconds: Double,
        speedKmh: Double,
        previousAccuracyM: Float = 6f,
        currentAccuracyM: Float = 6f
    ): DistanceAnchorAction {
        return GpsDistanceRules.evaluateSegment(
            segmentDistanceM = segmentDistanceM,
            elapsedSeconds = elapsedSeconds,
            previousAccuracyM = previousAccuracyM,
            currentAccuracyM = currentAccuracyM,
            movementSpeedKmh = speedKmh,
            currentGpsSpeedMps = speedKmh / 3.6,
            minimumMovingSpeedKmh = 0.8
        )
    }
}
