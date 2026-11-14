package io.selfcheck.simulator.control

import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.Variable
import io.selfcheck.simulator.SimulationLoop
import io.selfcheck.simulator.clock.MissionClock
import io.selfcheck.simulator.crew.CrewRoster
import io.selfcheck.simulator.scenario.ScenarioRunner
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Control API of the simulator (spec sections 8.4 and 9): start, pause, speed, scenarios, reset and environment. */
@RestController
@RequestMapping("/api/simulator")
class ControlController(
    private val clock: MissionClock,
    private val loop: SimulationLoop,
    private val overrides: Overrides,
    private val scenarios: ScenarioRunner,
    private val resetService: ResetService,
) {

    data class SpeedRequest(val speed: Double)
    data class EnvironmentRequest(val environment: MissionEnvironment)
    data class ScenarioRequest(val crewMemberId: String? = null)
    data class OverrideRequest(val subjectId: String, val variable: Variable, val value: Double)

    @GetMapping("/status")
    fun status(): Map<String, Any> = mapOf(
        "missionTimeMs" to clock.missionTimeMs,
        "running" to clock.running,
        "speed" to clock.speed,
        "maxSpeed" to MissionClock.MAX_SPEED,
        "environment" to loop.environment.name,
        "plannedDurationDays" to loop.plannedDurationDays,
        "crew" to CrewRoster.members.map { it.id },
        "activeScenarios" to scenarios.active(clock.missionTimeMs).map {
            mapOf("id" to it.definition.id, "subjects" to it.subjects, "startedAt" to it.startedAt, "endsAt" to it.endsAt())
        },
        "overrides" to overrides.all(),
    )

    /** Every scripted scenario with its texts in Spanish and English. */
    @GetMapping("/scenarios")
    fun scenarios(): List<Map<String, Any>> = scenarios.library.values.map {
        mapOf("id" to it.id, "target" to it.target.name, "texts" to it.texts, "durationMs" to it.duration.toMillis())
    }

    @PostMapping("/start")
    fun start(): Map<String, Any> {
        clock.start()
        return status()
    }

    @PostMapping("/pause")
    fun pause(): Map<String, Any> {
        clock.pause()
        return status()
    }

    @PutMapping("/speed")
    fun speed(@RequestBody request: SpeedRequest): Map<String, Any> {
        clock.setSpeed(request.speed)
        return status()
    }

    @PutMapping("/environment")
    fun environment(@RequestBody request: EnvironmentRequest): Map<String, Any> {
        loop.changeEnvironment(request.environment)
        return status()
    }

    /** Starts a scenario at the current mission time; it does not reset the mission. */
    @PostMapping("/scenarios/{id}")
    fun trigger(@PathVariable id: String, @RequestBody(required = false) request: ScenarioRequest?): Map<String, Any> {
        loop.triggerScenario(id, request?.crewMemberId)
        return status()
    }

    /** Clears the mission data everywhere and starts again from day 1. */
    @PostMapping("/reset")
    fun reset(): Map<String, Any> {
        resetService.reset()
        return status()
    }

    /** Forces a value for a subject until overrides are cleared (manual testing). */
    @PostMapping("/overrides")
    fun override(@RequestBody request: OverrideRequest): Map<String, Any> {
        overrides.set(request.subjectId, request.variable, request.value)
        return status()
    }

    @DeleteMapping("/overrides")
    fun clearOverrides(): Map<String, Any> {
        overrides.clear()
        return status()
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: IllegalArgumentException): Map<String, String?> = mapOf("error" to e.message)
}
