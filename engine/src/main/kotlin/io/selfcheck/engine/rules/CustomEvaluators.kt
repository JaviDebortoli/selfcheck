package io.selfcheck.engine.rules

import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Variable
import io.selfcheck.knowledge.Condition
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
     * Space weather alert of a solar particle event (spec section 7.4): NORMAL without an active event, OBSERVATION
     * while one is active. Same rule as the solar event dose, which also gives the ALERT at its limit.
     */
    val solarEventFlag = CustomEvaluator { def, input, _ ->
        val value = input.measurement.value
        val params = mapOf("value" to value.format(), "unit" to def.unit)
        if (value >= 1) {
            Evaluation(HealthState.OBSERVATION, ReasonCodes.SOLAR_EVENT_IN_PROGRESS, params, value = value)
        } else {
            Evaluation(HealthState.NORMAL, ReasonCodes.NO_ACTIVE_EVENT, params, value = value)
        }
    }

    /**
     * Creatinine (spec section 7.8). NORMAL in range. Out of range is OBSERVATION, and ALERT when it comes with any of:
     * a rise of `riseMgDl` within `riseWindowHours` or `ratio` times within `ratioWindowDays` (against the lowest value of
     * the window), blood pressure out of its limits, urea out of range or urine output below `diuresisLow`.
     */
    val creatinine = CustomEvaluator { def, input, previous ->
        val v = input.measurement.value
        val now = input.now
        val history = previous?.samples.orEmpty()
        val params = mapOf("value" to v.format(), "unit" to def.unit)
        val low = def.param("low")
        val high = def.param("high")
        if (v in low..high) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = v)

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

        val runtime = input.runtime
        val findings = buildList {
            runtime.latest(Variable.SYSTOLIC_BP)?.let { if (it >= def.param("systolicHigh") || it < def.param("systolicLow")) add(Variable.SYSTOLIC_BP) }
            runtime.latest(Variable.DIASTOLIC_BP)?.let { if (it >= def.param("diastolicHigh") || it < def.param("diastolicLow")) add(Variable.DIASTOLIC_BP) }
            runtime.latest(Variable.UREA)?.let { if (it < def.param("ureaLow") || it > def.param("ureaHigh")) add(Variable.UREA) }
            runtime.latest(Variable.DIURESIS)?.let { if (it < def.param("diuresisLow")) add(Variable.DIURESIS) }
        }
        if (findings.isNotEmpty()) {
            return@CustomEvaluator Evaluation(HealthState.ALERT, ReasonCodes.CREATININE_WITH_FINDINGS, params + findings.param(), value = v)
        }
        outOfRange(HealthState.OBSERVATION, v, low, high, params)
    }

    /**
     * Urea (spec section 7.8). OBSERVATION when out of range in two analyses in a row, or in one analysis together with
     * urine output below `diuresisLow`, creatinine out of range or the daily fluid target not met.
     */
    val urea = CustomEvaluator { def, input, previous ->
        val v = input.measurement.value
        val params = mapOf("value" to v.format(), "unit" to def.unit)
        val low = def.param("low")
        val high = def.param("high")
        fun out(value: Double) = value < low || value > high
        if (!out(v)) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = v)

        val runtime = input.runtime
        val findings = buildList {
            runtime.latest(Variable.DIURESIS)?.let { if (it < def.param("diuresisLow")) add(Variable.DIURESIS) }
            runtime.latest(Variable.CREATININE)?.let { if (it < def.param("creatinineLow") || it > def.param("creatinineHigh")) add(Variable.CREATININE) }
            runtime.latest(Variable.FLUID_INTAKE)?.let { if (it < def.param("fluidTargetPercent")) add(Variable.FLUID_INTAKE) }
        }
        if (findings.isNotEmpty()) {
            return@CustomEvaluator Evaluation(HealthState.OBSERVATION, ReasonCodes.UREA_WITH_FINDINGS, params + findings.param(), value = v)
        }
        val last = previous?.samples?.lastOrNull()
        if (last != null && out(last.v) && input.now - last.t <= def.effectiveExpiry.toMillis()) {
            return@CustomEvaluator outOfRange(HealthState.OBSERVATION, v, low, high, params)
        }
        val required = def.persistence[HealthState.OBSERVATION] ?: 2
        pending(params, 1, required, HealthState.OBSERVATION, v)
    }

    /**
     * Urine output in mL/kg/h, one value per hour that stands for the hour before (spec section 7.8). NORMAL from
     * `normalLow` to `normalHigh`; above it there is no state. Below `normalLow` is OBSERVATION, and ALERT when it lasts
     * `lowHours` values in a row, below `veryLow` for `veryLowHours` values, or at zero for `anuriaHours` values.
     */
    val diuresis = CustomEvaluator { def, input, previous ->
        val v = input.measurement.value
        val params = mapOf("value" to v.format(), "unit" to def.unit)
        val normalLow = def.param("normalLow")
        if (v > def.param("normalHigh")) return@CustomEvaluator Evaluation(null, ReasonCodes.NO_CUT, params, value = v)
        if (v >= normalLow) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = v)

        val samples = previous?.samples.orEmpty()
        val expiry = def.effectiveExpiry.toMillis()
        fun hoursMatching(condition: String) = Classifier.streak(listOf(Condition.parse(condition)), v, input.now, samples, expiry)
        val anuria = hoursMatching("<= 0")
        if (anuria >= def.param("anuriaHours")) {
            return@CustomEvaluator Evaluation(HealthState.ALERT, ReasonCodes.DIURESIS_ANURIA, params + ("hours" to anuria.toString()), value = v)
        }
        val veryLow = def.param("veryLow")
        val veryLowHours = hoursMatching("< $veryLow")
        if (veryLowHours >= def.param("veryLowHours")) {
            return@CustomEvaluator Evaluation(
                HealthState.ALERT, ReasonCodes.DIURESIS_LOW_SUSTAINED,
                params + mapOf("limit" to veryLow.format(), "hours" to veryLowHours.toString()), value = v,
            )
        }
        val lowHours = hoursMatching("< $normalLow")
        if (lowHours >= def.param("lowHours")) {
            return@CustomEvaluator Evaluation(
                HealthState.ALERT, ReasonCodes.DIURESIS_LOW_SUSTAINED,
                params + mapOf("limit" to normalLow.format(), "hours" to lowHours.toString()), value = v,
            )
        }
        Evaluation(HealthState.OBSERVATION, ReasonCodes.LOW, params + ("condition" to "< ${normalLow.format()}"), value = v)
    }

    /**
     * Daily fluid intake as % of the target (spec section 7.8). NORMAL when the target is met. Below the target it is
     * OBSERVATION only with signs of dehydration (urine output low, creatinine or urea high) or electrolyte changes
     * (sodium high, potassium out of range); otherwise NORMAL with a "target not met" notice.
     */
    val fluidIntake = CustomEvaluator { def, input, _ ->
        val percent = input.scaled
            ?: return@CustomEvaluator Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.NO_REFERENCE, mapOf("unit" to def.unit))
        val params = mapOf("value" to input.measurement.value.format(), "unit" to def.unit, "evaluated" to percent.format())
        if (percent >= def.param("targetPercent")) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = percent)

        val runtime = input.runtime
        val findings = buildList {
            runtime.latest(Variable.DIURESIS)?.let { if (it < def.param("diuresisLow")) add(Variable.DIURESIS) }
            runtime.latest(Variable.CREATININE)?.let { if (it > def.param("creatinineHigh")) add(Variable.CREATININE) }
            runtime.latest(Variable.UREA)?.let { if (it > def.param("ureaHigh")) add(Variable.UREA) }
            runtime.latest(Variable.SODIUM)?.let { if (it > def.param("sodiumHigh")) add(Variable.SODIUM) }
            runtime.latest(Variable.POTASSIUM)?.let { if (it < def.param("potassiumLow") || it > def.param("potassiumHigh")) add(Variable.POTASSIUM) }
        }
        if (findings.isEmpty()) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.FLUID_TARGET_NOT_MET, params, value = percent)
        Evaluation(HealthState.OBSERVATION, ReasonCodes.FLUID_TARGET_WITH_FINDINGS, params + findings.param(), value = percent)
    }

    /**
     * Daily caloric intake as % of the target (spec section 7.8). ALERT below `alertBelowPercent` together with fatigue
     * from `fatigueHigh`, mood (PHQ-9) from `moodHigh` or glucose below `glucoseLow`, with no streak. Otherwise the
     * rule's thresholds apply (OBSERVATION out of the normal range three days in a row).
     */
    val caloricIntake = CustomEvaluator { def, input, previous ->
        val percent = input.scaled
            ?: return@CustomEvaluator Evaluation(HealthState.NOT_EVALUABLE, ReasonCodes.NO_REFERENCE, mapOf("unit" to def.unit))
        val params = mapOf("value" to input.measurement.value.format(), "unit" to def.unit, "evaluated" to percent.format())
        if (percent < def.param("alertBelowPercent")) {
            val runtime = input.runtime
            val findings = buildList {
                runtime.latest(Variable.FATIGUE)?.let { if (it >= def.param("fatigueHigh")) add(Variable.FATIGUE) }
                runtime.latest(Variable.MOOD_PHQ9)?.let { if (it >= def.param("moodHigh")) add(Variable.MOOD_PHQ9) }
                runtime.latest(Variable.GLUCOSE)?.let { if (it < def.param("glucoseLow")) add(Variable.GLUCOSE) }
            }
            if (findings.isNotEmpty()) {
                return@CustomEvaluator Evaluation(HealthState.ALERT, ReasonCodes.CALORIC_LOW_WITH_FINDINGS, params + findings.param(), value = percent)
            }
        }
        thresholds(def, input, previous, percent, params)
    }

    /**
     * Sleep hours (spec section 7.9). ALERT for one night below `shortNight` together with fatigue from `fatigueHigh`
     * and mood (PHQ-9) from `moodHigh`; otherwise the rule's thresholds apply (ALERT below 6 h three nights in a row).
     */
    val sleep = CustomEvaluator { def, input, previous ->
        val hours = input.measurement.value
        val params = mapOf("value" to hours.format(), "unit" to def.unit)
        val runtime = input.runtime
        val fatigue = runtime.latest(Variable.FATIGUE)
        val mood = runtime.latest(Variable.MOOD_PHQ9)
        if (hours < def.param("shortNight") && fatigue != null && fatigue >= def.param("fatigueHigh") && mood != null && mood >= def.param("moodHigh")) {
            return@CustomEvaluator Evaluation(
                HealthState.ALERT, ReasonCodes.SLEEP_SHORT_WITH_FINDINGS,
                params + listOf(Variable.FATIGUE, Variable.MOOD_PHQ9).param(), value = hours,
            )
        }
        thresholds(def, input, previous, hours, params)
    }

    /**
     * Vomiting episodes in 24 h (spec section 7.10). ALERT from `alertCount`, or below it together with liquid stool
     * (Bristol from `liquidStool`), abdominal pain or nausea from `symptomHigh`, or blood in the stool (a stool alarm
     * sign). OBSERVATION for episodes without those symptoms. Vomit alarm signs are handled before this rule.
     */
    val vomiting = CustomEvaluator { def, input, _ ->
        val count = input.measurement.value
        val params = mapOf("value" to count.format(), "unit" to def.unit)
        if (count <= 0) return@CustomEvaluator Evaluation(HealthState.NORMAL, ReasonCodes.IN_RANGE, params, value = count)
        val alertCount = def.param("alertCount")
        if (count >= alertCount) {
            return@CustomEvaluator Evaluation(HealthState.ALERT, ReasonCodes.HIGH, params + ("condition" to ">= ${alertCount.format()}"), value = count)
        }
        val runtime = input.runtime
        val stool = runtime.current(Variable.STOOL_FORM_BRISTOL)
        val findings = buildList {
            if (stool != null && (stool.value >= def.param("liquidStool") || stool.code == ReasonCodes.ALARM_SIGN)) add(Variable.STOOL_FORM_BRISTOL)
            runtime.latest(Variable.ABDOMINAL_PAIN)?.let { if (it >= def.param("symptomHigh")) add(Variable.ABDOMINAL_PAIN) }
            runtime.latest(Variable.NAUSEA)?.let { if (it >= def.param("symptomHigh")) add(Variable.NAUSEA) }
        }
        if (findings.isNotEmpty()) {
            return@CustomEvaluator Evaluation(HealthState.ALERT, ReasonCodes.VOMITING_WITH_FINDINGS, params + findings.param(), value = count)
        }
        Evaluation(HealthState.OBSERVATION, ReasonCodes.HIGH, params + ("condition" to ">= 1"), value = count)
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
        "SOLAR_EVENT_FLAG" to solarEventFlag,
        "CREATININE" to creatinine,
        "UREA" to urea,
        "DIURESIS" to diuresis,
        "FLUID_INTAKE" to fluidIntake,
        "CALORIC_INTAKE" to caloricIntake,
        "SLEEP_HOURS" to sleep,
        "VOMITING" to vomiting,
        "BONE_RESORPTION" to boneResorption,
        "BOWEL_FREQUENCY" to bowelFrequency,
    )

    /** Evaluators that look at previous values. */
    val withSamples = setOf("CREATININE", "UREA", "DIURESIS", "CALORIC_INTAKE", "SLEEP_HOURS", "BONE_RESORPTION", "BOWEL_FREQUENCY")

    /** Latest valid value of another variable of the subject (the evaluated value: raw, or % of a target). */
    private fun SubjectRuntime.latest(variable: Variable): Double? = current(variable)?.value

    /** Variables that make a combined condition true, as a reason parameter the frontend translates to names. */
    private fun List<Variable>.param(): Pair<String, String> = "findings" to joinToString(",") { it.name }

    /** The rule's own thresholds, with streaks, for evaluators that add a combined condition on top of them. */
    private fun thresholds(
        def: VariableDefinition,
        input: EvaluationInput,
        previous: VariableRuntime?,
        value: Double,
        params: Map<String, String>,
    ): Evaluation {
        val thresholds = def.thresholds ?: error("Rule ${def.variable} needs thresholds")
        return Classifier.classify(def, thresholds, value, params, false, input.now, previous?.samples.orEmpty()).copy(value = value)
    }

    /** [state] with a HIGH or LOW reason against the normal range [low]..[high]. */
    private fun outOfRange(state: HealthState, v: Double, low: Double, high: Double, params: Map<String, String>): Evaluation =
        if (v > high) {
            Evaluation(state, ReasonCodes.HIGH, params + ("condition" to "> ${high.format()}"), value = v)
        } else {
            Evaluation(state, ReasonCodes.LOW, params + ("condition" to "< ${low.format()}"), value = v)
        }

    private fun pending(params: Map<String, String>, count: Int, required: Int, state: HealthState, value: Double) = Evaluation(
        HealthState.NORMAL,
        ReasonCodes.PERSISTENCE_PENDING,
        params + mapOf("count" to count.toString(), "required" to required.toString(), "pendingState" to state.name),
        value = value,
    )

    private const val ACTIVE = "active"
    private const val START_DOSE = "startDose"
    private const val LAST_DOSE = "lastDose"
}
