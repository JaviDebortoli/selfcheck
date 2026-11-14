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
    /** Value evaluated: the raw value, a % of a reference, a window mean or a derived value. */
    val value: Double? = null,
    /** New memory for rules that track an episode; null keeps the previous memory. */
    val memory: Map<String, Double>? = null,
)

/** Reason codes. Their Spanish and English templates live in `knowledge/texts.yaml`. */
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
    const val NO_REFERENCE = "NO_REFERENCE"

    /** The value falls where the rule has a cut without a number (spec section 7.2): shown as data. */
    const val NO_CUT = "NO_CUT"

    /** Out of the normal range, but the "3 days / 2 analyses" streak is not complete yet. */
    const val PERSISTENCE_PENDING = "PERSISTENCE_PENDING"
    const val RHYTHM = "RHYTHM"
    const val CAREER_DOSE_LIMIT = "CAREER_DOSE_LIMIT"
    const val CAREER_DOSE_NEAR_LIMIT = "CAREER_DOSE_NEAR_LIMIT"
    const val CAREER_DOSE_PROJECTED = "CAREER_DOSE_PROJECTED"
    const val NO_ACTIVE_EVENT = "NO_ACTIVE_EVENT"
    const val SOLAR_EVENT_ACTIVE = "SOLAR_EVENT_ACTIVE"
    const val SOLAR_EVENT_LIMIT = "SOLAR_EVENT_LIMIT"
    const val CREATININE_RISE_48H = "CREATININE_RISE_48H"
    const val CREATININE_RISE_7D = "CREATININE_RISE_7D"
    const val CREATININE_RISING = "CREATININE_RISING"
    const val OUT_OF_RANGE = "OUT_OF_RANGE"
    const val NO_LAB_RANGE = "NO_LAB_RANGE"
    const val BONE_LOSS_MARKERS = "BONE_LOSS_MARKERS"
    const val BOWEL_LOW_WEEK = "BOWEL_LOW_WEEK"
    const val DIARRHEA_DEHYDRATION = "DIARRHEA_DEHYDRATION"
}

/** Severity order for groups and crew members (spec sections 7.3 and 13): ALERT, OBSERVATION, NOT_EVALUABLE, NORMAL. */
val HealthState.severity: Int
    get() = when (this) {
        HealthState.ALERT -> 3
        HealthState.OBSERVATION -> 2
        HealthState.NOT_EVALUABLE -> 1
        HealthState.NORMAL -> 0
    }

/** Formats a number without trailing zeros, rounded to 3 decimals, for reason parameters. */
fun Double.format(): String =
    toBigDecimal().setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
