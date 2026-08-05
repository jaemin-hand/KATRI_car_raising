package kr.or.katri.carraising

internal object DistanceCorrectionRules {
    const val STEP_TENTHS_PERCENT = 1
    const val MIN_TENTHS_PERCENT = -50
    const val MAX_TENTHS_PERCENT = 50

    fun normalize(correctionTenthsPercent: Int): Int {
        return correctionTenthsPercent.coerceIn(
            MIN_TENTHS_PERCENT,
            MAX_TENTHS_PERCENT
        )
    }

    fun adjust(currentTenthsPercent: Int, step: Int): Int {
        return normalize(normalize(currentTenthsPercent) + step)
    }

    fun percent(correctionTenthsPercent: Int): Double {
        return normalize(correctionTenthsPercent) / 10.0
    }

    fun factor(correctionTenthsPercent: Int): Double {
        return 1.0 + normalize(correctionTenthsPercent) / 1000.0
    }

    fun correctedDistanceM(
        rawDistanceM: Double,
        correctionTenthsPercent: Int
    ): Double {
        require(rawDistanceM.isFinite() && rawDistanceM >= 0.0)
        return rawDistanceM * factor(correctionTenthsPercent)
    }
}
