package io.selfcheck.simulator.model

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.Variable
import io.selfcheck.simulator.clock.MissionClock.Companion.DAY_MS
import io.selfcheck.simulator.clock.MissionClock.Companion.HOUR_MS

/** How often each variable is measured (spec section 6.2). */
sealed interface Cadence {
    /** Every mission second at real-time speed. */
    data object Continuous : Cadence

    /** Every mission minute: cumulative dosimetry, space weather and slow habitat sensors. */
    data object EveryMinute : Cadence

    /** On every mission hour; each value stands for the hour before (urine output). */
    data object Hourly : Cadence

    /** Once every [everyDays] days, at [hour] of the mission day. */
    data class Periodic(val everyDays: Int, val hour: Int) : Cadence
}

object Schedule {

    private const val MORNING = 7
    private const val LAB = 8
    private const val EVENING = 21

    /**
     * Analysis intervals are PROVISIONAL (spec section 11): weekly blood chemistry, urinary calcium and strength;
     * monthly bone density and bone markers. They match the intervals noted in each rule in `knowledge/`.
     */
    val cadences: Map<Variable, Cadence> = buildMap {
        listOf(Variable.HEART_RATE, Variable.SPO2, Variable.RESPIRATORY_RATE, Variable.DOSE_RATE, Variable.CO2)
            .forEach { put(it, Cadence.Continuous) }
        listOf(
            Variable.MISSION_EFFECTIVE_DOSE, Variable.ABSORBED_DOSE, Variable.SOLAR_PARTICLE_EVENT,
            Variable.AIRBORNE_DUST, Variable.NOISE_DAY, Variable.NOISE_NIGHT,
        ).forEach { put(it, Cadence.EveryMinute) }
        put(Variable.DIURESIS, Cadence.Hourly)
        // Measurements due at the same time are sent in this order: rules that combine variables (sleep with
        // fatigue, vomiting with stool and pain, creatinine with urea) see the values of the same round.
        listOf(
            Variable.FATIGUE, Variable.SLEEP_HOURS, Variable.NAUSEA, Variable.MUSCULOSKELETAL_PAIN, Variable.BODY_TEMPERATURE,
            Variable.SYSTOLIC_BP, Variable.DIASTOLIC_BP, Variable.GLUCOSE, Variable.BODY_MASS, Variable.URINE_SPECIFIC_GRAVITY,
        ).forEach { put(it, Cadence.Periodic(1, MORNING)) }
        listOf(
            Variable.CALORIC_INTAKE, Variable.PROTEIN_INTAKE, Variable.FLUID_INTAKE, Variable.PHYSICAL_ACTIVITY,
            Variable.BOWEL_FREQUENCY, Variable.ABDOMINAL_PAIN, Variable.STOOL_FORM_BRISTOL, Variable.VOMITING, Variable.APPETITE,
        ).forEach { put(it, Cadence.Periodic(1, EVENING)) }
        listOf(
            Variable.HEMOGLOBIN, Variable.HEMATOCRIT, Variable.RED_BLOOD_CELLS, Variable.WHITE_BLOOD_CELLS, Variable.NEUTROPHILS,
            Variable.LYMPHOCYTES, Variable.PLATELETS, Variable.CRP, Variable.SODIUM, Variable.POTASSIUM, Variable.UREA, Variable.CREATININE,
            Variable.URINARY_CALCIUM, Variable.MUSCLE_STRENGTH,
        ).forEach { put(it, Cadence.Periodic(7, LAB)) }
        put(Variable.MOOD_PHQ9, Cadence.Periodic(14, LAB))
        listOf(
            Variable.SLEEP_QUALITY_PSQI, Variable.STRESS_PSS10, Variable.ATTENTION, Variable.MEMORY,
            Variable.BMD_HIP, Variable.BMD_SPINE, Variable.BMD_FEMORAL_NECK, Variable.BONE_RESORPTION_MARKERS,
        ).forEach { put(it, Cadence.Periodic(30, LAB)) }
    }

    /** Variables of the shared habitat; the rest belong to each crew member. */
    val habitatVariables = setOf(Variable.CO2, Variable.NOISE_DAY, Variable.NOISE_NIGHT, Variable.AIRBORNE_DUST)

    /** Yes/no alarm signs reported with each variable (spec section 7.12). */
    val alarmSigns: Map<Variable, List<AlarmSign>> = mapOf(
        Variable.RESPIRATORY_RATE to listOf(AlarmSign.RESPIRATORY_DISTRESS),
        Variable.HEART_RATE to listOf(AlarmSign.CHEST_PAIN, AlarmSign.SYNCOPE),
        Variable.URINARY_CALCIUM to listOf(AlarmSign.RENAL_COLIC_OR_HEMATURIA),
        Variable.MUSCULOSKELETAL_PAIN to listOf(AlarmSign.TRAUMA_OR_DEFORMITY),
        Variable.VOMITING to listOf(AlarmSign.VOMIT_WITH_BLOOD, AlarmSign.PERSISTENT_GREEN_VOMIT, AlarmSign.CANNOT_RETAIN_FLUIDS),
        Variable.ABDOMINAL_PAIN to listOf(AlarmSign.SUDDEN_ABDOMINAL_PAIN, AlarmSign.RIGID_ABDOMEN),
        Variable.BOWEL_FREQUENCY to listOf(AlarmSign.NO_STOOL_OR_GAS_WITH_DISTENSION),
        Variable.STOOL_FORM_BRISTOL to listOf(AlarmSign.BLACK_TARRY_STOOL, AlarmSign.HEAVY_BLOOD_IN_STOOL),
    )

    /** Hour of the mission day, 0 to 24. */
    fun hourOfDay(t: Long): Double = (t.mod(DAY_MS)).toDouble() / HOUR_MS

    /** Daily routine: sleep 23:00-06:30, exercise 10:00-11:00 and 17:00-18:00, rest otherwise. */
    fun activityAt(t: Long): ActivityContext {
        val hour = hourOfDay(t)
        return when {
            hour >= 23.0 || hour < 6.5 -> ActivityContext.SLEEP
            hour in 10.0..<11.0 || hour in 17.0..<18.0 -> ActivityContext.EXERCISE
            else -> ActivityContext.REST
        }
    }

    /** Daytime noise is measured 07:00-23:00 (16 h) and night-time noise 23:00-07:00 (8 h). */
    fun isDaytime(t: Long): Boolean = hourOfDay(t) in 7.0..<23.0

    /** Mission hours in (from, to]. */
    fun hourlyTimes(from: Long, to: Long): List<Long> {
        val result = mutableListOf<Long>()
        var t = (from / HOUR_MS + 1) * HOUR_MS
        while (t <= to) {
            result += t
            t += HOUR_MS
        }
        return result
    }

    /** Mission times in (from, to] at which a periodic variable is measured, after the day-1 initial round. */
    fun dueTimes(cadence: Cadence.Periodic, from: Long, to: Long): List<Long> {
        val result = mutableListOf<Long>()
        var day = from / DAY_MS
        while (day * DAY_MS <= to) {
            val t = day * DAY_MS + cadence.hour * HOUR_MS
            if (t in (from + 1)..to && day % cadence.everyDays == 0L && (day > 0 || cadence.everyDays == 1)) result += t
            day++
        }
        return result
    }
}
