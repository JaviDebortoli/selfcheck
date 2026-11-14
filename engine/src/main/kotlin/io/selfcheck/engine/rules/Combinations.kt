package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Variable
import io.selfcheck.knowledge.RuleCatalog

/**
 * Clinical combinations (spec section 7.13, to be reviewed). They never change a color: each active combination
 * adds one high-priority alert, raised again only after it clears.
 */
class Combinations(private val catalog: RuleCatalog) {

    /** Active combinations of a crew member, with the worst state among the variables involved. */
    fun active(runtime: SubjectRuntime): Map<String, HealthState> {
        val result = linkedMapOf<String, HealthState>()

        fun flagged(variable: Variable, code: String): VariableRuntime? =
            runtime.current(variable)?.takeIf {
                (it.state == HealthState.OBSERVATION || it.state == HealthState.ALERT) && it.code == code
            }

        fun atLeastObservation(variable: Variable): VariableRuntime? =
            runtime.current(variable)?.takeIf { it.state == HealthState.OBSERVATION || it.state == HealthState.ALERT }

        fun value(variable: Variable): Double? = runtime.current(variable)?.value

        fun param(code: String, name: String): Double =
            catalog.combination(code)?.params?.get(name) ?: error("Combination $code needs param '$name'")

        fun add(code: String, vararg involved: VariableRuntime?) {
            if (catalog.combination(code) == null) return
            val worst = involved.mapNotNull { it?.state }.maxByOrNull { it.severity } ?: HealthState.OBSERVATION
            result[code] = if (worst.severity < HealthState.OBSERVATION.severity) HealthState.OBSERVATION else worst
        }

        val distress = runtime.current(Variable.RESPIRATORY_RATE)
            ?.takeIf { it.code == ReasonCodes.ALARM_SIGN && it.params["signs"].orEmpty().contains("RESPIRATORY_DISTRESS") }
        val highHeartRate = flagged(Variable.HEART_RATE, ReasonCodes.HIGH)

        // 1. High heart rate + low systolic pressure.
        val lowSystolic = flagged(Variable.SYSTOLIC_BP, ReasonCodes.LOW)
        if (highHeartRate != null && lowSystolic != null) add(HYPOVOLEMIA, highHeartRate, lowSystolic)

        // 2. Low SpO2 + high respiratory rate or respiratory distress.
        val lowSpo2 = flagged(Variable.SPO2, ReasonCodes.LOW)
        val respiratory = flagged(Variable.RESPIRATORY_RATE, ReasonCodes.HIGH) ?: distress
        if (lowSpo2 != null && respiratory != null) add(RESPIRATORY, lowSpo2, respiratory)

        // 3. Temperature >= 38 °C + neutrophils < 500/µL.
        val temperature = value(Variable.BODY_TEMPERATURE)
        val neutrophils = value(Variable.NEUTROPHILS)
        if (temperature != null && neutrophils != null &&
            temperature >= param(NEUTROPENIC, "temperature") && neutrophils < param(NEUTROPENIC, "neutrophils")
        ) {
            add(NEUTROPENIC, runtime.current(Variable.BODY_TEMPERATURE), runtime.current(Variable.NEUTROPHILS))
        }

        // 4. Low hemoglobin + high heart rate or respiratory distress.
        val lowHemoglobin = flagged(Variable.HEMOGLOBIN, ReasonCodes.LOW)
        val compensation = highHeartRate ?: distress
        if (lowHemoglobin != null && compensation != null) add(ANEMIA, lowHemoglobin, compensation)

        // 5. Rising creatinine + potassium >= 6 mmol/L.
        val creatinine = runtime.current(Variable.CREATININE)
        val rising = creatinine?.samples?.let { it.size >= 2 && it[it.size - 1].v > it[it.size - 2].v } == true
        val potassium = value(Variable.POTASSIUM)
        if (rising && potassium != null && potassium >= param(AKI, "potassium")) add(AKI, creatinine, runtime.current(Variable.POTASSIUM))

        // 6. High urinary calcium + bone density in Alert.
        val calcium = value(Variable.URINARY_CALCIUM)
        val boneAlert = listOf(Variable.BMD_HIP, Variable.BMD_SPINE, Variable.BMD_FEMORAL_NECK)
            .mapNotNull { runtime.current(it) }.firstOrNull { it.state == HealthState.ALERT }
        if (calcium != null && calcium > param(BONE, "urinaryCalcium") && boneAlert != null) {
            add(BONE, runtime.current(Variable.URINARY_CALCIUM), boneAlert)
        }

        // 7. Active solar event + career dose near the limit (Observation or Alert).
        val solar = atLeastObservation(Variable.SOLAR_EVENT_DOSE)
        val career = atLeastObservation(Variable.CAREER_EFFECTIVE_DOSE)
        if (solar != null && career != null) add(RADIATION, solar, career)

        return result
    }

    companion object {
        const val HYPOVOLEMIA = "COMBO_HYPOVOLEMIA"
        const val RESPIRATORY = "COMBO_RESPIRATORY_COMPROMISE"
        const val NEUTROPENIC = "COMBO_NEUTROPENIC_INFECTION"
        const val ANEMIA = "COMBO_ANEMIA_HYPOXIA"
        const val AKI = "COMBO_AKI_HYPERKALEMIA"
        const val BONE = "COMBO_BONE_REMODELING"
        const val RADIATION = "COMBO_RADIATION_EXPOSURE"
    }
}
