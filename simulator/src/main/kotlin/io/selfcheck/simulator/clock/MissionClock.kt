package io.selfcheck.simulator.clock

import org.springframework.stereotype.Component

/**
 * Mission clock (spec section 5): milliseconds since mission start.
 * Starts at real-time speed, can be accelerated or paused; while paused, mission time does not advance.
 */
@Component
class MissionClock {

    @Volatile
    private var time: Long = 0

    @Volatile
    private var rate: Double = 1.0

    @Volatile
    private var active: Boolean = false

    val missionTimeMs: Long get() = time
    val speed: Double get() = rate
    val running: Boolean get() = active

    /** Advances mission time by the elapsed real time multiplied by the speed. Returns the new mission time. */
    @Synchronized
    fun advance(elapsedRealMs: Double): Long {
        if (active && elapsedRealMs > 0) time += (elapsedRealMs * speed).toLong()
        return missionTimeMs
    }

    fun start() {
        active = true
    }

    fun pause() {
        active = false
    }

    fun setSpeed(value: Double) {
        require(value in MIN_SPEED..MAX_SPEED) { "Speed must be between $MIN_SPEED and $MAX_SPEED" }
        rate = value
    }

    /** Back to day 1 (spec section 5, reset command). */
    @Synchronized
    fun reset(startAtMs: Long = 0) {
        active = false
        time = startAtMs
        rate = 1.0
    }

    companion object {
        const val MIN_SPEED = 1.0

        /** One mission hour per real second. */
        const val MAX_SPEED = 3600.0
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}
