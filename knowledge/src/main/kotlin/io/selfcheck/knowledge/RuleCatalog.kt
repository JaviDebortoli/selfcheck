package io.selfcheck.knowledge

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.Variable

/** Every rule in `knowledge/`: one file per measurement group, plus clinical combinations and shared texts. */
class RuleCatalog(
    val groups: List<GroupDefinition>,
    val combinations: List<CombinationDefinition> = emptyList(),
    val shared: SharedTexts = SharedTexts(),
) {

    val variables: Map<Variable, VariableDefinition> =
        groups.flatMap { it.variables }.associateBy { it.variable }

    val groupOf: Map<Variable, MeasurementGroup> =
        groups.flatMap { g -> g.variables.map { it.variable to g.group } }.toMap()

    /** Rules triggered by a measurement of each variable: its own rule plus rules derived from it. */
    val triggeredBy: Map<Variable, List<VariableDefinition>> =
        Variable.entries.associateWith { input ->
            variables.values.filter { it.derivedFrom.contains(input) || (it.variable == input && it.derivedFrom.isEmpty()) }
        }

    operator fun get(variable: Variable): VariableDefinition? = variables[variable]

    fun combination(code: String): CombinationDefinition? = combinations.firstOrNull { it.code == code }

    /** Variables of a group that can have a state; they decide the group's state. */
    fun statefulVariables(group: MeasurementGroup): List<VariableDefinition> =
        groups.firstOrNull { it.group == group }?.variables?.filter { it.hasState }.orEmpty()

    init {
        val duplicates = groups.flatMap { it.variables }.groupBy { it.variable }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Variables defined more than once: $duplicates" }
        variables.values.forEach { definition ->
            definition.thresholds?.values?.flatten()?.forEach(Condition::parse)
            definition.thresholdsBySex?.values?.flatMap { it.values }?.flatten()?.forEach(Condition::parse)
            definition.alarmSignCondition?.let(Condition::parse)
        }
    }

    companion object {
        private const val ROOT = "knowledge"

        val yaml: ObjectMapper = ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

        /** File name of a group's rules: `CARDIOVASCULAR_RESPIRATORY` -> `cardiovascular-respiratory.yaml`. */
        fun fileName(group: MeasurementGroup): String = group.name.lowercase().replace('_', '-') + ".yaml"

        private data class CombinationFile(val combinations: List<CombinationDefinition>)

        /** Loads every rule file present on the classpath. */
        fun load(classLoader: ClassLoader = RuleCatalog::class.java.classLoader): RuleCatalog {
            val groups = MeasurementGroup.entries.mapNotNull { group ->
                classLoader.getResourceAsStream("$ROOT/rules/${fileName(group)}")?.use { stream ->
                    yaml.readValue<GroupDefinition>(stream).also {
                        require(it.group == group) { "${fileName(group)} declares group ${it.group}" }
                    }
                }
            }
            val combinations = classLoader.getResourceAsStream("$ROOT/combinations.yaml")
                ?.use { yaml.readValue<CombinationFile>(it).combinations }
                .orEmpty()
            val shared = classLoader.getResourceAsStream("$ROOT/texts.yaml")
                ?.use { yaml.readValue<SharedTexts>(it) }
                ?: SharedTexts()
            return RuleCatalog(groups, combinations, shared)
        }
    }
}
