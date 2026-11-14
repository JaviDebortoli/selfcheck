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
) {
    companion object {
        const val OVERALL = "OVERALL"
    }
}

data class VariableRuntime(
    val state: HealthState?,
    val code: String,
    val params: Map<String, String>,
    val toConfirm: Boolean,
    /** Mission time of the latest measurement of this variable; drives expiry. */
    val measuredAtMs: Long,
    val value: Double,
) {
    /** Whether a change from [previous] must be published (the value alone does not count). */
    fun differsFrom(previous: VariableRuntime?): Boolean =
        previous == null || previous.state != state || previous.code != code || previous.toConfirm != toConfirm
}

data class SummaryRuntime(val state: HealthState, val triggerVariable: Variable?)
