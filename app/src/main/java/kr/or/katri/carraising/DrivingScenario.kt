package kr.or.katri.carraising

enum class ScenarioDriveMode(val label: String) {
    CONSTANT("정속"),
    ACCEL_DECEL("가감속")
}

enum class AccelerationMethod(val label: String) {
    NONE("없음"),
    SMOOTH("완가속"),
    RAPID("급가속"),
    WOT("WOT")
}

data class DrivingScenarioStep(
    val id: String,
    val section: String,
    val startKm: Double,
    val endKm: Double,
    val targetSpeedKmh: Int,
    val driveMode: ScenarioDriveMode,
    val decelTargetKmh: Int? = null,
    val accelerationMethod: AccelerationMethod = AccelerationMethod.NONE
) {
    init {
        require(startKm >= 0.0)
        require(endKm > startKm)
        require(targetSpeedKmh > 0)
        require((driveMode == ScenarioDriveMode.ACCEL_DECEL) == (decelTargetKmh != null))
    }

    fun contains(distanceKm: Double): Boolean = distanceKm >= startKm && distanceKm < endKm
}

object KatriDrivingScenario {
    const val configuredEndKm = 3_000.0

    val steps: List<DrivingScenarioStep> = buildList {
        add(constant("A-1", "A", 0.0, 100.0, 60))
        add(accelDecel("A-2", "A", 100.0, 300.0, 60, 0, AccelerationMethod.SMOOTH))
        add(constant("B-1", "B", 300.0, 500.0, 80))
        add(accelDecel("B-2", "B", 500.0, 700.0, 80, 0, AccelerationMethod.SMOOTH))
        add(constant("C-1", "C", 700.0, 800.0, 100))
        add(accelDecel("C-2", "C", 800.0, 1_000.0, 100, 50, AccelerationMethod.SMOOTH))
        add(constant("D-1", "D", 1_000.0, 1_100.0, 110))
        add(accelDecel("D-2", "D", 1_100.0, 1_200.0, 110, 60, AccelerationMethod.RAPID))
        add(constant("E-1", "E", 1_200.0, 1_300.0, 130))
        add(accelDecel("E-2", "E", 1_300.0, 1_400.0, 130, 80, AccelerationMethod.RAPID))
        add(constant("F-1", "F", 1_400.0, 1_500.0, 145))
        add(accelDecel("F-2", "F", 1_500.0, 1_600.0, 145, 110, AccelerationMethod.RAPID))
        add(constant("G-1", "G", 1_600.0, 1_700.0, 130))
        add(accelDecel("G-2", "G", 1_700.0, 1_800.0, 130, 80, AccelerationMethod.RAPID))
        add(constant("H-1", "H", 1_800.0, 1_900.0, 145))
        add(accelDecel("H-2", "H", 1_900.0, 2_000.0, 145, 110, AccelerationMethod.RAPID))

        var blockStartKm = 2_000.0
        var repeatIndex = 1
        while (blockStartKm < configuredEndKm) {
            add(constant("H-R${repeatIndex}-1", "H 반복", blockStartKm, blockStartKm + 100.0, 145))
            add(
                accelDecel(
                    "H-R${repeatIndex}-2",
                    "H 반복",
                    blockStartKm + 100.0,
                    blockStartKm + 200.0,
                    145,
                    110,
                    AccelerationMethod.WOT
                )
            )
            blockStartKm += 200.0
            repeatIndex += 1
        }
    }

    fun stepAt(distanceKm: Double): DrivingScenarioStep? {
        if (!distanceKm.isFinite() || distanceKm < 0.0) return null
        return steps.firstOrNull { it.contains(distanceKm) }
    }

    private fun constant(
        id: String,
        section: String,
        startKm: Double,
        endKm: Double,
        speedKmh: Int
    ) = DrivingScenarioStep(
        id = id,
        section = section,
        startKm = startKm,
        endKm = endKm,
        targetSpeedKmh = speedKmh,
        driveMode = ScenarioDriveMode.CONSTANT
    )

    private fun accelDecel(
        id: String,
        section: String,
        startKm: Double,
        endKm: Double,
        speedKmh: Int,
        decelTargetKmh: Int,
        accelerationMethod: AccelerationMethod
    ) = DrivingScenarioStep(
        id = id,
        section = section,
        startKm = startKm,
        endKm = endKm,
        targetSpeedKmh = speedKmh,
        driveMode = ScenarioDriveMode.ACCEL_DECEL,
        decelTargetKmh = decelTargetKmh,
        accelerationMethod = accelerationMethod
    )
}
