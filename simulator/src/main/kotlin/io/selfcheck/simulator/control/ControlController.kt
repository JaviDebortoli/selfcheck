package io.selfcheck.simulator.control

import io.selfcheck.contracts.Variable
import io.selfcheck.simulator.SimulationLoop
import io.selfcheck.simulator.clock.MissionClock
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Control API of the simulator (spec section 9). */
@RestController
@RequestMapping("/api/simulator")
class ControlController(
    private val clock: MissionClock,
    private val loop: SimulationLoop,
    private val overrides: Overrides,
) {

    data class SpeedRequest(val speed: Double)
    data class OverrideRequest(val subjectId: String, val variable: Variable, val value: Double)

    @GetMapping("/status")
    fun status(): Map<String, Any> = mapOf(
        "missionTimeMs" to clock.missionTimeMs,
        "running" to clock.running,
        "speed" to clock.speed,
        "maxSpeed" to MissionClock.MAX_SPEED,
        "environment" to loop.environment.name,
        "plannedDurationDays" to loop.plannedDurationDays,
        "overrides" to overrides.all(),
    )

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

    /** Forces a value for a subject until overrides are cleared. */
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
