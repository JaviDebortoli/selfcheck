package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Variable
import io.selfcheck.knowledge.VariableDefinition

/** A rule that is not a plain threshold check. Every number it uses comes from the rule's `params` in `knowledge/`. */
fun interface CustomEvaluator {
    /** Returns null when the measurement does not update this variable. */
    fun evaluate(definition: VariableDefinition, input: EvaluationInput, previous: VariableRuntime?): Evaluation?
}

private fun VariableDefinition.param(name: String): Double =
    params[name] ?: error("Rule $variable needs param '$name'")

object CustomEvaluators {

    const val DAY_MS = 86_400_000.0
    const val HOUR_MS = 3_600_000L

    /**
     * Career effective dose (spec section 7.4): previous missions + this mission.
     * OBSERVATION when the accumulated dose, or its projection to the end of the mission, reaches the observation cut.
     * Projection = accumulated + mean mission dose rate x days left of the planned duration.
     */
    val careerDose = CustomEvaluator { def, input, _ ->
        val missionDose = input.measurement.value
        val prior = input.history?.priorEffectiveDoseMsv
            ?: return@CustomEvaluator Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.NO_REFERENCE, mapOf("unit" to def.unit))
        val career = prior + missionDose
        val elapsedDays = input.now / DAY_MS
        val plannedDays = input.profile?.plannedDurationDays?.toDouble()
        val projected = if (plannedDays != null && elapsedDays > 0) {
            career + missionDose / elapsedDays * (plannedDays - elapsedDays).coerceAtLeast(0.0)
        } else {
            career
        }
        val limit = def.param("limitMsv")
        val observation = def.param("observationMsv")
        val params = mapOf(
            "value" to career.format(),
            "unit" to def.unit,
            "projected" to projected.format(),
            "limit" to limit.format(),
            "observation" to observation.format(),
        )
        val (state, code) = when {
            career >= limit -> HealthState.ALERT to ReasonCodes.CAREER_DOSE_LIMIT
            career >= observation -> HealthState.OBSERVATION to ReasonCodes.CAREER_DOSE_NEAR_LIMIT
            projected >= observation -> HealthState.OBSERVATION to ReasonCodes.CAREER_DOSE_PROJECTED
            else -> HealthState.NORMAL to ReasonCodes.IN_RANGE
        }
        Evaluation(state, code, params, value = career)
    }

    /**
     * Dose of the current solar particle event (spec section 7.4): mission dose since the event started.
     * NORMAL without an active event, OBSERVATION while active, ALERT from the per-event limit.
     */
    val solarEventDose = CustomEvaluator { def, input, previous ->
        val memory = previous?.memory.orEmpty().toMutableMap()
        val m = input.measurement
        when (m.variable) {
            Variable.SOLAR_PARTICLE_EVENT -> {
                val active = m.value >= 1
                val wasActive = memory[ACTIVE] == 1.0
                if (active && !wasActive) memory[START_DOSE] = memory[LAST_DOSE] ?: input.runtime.current(Variable.MISSION_EFFECTIVE_DOSE)?.value ?: 0.0
                memory[ACTIVE] = if (active) 1.0 else 0.0
            }
            Variable.MISSION_EFFECTIVE_DOSE -> memory[LAST_DOSE] = m.value
            else -> return@CustomEvaluator null
        }
        val active = memory[ACTIVE] ?: return@CustomEvaluator Evaluation(
            HealthState.NOT_EVALUABLE, ReasonCodes.NO_DATA, mapOf("unit" to def.unit), memory = memory,
        )
        val eventDose = if (active == 1.0) ((memory[LAST_DOSE] ?: 0.0) - (memory[START_DOSE] ?: 0.0)).coerceAtLeast(0.0) else 0.0
        val limit = def.param("limitMsv")
        val params = mapOf("value" to eventDose.format(), "unit" to def.unit, "limit" to limit.format())
        val (state, code) = when {
            active != 1.0 -> HealthState.NORMAL to ReasonCodes.NO_ACTIVE_EVENT
            eventDose >= limit -> HealthState.ALERT to ReasonCodes.SOLAR_EVENT_LIMIT
            else -> HealthState.OBSERVATION to ReasonCodes.SOLAR_EVENT_ACTIVE
        }
        Evaluation(state, code, params, value = eventDose, memory = memory)
    }

    /**
     * Creatinine (spec section 7.8, KDIGO): ALERT on a rise of `riseMgDl` within `riseWindowHours`, or `ratio` times
     * within `ratioWindowDays`, against the lowest value of that window. OBSERVATION when out of range or rising in
     * two analyses in a row.
     */
    val creatinine = CustomEvaluator { def, input, previous ->
        val v = input.measurement.value
        val now = input.now
        val history = previous?.samples.orEmpty()
        val params = mapOf("value" to v.format(), "unit" to def.unit)

        val riseWindow = def.param("riseWindowHours").toLong() * HOUR_MS
        history.filter { now - it.t <= riseWindow }.minOfOrNull { it.v }?.let { baseline ->
            if (v - baseline >= def.param("riseMgDl") - 1e-9) {
                return@CustomEvaluator Evaluation(
                    HealthState.ALERT, ReasonCodes.CREATININE_RISE_48H,
                    params + mapOf("baseline" to baseline.format(), "rise" to (v - baseline).format()), value = v,
                )
            }
        }
        val ratioWindow = (def.param("ratioWindowDays") * DAY_MS).toLong()
        history.filter { now - it.t <= ratioWindow }.minOfOrNull { it.v }?.let { baseline ->
            if (baseline > 0 && v >= def.param("ratio") * baseline) {
                return@CustomEvaluator Evaluation(
                    HealthState.ALERT, ReasonCodes.CREATININE_RISE_7D,
                    params + mapOf("baseline" to baseline.format(), "ratio" to (v / baseline).format()), value = v,
                )
            }
        }

        val low = def.param("low")
        val high = def.param("high")
        fun flagged(value: Double, before: Double?) = value < low || value > high || (before != null && value > before)
        val previousSample = history.lastOrNull()
        val currentFlag = flagged(v, previousSample?.v)
        val code = if (v < low || v > high) ReasonCodes.OUT_OF_RANGE else ReasonCodes.CREATININE_RISING
        if (!currentFlag) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = v)

        val expiry = def.effectiveExpiry.toMillis()
        val previousFlag = previousSample != null && now - previousSample.t <= expiry &&
            flagged(previousSample.v, history.getOrNull(history.size - 2)?.v)
        val required = def.persistence[HealthState.OBSERVATION] ?: 2
        if (previousFlag) return@CustomEvaluator Evaluation(HealthState.OBSERVATION, code, params, value = v)
        Evaluation(
            HealthState.NORMAL, ReasonCodes.PERSISTENCE_PENDING,
            params + mapOf("count" to "1", "required" to required.toString(), "pendingState" to "OBSERVATION"), value = v,
        )
    }

    /**
     * Bone resorption markers (spec section 7.6): compared with the laboratory range sent with the measurement.
     * ALERT when out of range together with bone density in ALERT or urinary calcium above `urinaryCalciumHigh`;
     * OBSERVATION when out of range in two analyses in a row. Samples store 1 for out of range, 0 for in range.
     */
    val boneResorption = CustomEvaluator { def, input, previous ->
        val m = input.measurement
        val range = m.referenceRange
        val params = buildMap {
            put("value", m.value.format())
            put("unit", def.unit)
            m.boneMarker?.let { put("marker", it.name) }
            range?.let { put("low", it.low.format()); put("high", it.high.format()) }
        }
        if (range == null) return@CustomEvaluator Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.NO_LAB_RANGE, params, value = 0.0)
        val out = m.value < range.low || m.value > range.high
        if (!out) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = 0.0)

        val runtime = input.runtime
        val boneAlert = listOf(Variable.BMD_HIP, Variable.BMD_SPINE, Variable.BMD_FEMORAL_NECK)
            .any { runtime.current(it)?.state == HealthState.ALERT }
        val calciumHigh = (runtime.current(Variable.URINARY_CALCIUM)?.value ?: 0.0) > def.param("urinaryCalciumHigh")
        if (boneAlert || calciumHigh) {
            return@CustomEvaluator Evaluation(HealthState.ALERT, ReasonCodes.BONE_LOSS_MARKERS, params, value = 1.0)
        }
        val last = previous?.samples?.lastOrNull()
        val required = def.persistence[HealthState.OBSERVATION] ?: 2
        if (last != null && last.v == 1.0 && input.now - last.t <= def.effectiveExpiry.toMillis()) {
            return@CustomEvaluator Evaluation(HealthState.OBSERVATION, ReasonCodes.OUT_OF_RANGE, params, value = 1.0)
        }
        Evaluation(
            HealthState.NORMAL, ReasonCodes.PERSISTENCE_PENDING,
            params + mapOf("count" to "1", "required" to required.toString(), "pendingState" to "OBSERVATION"), value = 1.0,
        )
    }

    /**
     * Bowel movements per day (spec section 7.10). Normal from `maxPerDay` per day to `minPerWeek` per week.
     * ALERT for diarrhoea with dehydration: more than `maxPerDay` with urine specific gravity or sodium out of range.
     * The weekly check needs 7 daily records.
     */
    val bowelFrequency = CustomEvaluator { def, input, previous ->
        val count = input.measurement.value
        val params = mapOf("value" to count.format(), "unit" to def.unit)
        val runtime = input.runtime
        val maxPerDay = def.param("maxPerDay")
        if (count > maxPerDay) {
            val gravity = runtime.current(Variable.URINE_SPECIFIC_GRAVITY)?.value
            val sodium = runtime.current(Variable.SODIUM)?.value
            val dehydrated = (gravity != null && (gravity < def.param("urineSpecificGravityLow") || gravity > def.param("urineSpecificGravityHigh"))) ||
                (sodium != null && (sodium < def.param("sodiumLow") || sodium > def.param("sodiumHigh")))
            return@CustomEvaluator if (dehydrated) {
                Evaluation(HealthState.ALERT, ReasonCodes.DIARRHEA_DEHYDRATION, params, value = count)
            } else {
                Evaluation(HealthState.OBSERVATION, ReasonCodes.HIGH, params + ("condition" to "> ${maxPerDay.format()}"), value = count)
            }
        }
        val week = (previous?.samples.orEmpty().filter { input.now - it.t < 7 * DAY_MS }.takeLast(6) + Sample(input.now, count))
        if (week.size >= 7 && week.sumOf { it.v } < def.param("minPerWeek")) {
            return@CustomEvaluator Evaluation(HealthState.OBSERVATION, ReasonCodes.BOWEL_LOW_WEEK, params + ("weekly" to week.sumOf { it.v }.format()), value = count)
        }
        Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = count)
    }

    val all: Map<String, CustomEvaluator> = mapOf(
        "CAREER_DOSE" to careerDose,
        "SOLAR_EVENT_DOSE" to solarEventDose,
        "CREATININE_KDIGO" to creatinine,
        "BONE_RESORPTION" to boneResorption,
        "BOWEL_FREQUENCY" to bowelFrequency,
    )

    /** Evaluators that look at previous values. */
    val withSamples = setOf("CREATININE_KDIGO", "BONE_RESORPTION", "BOWEL_FREQUENCY")

    private const val ACTIVE = "active"
    private const val START_DOSE = "startDose"
    private const val LAST_DOSE = "lastDose"
}
