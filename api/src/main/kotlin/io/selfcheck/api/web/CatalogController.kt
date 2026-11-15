package io.selfcheck.api.web

import io.selfcheck.knowledge.RuleCatalog
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Configuration
class CatalogConfig {
    @Bean
    fun ruleCatalog(): RuleCatalog = RuleCatalog.load()
}

/**
 * Text catalog of one language (spec section 4.4): the frontend translates the codes the backend sends
 * (variables, groups, reasons, alarm signs, rhythms, combinations) with it.
 */
@RestController
@RequestMapping("/api/catalog")
class CatalogController(private val catalog: RuleCatalog) {

    @GetMapping("/{language}")
    fun catalog(@PathVariable language: String): Map<String, Any?> {
        if (language !in LANGUAGES) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unsupported language $language")
        val shared = catalog.shared
        fun Map<String, Map<String, String>>.translated() = mapValues { it.value[language] }
        return mapOf(
            "language" to language,
            "groups" to catalog.groups.associate { it.group.name to mapOf("name" to it.texts[language]?.name) },
            "variables" to catalog.groups.flatMap { group ->
                group.variables.map { definition ->
                    val texts = definition.texts[language]
                    definition.variable.name to mapOf(
                        "group" to group.group.name,
                        "name" to texts?.name,
                        "measures" to texts?.measures,
                        "guidance" to texts?.guidance,
                        "unit" to definition.unit,
                        "hasState" to definition.hasState,
                        // False when the state repeats another variable's (dose rate, mission dose, solar event alert).
                        "raisesAlerts" to definition.raisesAlerts,
                        "review" to definition.review,
                        "source" to definition.source,
                        // Rules evaluated as a % of a pre-flight value or daily target, or as a window mean.
                        "reference" to definition.reference?.name,
                        "window" to definition.window?.toString(),
                        "notes" to definition.notes,
                    )
                }
            }.toMap(),
            "reasons" to shared.reasons.translated(),
            "alarmSigns" to shared.alarmSigns.translated(),
            "rhythms" to shared.rhythms.translated(),
            "contexts" to shared.contexts.translated(),
            "combinations" to catalog.combinations.associate { combination ->
                combination.code to combination.texts[language]?.let { mapOf("name" to it.name, "interpretation" to it.interpretation) }
            },
        )
    }

    companion object {
        val LANGUAGES = setOf("es", "en")
    }
}
