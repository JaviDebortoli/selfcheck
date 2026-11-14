package io.selfcheck.engine

import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.HealthState.ALERT
import io.selfcheck.contracts.HealthState.NORMAL
import io.selfcheck.contracts.HealthState.NOT_EVALUABLE
import io.selfcheck.contracts.HealthState.OBSERVATION
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.Sex
import io.selfcheck.contracts.Variable
import io.selfcheck.engine.rules.Combinations
import io.selfcheck.engine.rules.ReasonCodes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules of spec section 7 beyond plain thresholds: streaks, rhythm, sex, KDIGO, lab ranges, combinations. */
class GroupRulesTest {

    private val engine = EngineHarness()

    @AfterTest
    fun close() = engine.close()

    @Test
    fun `sleep - under 6 h is observation the first night and alert on the third night in a row`() {
        engine.send(measurement(Variable.SLEEP_HOURS, 5.5, DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.SLEEP_HOURS))
        engine.send(measurement(Variable.SLEEP_HOURS, 5.0, 2 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.SLEEP_HOURS))
        engine.send(measurement(Variable.SLEEP_HOURS, 5.8, 3 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.SLEEP_HOURS))
        engine.send(measurement(Variable.SLEEP_HOURS, 7.2, 4 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.SLEEP_HOURS))
    }

    @Test
    fun `sleep - a gap longer than the expiry breaks the streak`() {
        engine.send(measurement(Variable.SLEEP_HOURS, 5.5, DAY))
        engine.send(measurement(Variable.SLEEP_HOURS, 5.5, 2 * DAY))
        engine.send(measurement(Variable.SLEEP_HOURS, 5.5, 4 * DAY)) // 48 h gap > 36 h expiry
        assertEquals(OBSERVATION, engine.stateOf(Variable.SLEEP_HOURS))
    }

    @Test
    fun `caloric intake - normal with pending streak, observation after 3 days, alert after 3 days under 75 percent`() {
        engine.history(calories = 2000.0)
        engine.send(measurement(Variable.CALORIC_INTAKE, 1700.0, DAY)) // 85 %
        assertEquals(NORMAL, engine.stateOf(Variable.CALORIC_INTAKE))
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.CALORIC_INTAKE))
        assertEquals("1", engine.state(Variable.CALORIC_INTAKE)!!.reason.params["count"])
        engine.send(measurement(Variable.CALORIC_INTAKE, 1400.0, 2 * DAY)) // 70 %
        assertEquals("2", engine.state(Variable.CALORIC_INTAKE)!!.reason.params["count"])
        engine.send(measurement(Variable.CALORIC_INTAKE, 1400.0, 3 * DAY)) // 70 %, but only 2 days under 75 %
        assertEquals(OBSERVATION, engine.stateOf(Variable.CALORIC_INTAKE))
        engine.send(measurement(Variable.CALORIC_INTAKE, 1400.0, 4 * DAY)) // third day under 75 %
        assertEquals(ALERT, engine.stateOf(Variable.CALORIC_INTAKE))
        engine.send(measurement(Variable.CALORIC_INTAKE, 2000.0, 5 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.CALORIC_INTAKE))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.CALORIC_INTAKE))
    }

    @Test
    fun `fluid intake below target has no state until the deviation cut has a number`() {
        engine.history(fluids = 2500.0)
        engine.send(measurement(Variable.FLUID_INTAKE, 2600.0, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.FLUID_INTAKE))
        engine.send(measurement(Variable.FLUID_INTAKE, 1200.0, 2 * DAY))
        assertNull(engine.stateOf(Variable.FLUID_INTAKE))
        assertEquals(ReasonCodes.NO_CUT, engine.codeOf(Variable.FLUID_INTAKE))
    }

    @Test
    fun `body mass - alert above 5 percent change, observation above 2 percent for 3 days`() {
        engine.history(bodyMass = 70.0)
        engine.send(measurement(Variable.BODY_MASS, 68.0, DAY)) // -2.86 %
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.BODY_MASS))
        engine.send(measurement(Variable.BODY_MASS, 68.0, 2 * DAY))
        engine.send(measurement(Variable.BODY_MASS, 68.0, 3 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.BODY_MASS))
        engine.send(measurement(Variable.BODY_MASS, 66.0, 4 * DAY)) // -5.7 %
        assertEquals(ALERT, engine.stateOf(Variable.BODY_MASS))
    }

    @Test
    fun `urinary calcium - alarm sign only gives alert above 300 mg per 24 h`() {
        engine.send(measurement(Variable.URINARY_CALCIUM, 250.0, DAY, signs = listOf(AlarmSign.RENAL_COLIC_OR_HEMATURIA)))
        assertEquals(NORMAL, engine.stateOf(Variable.URINARY_CALCIUM))
        engine.send(measurement(Variable.URINARY_CALCIUM, 320.0, 8 * DAY, signs = listOf(AlarmSign.RENAL_COLIC_OR_HEMATURIA)))
        assertEquals(ALERT, engine.stateOf(Variable.URINARY_CALCIUM))
        assertEquals(ReasonCodes.ALARM_SIGN, engine.codeOf(Variable.URINARY_CALCIUM))
    }

    @Test
    fun `urea - observation only after 2 analyses in a row out of range`() {
        engine.send(measurement(Variable.UREA, 55.0, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.UREA))
        engine.send(measurement(Variable.UREA, 58.0, 8 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.UREA))
    }

    @Test
    fun `ECG rhythm from the heart-rate label - sinus, premature beats, ventricular tachycardia, chest pain`() {
        engine.send(measurement(Variable.HEART_RATE, 70.0, 1_000, rhythm = RhythmLabel.SINUS))
        assertEquals(NORMAL, engine.stateOf(Variable.ECG_RHYTHM))
        engine.send(measurement(Variable.HEART_RATE, 75.0, 2_000, rhythm = RhythmLabel.PREMATURE_BEATS))
        assertEquals(OBSERVATION, engine.stateOf(Variable.ECG_RHYTHM))
        engine.send(measurement(Variable.HEART_RATE, 180.0, 3_000, rhythm = RhythmLabel.VENTRICULAR_TACHYCARDIA))
        assertEquals(ALERT, engine.stateOf(Variable.ECG_RHYTHM))
        engine.send(measurement(Variable.HEART_RATE, 70.0, 4_000, rhythm = RhythmLabel.SINUS, signs = listOf(AlarmSign.CHEST_PAIN)))
        assertEquals(ALERT, engine.stateOf(Variable.ECG_RHYTHM))
        assertEquals(ReasonCodes.ALARM_SIGN, engine.codeOf(Variable.ECG_RHYTHM))
    }

    @Test
    fun `hemoglobin cuts depend on sex`() {
        engine.history("crew-1", sex = Sex.MALE)
        engine.history("crew-2", sex = Sex.FEMALE)
        engine.send(measurement(Variable.HEMOGLOBIN, 12.5, DAY, subjectId = "crew-1"))
        engine.send(measurement(Variable.HEMOGLOBIN, 12.5, DAY, subjectId = "crew-2"))
        assertEquals(OBSERVATION, engine.stateOf(Variable.HEMOGLOBIN, "crew-1"))
        assertEquals(NORMAL, engine.stateOf(Variable.HEMOGLOBIN, "crew-2"))
        engine.send(measurement(Variable.HEMOGLOBIN, 16.5, 8 * DAY, subjectId = "crew-2"))
        assertEquals(OBSERVATION, engine.stateOf(Variable.HEMOGLOBIN, "crew-2"))
        engine.send(measurement(Variable.HEMOGLOBIN, 20.0, 8 * DAY, subjectId = "crew-1"))
        assertEquals(ALERT, engine.stateOf(Variable.HEMOGLOBIN, "crew-1"))
    }

    @Test
    fun `creatinine - KDIGO alerts on 0,3 mg per dL in 48 h and 1,5 times in 7 days`() {
        engine.send(measurement(Variable.CREATININE, 0.9, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.CREATININE))
        engine.send(measurement(Variable.CREATININE, 1.2, 2 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.CREATININE))
        assertEquals(ReasonCodes.CREATININE_RISE_48H, engine.codeOf(Variable.CREATININE))
    }

    @Test
    fun `creatinine - 1,5 times the lowest value of the last 7 days`() {
        engine.send(measurement(Variable.CREATININE, 0.6, DAY))
        engine.send(measurement(Variable.CREATININE, 0.7, 4 * DAY))
        engine.send(measurement(Variable.CREATININE, 0.9, 7 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.CREATININE))
        assertEquals(ReasonCodes.CREATININE_RISE_7D, engine.codeOf(Variable.CREATININE))
    }

    @Test
    fun `bone resorption markers - laboratory range, two analyses, alert with bone loss`() {
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 0.9, DAY, range = 0.1 to 0.8))
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 0.95, 31 * DAY, range = 0.1 to 0.8))
        assertEquals(OBSERVATION, engine.stateOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.URINARY_CALCIUM, 340.0, 32 * DAY))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 0.95, 33 * DAY, range = 0.1 to 0.8))
        assertEquals(ALERT, engine.stateOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 0.5, 34 * DAY))
        assertEquals(NOT_EVALUABLE, engine.stateOf(Variable.BONE_RESORPTION_MARKERS))
    }

    @Test
    fun `bowel movements - diarrhoea with dehydration gives alert`() {
        engine.send(measurement(Variable.URINE_SPECIFIC_GRAVITY, 1.035, DAY))
        engine.send(measurement(Variable.BOWEL_FREQUENCY, 5.0, DAY))
        assertEquals(ALERT, engine.stateOf(Variable.BOWEL_FREQUENCY))
        assertEquals(ReasonCodes.DIARRHEA_DEHYDRATION, engine.codeOf(Variable.BOWEL_FREQUENCY))
    }

    @Test
    fun `bowel movements - fewer than 3 per week needs a full week of records`() {
        for (day in 1..6) engine.send(measurement(Variable.BOWEL_FREQUENCY, 0.0, day * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.BOWEL_FREQUENCY))
        engine.send(measurement(Variable.BOWEL_FREQUENCY, 1.0, 7 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.BOWEL_FREQUENCY))
    }

    @Test
    fun `glucose is only evaluated fasting`() {
        engine.send(measurement(Variable.GLUCOSE, 140.0, DAY, fasting = false))
        assertNull(engine.stateOf(Variable.GLUCOSE))
        engine.send(measurement(Variable.GLUCOSE, 100.0, 2 * DAY, fasting = true))
        assertEquals(OBSERVATION, engine.stateOf(Variable.GLUCOSE))
    }

    @Test
    fun `fever with neutropenia raises one high-priority combination alert without changing colors`() {
        engine.send(measurement(Variable.NEUTROPHILS, 400.0, DAY))
        engine.send(measurement(Variable.BODY_TEMPERATURE, 38.5, DAY + 1))
        engine.send(measurement(Variable.BODY_TEMPERATURE, 38.6, 2 * DAY))
        val combined = engine.alerts.filter { it.combination }
        assertEquals(listOf(Combinations.NEUTROPENIC), combined.map { it.code })
        assertEquals(ALERT, combined.single().state)
    }

    @Test
    fun `habitat state never changes a crew member and stateless variables never change a group`() {
        for (minute in 0..5) engine.send(habitat(Variable.CO2, 8.0, minute * 60_000L))
        assertEquals(ALERT, engine.summaries.last { it.subjectId == "habitat" }.state)
        assertTrue(engine.summaries.none { it.subjectId == "crew-1" })

        engine.send(measurement(Variable.STRESS_PSS10, 30.0, DAY))
        assertNull(engine.stateOf(Variable.STRESS_PSS10))
        val neuro = engine.summaries.last { it.subjectId == "crew-1" && it.group == MeasurementGroup.NEUROLOGICAL_COGNITIVE_BEHAVIORAL }
        assertEquals(NOT_EVALUABLE, neuro.state)
        assertTrue(neuro.triggerVariable != Variable.STRESS_PSS10)
    }

    @Test
    fun `reset forgets every subject so day 1 measurements are processed again`() {
        engine.send(measurement(Variable.HEART_RATE, 140.0, 5 * DAY))
        assertEquals(1, engine.alerts.size)
        engine.send(measurement(Variable.HEART_RATE, 150.0, DAY)) // older than 5 days: discarded
        assertEquals(1, engine.alerts.size)

        engine.reset()
        engine.send(measurement(Variable.HEART_RATE, 150.0, 1_000))
        assertEquals(2, engine.alerts.size)
        assertEquals(ALERT, engine.stateOf(Variable.HEART_RATE))
    }

    @Test
    fun `a newer mission epoch resets the engine and older epochs are dropped, in any arrival order`() {
        engine.send(measurement(Variable.HEART_RATE, 140.0, 5 * DAY), epoch = 1)
        assertEquals(ALERT, engine.stateOf(Variable.HEART_RATE))

        // The new mission's first measurement arrives before any reset command.
        engine.send(measurement(Variable.HEART_RATE, 70.0, 8 * HOUR), epoch = 2)
        assertEquals(NORMAL, engine.stateOf(Variable.HEART_RATE))
        assertEquals("2", engine.stateRecordEpoch())

        // A delayed measurement of the old mission is ignored.
        val before = engine.states.size
        engine.send(measurement(Variable.HEART_RATE, 150.0, 9 * HOUR), epoch = 1)
        assertEquals(before, engine.states.size)
    }
}
