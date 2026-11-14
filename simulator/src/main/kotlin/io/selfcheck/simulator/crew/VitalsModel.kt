package io.selfcheck.simulator.crew

import io.selfcheck.contracts.Variable
import org.springframework.stereotype.Component
import java.util.Random
import kotlin.math.roundToInt

/**
 * Continuous vital signs: baseline plus a slow random walk (natural variation), kept near the baseline.
 * Values are rounded like a bedside monitor reports them.
 */
@Component
class VitalsModel {

    private val random = Random(42)
    private val drift = mutableMapOf<Pair<String, Variable>, Double>()

    fun sample(member: CrewMember, variable: Variable): Double {
        val base = when (variable) {
            Variable.HEART_RATE -> member.baseline.heartRate
            Variable.SPO2 -> member.baseline.spo2
            Variable.RESPIRATORY_RATE -> member.baseline.respiratoryRate
            else -> error("$variable is not a continuous vital sign")
        }
        val spread = when (variable) {
            Variable.HEART_RATE -> 3.0
            Variable.SPO2 -> 0.8
            else -> 1.0
        }
        val key = member.id to variable
        // Mean-reverting walk: each step pulls 10% back toward the baseline.
        val next = (drift[key] ?: 0.0) * 0.9 + random.nextGaussian() * spread * 0.3
        drift[key] = next
        val value = (base + next).roundToInt().toDouble()
        return if (variable == Variable.SPO2) value.coerceAtMost(100.0) else value
    }
}
