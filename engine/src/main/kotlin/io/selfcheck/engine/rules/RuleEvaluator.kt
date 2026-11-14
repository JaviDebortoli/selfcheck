package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.Quality
import io.selfcheck.contracts.Sex
import io.selfcheck.knowledge.Condition
import io.selfcheck.knowledge.Direction
import io.selfcheck.knowledge.VariableDefinition

/** What a rule may need besides the measurement itself. */
data class EvaluationContext(
    val environment: MissionEnvironment? = null,
    val sex: Sex? = null,
)

/** Applies the declarative threshold rules from `knowledge/` (spec section 7.2). */
class RuleEvaluator {

    private val checkOrder = listOf(HealthState.ALERT, HealthState.OBSERVATION, HealthState.NORMAL)

    fun evaluate(definition: VariableDefinition, measurement: Measurement, context: EvaluationContext): Evaluation {
        val valueParams = valueParams(definition, measurement)
        if (!definition.hasState) return Evaluation(null, ReasonCodes.DATA, valueParams)

        val environments = definition.environments
        if (environments != null && context.environment != null && context.environment !in environments) {
            return Evaluation(null, ReasonCodes.NOT_APPLICABLE_ENVIRONMENT, valueParams + ("environment" to context.environment.name))
        }

        val toConfirm = measurement.quality == Quality.DOUBTFUL

        // Alarm signs put the variable in ALERT regardless of the value (spec section 7.2).
        val signs = measurement.alarmSigns.filter { it.present && it.sign in definition.alarmSigns }.map { it.sign.name }
        if (signs.isNotEmpty()) {
            return Evaluation(HealthState.ALERT, ReasonCodes.ALARM_SIGN, valueParams + ("signs" to signs.joinToString(",")), toConfirm)
        }

        if (definition.context != null && measurement.activityContext != definition.context) {
            val actual = measurement.activityContext?.name ?: "UNKNOWN"
            return Evaluation(null, ReasonCodes.CONTEXT_NOT_APPLICABLE, valueParams + ("context" to actual))
        }
        if (definition.fasting == true && measurement.fasting != true) {
            return Evaluation(null, ReasonCodes.NOT_FASTING, valueParams)
        }

        if (measurement.quality == Quality.POOR) return Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.POOR_QUALITY, valueParams)

        val thresholds = definition.thresholdsBySex?.let { bySex -> context.sex?.let { bySex[it] } } ?: definition.thresholds
            ?: return Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.NO_DATA, valueParams)

        return classify(thresholds, measurement.value, valueParams, toConfirm)
    }

    /** First matching condition, checked in order ALERT, OBSERVATION, NORMAL. */
    fun classify(
        thresholds: Map<HealthState, List<String>>,
        value: Double,
        params: Map<String, String>,
        toConfirm: Boolean,
    ): Evaluation {
        for (state in checkOrder) {
            val condition = thresholds[state].orEmpty().map(Condition::parse).firstOrNull { it.matches(value) } ?: continue
            val code = when (condition.direction) {
                Direction.HIGH -> ReasonCodes.HIGH
                Direction.LOW -> ReasonCodes.LOW
                Direction.IN_RANGE -> ReasonCodes.IN_RANGE
            }
            return Evaluation(state, code, params + ("condition" to condition.text), toConfirm)
        }
        return Evaluation(null, ReasonCodes.UNCLASSIFIED, params)
    }

    private fun valueParams(definition: VariableDefinition, measurement: Measurement): Map<String, String> =
        mapOf("value" to measurement.value.format(), "unit" to definition.unit)
}
