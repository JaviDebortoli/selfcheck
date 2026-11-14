package io.selfcheck.knowledge

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.Variable

/** Every rule in `knowledge/rules/`, one file per measurement group. */
class RuleCatalog(val groups: List<GroupDefinition>) {

    val variables: Map<Variable, VariableDefinition> =
        groups.flatMap { it.variables }.associateBy { it.variable }

    val groupOf: Map<Variable, MeasurementGroup> =
        groups.flatMap { g -> g.variables.map { it.variable to g.group } }.toMap()

    operator fun get(variable: Variable): VariableDefinition? = variables[variable]

    /** Variables of a group that can have a state; they decide the group's state. */
    fun statefulVariables(group: MeasurementGroup): List<VariableDefinition> =
        groups.firstOrNull { it.group == group }?.variables?.filter { it.hasState }.orEmpty()

    init {
        val duplicates = groups.flatMap { it.variables }.groupBy { it.variable }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Variables defined more than once: $duplicates" }
        variables.values.forEach { definition ->
            definition.thresholds?.values?.flatten()?.forEach(Condition::parse)
            definition.thresholdsBySex?.values?.flatMap { it.values }?.flatten()?.forEach(Condition::parse)
        }
    }

    companion object {
        private const val RULES_PATH = "knowledge/rules"

        val yaml: ObjectMapper = ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

        /** File name of a group's rules: `CARDIOVASCULAR_RESPIRATORY` -> `cardiovascular-respiratory.yaml`. */
        fun fileName(group: MeasurementGroup): String = group.name.lowercase().replace('_', '-') + ".yaml"

        /** Loads every group file present on the classpath. */
        fun load(classLoader: ClassLoader = RuleCatalog::class.java.classLoader): RuleCatalog =
            RuleCatalog(
                MeasurementGroup.entries.mapNotNull { group ->
                    classLoader.getResourceAsStream("$RULES_PATH/${fileName(group)}")?.use { stream ->
                        yaml.readValue<GroupDefinition>(stream).also {
                            require(it.group == group) { "${fileName(group)} declares group ${it.group}" }
                        }
                    }
                },
            )
    }
}
