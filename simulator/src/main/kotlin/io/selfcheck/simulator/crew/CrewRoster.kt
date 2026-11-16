package io.selfcheck.simulator.crew

import io.selfcheck.contracts.Sex

/** Resting baseline of a healthy young adult crew member (spec section 9). */
data class VitalBaseline(val heartRate: Double, val spo2: Double, val respiratoryRate: Double)

/** Simulated medical history, as the ship's medical system would send it (spec section 4.3). */
data class CrewMember(
    val id: String,
    val sex: Sex,
    val priorEffectiveDoseMsv: Double,
    /** Knee extension peak torque, N·m. */
    val preflightMuscleStrength: Double,
    val preflightBodyMassKg: Double,
    val preflightBmdHip: Double,
    val preflightBmdSpine: Double,
    val preflightBmdFemoralNeck: Double,
    /** Age and height feed the NASA energy requirement (NasaTargets). */
    val ageYears: Int,
    val heightM: Double,
    val baseline: VitalBaseline,
) {
    /** Daily targets of the medical history, from NASA requirements (NasaTargets). */
    val dailyCaloriesKcal: Double get() = kotlin.math.round(NasaTargets.caloriesKcal(sex, ageYears, preflightBodyMassKg, heightM))
    val dailyFluidsMl: Double get() = NasaTargets.fluidsMl(sex, preflightBodyMassKg)
    val dailyActivityMinutes: Double get() = NasaTargets.ACTIVITY_MINUTES
}

/** The four simulated crew members. */
object CrewRoster {
    val members: List<CrewMember> = listOf(
        CrewMember(
            id = "crew-1", sex = Sex.FEMALE, priorEffectiveDoseMsv = 45.0,
            preflightMuscleStrength = 180.0, preflightBodyMassKg = 62.0,
            preflightBmdHip = 0.98, preflightBmdSpine = 1.02, preflightBmdFemoralNeck = 0.85,
            ageYears = 41, heightM = 1.66,
            baseline = VitalBaseline(heartRate = 64.0, spo2 = 98.0, respiratoryRate = 14.0),
        ),
        CrewMember(
            id = "crew-2", sex = Sex.MALE, priorEffectiveDoseMsv = 120.0,
            preflightMuscleStrength = 240.0, preflightBodyMassKg = 78.0,
            preflightBmdHip = 1.08, preflightBmdSpine = 1.12, preflightBmdFemoralNeck = 0.95,
            ageYears = 45, heightM = 1.80,
            baseline = VitalBaseline(heartRate = 68.0, spo2 = 97.0, respiratoryRate = 15.0),
        ),
        CrewMember(
            id = "crew-3", sex = Sex.FEMALE, priorEffectiveDoseMsv = 0.0,
            preflightMuscleStrength = 170.0, preflightBodyMassKg = 58.0,
            preflightBmdHip = 0.95, preflightBmdSpine = 1.0, preflightBmdFemoralNeck = 0.82,
            ageYears = 38, heightM = 1.62,
            baseline = VitalBaseline(heartRate = 72.0, spo2 = 98.0, respiratoryRate = 13.0),
        ),
        CrewMember(
            id = "crew-4", sex = Sex.MALE, priorEffectiveDoseMsv = 210.0,
            preflightMuscleStrength = 255.0, preflightBodyMassKg = 82.0,
            preflightBmdHip = 1.12, preflightBmdSpine = 1.15, preflightBmdFemoralNeck = 0.98,
            ageYears = 47, heightM = 1.83,
            baseline = VitalBaseline(heartRate = 66.0, spo2 = 97.0, respiratoryRate = 16.0),
        ),
    )
}
