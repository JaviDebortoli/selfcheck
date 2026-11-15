package io.selfcheck.simulator

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.BoneResorptionMarker
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.Variable
import io.selfcheck.knowledge.RuleCatalog
import io.selfcheck.simulator.clock.MissionClock
import io.selfcheck.simulator.control.Overrides
import io.selfcheck.simulator.crew.CrewActivity
import io.selfcheck.simulator.crew.CrewMember
import io.selfcheck.simulator.crew.CrewRoster
import io.selfcheck.simulator.mission.MissionSettings
import io.selfcheck.simulator.model.BodyModel
import io.selfcheck.simulator.model.Cadence
import io.selfcheck.simulator.model.Schedule
import io.selfcheck.simulator.publish.MeasurementPublisher
import io.selfcheck.simulator.scenario.ActiveScenario
import io.selfcheck.simulator.scenario.ScenarioRunner
import io.selfcheck.simulator.scenario.ScenarioTarget
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/**
 * Drives the mission: advances the clock every tick and emits every measurement due in that span of mission time
 * (spec sections 6.2 and 9). The simulator stands in for the ship's and suit's measurement systems.
 *
 * Continuous signals are sampled every mission second at real-time speed; when the clock is accelerated the
 * spacing grows with the speed, capped at [MAX_CONTINUOUS_STEP_MS] so they never reach their 1-minute expiry.
 */
@Component
class SimulationLoop(
    private val clock: MissionClock,
    private val body: BodyModel,
    private val scenarios: ScenarioRunner,
    private val overrides: Overrides,
    private val crewActivity: CrewActivity,
    private val publisher: MeasurementPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val units = RuleCatalog.load().variables.mapValues { it.value.unit }

    /** Effective dose accumulated during the mission per crew member, mSv. */
    private val missionDose = ConcurrentHashMap<String, Double>()
    private var lastTickNanos = System.nanoTime()
    private var nextContinuousAt = MissionSettings.START_TIME_MS
    private var nextMinuteAt = MissionSettings.START_TIME_MS

    /** Day 1: medical histories and an initial round of every daily, hourly and periodic measurement. */
    @Synchronized
    fun startMission() {
        val now = clock.missionTimeMs
        CrewRoster.members.forEach { publisher.publishHistory(it, now) }
        val periodic = Schedule.cadences.filterValues { it is Cadence.Periodic || it == Cadence.Hourly }.keys
        for (variable in periodic) subjectsOf(variable).forEach { emit(it, variable, now) }
        // The habitat log already holds last night's noise mean, so the habitat is not grey all of day 1.
        emit(Topics.HABITAT_ID, Variable.NOISE_NIGHT, now, anyHour = true)
        publisher.flush()
        clock.start()
        log.info("Mission started at mission time {}", now)
    }

    /** Forgets the running mission (spec section 5, reset). The clock is reset by the caller. */
    @Synchronized
    fun clearMission() {
        missionDose.clear()
        scenarios.clear()
        overrides.clear()
        crewActivity.clear()
        body.reset()
        nextContinuousAt = MissionSettings.START_TIME_MS
        nextMinuteAt = MissionSettings.START_TIME_MS
    }

    /** Starts a scenario at the current mission time; it never resets the mission (spec section 5). */
    @Synchronized
    fun triggerScenario(id: String, crewMemberId: String?): ActiveScenario {
        val definition = scenarios.library[id] ?: throw IllegalArgumentException("Unknown scenario $id")
        val subjects = when (definition.target) {
            ScenarioTarget.HABITAT -> setOf(Topics.HABITAT_ID)
            ScenarioTarget.ALL_CREW -> CrewRoster.members.map { it.id }.toSet()
            ScenarioTarget.CREW_MEMBER -> setOf(crewMemberId ?: CrewRoster.members.first().id).also { ids ->
                require(ids.all { id -> CrewRoster.members.any { it.id == id } }) { "Unknown crew member $crewMemberId" }
            }
        }
        val now = clock.missionTimeMs
        val active = scenarios.start(definition, subjects, now)
        for (subject in subjects) definition.measureNow.forEach { emit(subject, it, now) }
        log.info("Scenario {} started for {} at {}", id, subjects, now)
        return active
    }

    /** Real time is used only to pace the loop; every decision downstream uses mission time. */
    @Scheduled(fixedRate = TICK_MS)
    @Synchronized
    fun tick() {
        val nowNanos = System.nanoTime()
        val elapsedRealMs = (nowNanos - lastTickNanos) / 1_000_000.0
        lastTickNanos = nowNanos
        if (!clock.running) return

        val before = clock.missionTimeMs
        val after = clock.advance(elapsedRealMs)
        if (after <= before) return
        accumulateDose(before, after)

        // Collect everything due in this span and send it in mission-time order: the engine discards any
        // measurement older than one it already processed for the same subject (spec section 5).
        val due = mutableListOf<Pair<Long, Variable>>()
        val step = (after - before).coerceIn(MIN_CONTINUOUS_STEP_MS, MAX_CONTINUOUS_STEP_MS)
        val continuous = Schedule.cadences.filterValues { it == Cadence.Continuous }.keys
        while (nextContinuousAt <= after) {
            continuous.forEach { due += nextContinuousAt to it }
            nextContinuousAt += step
        }
        val perMinute = Schedule.cadences.filterValues { it == Cadence.EveryMinute }.keys
        while (nextMinuteAt <= after) {
            perMinute.forEach { due += nextMinuteAt to it }
            nextMinuteAt += MINUTE_MS
        }
        for ((variable, cadence) in Schedule.cadences) {
            when (cadence) {
                is Cadence.Periodic -> Schedule.dueTimes(cadence, before, after).forEach { due += it to variable }
                Cadence.Hourly -> Schedule.hourlyTimes(before, after).forEach { due += it to variable }
                else -> Unit
            }
        }
        for ((t, variable) in due.sortedBy { it.first }) subjectsOf(variable).forEach { emit(it, variable, t) }
    }

    private fun subjectsOf(variable: Variable): List<String> =
        if (variable in Schedule.habitatVariables) listOf(Topics.HABITAT_ID) else CrewRoster.members.map { it.id }

    /** Integrates each crew member's dose rate (µSv/h) over the tick. */
    private fun accumulateDose(from: Long, to: Long) {
        val middle = from + (to - from) / 2
        for (member in CrewRoster.members) {
            val rate = value(member.id, Variable.DOSE_RATE, middle)
            missionDose.merge(member.id, rate * (to - from) / HOUR_MS / 1000.0, Double::plus)
        }
    }

    /** Value of a variable: forced value, else the running scenario's effect, else the body or habitat model. */
    private fun value(subjectId: String, variable: Variable, t: Long): Double {
        overrides.get(subjectId, variable)?.let { return it }
        val member = CrewRoster.members.firstOrNull { it.id == subjectId }
        scenarios.effect(subjectId, variable, t)?.let { effect ->
            effect.value?.let { return it }
            val reference = member?.let { body.reference(it, variable) }
            if (effect.percentOfReference != null && reference != null) {
                return round(reference * effect.percentOfReference * 10) / 1000
            }
        }
        return when {
            variable == Variable.MISSION_EFFECTIVE_DOSE -> round((missionDose[subjectId] ?: 0.0) * 1000) / 1000
            variable == Variable.ABSORBED_DOSE -> round((missionDose[subjectId] ?: 0.0) * 0.4 * 1000) / 1000
            member != null -> body.crew(member, variable, t, activityOf(subjectId, t))
            else -> body.habitat(variable, t)
        }
    }

    private fun emit(subjectId: String, variable: Variable, t: Long, anyHour: Boolean = false) {
        // Day and night noise are separate variables (spec section 6.1), each measured in its own hours.
        if (!anyHour && variable == Variable.NOISE_DAY && !Schedule.isDaytime(t)) return
        if (!anyHour && variable == Variable.NOISE_NIGHT && Schedule.isDaytime(t)) return

        val effect = scenarios.effect(subjectId, variable, t)
        val context = when (variable) {
            Variable.HEART_RATE, Variable.SPO2, Variable.RESPIRATORY_RATE -> activityOf(subjectId, t)
            Variable.SYSTOLIC_BP, Variable.DIASTOLIC_BP, Variable.BODY_TEMPERATURE, Variable.GLUCOSE -> ActivityContext.REST
            else -> null
        }
        val signs = Schedule.alarmSigns[variable].orEmpty().associateWith { it in effect?.signs.orEmpty() }
        publisher.publish(
            subjectId = subjectId,
            variable = variable,
            value = value(subjectId, variable, t),
            unit = units[variable].orEmpty(),
            missionTimeMs = t,
            context = context,
            fasting = if (variable == Variable.GLUCOSE) true else null,
            alarmSigns = signs,
            rhythm = if (variable == Variable.HEART_RATE) effect?.rhythm ?: RhythmLabel.SINUS else null,
            boneMarker = if (variable == Variable.BONE_RESORPTION_MARKERS) BoneResorptionMarker.CTX else null,
            referenceRange = if (variable == Variable.BONE_RESORPTION_MARKERS) CTX_RANGE else null,
        )
    }

    /** Daily routine, unless the crew member started a training session from the quick view. */
    private fun activityOf(subjectId: String, t: Long): ActivityContext =
        if (crewActivity.isTraining(subjectId)) ActivityContext.EXERCISE else Schedule.activityAt(t)

    fun crewMember(id: String): CrewMember? = CrewRoster.members.firstOrNull { it.id == id }

    companion object {
        const val TICK_MS = 200L
        const val MIN_CONTINUOUS_STEP_MS = 1_000L
        const val MAX_CONTINUOUS_STEP_MS = 30_000L
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000.0

        /** Laboratory reference range for serum CTX, ng/mL (simulated laboratory). */
        val CTX_RANGE = 0.1 to 0.6
    }
}
