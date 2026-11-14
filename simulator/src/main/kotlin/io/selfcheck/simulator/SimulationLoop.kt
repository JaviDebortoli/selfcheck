package io.selfcheck.simulator

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.Variable
import io.selfcheck.simulator.clock.MissionClock
import io.selfcheck.simulator.control.Overrides
import io.selfcheck.simulator.crew.CrewRoster
import io.selfcheck.simulator.crew.VitalsModel
import io.selfcheck.simulator.publish.MeasurementPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drives the mission: advances the clock every tick and emits the measurements due in that span of mission time.
 *
 * Continuous signals are sampled every mission second at real-time speed. When the clock is accelerated the
 * spacing grows with the speed, capped at [MAX_CONTINUOUS_STEP_MS] so they never reach their 1-minute expiry.
 */
@Component
class SimulationLoop(
    private val clock: MissionClock,
    private val vitals: VitalsModel,
    private val overrides: Overrides,
    private val publisher: MeasurementPublisher,
    @Value("\${selfcheck.simulator.autostart:true}") private val autostart: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var currentEnvironment: MissionEnvironment = MissionEnvironment.ORBITAL_STATION

    val environment: MissionEnvironment get() = currentEnvironment
    val plannedDurationDays: Int get() = DEFAULT_DURATION_DAYS

    private var lastTickNanos = System.nanoTime()
    private var nextContinuousAt = 0L

    @EventListener(ApplicationReadyEvent::class)
    fun startMission() {
        publishMissionStart()
        if (autostart) clock.start()
        log.info("Mission ready in {} (autostart={})", environment, autostart)
    }

    fun publishMissionStart() {
        val now = clock.missionTimeMs
        publisher.publishProfile(environment, plannedDurationDays, now)
        CrewRoster.members.forEach { publisher.publishHistory(it, now) }
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

        val step = (after - before).coerceIn(MIN_CONTINUOUS_STEP_MS, MAX_CONTINUOUS_STEP_MS)
        while (nextContinuousAt <= after) {
            emitContinuous(nextContinuousAt)
            nextContinuousAt += step
        }
    }

    private fun emitContinuous(t: Long) {
        for (member in CrewRoster.members) {
            val hr = overrides.get(member.id, Variable.HEART_RATE) ?: vitals.sample(member, Variable.HEART_RATE)
            val spo2 = overrides.get(member.id, Variable.SPO2) ?: vitals.sample(member, Variable.SPO2)
            val rr = overrides.get(member.id, Variable.RESPIRATORY_RATE) ?: vitals.sample(member, Variable.RESPIRATORY_RATE)
            publisher.publish(member.id, Variable.HEART_RATE, hr, "bpm", t, context = ActivityContext.REST, rhythm = RhythmLabel.SINUS)
            publisher.publish(member.id, Variable.SPO2, spo2, "%", t, context = ActivityContext.REST)
            publisher.publish(member.id, Variable.RESPIRATORY_RATE, rr, "rpm", t, context = ActivityContext.REST)
        }
    }

    companion object {
        const val TICK_MS = 200L
        const val MIN_CONTINUOUS_STEP_MS = 1_000L
        const val MAX_CONTINUOUS_STEP_MS = 30_000L
        const val DEFAULT_DURATION_DAYS = 180
    }
}
