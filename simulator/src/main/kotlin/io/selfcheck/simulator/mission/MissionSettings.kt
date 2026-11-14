package io.selfcheck.simulator.mission

import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.simulator.clock.MissionClock

/**
 * Simulated conditions of each environment (spec section 2.1). These are inputs of the simulation,
 * not thresholds: every cut lives in `knowledge/`.
 */
data class EnvironmentSettings(
    val plannedDurationDays: Int,
    /** Background effective dose rate, µSv/h. */
    val doseRateMicroSvPerHour: Double,
    /** Typical airborne dust, mg/m³. */
    val dustMgPerM3: Double,
)

object MissionSettings {

    /** The mission starts on day 1 at 08:00, after the crew wakes up. */
    const val START_TIME_MS = 8 * MissionClock.HOUR_MS

    val environments: Map<MissionEnvironment, EnvironmentSettings> = mapOf(
        // ISS-like orbit: about 0.5 mSv per day.
        MissionEnvironment.ORBITAL_STATION to EnvironmentSettings(180, 21.0, 0.02),
        // Lunar surface: about 1.3 mSv per day; regolith dust inside the base.
        MissionEnvironment.LUNAR_BASE to EnvironmentSettings(180, 54.0, 0.12),
        // Interplanetary cruise: about 1.8 mSv per day.
        MissionEnvironment.DEEP_SPACE_TRANSIT to EnvironmentSettings(300, 75.0, 0.02),
    )

    fun of(environment: MissionEnvironment): EnvironmentSettings = environments.getValue(environment)
}
