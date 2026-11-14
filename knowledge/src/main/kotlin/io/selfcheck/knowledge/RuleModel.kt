package io.selfcheck.knowledge

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.Sex
import io.selfcheck.contracts.Variable
import java.time.Duration

/** One YAML file in `knowledge/rules/`: every variable of one measurement group. */
data class GroupDefinition(
    val group: MeasurementGroup,
    val texts: Map<String, GroupTexts> = emptyMap(),
    val variables: List<VariableDefinition>,
)

data class GroupTexts(val name: String)

/**
 * Declarative rule of one variable.
 *
 * A variable without [thresholds], [thresholdsBySex] and [evaluator] is shown as data, without state.
 */
data class VariableDefinition(
    val variable: Variable,
    val unit: String,
    /** How often the variable is measured (spec section 6.2). */
    val interval: Duration,
    /** When a value stops being valid; defaults to 1.5 x [interval] (spec section 7.2). */
    val expiry: Duration? = null,
    /** Only evaluated in this context (heart rate at rest); otherwise shown without state. */
    val context: ActivityContext? = null,
    /** Only evaluated when fasting (glucose); otherwise shown without state. */
    val fasting: Boolean? = null,
    /** Environments where the rule applies; null means all. Elsewhere the value is shown without state. */
    val environments: Set<MissionEnvironment>? = null,
    /** Alarm signs that put this variable in ALERT (spec section 7.12). */
    val alarmSigns: List<AlarmSign> = emptyList(),
    /** Ordered checks: ALERT, then OBSERVATION, then NORMAL. Each entry is a condition such as "< 40" or "60..100". */
    val thresholds: Map<HealthState, List<String>>? = null,
    /** Same as [thresholds], per sex (hemoglobin). */
    val thresholdsBySex: Map<Sex, Map<HealthState, List<String>>>? = null,
    /** Name of a custom evaluator in the engine, for rules that are not plain thresholds. */
    val evaluator: String? = null,
    /** Numbers used by a custom evaluator, kept here so every cut lives in `knowledge/`. */
    val params: Map<String, Double> = emptyMap(),
    /** Citable source of the cuts. */
    val source: String = "",
    /** True for cuts without a source, implemented as final values until a professional reviews them. */
    val review: Boolean = false,
    /** Operational notes: parts of the rule without a number, provisional intervals. */
    val notes: List<String> = emptyList(),
    val texts: Map<String, VariableTexts> = emptyMap(),
) {
    val hasState: Boolean get() = thresholds != null || thresholdsBySex != null || evaluator != null

    /** Expiry per spec section 7.2: explicit value, or 1.5 times the measurement interval. */
    val effectiveExpiry: Duration get() = expiry ?: interval.multipliedBy(3).dividedBy(2)
}

data class VariableTexts(
    val name: String,
    /** What the indicator measures. */
    val measures: String,
    /** Guidance per state. */
    val guidance: Map<HealthState, String> = emptyMap(),
)
