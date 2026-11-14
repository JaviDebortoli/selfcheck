package io.selfcheck.simulator

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.MissionEnvironment
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
                val sleep = body.crew(member, Variable.SLEEP_HOURS, day * DAY_MS + 7 * HOUR_MS, MissionEnvironment.ORBITAL_STATION)
                assertTrue(sleep in 7.0..7.5, "sleep $sleep")
            }
        }
    }

    @Test
    fun `all eight scenarios load and effects apply only inside their window`() {
        val runner = ScenarioRunner()
        assertEquals(
            setOf("solar-event", "dehydration", "poor-sleep", "high-co2", "high-noise", "fever-crp", "arrhythmia", "muscle-bone-loss"),
            runner.library.keys,
        )
        runner.library.values.forEach { assertTrue(it.texts.keys.containsAll(listOf("es", "en")), it.id) }

        runner.start(runner.library.getValue("arrhythmia"), setOf("crew-2"), 1_000)
        assertNull(runner.effect("crew-2", Variable.HEART_RATE, 1_000 + 60_000))
        assertNotNull(runner.effect("crew-2", Variable.HEART_RATE, 1_000 + 3 * 60_000))
        assertNull(runner.effect("crew-1", Variable.HEART_RATE, 1_000 + 3 * 60_000))
    }
}
