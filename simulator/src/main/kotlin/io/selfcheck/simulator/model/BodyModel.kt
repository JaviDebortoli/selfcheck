package io.selfcheck.simulator.model

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.Sex
import io.selfcheck.contracts.Variable
import io.selfcheck.simulator.clock.MissionClock.Companion.DAY_MS
import io.selfcheck.simulator.crew.CrewMember
import io.selfcheck.simulator.mission.MissionSettings
import org.springframework.stereotype.Component
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/**
 * Values of a healthy young adult crew member (spec section 9): normal adult values, the usual in-flight
 * variation, a day/night rhythm and natural variation. On a normal mission day each member sleeps 7 to 7.5 h.
 *
 * Continuous signals follow a slow mean-reverting walk; daily and periodic values are drawn once per day from a
 * seed, so the same mission day always gives the same value.
 */
@Component
class BodyModel {

    private val walks = ConcurrentHashMap<Pair<String, Variable>, Double>()
    private val random = Random(7)

    fun reset() = walks.clear()

    /** Value of a crew member's variable at mission time [t]. */
    fun crew(
        member: CrewMember,
        variable: Variable,
        t: Long,
        environment: MissionEnvironment,
        activity: ActivityContext = Schedule.activityAt(t),
    ): Double {
        val day = (t / DAY_MS).toInt()
        val u = uniform(member.id, variable, day)
        val months = t.toDouble() / (30 * DAY_MS)
        val male = member.sex == Sex.MALE
        return when (variable) {
            // A healthy crew member stays inside the normal resting range; only scenarios push values out.
            Variable.HEART_RATE -> when (activity) {
                ActivityContext.SLEEP -> round(member.baseline.heartRate - 8 + walk(member.id, variable, 2.5))
                ActivityContext.EXERCISE -> round(128.0 + member.baseline.heartRate / 10 + walk(member.id, variable, 2.5))
                else -> round(member.baseline.heartRate + walk(member.id, variable, 2.5)).coerceIn(62.0, 90.0)
            }
            Variable.SPO2 -> round(member.baseline.spo2 + walk(member.id, variable, 0.6)).coerceIn(96.0, 100.0)
            Variable.RESPIRATORY_RATE -> round(
                when (activity) {
                    ActivityContext.SLEEP -> member.baseline.respiratoryRate - 1.5
                    ActivityContext.EXERCISE -> 18.0
                    else -> member.baseline.respiratoryRate
                } + walk(member.id, variable, 0.8),
            ).coerceIn(12.0, 20.0)
            Variable.DOSE_RATE -> MissionSettings.of(environment).doseRateMicroSvPerHour * (1 + walk(member.id, variable, 0.04))
            Variable.SOLAR_PARTICLE_EVENT -> 0.0

            Variable.SLEEP_HOURS -> round1(7.0 + 0.5 * u)
            Variable.FATIGUE -> round(1 + 2 * u).coerceAtMost(3.0)
            Variable.NAUSEA, Variable.MUSCULOSKELETAL_PAIN, Variable.VOMITING, Variable.ABDOMINAL_PAIN -> 0.0
            Variable.BODY_TEMPERATURE -> round1(36.5 + 0.5 * u)
            Variable.SYSTOLIC_BP -> round(106 + 12 * u)
            Variable.DIASTOLIC_BP -> round(66 + 10 * u)
            Variable.GLUCOSE -> round(82 + 12 * u)
            // In flight body mass drifts down by up to 1 % over the first two months.
            Variable.BODY_MASS -> round1(member.preflightBodyMassKg * (1 - 0.01 * (t.toDouble() / (60 * DAY_MS)).coerceAtMost(1.0)) + 0.4 * (u - 0.5))
            Variable.URINE_SPECIFIC_GRAVITY -> round3(1.012 + 0.010 * u)

            Variable.CALORIC_INTAKE -> round(member.dailyCaloriesKcal * (0.95 + 0.10 * u))
            Variable.PROTEIN_INTAKE -> round(90 + 20 * u)
            Variable.FLUID_INTAKE -> round(member.dailyFluidsMl * (1.0 + 0.15 * u))
            Variable.PHYSICAL_ACTIVITY -> round(member.dailyActivityMinutes + 15 * u)
            Variable.BOWEL_FREQUENCY -> if (u < 0.7) 1.0 else 2.0
            Variable.STOOL_FORM_BRISTOL -> if (u < 0.5) 3.0 else 4.0
            Variable.APPETITE -> round(6 + 2 * u)

            Variable.HEMOGLOBIN -> round1(if (male) 14.2 + 1.0 * u else 13.0 + 0.8 * u)
            Variable.HEMATOCRIT -> round(3 * (if (male) 14.2 + 1.0 * u else 13.0 + 0.8 * u))
            Variable.WHITE_BLOOD_CELLS -> round(5500 + 2000 * u, 100)
            Variable.NEUTROPHILS -> round(3000 + 1500 * u, 10)
            Variable.LYMPHOCYTES -> round(1500 + 1000 * u, 10)
            Variable.PLATELETS -> round(200_000 + 100_000 * u, 1000)
            Variable.CRP -> round1(0.5 + 1.5 * u)
            // Stable kidney function: the same value at every analysis.
            Variable.CREATININE -> if (male) 1.0 else 0.8
            Variable.UREA -> round(25 + 10 * u)
            Variable.SODIUM -> round(138 + 4 * u)
            Variable.POTASSIUM -> round1(3.9 + 0.6 * u)
            Variable.URINARY_CALCIUM -> round(180 + 70 * u)
            // Countermeasures keep losses small: strength 97 % minus 0.02 % per day, bone 0.3-0.4 % per month.
            Variable.MUSCLE_STRENGTH -> round1(member.preflightMuscleStrength * (0.97 - 0.0002 * day))
            Variable.BMD_HIP -> round3(member.preflightBmdHip * (1 - 0.003 * months))
            Variable.BMD_SPINE -> round3(member.preflightBmdSpine * (1 - 0.003 * months))
            Variable.BMD_FEMORAL_NECK -> round3(member.preflightBmdFemoralNeck * (1 - 0.004 * months))
            Variable.BONE_RESORPTION_MARKERS -> round3(0.3 + 0.2 * u)

            Variable.MOOD_PHQ9 -> round(3 * u)
            Variable.SLEEP_QUALITY_PSQI -> round(3 + 2 * u)
            Variable.STRESS_PSS10 -> round(10 + 5 * u)
            Variable.ATTENTION -> round(280 + 40 * u)
            Variable.MEMORY -> round(85 + 10 * u)
            else -> error("$variable is not a crew measurement")
        }
    }

    /** Value of a habitat sensor at mission time [t]. */
    fun habitat(variable: Variable, t: Long, environment: MissionEnvironment): Double = when (variable) {
        Variable.CO2 -> round2(2.2 + walk("habitat", variable, 0.15))
        Variable.NOISE_DAY -> round1(56.0 + walk("habitat", variable, 1.0))
        Variable.NOISE_NIGHT -> round1(46.0 + walk("habitat", variable, 1.0))
        Variable.AIRBORNE_DUST -> round3((MissionSettings.of(environment).dustMgPerM3 * (1 + walk("habitat", variable, 0.15))).coerceAtLeast(0.0))
        else -> error("$variable is not a habitat measurement")
    }

    /** Pre-flight value or daily target that a scenario percentage refers to. */
    fun reference(member: CrewMember, variable: Variable): Double? = when (variable) {
        Variable.MUSCLE_STRENGTH -> member.preflightMuscleStrength
        Variable.BMD_HIP -> member.preflightBmdHip
        Variable.BMD_SPINE -> member.preflightBmdSpine
        Variable.BMD_FEMORAL_NECK -> member.preflightBmdFemoralNeck
        Variable.BODY_MASS -> member.preflightBodyMassKg
        Variable.CALORIC_INTAKE -> member.dailyCaloriesKcal
        Variable.FLUID_INTAKE -> member.dailyFluidsMl
        Variable.PHYSICAL_ACTIVITY -> member.dailyActivityMinutes
        else -> null
    }

    /** Mean-reverting random walk: each step pulls 10 % back toward zero. */
    private fun walk(subject: String, variable: Variable, spread: Double): Double {
        val next = (walks[subject to variable] ?: 0.0) * 0.9 + random.nextGaussian() * spread * 0.3
        walks[subject to variable] = next
        return next
    }

    private fun uniform(subject: String, variable: Variable, day: Int): Double =
        Random(31L * subject.hashCode() + 17L * variable.ordinal + 1_000_003L * day).nextDouble()

    private fun round1(v: Double) = round(v * 10) / 10
    private fun round2(v: Double) = round(v * 100) / 100
    private fun round3(v: Double) = round(v * 1000) / 1000
    private fun round(v: Double, step: Int) = round(v / step) * step
}
