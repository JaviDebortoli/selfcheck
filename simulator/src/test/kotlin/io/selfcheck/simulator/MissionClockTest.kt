package io.selfcheck.simulator

import io.selfcheck.simulator.clock.MissionClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MissionClockTest {

    @Test
    fun `does not advance until started and while paused`() {
        val clock = MissionClock()
        assertEquals(0, clock.advance(1_000.0))
        clock.start()
        assertEquals(1_000, clock.advance(1_000.0))
        clock.pause()
        assertEquals(1_000, clock.advance(5_000.0))
    }

    @Test
    fun `acceleration multiplies elapsed real time`() {
        val clock = MissionClock()
        clock.start()
        clock.setSpeed(60.0)
        assertEquals(60_000, clock.advance(1_000.0))
        assertFailsWith<IllegalArgumentException> { clock.setSpeed(MissionClock.MAX_SPEED + 1) }
    }

    @Test
    fun `reset goes back to the start, paused and at real-time speed`() {
        val clock = MissionClock()
        clock.start()
        clock.setSpeed(10.0)
        clock.advance(1_000.0)
        clock.reset()
        assertEquals(0, clock.missionTimeMs)
        assertEquals(1.0, clock.speed)
        assertEquals(false, clock.running)
    }
}
