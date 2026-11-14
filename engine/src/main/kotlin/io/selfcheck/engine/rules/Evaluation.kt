package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState

/**
 * Result of applying a rule: a state (null when the value is shown as data) and a reason code with parameters.
 * The backend never sends sentences (spec section 4.4); the frontend translates [code] with the text catalog.
 */
data class Evaluation(
    val state: HealthState?,
    val code: String,
    val params: Map<String, String> = emptyMap(),
    val toConfirm: Boolean = false,
)

/** Reason codes shared by every rule. Rule-specific evaluators may add their own. */
object ReasonCodes {
    /** Variable without state: shown as data. */
    const val DATA = "DATA"
    const val IN_RANGE = "IN_RANGE"
    const val HIGH = "HIGH"
    const val LOW = "LOW"
    const val ALARM_SIGN = "ALARM_SIGN"
    const val POOR_QUALITY = "POOR_QUALITY"
    const val CONTEXT_NOT_APPLICABLE = "CONTEXT_NOT_APPLICABLE"
    const val NOT_FASTING = "NOT_FASTING"
    const val NOT_APPLICABLE_ENVIRONMENT = "NOT_APPLICABLE_ENVIRONMENT"
    const val DATA_EXPIRED = "DATA_EXPIRED"
    const val NO_DATA = "NO_DATA"
    const val UNCLASSIFIED = "UNCLASSIFIED"
}

/** Severity order for groups and crew members (spec sections 7.3 and 13): ALERT, OBSERVATION, NOT_EVALUABLE, NORMAL. */
val HealthState.severity: Int
    get() = when (this) {
        HealthState.ALERT -> 3
        HealthState.OBSERVATION -> 2
        HealthState.NOT_EVALUABLE -> 1
        HealthState.NORMAL -> 0
    }

/** Formats a number without trailing zeros, for reason parameters. */
fun Double.format(): String = toBigDecimal().stripTrailingZeros().toPlainString()
