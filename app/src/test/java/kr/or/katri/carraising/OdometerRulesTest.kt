package kr.or.katri.carraising

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OdometerRulesTest {
    @Test
    fun gpsDistanceIsAddedToConfirmedOdo() {
        assertEquals(
            1_008.25,
            OdometerRules.currentOdoKm(
                confirmedOdoKm = 1_000.0,
                trackedDistanceM = 8_250.0,
                confirmationDistanceM = 0.0
            )!!,
            0.0001
        )
    }

    @Test
    fun onlyDistanceAfterConfirmationIsAdded() {
        assertEquals(
            1_002.0,
            OdometerRules.currentOdoKm(
                confirmedOdoKm = 1_000.0,
                trackedDistanceM = 10_000.0,
                confirmationDistanceM = 8_000.0
            )!!,
            0.0001
        )
    }

    @Test
    fun missingOdoRemainsUnset() {
        assertNull(
            OdometerRules.currentOdoKm(
                confirmedOdoKm = null,
                trackedDistanceM = 5_000.0,
                confirmationDistanceM = 0.0
            )
        )
    }
}
