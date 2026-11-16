package io.selfcheck.simulator.crew

import io.selfcheck.contracts.Sex

/**
 * Daily targets of the simulated medical history, from NASA requirements (spec section 7.8 and 7.6).
 * - Fluids: JSC-67378 Rev 1, "32 mL/kg", and no less than 2500 mL for men and 2100 mL for women.
 * - Energy: the National Academy of Medicine DRI estimated energy requirement that JSC-67378 and [V1 4020] use, with
 *   the 1.25 activity factor JSC-67378 gives for ISS-like exercise equipment. JSC-67378 prints 622 and 15.9 for men;
 *   the DRI equation it cites has 662 and 15.91.
 * - Activity: the minimum of the exercise prescription in OCHMO-TB-031, about 30 min aerobic plus 30-60 min
 *   load-bearing training per day.
 */
object NasaTargets {
    const val ACTIVITY_MINUTES = 60.0
    private const val FLUID_ML_PER_KG = 32.0
    private const val ISS_ACTIVITY_FACTOR = 1.25

    fun fluidsMl(sex: Sex, massKg: Double): Double =
        maxOf(FLUID_ML_PER_KG * massKg, if (sex == Sex.MALE) 2500.0 else 2100.0)

    fun caloriesKcal(sex: Sex, ageYears: Int, massKg: Double, heightM: Double): Double = when (sex) {
        Sex.MALE -> 662 - 9.53 * ageYears + ISS_ACTIVITY_FACTOR * (15.91 * massKg + 539.6 * heightM)
        else -> 354 - 6.91 * ageYears + ISS_ACTIVITY_FACTOR * (9.36 * massKg + 726 * heightM)
    }
}
