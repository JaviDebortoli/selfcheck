package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.knowledge.Condition
import io.selfcheck.knowledge.Direction
import io.selfcheck.knowledge.VariableDefinition

/** Threshold classification shared by the generic rules and the custom evaluators. */
object Classifier {

    private val checkOrder = listOf(HealthState.ALERT, HealthState.OBSERVATION, HealthState.NORMAL)

    /**
     * First matching state, checked in order ALERT, OBSERVATION, NORMAL. A state with a streak requirement only
     * applies when the condition held for that many consecutive measurements; otherwise the next state is checked.
     * Out of range with an incomplete streak is NORMAL with a PERSISTENCE_PENDING reason (user decision).
     */
    fun classify(
        definition: VariableDefinition,
        thresholds: Map<HealthState, List<String>>,
        value: Double,
        params: Map<String, String>,
        toConfirm: Boolean,
        now: Long,
        samples: List<Sample>,
    ): Evaluation {
        var pending: Evaluation? = null
        for (state in checkOrder) {
            val conditions = thresholds[state].orEmpty().map(Condition::parse)
            val condition = conditions.firstOrNull { it.matches(value) } ?: continue
            val required = definition.persistence[state] ?: 1
            if (required > 1) {
                val streak = streak(conditions, value, now, samples, definition.effectiveExpiry.toMillis())
                if (streak < required) {
                    // Report the most advanced streak ("2 of 3 days"), the more severe state on a tie.
                    val best = pending?.params?.get("count")?.toInt() ?: -1
                    if (streak > best) {
                        pending = Evaluation(
                            HealthState.NORMAL,
                            ReasonCodes.PERSISTENCE_PENDING,
                            params + mapOf(
                                "condition" to condition.text,
                                "count" to streak.toString(),
                                "required" to required.toString(),
                                "pendingState" to state.name,
                            ),
                            toConfirm,
                        )
                    }
                    continue
                }
            }
            val code = when {
                state == HealthState.NORMAL -> ReasonCodes.IN_RANGE
                condition.direction == Direction.HIGH -> ReasonCodes.HIGH
                condition.direction == Direction.LOW -> ReasonCodes.LOW
                else -> ReasonCodes.OUT_OF_RANGE
            }
            return Evaluation(state, code, params + ("condition" to condition.text), toConfirm)
        }
        return pending ?: Evaluation(null, ReasonCodes.NO_CUT, params)
    }

    /** Consecutive measurements, current included, that match any of [conditions] without a gap longer than the expiry. */
    fun streak(conditions: List<Condition>, current: Double, now: Long, samples: List<Sample>, expiryMs: Long): Int {
        if (conditions.none { it.matches(current) }) return 0
        var count = 1
        var t = now
        for (sample in samples.asReversed()) {
            if (t - sample.t > expiryMs || conditions.none { it.matches(sample.v) }) break
            count++
            t = sample.t
        }
        return count
    }

}
