package io.selfcheck.engine

import io.selfcheck.contracts.HealthState.ALERT
import io.selfcheck.contracts.HealthState.NORMAL
import io.selfcheck.contracts.HealthState.OBSERVATION
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.Variable
import io.selfcheck.engine.rules.ReasonCodes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PLAN.md phase 2: every NASA and ISS limit. 600 mSv career, 250 mSv per solar event, CO2 3 mmHg,
 * noise 60/72 dBA by day and 50/62 dBA by night, dust 0.3 mg/m³ only at the lunar base,
 * strength 80 % and bone density 95 % / 90 % of the pre-flight value.
 */
class NasaLimitsTest {

    private val engine = EngineHarness()

    @AfterTest
    fun close() = engine.close()

    @Test
    fun `career dose - alert at 600 mSv, observation at 480 accumulated`() {
        engine.profile(plannedDays = 180)
        engine.history(priorDose = 120.0)
        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 10.0, 100 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.CAREER_EFFECTIVE_DOSE))

        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 360.0, 170 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.CAREER_EFFECTIVE_DOSE))
        assertEquals(ReasonCodes.CAREER_DOSE_NEAR_LIMIT, engine.codeOf(Variable.CAREER_EFFECTIVE_DOSE))

        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 479.9, 175 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.CAREER_EFFECTIVE_DOSE))
        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 480.0, 176 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.CAREER_EFFECTIVE_DOSE))
        assertEquals("600", engine.state(Variable.CAREER_EFFECTIVE_DOSE)!!.reason.params["value"])
    }

    @Test
    fun `career dose - observation when the projection to the end of the mission reaches 480 mSv`() {
        engine.profile(plannedDays = 180)
        engine.history(priorDose = 0.0)
        // 3 mSv/day for 10 days: 30 + 3 x 170 = 540 projected.
        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 30.0, 10 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.CAREER_EFFECTIVE_DOSE))
        assertEquals(ReasonCodes.CAREER_DOSE_PROJECTED, engine.codeOf(Variable.CAREER_EFFECTIVE_DOSE))
        // 1 mSv/day: 10 + 170 = 180 projected.
        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 10.0, 10 * DAY + 1))
        assertEquals(NORMAL, engine.stateOf(Variable.CAREER_EFFECTIVE_DOSE))
    }

    @Test
    fun `solar event - observation while active, alert at 250 mSv in the event, normal when it ends`() {
        engine.profile()
        engine.history()
        engine.send(measurement(Variable.SOLAR_PARTICLE_EVENT, 0.0, 1_000))
        assertEquals(NORMAL, engine.stateOf(Variable.SOLAR_EVENT_DOSE))

        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 20.0, 2_000))
        engine.send(measurement(Variable.SOLAR_PARTICLE_EVENT, 1.0, 3_000))
        assertEquals(OBSERVATION, engine.stateOf(Variable.SOLAR_EVENT_DOSE))

        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 269.9, 4_000))
        assertEquals(OBSERVATION, engine.stateOf(Variable.SOLAR_EVENT_DOSE))
        engine.send(measurement(Variable.MISSION_EFFECTIVE_DOSE, 270.0, 5_000))
        assertEquals(ALERT, engine.stateOf(Variable.SOLAR_EVENT_DOSE))
        assertEquals("250", engine.state(Variable.SOLAR_EVENT_DOSE)!!.reason.params["value"])

        engine.send(measurement(Variable.SOLAR_PARTICLE_EVENT, 0.0, 6_000))
        assertEquals(NORMAL, engine.stateOf(Variable.SOLAR_EVENT_DOSE))
    }

    @Test
    fun `CO2 - alert when the 1-hour mean is above 3 mmHg, not on a single spike`() {
        for (minute in 0 until 60) engine.send(habitat(Variable.CO2, 2.0, minute * 60_000L))
        engine.send(habitat(Variable.CO2, 9.0, 60 * 60_000L))
        assertEquals(NORMAL, engine.stateOf(Variable.CO2, "habitat"))

        for (minute in 61 until 120) engine.send(habitat(Variable.CO2, 3.5, minute * 60_000L))
        assertEquals(ALERT, engine.stateOf(Variable.CO2, "habitat"))
    }

    @Test
    fun `CO2 - exactly 3 mmHg is normal`() {
        engine.send(habitat(Variable.CO2, 3.0, 0))
        assertEquals(NORMAL, engine.stateOf(Variable.CO2, "habitat"))
    }

    @Test
    fun `daytime noise - observation above 60 dBA, alert above 72 dBA (16-hour mean)`() {
        engine.send(habitat(Variable.NOISE_DAY, 60.0, 0))
        assertEquals(NORMAL, engine.stateOf(Variable.NOISE_DAY, "habitat"))
        engine.send(habitat(Variable.NOISE_DAY, 62.0, HOUR)) // mean 61
        assertEquals(OBSERVATION, engine.stateOf(Variable.NOISE_DAY, "habitat"))
        engine.send(habitat(Variable.NOISE_DAY, 97.0, 2 * HOUR)) // mean 73
        assertEquals(ALERT, engine.stateOf(Variable.NOISE_DAY, "habitat"))
        // 18 hours later the earlier readings have left the 16-hour window: the mean is 72, not above 72.
        engine.send(habitat(Variable.NOISE_DAY, 72.0, 20 * HOUR))
        assertEquals(OBSERVATION, engine.stateOf(Variable.NOISE_DAY, "habitat"))
    }

    @Test
    fun `night-time noise - observation above 50 dBA, alert above 62 dBA (8-hour mean)`() {
        engine.send(habitat(Variable.NOISE_NIGHT, 50.0, 0))
        assertEquals(NORMAL, engine.stateOf(Variable.NOISE_NIGHT, "habitat"))
        engine.send(habitat(Variable.NOISE_NIGHT, 52.0, HOUR)) // mean 51
        assertEquals(OBSERVATION, engine.stateOf(Variable.NOISE_NIGHT, "habitat"))
        engine.send(habitat(Variable.NOISE_NIGHT, 90.0, 2 * HOUR)) // mean 64
        assertEquals(ALERT, engine.stateOf(Variable.NOISE_NIGHT, "habitat"))
    }

    @Test
    fun `airborne dust - alert above 0,3 mg per m3 only at the lunar base`() {
        engine.profile(MissionEnvironment.ORBITAL_STATION)
        engine.send(habitat(Variable.AIRBORNE_DUST, 0.8, 0))
        assertNull(engine.stateOf(Variable.AIRBORNE_DUST, "habitat"))
        assertEquals(ReasonCodes.NOT_APPLICABLE_ENVIRONMENT, engine.codeOf(Variable.AIRBORNE_DUST, "habitat"))
        assertEquals(emptyList(), engine.alerts)

        engine.profile(MissionEnvironment.LUNAR_BASE)
        engine.send(habitat(Variable.AIRBORNE_DUST, 0.3, 60_000))
        assertEquals(NORMAL, engine.stateOf(Variable.AIRBORNE_DUST, "habitat"))
        engine.send(habitat(Variable.AIRBORNE_DUST, 0.31, 120_000))
        assertEquals(ALERT, engine.stateOf(Variable.AIRBORNE_DUST, "habitat"))
    }

    @Test
    fun `muscle strength - alert below 80 percent of the pre-flight value`() {
        engine.history(strength = 200.0)
        engine.send(measurement(Variable.MUSCLE_STRENGTH, 160.0, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.MUSCLE_STRENGTH))
        engine.send(measurement(Variable.MUSCLE_STRENGTH, 159.0, 2 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.MUSCLE_STRENGTH))
        assertEquals("79.5", engine.state(Variable.MUSCLE_STRENGTH)!!.reason.params["evaluated"])
    }

    @Test
    fun `bone density - alert below 95 percent at hip and spine and below 90 percent at the femoral neck`() {
        engine.history(bmd = Triple(1.0, 1.2, 0.8))
        engine.send(measurement(Variable.BMD_HIP, 0.95, DAY))
        engine.send(measurement(Variable.BMD_SPINE, 1.14, DAY))
        engine.send(measurement(Variable.BMD_FEMORAL_NECK, 0.72, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.BMD_HIP))
        assertEquals(NORMAL, engine.stateOf(Variable.BMD_SPINE))
        assertEquals(NORMAL, engine.stateOf(Variable.BMD_FEMORAL_NECK))

        engine.send(measurement(Variable.BMD_HIP, 0.949, 2 * DAY))
        engine.send(measurement(Variable.BMD_SPINE, 1.139, 2 * DAY))
        engine.send(measurement(Variable.BMD_FEMORAL_NECK, 0.719, 2 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.BMD_HIP))
        assertEquals(ALERT, engine.stateOf(Variable.BMD_SPINE))
        assertEquals(ALERT, engine.stateOf(Variable.BMD_FEMORAL_NECK))
    }
}
