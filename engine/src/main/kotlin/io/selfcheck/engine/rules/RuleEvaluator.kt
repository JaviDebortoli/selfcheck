package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.Quality
import io.selfcheck.knowledge.Condition
import io.selfcheck.knowledge.Reference
import io.selfcheck.knowledge.Scale
import io.selfcheck.knowledge.VariableDefinition

/** Everything a rule may look at when a measurement arrives. */
data class EvaluationInput(
    val measurement: Measurement,
    /** Mission time of the measurement. */
    val now: Long,
    /** The subject as it is before this rule is applied (other variables, for cross-variable rules). */
    val runtime: SubjectRuntime,
    val history: MedicalHistory? = null,
    val profile: MissionProfile? = null,
    /** Value after the rule's reference or window is applied (% of a target, mean); set for custom evaluators. */
    val scaled: Double? = null,
)

/**
 * Applies the declarative rules from `knowledge/` (spec section 7.2), in this order:
 * stateless -> environment -> alarm signs -> context/fasting -> quality -> reference and window ->
 * custom evaluator, rhythm labels or thresholds (with streaks).
 */
class RuleEvaluator(private val custom: Map<String, CustomEvaluator> = CustomEvaluators.all) {

    /** Returns the variable's new runtime, or null when this measurement does not update it. */
    fun evaluate(definition: VariableDefinition, input: EvaluationInput, previous: VariableRuntime?): VariableRuntime? {
        val evaluation = evaluation(definition, input, previous) ?: return null
        val value = evaluation.value ?: input.measurement.value
        val keepsSamples = definition.persistence.isNotEmpty() || definition.evaluator in CustomEvaluators.withSamples
        val samples = if (keepsSamples) ((previous?.samples ?: emptyList()) + Sample(input.now, value)).takeLast(MAX_SAMPLES) else emptyList()
        return VariableRuntime(
            state = evaluation.state,
            code = evaluation.code,
            params = evaluation.params,
            toConfirm = evaluation.toConfirm,
            measuredAtMs = input.now,
            value = value,
            samples = samples,
            buckets = windowBuckets(definition, input, previous),
            memory = evaluation.memory ?: previous?.memory.orEmpty(),
        )
    }

    private fun evaluation(definition: VariableDefinition, input: EvaluationInput, previous: VariableRuntime?): Evaluation? {
        val m = input.measurement
        val baseParams = mapOf("value" to m.value.format(), "unit" to definition.unit)
        if (!definition.hasState) return Evaluation(null, ReasonCodes.DATA, baseParams)

        val environment = input.profile?.environment
        val environments = definition.environments
        if (environments != null && environment != null && environment !in environments) {
            return Evaluation(null, ReasonCodes.NOT_APPLICABLE_ENVIRONMENT, baseParams + ("environment" to environment.name))
        }

        val toConfirm = m.quality == Quality.DOUBTFUL
        val scaled = scaledValue(definition, input)

        // Alarm signs put the variable in ALERT (spec section 7.2), unless the rule also needs a value (urinary calcium).
        val signs = m.alarmSigns.filter { it.present && it.sign in definition.alarmSigns }.map { it.sign.name }
        if (signs.isNotEmpty()) {
            val condition = definition.alarmSignCondition?.let(Condition::parse)
            if (condition == null || (scaled != null && condition.matches(scaled))) {
                return Evaluation(HealthState.ALERT, ReasonCodes.ALARM_SIGN, baseParams + ("signs" to signs.joinToString(",")), toConfirm)
            }
        }

        if (definition.context != null && m.activityContext != definition.context) {
            val actual = m.activityContext?.name ?: "UNKNOWN"
            return Evaluation(null, ReasonCodes.CONTEXT_NOT_APPLICABLE, baseParams + ("context" to actual))
        }
        if (definition.fasting == true && m.fasting != true) return Evaluation(null, ReasonCodes.NOT_FASTING, baseParams)
        if (m.quality == Quality.POOR) return Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.POOR_QUALITY, baseParams)

        definition.evaluator?.let { name ->
            val evaluator = custom[name] ?: error("Unknown evaluator $name for ${definition.variable}")
            return evaluator.evaluate(definition, input.copy(scaled = scaled), previous)?.let { it.copy(toConfirm = it.toConfirm || toConfirm) }
        }

        if (scaled == null) return Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.NO_REFERENCE, baseParams)
        val params = if (definition.reference != null || definition.window != null) baseParams + ("evaluated" to scaled.format()) else baseParams

        definition.labelStates?.let { labels ->
            val label = m.rhythm ?: return null
            val state = labels.entries.firstOrNull { label in it.value }?.key ?: HealthState.OBSERVATION
            return Evaluation(state, ReasonCodes.RHYTHM, params + ("rhythm" to label.name), toConfirm)
        }

        val thresholds = definition.thresholdsBySex?.let { bySex -> input.history?.sex?.let { bySex[it] } }
            ?: definition.thresholds
            ?: return Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.NO_REFERENCE, params)
        return classify(definition, thresholds, scaled, params, toConfirm, input.now, previous?.samples.orEmpty())
            .copy(value = scaled)
    }

    /** Threshold check with streaks; see [Classifier.classify]. */
    fun classify(
        definition: VariableDefinition,
        thresholds: Map<HealthState, List<String>>,
        value: Double,
        params: Map<String, String>,
        toConfirm: Boolean,
        now: Long,
        samples: List<Sample>,
    ): Evaluation = Classifier.classify(definition, thresholds, value, params, toConfirm, now, samples)

    /** Value the thresholds are checked against: raw, % of a reference, or the mean of a window. Null if a reference is missing. */
    private fun scaledValue(definition: VariableDefinition, input: EvaluationInput): Double? {
        val raw = input.measurement.value
        definition.window?.let { return windowMean(definition, input) }
        val reference = definition.reference ?: return raw
        val base = referenceValue(reference, input.history) ?: return null
        if (base <= 0) return null
        val percent = when (definition.scale) {
            Scale.PERCENT_OF_REFERENCE -> raw / base * 100
            Scale.PERCENT_CHANGE_FROM_REFERENCE -> kotlin.math.abs(raw - base) / base * 100
        }
        // Round away floating-point noise so a value at exactly the limit (95 %) is not read as 94.99999...
        return Math.round(percent * 1e6) / 1e6
    }

    private fun referenceValue(reference: Reference, history: MedicalHistory?): Double? = history?.let {
        when (reference) {
            Reference.PREFLIGHT_MUSCLE_STRENGTH -> it.preflightMuscleStrength
            Reference.PREFLIGHT_BMD_HIP -> it.preflightBoneDensity.hip
            Reference.PREFLIGHT_BMD_SPINE -> it.preflightBoneDensity.spine
            Reference.PREFLIGHT_BMD_FEMORAL_NECK -> it.preflightBoneDensity.femoralNeck
            Reference.PREFLIGHT_BODY_MASS -> it.preflightBodyMassKg
            Reference.DAILY_CALORIES -> it.dailyTargets.caloriesKcal
            Reference.DAILY_FLUIDS -> it.dailyTargets.fluidsMl
            Reference.DAILY_ACTIVITY -> it.dailyTargets.activityMinutes
        }
    }

    /** Mean of the window, current measurement included, using [WINDOW_SLICES] slices of mission time. */
    private fun windowMean(definition: VariableDefinition, input: EvaluationInput): Double {
        val buckets = windowBuckets(definition, input, input.runtime.variables[definition.variable])
        val sum = buckets.values.sumOf { it.sum }
        val count = buckets.values.sumOf { it.count }
        return if (count == 0) input.measurement.value else sum / count
    }

    private fun windowBuckets(definition: VariableDefinition, input: EvaluationInput, previous: VariableRuntime?): Map<Long, Bucket> {
        val window = definition.window?.toMillis() ?: return emptyMap()
        val slice = (window / WINDOW_SLICES).coerceAtLeast(1)
        val start = input.now - input.now.mod(slice)
        val kept = previous?.buckets.orEmpty().filterKeys { it > input.now - window }.toMutableMap()
        val current = kept[start] ?: Bucket(0.0, 0)
        kept[start] = Bucket(current.sum + input.measurement.value, current.count + 1)
        return kept
    }

    companion object {
        const val MAX_SAMPLES = 30
        const val WINDOW_SLICES = 60L
    }
}
