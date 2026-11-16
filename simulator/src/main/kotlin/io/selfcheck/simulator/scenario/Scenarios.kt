package io.selfcheck.simulator.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.Sex
import io.selfcheck.contracts.Variable
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Who a scenario acts on. */
enum class ScenarioTarget { CREW_MEMBER, ALL_CREW, HABITAT }

/**
 * Changes one variable while the scenario runs, between [from] and [until] after it starts.
 * The value is either absolute or a percentage of the crew member's pre-flight value or daily target. [femaleValue]
 * replaces [value] for women where the cut depends on sex (hemoglobin, red blood cells, hematocrit).
 */
data class ScenarioEffect(
    val variable: Variable,
    val value: Double? = null,
    val femaleValue: Double? = null,
    val percentOfReference: Double? = null,
    val rhythm: RhythmLabel? = null,
    val signs: List<AlarmSign> = emptyList(),
    val from: Duration = Duration.ZERO,
    val until: Duration,
) {
    fun valueFor(sex: Sex?): Double? = if (sex == Sex.FEMALE) femaleValue ?: value else value
}

data class ScenarioTexts(val name: String, val description: String)

/** A scripted situation from a YAML file in the scenarios folder (spec section 9). */
data class ScenarioDefinition(
    val id: String,
    val target: ScenarioTarget,
    val texts: Map<String, ScenarioTexts>,
    val effects: List<ScenarioEffect>,
    /** Variables measured right away when the scenario starts (on-demand analyses or readings). */
    val measureNow: List<Variable> = emptyList(),
) {
    val duration: Duration get() = effects.maxOf { it.until }
}

/** A scenario running since [startedAt] (mission time) on [subjects]. */
data class ActiveScenario(val definition: ScenarioDefinition, val subjects: Set<String>, val startedAt: Long) {
    fun endsAt(): Long = startedAt + definition.duration.toMillis()
}

/** Loads the YAML scenarios and keeps track of the ones running. */
@Component
class ScenarioRunner {

    val library: Map<String, ScenarioDefinition> = load()
    private val active = CopyOnWriteArrayList<ActiveScenario>()

    fun start(definition: ScenarioDefinition, subjects: Set<String>, now: Long): ActiveScenario =
        ActiveScenario(definition, subjects, now).also { active += it }

    fun active(now: Long): List<ActiveScenario> {
        active.removeIf { now >= it.endsAt() }
        return active.toList()
    }

    fun clear() = active.clear()

    /** Effect of the most recently started scenario acting on this subject and variable at mission time [t]. */
    fun effect(subjectId: String, variable: Variable, t: Long): ScenarioEffect? =
        active.asReversed().firstNotNullOfOrNull { scenario ->
            if (subjectId !in scenario.subjects) return@firstNotNullOfOrNull null
            val elapsed = t - scenario.startedAt
            scenario.definition.effects.firstOrNull {
                it.variable == variable && elapsed >= it.from.toMillis() && elapsed < it.until.toMillis()
            }
        }

    private fun load(): Map<String, ScenarioDefinition> {
        val yaml = ObjectMapper(YAMLFactory()).registerKotlinModule().registerModule(JavaTimeModule())
        return PathMatchingResourcePatternResolver().getResources("classpath*:scenarios/*.yaml")
            .map { resource -> resource.inputStream.use { yaml.readValue<ScenarioDefinition>(it) } }
            .sortedBy { it.id }
            .associateBy { it.id }
    }
}
