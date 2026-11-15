package io.selfcheck.simulator

import io.selfcheck.contracts.Sex
import io.selfcheck.simulator.crew.CrewRoster
import io.selfcheck.simulator.crew.NasaTargets
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class NasaTargetsTest {

    @Test
    fun `fluid target is 32 mL per kg with a floor of 2500 mL for men and 2100 mL for women`() {
        assertEquals(2100.0, NasaTargets.fluidsMl(Sex.FEMALE, 62.0))
        assertEquals(2500.0, NasaTargets.fluidsMl(Sex.MALE, 78.0))
        assertEquals(2624.0, NasaTargets.fluidsMl(Sex.MALE, 82.0))
        assertEquals(2240.0, NasaTargets.fluidsMl(Sex.FEMALE, 70.0))
    }

    @Test
    fun `calorie target follows the DRI estimated energy requirement with the ISS activity factor`() {
        assertEquals(2303, NasaTargets.caloriesKcal(Sex.FEMALE, ageYears = 41, massKg = 62.0, heightM = 1.66).roundToInt())
        assertEquals(2998, NasaTargets.caloriesKcal(Sex.MALE, ageYears = 45, massKg = 78.0, heightM = 1.80).roundToInt())
    }

    @Test
    fun `activity target is the minimum of the NASA exercise prescription`() {
        assertEquals(60.0, NasaTargets.ACTIVITY_MINUTES)
    }

    @Test
    fun `the roster takes its daily targets from the NASA formulas`() {
        val targets = CrewRoster.members.associate { it.id to Triple(it.dailyFluidsMl, it.dailyCaloriesKcal.roundToInt(), it.dailyActivityMinutes) }
        assertEquals(Triple(2100.0, 2303, 60.0), targets["crew-1"])
        assertEquals(Triple(2500.0, 2998, 60.0), targets["crew-2"])
        assertEquals(Triple(2100.0, 2240, 60.0), targets["crew-3"])
        assertEquals(Triple(2624.0, 3079, 60.0), targets["crew-4"])
    }
}
