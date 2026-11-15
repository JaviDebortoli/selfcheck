package io.selfcheck.knowledge

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.RhythmLabel
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
 * Per-crew-member value a rule compares against (spec section 7.2: strength, bone density and body mass use the
 * baseline; protein intake uses the pre-flight body mass as its per-kg reference).
 */
enum class Reference {
    PREFLIGHT_MUSCLE_STRENGTH,
    PREFLIGHT_BMD_HIP,
    PREFLIGHT_BMD_SPINE,
    PREFLIGHT_BMD_FEMORAL_NECK,
    PREFLIGHT_BODY_MASS,
    DAILY_CALORIES,
    DAILY_FLUIDS,
    DAILY_ACTIVITY,
}

/** How a value is expressed against its [Reference] before the thresholds are checked. */
enum class Scale {
    /** value / reference x 100. */
    PERCENT_OF_REFERENCE,

    /** |value - reference| / reference x 100. */
    PERCENT_CHANGE_FROM_REFERENCE,

    /** value / reference, without the x 100 (protein intake in g per kg of pre-flight body mass). */
    PER_UNIT_OF_REFERENCE,
}

/**
 * Declarative rule of one variable.
 *
 * A variable without [thresholds], [thresholdsBySex], [labelStates] and [evaluator] is shown as data, without state.
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
    /** Alarm signs that put this variable in ALERT (spec section 7.12). */
    val alarmSigns: List<AlarmSign> = emptyList(),
    /** When set, an alarm sign only gives ALERT if the value also matches this condition (urinary calcium > 300). */
    val alarmSignCondition: String? = null,
    /** Variables whose measurements trigger this rule; for values the engine derives (rhythm, career dose). */
    val derivedFrom: List<Variable> = emptyList(),
    /** Compare against a per-crew-member value instead of the raw value. */
    val reference: Reference? = null,
    val scale: Scale = Scale.PERCENT_OF_REFERENCE,
    /** Evaluate the mean over this mission-time window instead of the latest value (CO2, noise). */
    val window: Duration? = null,
    /** Ordered checks: ALERT, then OBSERVATION, then NORMAL. Each entry is a condition such as "< 40" or "60..100". */
    val thresholds: Map<HealthState, List<String>>? = null,
    /** Same as [thresholds], per sex (hemoglobin). */
    val thresholdsBySex: Map<Sex, Map<HealthState, List<String>>>? = null,
    /**
     * Consecutive measurements that must match a state's conditions for that state to apply
     * ("persistent"/"sustained": 3 daily values or 2 analyses, spec section 7.2).
     */
    val persistence: Map<HealthState, Int> = emptyMap(),
    /** State per label, for categorical values (ECG rhythm). */
    val labelStates: Map<HealthState, List<RhythmLabel>>? = null,
    /** Name of a custom evaluator in the engine, for rules that are not plain thresholds. */
    val evaluator: String? = null,
    /** Numbers used by a custom evaluator, kept here so every cut lives in `knowledge/`. */
    val params: Map<String, Double> = emptyMap(),
    /**
     * The variable takes the state of this other variable instead of evaluating its own value (spec section 7.4):
     * the mission dose follows the career dose, the dose rate follows the solar event alert.
     */
    val stateFrom: Variable? = null,
    /** False when the state repeats a situation another variable already alerts on, so it is not alerted twice. */
    val raisesAlerts: Boolean = true,
    /** Citable source of the cuts. */
    val source: String = "",
    /** True for cuts without a source, implemented as final values until a professional reviews them. */
    val review: Boolean = false,
    /** Operational notes: parts of the rule without a number, provisional intervals. */
    val notes: List<String> = emptyList(),
    val texts: Map<String, VariableTexts> = emptyMap(),
) {
    val hasState: Boolean
        get() = thresholds != null || thresholdsBySex != null || labelStates != null || evaluator != null || stateFrom != null

    /** Expiry per spec section 7.2: explicit value, or 1.5 times the measurement interval. */
    val effectiveExpiry: Duration get() = expiry ?: interval.multipliedBy(3).dividedBy(2)
}

data class VariableTexts(
    val name: String,
    /** What the indicator measures. */
    val measures: String,
    /**
     * Recommendation ("what to do") per state, from the professional rules document. Keys are a state
     * (OBSERVATION, ALERT), optionally narrowed by the direction of the value and the crew member's sex:
     * `ALERT_HIGH`, `OBSERVATION_HIGH_FEMALE`. The most specific key that matches is shown.
     */
    val guidance: Map<String, String> = emptyMap(),
)

/** A clinical combination (spec section 7.13): adds a high-priority alert, never changes a color. */
data class CombinationDefinition(
    val code: String,
    val params: Map<String, Double> = emptyMap(),
    val review: Boolean = true,
    val texts: Map<String, CombinationTexts> = emptyMap(),
)

data class CombinationTexts(val name: String, val interpretation: String)

/** Texts shared by every rule: reason codes, alarm signs, rhythm labels, contexts. */
data class SharedTexts(
    val reasons: Map<String, Map<String, String>> = emptyMap(),
    val alarmSigns: Map<String, Map<String, String>> = emptyMap(),
    val rhythms: Map<String, Map<String, String>> = emptyMap(),
    val contexts: Map<String, Map<String, String>> = emptyMap(),
)
