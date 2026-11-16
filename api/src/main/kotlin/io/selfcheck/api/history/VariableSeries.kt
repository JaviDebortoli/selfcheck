package io.selfcheck.api.history

/**
 * History of one variable for the control center chart: values grouped in time buckets and the periods of each
 * state the engine computed. [points] is empty for variables without values of their own (ECG rhythm, solar event
 * dose): the chart then shows only their state.
 */
data class VariableSeries(
    val fromMs: Long,
    val toMs: Long,
    val bucketMs: Long,
    val points: List<SeriesPoint>,
    val states: List<StatePeriod>,
)

/** One time bucket: mean time of its measurements, mean, minimum, maximum and count. */
data class SeriesPoint(val t: Long, val avg: Double, val min: Double, val max: Double, val n: Int) {
    fun shifted(offset: Double) = copy(avg = avg + offset, min = min + offset, max = max + offset)
}

/** The variable held [state] from [fromMs] to [toMs]. */
data class StatePeriod(val fromMs: Long, val toMs: Long, val state: String)

/** A state change as stored in variable_state ([state] null: a variable without state). */
data class StateChange(val missionTimeMs: Long, val state: String?)

object SeriesMath {
    /** Buckets per request: enough detail for a chart, whatever the length of the range. */
    const val TARGET_POINTS = 200

    /** Bucket width so that [fromMs, toMs] gives about [TARGET_POINTS] buckets (at least 1 ms). */
    fun bucketMs(fromMs: Long, toMs: Long): Long = maxOf(1L, (toMs - fromMs + TARGET_POINTS - 1) / TARGET_POINTS)

    /**
     * Periods of each state inside [fromMs, toMs] from the ordered state changes. The state in force at [fromMs] is
     * the last change before it; consecutive changes with the same state (only the reason changed) are merged, and a
     * change to "no state" ends the period without starting another.
     */
    fun periods(changes: List<StateChange>, fromMs: Long, toMs: Long): List<StatePeriod> {
        val sorted = changes.sortedBy { it.missionTimeMs }
        val result = mutableListOf<StatePeriod>()
        for ((i, change) in sorted.withIndex()) {
            val state = change.state ?: continue
            val start = maxOf(change.missionTimeMs, fromMs)
            val end = minOf(sorted.getOrNull(i + 1)?.missionTimeMs ?: toMs, toMs)
            if (end <= start) continue
            val last = result.lastOrNull()
            if (last != null && last.state == state && last.toMs == start) {
                result[result.lastIndex] = last.copy(toMs = end)
            } else {
                result += StatePeriod(start, end, state)
            }
        }
        return result
    }
}
