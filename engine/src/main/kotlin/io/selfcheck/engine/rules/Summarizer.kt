package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.Variable
import io.selfcheck.knowledge.RuleCatalog

/**
 * Group and crew member states (spec section 7.3).
 *
 * - A group takes the worst state among its variables with state. A stateful variable never measured is
 *   NOT_EVALUABLE ("never green by default"); a variable currently shown without state does not count.
 * - A crew member takes the worst state of the seven body groups. The habitat only has its own group.
 */
class Summarizer(private val catalog: RuleCatalog) {

    fun summarize(runtime: SubjectRuntime): Map<String, SummaryRuntime> {
        val groups = when (runtime.subjectType) {
            SubjectType.HABITAT -> listOf(MeasurementGroup.HABITAT)
            else -> MeasurementGroup.entries.filter { it != MeasurementGroup.HABITAT }
        }
        val result = linkedMapOf<String, SummaryRuntime>()
        for (group in groups) {
            val definitions = catalog.statefulVariables(group)
            if (definitions.isEmpty()) continue
            val contributions = definitions.mapNotNull { definition ->
                val current = runtime.variables[definition.variable]
                when {
                    current == null -> HealthState.NOT_EVALUABLE to definition.variable
                    current.state == null -> null
                    else -> current.state to definition.variable
                }
            }
            result[group.name] = worst(contributions) ?: SummaryRuntime(HealthState.NOT_EVALUABLE, null)
        }
        if (runtime.subjectType == SubjectType.CREW_MEMBER && result.isNotEmpty()) {
            val overall = result.values.maxBy { it.state.severity }
            result[SubjectRuntime.OVERALL] = overall
        }
        return result
    }

    private fun worst(contributions: List<Pair<HealthState, Variable>>): SummaryRuntime? {
        val (state, variable) = contributions.maxByOrNull { it.first.severity } ?: return null
        return SummaryRuntime(state, if (state == HealthState.NORMAL) null else variable)
    }
}
