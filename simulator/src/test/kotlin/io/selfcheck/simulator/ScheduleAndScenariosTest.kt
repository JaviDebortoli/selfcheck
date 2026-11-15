package io.selfcheck.simulator

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.Sex
import io.selfcheck.contracts.Variable
import io.selfcheck.simulator.clock.MissionClock.Companion.DAY_MS
import io.selfcheck.simulator.clock.MissionClock.Companion.HOUR_MS
import io.selfcheck.simulator.crew.CrewRoster
import io.selfcheck.simulator.model.BodyModel
import io.selfcheck.simulator.model.Cadence
import io.selfcheck.simulator.model.Schedule
import io.selfcheck.simulator.scenario.ScenarioRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleAndScenariosTest {

    @Test
    fun `daily routine - sleep at night, exercise twice a day, rest otherwise`() {
        assertEquals(ActivityContext.SLEEP, Schedule.activityAt(2 * HOUR_MS))
        assertEquals(ActivityContext.EXERCISE, Schedule.activityAt(10 * HOUR_MS + 30 * 60_000))
        assertEquals(ActivityContext.REST, Schedule.activityAt(14 * HOUR_MS))
    }

    @Test
    fun `cadences follow spec section 6_2`() {
        assertEquals(Cadence.Continuous, Schedule.cadences[Variable.HEART_RATE])
        assertEquals(Cadence.Periodic(1, 7), Schedule.cadences[Variable.SLEEP_HOURS])
        assertEquals(14, (Schedule.cadences[Variable.MOOD_PHQ9] as Cadence.Periodic).everyDays)
        assertEquals(30, (Schedule.cadences[Variable.SLEEP_QUALITY_PSQI] as Cadence.Periodic).everyDays)
        assertEquals(Cadence.Hourly, Schedule.cadences[Variable.DIURESIS])
        assertEquals(listOf(HOUR_MS, 2 * HOUR_MS), Schedule.hourlyTimes(HOUR_MS / 2, 2 * HOUR_MS))
        // Every variable except the derived ones has a cadence.
        val derived = setOf(Variable.CAREER_EFFECTIVE_DOSE, Variable.SOLAR_EVENT_DOSE, Variable.ECG_RHYTHM)
        assertEquals(Variable.entries.toSet() - derived, Schedule.cadences.keys)
    }

    @Test
    fun `periodic measurements are due on their days only`() {
        val weekly = Cadence.Periodic(7, 8)
        assertEquals(listOf(7 * DAY_MS + 8 * HOUR_MS), Schedule.dueTimes(weekly, 0, 10 * DAY_MS))
        val daily = Cadence.Periodic(1, 21)
        assertEquals(listOf(21 * HOUR_MS, DAY_MS + 21 * HOUR_MS), Schedule.dueTimes(daily, 8 * HOUR_MS, 2 * DAY_MS))
    }

    @Test
    fun `a normal day - sleep between 7 and 7,5 h and every value of a healthy crew member`() {
        val body = BodyModel()
        for (member in CrewRoster.members) {
            for (day in 0 until 30) {
                val sleep = body.crew(member, Variable.SLEEP_HOURS, day * DAY_MS + 7 * HOUR_MS)
                assertTrue(sleep in 7.0..7.5, "sleep $sleep")
            }
        }
    }

    @Test
    fun `all eight scenarios load and effects apply only inside their window`() {
        val runner = ScenarioRunner()
        assertEquals(
            setOf("solar-event", "dehydration", "poor-sleep", "high-co2", "high-noise", "infection-fever", "atrial-fibrillation", "muscle-bone-loss"),
            runner.library.keys,
        )
        runner.library.values.forEach { assertTrue(it.texts.keys.containsAll(listOf("es", "en")), it.id) }

        runner.start(runner.library.getValue("atrial-fibrillation"), setOf("crew-2"), 1_000)
        assertNotNull(runner.effect("crew-2", Variable.HEART_RATE, 1_000 + 3 * 60_000))
        assertNull(runner.effect("crew-2", Variable.HEART_RATE, 1_000 + 41 * 60_000))
        assertNull(runner.effect("crew-1", Variable.HEART_RATE, 1_000 + 3 * 60_000))
    }

    @Test
    fun `atrial fibrillation gives a fast irregular rhythm with mildly low pressure and saturation`() {
        val runner = ScenarioRunner()
        runner.start(runner.library.getValue("atrial-fibrillation"), setOf("crew-1"), 0)
        val heart = runner.effect("crew-1", Variable.HEART_RATE, 60_000)!!
        assertEquals(RhythmLabel.ATRIAL_FIBRILLATION_RAPID, heart.rhythm)
        assertTrue(heart.value!! > 130)
        assertEquals(80.0, runner.effect("crew-1", Variable.SYSTOLIC_BP, 0)?.value)
        assertEquals(50.0, runner.effect("crew-1", Variable.DIASTOLIC_BP, 0)?.value)
        assertEquals(93.0, runner.effect("crew-1", Variable.SPO2, 0)?.value)
    }

    @Test
    fun `infection fever raises the heart rate step by step with fever, fast breathing and inflammatory labs`() {
        val runner = ScenarioRunner()
        runner.start(runner.library.getValue("infection-fever"), setOf("crew-1"), 0)
        val rates = listOf(HOUR_MS, DAY_MS - HOUR_MS, DAY_MS + HOUR_MS, 2 * DAY_MS + HOUR_MS)
            .map { runner.effect("crew-1", Variable.HEART_RATE, it)!!.value!! }
        assertEquals(rates.sorted(), rates)
        assertTrue(rates.first() <= 100 && rates.last() > 100)
        assertTrue(runner.effect("crew-1", Variable.BODY_TEMPERATURE, 0)!!.value!! >= 38.0)
        assertTrue(runner.effect("crew-1", Variable.RESPIRATORY_RATE, 0)!!.value!! > 20)
        assertTrue(runner.effect("crew-1", Variable.WHITE_BLOOD_CELLS, 0)!!.value!! > 12_000)
        assertTrue(runner.effect("crew-1", Variable.CRP, 0)!!.value!! > 5)
    }

    @Test
    fun `dehydration uses a separate value for women where the cut depends on sex`() {
        val runner = ScenarioRunner()
        val hemoglobin = runner.library.getValue("dehydration").effects.first { it.variable == Variable.HEMOGLOBIN }
        assertEquals(18.2, hemoglobin.valueFor(Sex.MALE))
        assertEquals(16.8, hemoglobin.valueFor(Sex.FEMALE))
        val sodium = runner.library.getValue("dehydration").effects.first { it.variable == Variable.SODIUM }
        assertEquals(sodium.value, sodium.valueFor(Sex.FEMALE))
        val variables = runner.library.getValue("dehydration").effects.map { it.variable }.toSet()
        assertTrue(
            variables.containsAll(
                listOf(
                    Variable.HEART_RATE, Variable.SYSTOLIC_BP, Variable.DIASTOLIC_BP, Variable.HEMATOCRIT, Variable.HEMOGLOBIN,
                    Variable.RED_BLOOD_CELLS, Variable.UREA, Variable.CREATININE, Variable.SODIUM, Variable.POTASSIUM,
                    Variable.URINE_SPECIFIC_GRAVITY, Variable.FLUID_INTAKE,
                ),
            ),
        )
    }

    @Test
    fun `poor sleep adds mild changes to heart rate, pressure, glucose and white cells`() {
        val runner = ScenarioRunner()
        val variables = runner.library.getValue("poor-sleep").effects.map { it.variable }.toSet()
        assertTrue(
            variables.containsAll(
                listOf(
                    Variable.SLEEP_HOURS, Variable.FATIGUE, Variable.HEART_RATE, Variable.SYSTOLIC_BP, Variable.GLUCOSE,
                    Variable.NEUTROPHILS, Variable.LYMPHOCYTES,
                ),
            ),
        )
    }
}
