package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.Variable

/** Everything the engine remembers about one subject (crew member or habitat). Stored as JSON in a state store. */
data class SubjectRuntime(
    val subjectType: SubjectType,
    val subjectId: String,
    /** Latest mission time processed; older measurements are discarded (spec section 5). */
    var lastMissionTimeMs: Long = Long.MIN_VALUE,
    val variables: MutableMap<Variable, VariableRuntime> = mutableMapOf(),
    /** Summary states by key: a group name, or [OVERALL]. */
    val summaries: MutableMap<String, SummaryRuntime> = mutableMapOf(),
    /** Clinical combinations currently active, so their alert is not repeated (spec section 7.13). */
    val activeCombinations: MutableSet<String> = mutableSetOf(),
) {
    /** Latest valid (not expired) runtime of a variable. */
    fun current(variable: Variable): VariableRuntime? =
        variables[variable]?.takeIf { it.code != ReasonCodes.DATA_EXPIRED }

    companion object {
        const val OVERALL = "OVERALL"
    }
}

/** A past evaluated value, for streaks ("3 days in a row") and trend rules. */
data class Sample(val t: Long, val v: Double)

/** Running sum of the values that fall in one slice of a mean window. */
data class Bucket(val sum: Double, val count: Int)

data class VariableRuntime(
    val state: HealthState?,
    val code: String,
    val params: Map<String, String>,
    val toConfirm: Boolean,
    /** Mission time of the latest update of this variable; drives expiry. */
    val measuredAtMs: Long,
    /** Value evaluated (raw, % of a reference, window mean or derived). */
    val value: Double,
    /** Previous evaluated values, oldest first, current last. Kept only for rules that need them. */
    val samples: List<Sample> = emptyList(),
    /** Mean window slices by start time (CO2, noise). */
    val buckets: Map<Long, Bucket> = emptyMap(),
    /** Free memory for rules that track an episode (solar event start dose). */
    val memory: Map<String, Double> = emptyMap(),
) {
    /** Whether a change from [previous] must be published: state, reason code, flag, or streak progress. */
    fun differsFrom(previous: VariableRuntime?): Boolean =
        previous == null ||
            previous.state != state ||
            previous.code != code ||
            previous.toConfirm != toConfirm ||
            (code == ReasonCodes.PERSISTENCE_PENDING && previous.params != params)
}

data class SummaryRuntime(val state: HealthState, val triggerVariable: Variable?)
