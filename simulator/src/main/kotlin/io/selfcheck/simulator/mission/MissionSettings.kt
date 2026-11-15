package io.selfcheck.simulator.mission

import io.selfcheck.simulator.clock.MissionClock

/**
 * Simulated conditions of the mission. These are inputs of the simulation, not thresholds: every cut lives in
 * `knowledge/`. The system does not tell mission types apart (team decision).
 */
object MissionSettings {

    /** The mission starts on day 1 at 08:00, after the crew wakes up. */
    const val START_TIME_MS = 8 * MissionClock.HOUR_MS

    /** Background effective dose rate, µSv/h (about 0.5 mSv per day). */
    const val DOSE_RATE_MICRO_SV_PER_HOUR = 21.0

    /** Typical airborne dust in the habitat, mg/m³. */
    const val DUST_MG_PER_M3 = 0.02
}
