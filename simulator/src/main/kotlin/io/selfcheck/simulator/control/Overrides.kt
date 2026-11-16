package io.selfcheck.simulator.control

import io.selfcheck.contracts.Variable
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Values forced from the control API; they replace the model's value until cleared. */
@Component
class Overrides {
    private val values = ConcurrentHashMap<Pair<String, Variable>, Double>()

    fun set(subjectId: String, variable: Variable, value: Double) {
        values[subjectId to variable] = value
    }

    fun get(subjectId: String, variable: Variable): Double? = values[subjectId to variable]

    fun clear() = values.clear()

    fun all(): List<Map<String, Any>> =
        values.map { (key, value) -> mapOf("subjectId" to key.first, "variable" to key.second.name, "value" to value) }
}
