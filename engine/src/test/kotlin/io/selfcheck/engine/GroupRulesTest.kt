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

/** Rules of spec section 7 beyond plain thresholds: streaks, rhythm, sex, combined conditions, lab ranges, combinations. */
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
    fun `caloric intake - in-flight normal 80-110 percent, observation out of it 3 days in a row`() {
        engine.history(calories = 2000.0)
        engine.send(measurement(Variable.CALORIC_INTAKE, 1600.0, DAY)) // 80 %
        assertEquals(NORMAL, engine.stateOf(Variable.CALORIC_INTAKE))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.CALORIC_INTAKE))
        engine.send(measurement(Variable.CALORIC_INTAKE, 1580.0, 2 * DAY)) // 79 %
        assertEquals(NORMAL, engine.stateOf(Variable.CALORIC_INTAKE))
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.CALORIC_INTAKE))
        assertEquals("1", engine.state(Variable.CALORIC_INTAKE)!!.reason.params["count"])
        engine.send(measurement(Variable.CALORIC_INTAKE, 1400.0, 3 * DAY)) // 70 %, no symptoms: no alert
        assertEquals(NORMAL, engine.stateOf(Variable.CALORIC_INTAKE))
        engine.send(measurement(Variable.CALORIC_INTAKE, 2220.0, 4 * DAY)) // 111 %
        assertEquals(OBSERVATION, engine.stateOf(Variable.CALORIC_INTAKE))
        engine.send(measurement(Variable.CALORIC_INTAKE, 2200.0, 5 * DAY)) // 110 %
        assertEquals(NORMAL, engine.stateOf(Variable.CALORIC_INTAKE))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.CALORIC_INTAKE))
    }

    @Test
    fun `caloric intake - alert below 75 percent with fatigue, low mood or low glucose, without a streak`() {
        engine.history(calories = 2000.0)
        engine.send(measurement(Variable.GLUCOSE, 65.0, DAY, fasting = true))
        engine.send(measurement(Variable.CALORIC_INTAKE, 1400.0, DAY + 1)) // 70 %
        assertEquals(ALERT, engine.stateOf(Variable.CALORIC_INTAKE))
        assertEquals(ReasonCodes.CALORIC_LOW_WITH_FINDINGS, engine.codeOf(Variable.CALORIC_INTAKE))
        assertEquals("GLUCOSE", engine.state(Variable.CALORIC_INTAKE)!!.reason.params["findings"])

        engine.send(measurement(Variable.GLUCOSE, 90.0, 2 * DAY, fasting = true))
        engine.send(measurement(Variable.FATIGUE, 6.0, 2 * DAY + 1))
        engine.send(measurement(Variable.MOOD_PHQ9, 9.0, 2 * DAY + 2))
        engine.send(measurement(Variable.CALORIC_INTAKE, 1400.0, 2 * DAY + 3))
        assertEquals(NORMAL, engine.stateOf(Variable.CALORIC_INTAKE))
        engine.send(measurement(Variable.FATIGUE, 7.0, 3 * DAY))
        engine.send(measurement(Variable.CALORIC_INTAKE, 1400.0, 3 * DAY + 1))
        assertEquals(ALERT, engine.stateOf(Variable.CALORIC_INTAKE))
        assertEquals("FATIGUE", engine.state(Variable.CALORIC_INTAKE)!!.reason.params["findings"])
    }

    @Test
    fun `fluid intake - below target is normal with a notice, observation with dehydration or electrolyte signs`() {
        engine.history(fluids = 2500.0)
        engine.send(measurement(Variable.FLUID_INTAKE, 2600.0, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.FLUID_INTAKE))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.FLUID_INTAKE))
        engine.send(measurement(Variable.FLUID_INTAKE, 1200.0, 2 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.FLUID_INTAKE))
        assertEquals(ReasonCodes.FLUID_TARGET_NOT_MET, engine.codeOf(Variable.FLUID_INTAKE))

        engine.send(measurement(Variable.SODIUM, 134.0, 3 * DAY)) // low sodium does not count
        engine.send(measurement(Variable.FLUID_INTAKE, 1200.0, 3 * DAY + 1))
        assertEquals(ReasonCodes.FLUID_TARGET_NOT_MET, engine.codeOf(Variable.FLUID_INTAKE))
        engine.send(measurement(Variable.SODIUM, 147.0, 4 * DAY))
        engine.send(measurement(Variable.FLUID_INTAKE, 1200.0, 4 * DAY + 1))
        assertEquals(OBSERVATION, engine.stateOf(Variable.FLUID_INTAKE))
        assertEquals(ReasonCodes.FLUID_TARGET_WITH_FINDINGS, engine.codeOf(Variable.FLUID_INTAKE))
        assertEquals("SODIUM", engine.state(Variable.FLUID_INTAKE)!!.reason.params["findings"])

        engine.send(measurement(Variable.SODIUM, 140.0, 5 * DAY))
        engine.send(measurement(Variable.DIURESIS, 0.4, 5 * DAY + 1))
        engine.send(measurement(Variable.FLUID_INTAKE, 1200.0, 5 * DAY + 2))
        assertEquals(OBSERVATION, engine.stateOf(Variable.FLUID_INTAKE))
        assertEquals("DIURESIS", engine.state(Variable.FLUID_INTAKE)!!.reason.params["findings"])
    }

    @Test
    fun `body mass - in-flight normal up to 5 percent change, observation above 5 for 3 days, alert above 10`() {
        engine.history(bodyMass = 70.0)
        engine.send(measurement(Variable.BODY_MASS, 66.5, DAY)) // -5.0 %
        assertEquals(NORMAL, engine.stateOf(Variable.BODY_MASS))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.BODY_MASS))
        engine.send(measurement(Variable.BODY_MASS, 66.4, 2 * DAY)) // -5.14 %
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.BODY_MASS))
        engine.send(measurement(Variable.BODY_MASS, 66.4, 3 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.BODY_MASS))
        engine.send(measurement(Variable.BODY_MASS, 73.6, 4 * DAY)) // +5.14 %, third day in a row
        assertEquals(OBSERVATION, engine.stateOf(Variable.BODY_MASS))
        engine.send(measurement(Variable.BODY_MASS, 63.0, 5 * DAY)) // -10.0 %
        assertEquals(OBSERVATION, engine.stateOf(Variable.BODY_MASS))
        engine.send(measurement(Variable.BODY_MASS, 62.9, 6 * DAY)) // -10.14 %
        assertEquals(ALERT, engine.stateOf(Variable.BODY_MASS))
        assertEquals(ReasonCodes.HIGH, engine.codeOf(Variable.BODY_MASS))
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
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.UREA))
        engine.send(measurement(Variable.UREA, 58.0, 8 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.UREA))
    }

    @Test
    fun `urea - observation in one analysis with low urine output, creatinine out of range or fluid target not met`() {
        engine.send(measurement(Variable.DIURESIS, 0.4, DAY))
        engine.send(measurement(Variable.UREA, 55.0, DAY + 1))
        assertEquals(OBSERVATION, engine.stateOf(Variable.UREA))
        assertEquals(ReasonCodes.UREA_WITH_FINDINGS, engine.codeOf(Variable.UREA))
        assertEquals("DIURESIS", engine.state(Variable.UREA)!!.reason.params["findings"])
    }

    @Test
    fun `urine output - observation below 0,5, alert after 12 hours, 24 hours below 0,3 or 12 hours at zero`() {
        engine.send(measurement(Variable.DIURESIS, 1.0, HOUR))
        assertEquals(NORMAL, engine.stateOf(Variable.DIURESIS))
        for (hour in 2..12) {
            engine.send(measurement(Variable.DIURESIS, 0.4, hour * HOUR))
            assertEquals(OBSERVATION, engine.stateOf(Variable.DIURESIS))
        }
        engine.send(measurement(Variable.DIURESIS, 0.4, 13 * HOUR)) // 12 values in a row below 0,5
        assertEquals(ALERT, engine.stateOf(Variable.DIURESIS))
        assertEquals(ReasonCodes.DIURESIS_LOW_SUSTAINED, engine.codeOf(Variable.DIURESIS))
        assertEquals("12", engine.state(Variable.DIURESIS)!!.reason.params["hours"])

        engine.send(measurement(Variable.DIURESIS, 2.5, 14 * HOUR)) // above 2: no state
        assertNull(engine.stateOf(Variable.DIURESIS))
        assertEquals(ReasonCodes.NO_CUT, engine.codeOf(Variable.DIURESIS))

        for (hour in 15..38) engine.send(measurement(Variable.DIURESIS, 0.2, hour * HOUR))
        assertEquals("0.3", engine.state(Variable.DIURESIS)!!.reason.params["limit"])
        assertEquals("24", engine.state(Variable.DIURESIS)!!.reason.params["hours"])

        for (hour in 39..50) engine.send(measurement(Variable.DIURESIS, 0.0, hour * HOUR))
        assertEquals(ALERT, engine.stateOf(Variable.DIURESIS))
        assertEquals(ReasonCodes.DIURESIS_ANURIA, engine.codeOf(Variable.DIURESIS))
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
    fun `creatinine - observation out of range in one analysis, alert with blood pressure, urea or urine output`() {
        engine.send(measurement(Variable.CREATININE, 1.0, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.CREATININE))
        engine.send(measurement(Variable.CREATININE, 1.4, 8 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.CREATININE))
        assertEquals(ReasonCodes.HIGH, engine.codeOf(Variable.CREATININE))

        engine.send(measurement(Variable.SYSTOLIC_BP, 81.0, 15 * DAY))
        engine.send(measurement(Variable.CREATININE, 1.4, 15 * DAY + 1))
        assertEquals(ALERT, engine.stateOf(Variable.CREATININE))
        assertEquals(ReasonCodes.CREATININE_WITH_FINDINGS, engine.codeOf(Variable.CREATININE))
        assertEquals("SYSTOLIC_BP", engine.state(Variable.CREATININE)!!.reason.params["findings"])
    }

    @Test
    fun `creatinine - low blood pressure follows the in-flight low edges, below 82 systolic or 51 diastolic`() {
        engine.send(measurement(Variable.SYSTOLIC_BP, 82.0, DAY))
        engine.send(measurement(Variable.DIASTOLIC_BP, 51.0, DAY))
        engine.send(measurement(Variable.CREATININE, 1.4, DAY + 1))
        assertEquals(OBSERVATION, engine.stateOf(Variable.CREATININE))
        assertEquals(ReasonCodes.HIGH, engine.codeOf(Variable.CREATININE))

        engine.send(measurement(Variable.DIASTOLIC_BP, 50.0, 8 * DAY))
        engine.send(measurement(Variable.CREATININE, 1.4, 8 * DAY + 1))
        assertEquals(ALERT, engine.stateOf(Variable.CREATININE))
        assertEquals("DIASTOLIC_BP", engine.state(Variable.CREATININE)!!.reason.params["findings"])
    }

    @Test
    fun `creatinine - out of range with a rise of 0,3 mg per dL in 48 h`() {
        engine.send(measurement(Variable.CREATININE, 1.1, DAY))
        engine.send(measurement(Variable.CREATININE, 1.4, 2 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.CREATININE))
        assertEquals(ReasonCodes.CREATININE_RISE_48H, engine.codeOf(Variable.CREATININE))
    }

    @Test
    fun `creatinine - out of range at 1,5 times the lowest value of the last 7 days`() {
        engine.send(measurement(Variable.CREATININE, 0.8, DAY))
        engine.send(measurement(Variable.CREATININE, 0.9, 4 * DAY))
        engine.send(measurement(Variable.CREATININE, 1.4, 7 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.CREATININE))
        assertEquals(ReasonCodes.CREATININE_RISE_7D, engine.codeOf(Variable.CREATININE))
    }

    @Test
    fun `a rise that stays in range is normal`() {
        engine.send(measurement(Variable.CREATININE, 0.9, DAY))
        engine.send(measurement(Variable.CREATININE, 1.2, 2 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.CREATININE))
    }

    @Test
    fun `bone resorption markers - normal up to 1,5 times the lab upper limit, two analyses, alert with bone loss`() {
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 1.2, DAY, range = 0.1 to 0.8)) // 1,5 x 0,8
        assertEquals(NORMAL, engine.stateOf(Variable.BONE_RESORPTION_MARKERS))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 0.1, 2 * DAY, range = 0.1 to 0.8))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 1.21, 3 * DAY, range = 0.1 to 0.8))
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 0.09, 33 * DAY, range = 0.1 to 0.8))
        assertEquals(OBSERVATION, engine.stateOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.URINARY_CALCIUM, 340.0, 34 * DAY))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 1.25, 35 * DAY, range = 0.1 to 0.8))
        assertEquals(ALERT, engine.stateOf(Variable.BONE_RESORPTION_MARKERS))
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 1.0, 36 * DAY, range = 0.1 to 0.8))
        assertEquals(NORMAL, engine.stateOf(Variable.BONE_RESORPTION_MARKERS)) // inside the band even with high calcium
        engine.send(measurement(Variable.BONE_RESORPTION_MARKERS, 0.5, 37 * DAY)) // no laboratory range
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
        engine.send(measurement(Variable.GLUCOSE, 105.0, 2 * DAY, fasting = true))
        assertEquals(NORMAL, engine.stateOf(Variable.GLUCOSE))
        engine.send(measurement(Variable.GLUCOSE, 110.0, 3 * DAY, fasting = true))
        assertEquals(OBSERVATION, engine.stateOf(Variable.GLUCOSE))
        engine.send(measurement(Variable.GLUCOSE, 53.0, 4 * DAY, fasting = true))
        assertEquals(ALERT, engine.stateOf(Variable.GLUCOSE))
    }

    @Test
    fun `vomiting - observation for 1-2 episodes, alert from 3 or with nausea, abdominal pain or liquid stool`() {
        engine.send(measurement(Variable.VOMITING, 2.0, DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.VOMITING))
        engine.send(measurement(Variable.VOMITING, 3.0, 2 * DAY))
        assertEquals(ALERT, engine.stateOf(Variable.VOMITING))
        engine.send(measurement(Variable.NAUSEA, 7.0, 3 * DAY))
        engine.send(measurement(Variable.VOMITING, 1.0, 3 * DAY + 1))
        assertEquals(ALERT, engine.stateOf(Variable.VOMITING))
        assertEquals(ReasonCodes.VOMITING_WITH_FINDINGS, engine.codeOf(Variable.VOMITING))
        engine.send(measurement(Variable.NAUSEA, 2.0, 4 * DAY))
        engine.send(measurement(Variable.STOOL_FORM_BRISTOL, 7.0, 4 * DAY + 1))
        engine.send(measurement(Variable.VOMITING, 1.0, 4 * DAY + 2))
        assertEquals(ALERT, engine.stateOf(Variable.VOMITING))
        assertEquals("STOOL_FORM_BRISTOL", engine.state(Variable.VOMITING)!!.reason.params["findings"])
        assertEquals(OBSERVATION, engine.stateOf(Variable.STOOL_FORM_BRISTOL)) // liquid stool alone is observation
        engine.send(measurement(Variable.VOMITING, 0.0, 5 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.VOMITING))
    }

    @Test
    fun `sleep - one short night with high fatigue and low mood gives alert`() {
        engine.send(measurement(Variable.FATIGUE, 8.0, DAY))
        engine.send(measurement(Variable.SLEEP_HOURS, 5.5, DAY + 1))
        assertEquals(OBSERVATION, engine.stateOf(Variable.SLEEP_HOURS)) // fatigue alone is not enough
        engine.send(measurement(Variable.MOOD_PHQ9, 12.0, 2 * DAY))
        engine.send(measurement(Variable.SLEEP_HOURS, 5.5, 2 * DAY + 1))
        assertEquals(ALERT, engine.stateOf(Variable.SLEEP_HOURS))
        assertEquals(ReasonCodes.SLEEP_SHORT_WITH_FINDINGS, engine.codeOf(Variable.SLEEP_HOURS))
    }

    @Test
    fun `hematocrit and red blood cells - cuts depend on sex, high hematocrit has no state`() {
        engine.history("crew-1", sex = Sex.MALE)
        engine.history("crew-2", sex = Sex.FEMALE)
        engine.send(measurement(Variable.HEMATOCRIT, 40.0, DAY, subjectId = "crew-1"))
        engine.send(measurement(Variable.HEMATOCRIT, 40.0, DAY, subjectId = "crew-2"))
        assertEquals(OBSERVATION, engine.stateOf(Variable.HEMATOCRIT, "crew-1"))
        assertEquals(NORMAL, engine.stateOf(Variable.HEMATOCRIT, "crew-2"))
        engine.send(measurement(Variable.HEMATOCRIT, 52.0, 8 * DAY, subjectId = "crew-1"))
        assertNull(engine.stateOf(Variable.HEMATOCRIT, "crew-1"))

        engine.send(measurement(Variable.RED_BLOOD_CELLS, 4.45, 9 * DAY, subjectId = "crew-1"))
        assertEquals(OBSERVATION, engine.stateOf(Variable.RED_BLOOD_CELLS, "crew-1"))
        engine.send(measurement(Variable.RED_BLOOD_CELLS, 6.3, 16 * DAY, subjectId = "crew-1"))
        assertEquals(ALERT, engine.stateOf(Variable.RED_BLOOD_CELLS, "crew-1"))
        engine.send(measurement(Variable.RED_BLOOD_CELLS, 5.3, 2 * DAY, subjectId = "crew-2"))
        assertEquals(OBSERVATION, engine.stateOf(Variable.RED_BLOOD_CELLS, "crew-2"))
    }

    @Test
    fun `axillary temperature - alert below 35,0 (NASA hypothermia), observation 35,0-35,9, high side unchanged`() {
        val cases = listOf(34.9 to ALERT, 35.0 to OBSERVATION, 35.9 to OBSERVATION, 36.0 to NORMAL, 37.2 to NORMAL, 37.3 to OBSERVATION, 37.9 to OBSERVATION, 38.0 to ALERT)
        cases.forEachIndexed { day, (value, expected) ->
            engine.send(measurement(Variable.BODY_TEMPERATURE, value, (day + 1) * DAY))
            assertEquals(expected, engine.stateOf(Variable.BODY_TEMPERATURE), "$value")
        }
    }

    @Test
    fun `protein intake - normal 1,2-1,8 g per kg of pre-flight mass, observation out of it 3 days in a row`() {
        engine.history(bodyMass = 70.0) // 84-126 g
        engine.send(measurement(Variable.PROTEIN_INTAKE, 84.0, DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.PROTEIN_INTAKE))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.PROTEIN_INTAKE))
        assertEquals("1.2", engine.state(Variable.PROTEIN_INTAKE)!!.reason.params["evaluated"])
        engine.send(measurement(Variable.PROTEIN_INTAKE, 126.0, 2 * DAY))
        assertEquals(ReasonCodes.IN_RANGE, engine.codeOf(Variable.PROTEIN_INTAKE))

        engine.send(measurement(Variable.PROTEIN_INTAKE, 83.0, 3 * DAY)) // 1,19 g/kg
        assertEquals(NORMAL, engine.stateOf(Variable.PROTEIN_INTAKE))
        assertEquals(ReasonCodes.PERSISTENCE_PENDING, engine.codeOf(Variable.PROTEIN_INTAKE))
        assertEquals("1", engine.state(Variable.PROTEIN_INTAKE)!!.reason.params["count"])
        engine.send(measurement(Variable.PROTEIN_INTAKE, 127.0, 4 * DAY)) // 1,81 g/kg
        assertEquals(NORMAL, engine.stateOf(Variable.PROTEIN_INTAKE))
        engine.send(measurement(Variable.PROTEIN_INTAKE, 60.0, 5 * DAY))
        assertEquals(OBSERVATION, engine.stateOf(Variable.PROTEIN_INTAKE))
        assertEquals(ReasonCodes.LOW, engine.codeOf(Variable.PROTEIN_INTAKE))
        engine.send(measurement(Variable.PROTEIN_INTAKE, 100.0, 6 * DAY))
        assertEquals(NORMAL, engine.stateOf(Variable.PROTEIN_INTAKE))
    }

    @Test
    fun `protein intake - without the pre-flight body mass it is not evaluable`() {
        engine.send(measurement(Variable.PROTEIN_INTAKE, 100.0, DAY))
        assertEquals(NOT_EVALUABLE, engine.stateOf(Variable.PROTEIN_INTAKE))
        assertEquals(ReasonCodes.NO_REFERENCE, engine.codeOf(Variable.PROTEIN_INTAKE))
    }

    @Test
    fun `high heart rate with systolic below the in-flight edge of 82 raises the hypovolemia combination`() {
        engine.send(measurement(Variable.HEART_RATE, 110.0, 1_000))
        engine.send(measurement(Variable.SYSTOLIC_BP, 82.0, 2_000))
        assertTrue(engine.alerts.none { it.combination })
        engine.send(measurement(Variable.HEART_RATE, 110.0, 3_000))
        engine.send(measurement(Variable.SYSTOLIC_BP, 81.0, 4_000))
        assertEquals(listOf(Combinations.HYPOVOLEMIA), engine.alerts.filter { it.combination }.map { it.code })
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
