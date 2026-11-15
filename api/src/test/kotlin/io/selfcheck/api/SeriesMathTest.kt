package io.selfcheck.api

import io.selfcheck.api.history.SeriesMath
import io.selfcheck.api.history.StateChange
import io.selfcheck.api.history.StatePeriod
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesMathTest {

    @Test
    fun `bucket width gives about 200 buckets whatever the range, never below 1 ms`() {
        val day = 86_400_000L
        assertEquals(43_200_000L, SeriesMath.bucketMs(0, 100 * day))
        assertEquals(36_000L, SeriesMath.bucketMs(0, 7_200_000))
        assertEquals(1L, SeriesMath.bucketMs(1_000, 1_050))
    }

    @Test
    fun `periods start with the state in force before the range and end at the range end`() {
        val changes = listOf(
            StateChange(0, "NORMAL"),
            StateChange(500, "OBSERVATION"),
            StateChange(800, "NORMAL"),
        )
        assertEquals(
            listOf(StatePeriod(300, 500, "NORMAL"), StatePeriod(500, 800, "OBSERVATION"), StatePeriod(800, 1_000, "NORMAL")),
            SeriesMath.periods(changes, 300, 1_000),
        )
        assertEquals(listOf(StatePeriod(550, 700, "OBSERVATION")), SeriesMath.periods(changes, 550, 700))
    }

    @Test
    fun `changes that keep the state are merged and a change to no state leaves a gap`() {
        val changes = listOf(
            StateChange(0, "NORMAL"),
            StateChange(100, "NORMAL"),
            StateChange(200, null),
            StateChange(300, "ALERT"),
        )
        assertEquals(
            listOf(StatePeriod(0, 200, "NORMAL"), StatePeriod(300, 400, "ALERT")),
            SeriesMath.periods(changes, 0, 400),
        )
    }

    @Test
    fun `no changes or a range before the first change give no periods`() {
        assertEquals(emptyList(), SeriesMath.periods(emptyList(), 0, 1_000))
        assertEquals(emptyList(), SeriesMath.periods(listOf(StateChange(500, "NORMAL")), 0, 400))
    }
}
