package kr.or.katri.carraising

enum class PowertrainType(val label: String) {
    COMBUSTION("내연기관 차량"),
    HYBRID("하이브리드 차량"),
    ELECTRIC("전기 차량")
}

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
    const val commonConfiguredEndKm = 3_000.0
    const val combustionTestTargetKm = 6_500.0
    const val hybridTestTargetKm = 8_900.0
    const val electricTestTargetKm = 5_500.0

    private val baseSteps: List<DrivingScenarioStep> = listOf(
        constant("A-1", "A", 0.0, 100.0, 60),
        accelDecel("A-2", "A", 100.0, 300.0, 60, 0, AccelerationMethod.SMOOTH),
        constant("B-1", "B", 300.0, 500.0, 80),
        accelDecel("B-2", "B", 500.0, 700.0, 80, 0, AccelerationMethod.SMOOTH),
        constant("C-1", "C", 700.0, 800.0, 100),
        accelDecel("C-2", "C", 800.0, 1_000.0, 100, 50, AccelerationMethod.SMOOTH),
        constant("D-1", "D", 1_000.0, 1_100.0, 110),
        accelDecel("D-2", "D", 1_100.0, 1_200.0, 110, 60, AccelerationMethod.RAPID),
        constant("E-1", "E", 1_200.0, 1_300.0, 130),
        accelDecel("E-2", "E", 1_300.0, 1_400.0, 130, 80, AccelerationMethod.RAPID),
        constant("F-1", "F", 1_400.0, 1_500.0, 145),
        accelDecel("F-2", "F", 1_500.0, 1_600.0, 145, 110, AccelerationMethod.RAPID),
        constant("G-1", "G", 1_600.0, 1_700.0, 130),
        accelDecel("G-2", "G", 1_700.0, 1_800.0, 130, 80, AccelerationMethod.RAPID),
        constant("H-1", "H", 1_800.0, 1_900.0, 145),
        accelDecel("H-2", "H", 1_900.0, 2_000.0, 145, 110, AccelerationMethod.RAPID)
    )

    val commonSteps: List<DrivingScenarioStep> = buildList {
        addAll(baseSteps)
        var blockStartKm = 2_000.0
        var repeatIndex = 1
        while (blockStartKm < commonConfiguredEndKm) {
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

    fun stepAt(distanceKm: Double, powertrainType: PowertrainType): DrivingScenarioStep? {
        if (
            !distanceKm.isFinite() ||
            distanceKm < 0.0 ||
            distanceKm >= targetDistanceKm(powertrainType)
        ) return null
        if (distanceKm < commonConfiguredEndKm) return commonStepAt(distanceKm)

        return when (powertrainType) {
            PowertrainType.COMBUSTION -> repeatedHStep(distanceKm)
            PowertrainType.HYBRID -> repeatedCommonStep(distanceKm)
            PowertrainType.ELECTRIC -> repeatedHStep(distanceKm)
        }
    }

    fun targetDistanceKm(powertrainType: PowertrainType): Double {
        return when (powertrainType) {
            PowertrainType.ELECTRIC -> electricTestTargetKm
            PowertrainType.COMBUSTION -> combustionTestTargetKm
            PowertrainType.HYBRID -> hybridTestTargetKm
        }
    }

    fun returnInstruction(powertrainType: PowertrainType): String? {
        return when (powertrainType) {
            PowertrainType.COMBUSTION ->
                "주유 경고등 점등 상태를 확인하고 차량을 반납하세요."
            PowertrainType.HYBRID ->
                "주유 경고등 점등 상태를 확인하고 8,900km에 차량을 반납하세요."
            PowertrainType.ELECTRIC -> null
        }
    }

    fun commonStepAt(distanceKm: Double): DrivingScenarioStep? {
        if (!distanceKm.isFinite() || distanceKm < 0.0 || distanceKm >= commonConfiguredEndKm) return null
        return commonSteps.firstOrNull { it.contains(distanceKm) }
    }

    private fun repeatedHStep(distanceKm: Double): DrivingScenarioStep {
        val repeatIndex = ((distanceKm - 2_000.0) / 200.0).toInt()
        val blockStartKm = 2_000.0 + repeatIndex * 200.0
        return if (distanceKm < blockStartKm + 100.0) {
            constant("H-R${repeatIndex + 1}-1", "H 반복", blockStartKm, blockStartKm + 100.0, 145)
        } else {
            accelDecel(
                "H-R${repeatIndex + 1}-2",
                "H 반복",
                blockStartKm + 100.0,
                blockStartKm + 200.0,
                145,
                110,
                AccelerationMethod.WOT
            )
        }
    }

    private fun repeatedCommonStep(distanceKm: Double): DrivingScenarioStep {
        val cycleIndex = ((distanceKm - commonConfiguredEndKm) / commonConfiguredEndKm).toInt()
        val cycleStartKm = commonConfiguredEndKm * (cycleIndex + 1)
        val localDistanceKm = distanceKm - cycleStartKm
        val commonStep = commonSteps.first { it.contains(localDistanceKm) }
        return commonStep.copy(
            section = if (commonStep.section.endsWith(" 반복")) {
                commonStep.section
            } else {
                "${commonStep.section} 반복"
            },
            startKm = cycleStartKm + commonStep.startKm,
            endKm = cycleStartKm + commonStep.endKm
        )
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
