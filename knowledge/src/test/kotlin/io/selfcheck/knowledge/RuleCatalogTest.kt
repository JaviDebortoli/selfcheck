package io.selfcheck.knowledge

import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.Variable
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleCatalogTest {

    /** A state, optionally narrowed by the direction of the value and the crew member's sex. */
    private val guidanceKey = Regex("^(OBSERVATION|ALERT)(_(HIGH|LOW))?(_(MALE|FEMALE))?$")

    private val catalog = RuleCatalog.load()
    private val languages = listOf("es", "en")

    @Test
    fun `every variable of spec section 6 belongs to exactly one group file`() {
        assertEquals(Variable.entries.toSet(), catalog.variables.keys)
        assertEquals(MeasurementGroup.entries.toSet(), catalog.groups.map { it.group }.toSet())
    }

    @Test
    fun `every variable and group has texts in Spanish and English`() {
        catalog.groups.forEach { group ->
            languages.forEach { assertNotNull(group.texts[it], "${group.group} $it") }
            group.variables.forEach { definition ->
                languages.forEach { language ->
                    val texts = assertNotNull(definition.texts[language], "${definition.variable} $language")
                    assertTrue(texts.name.isNotBlank() && texts.measures.isNotBlank(), "${definition.variable} $language")
                    texts.guidance.keys.forEach { key ->
                        assertTrue(definition.hasState && guidanceKey.matches(key), "${definition.variable} $language $key")
                    }
                }
            }
        }
    }

    @Test
    fun `recommendations exist only for Observation and Alert, with the same keys in both languages`() {
        catalog.variables.values.forEach { definition ->
            assertEquals(definition.texts["es"]?.guidance?.keys, definition.texts["en"]?.guidance?.keys, "${definition.variable}")
        }
        val hemoglobin = catalog[Variable.HEMOGLOBIN]!!.texts.getValue("es").guidance
        assertEquals(setOf("OBSERVATION", "ALERT", "OBSERVATION_HIGH_MALE", "ALERT_HIGH_MALE", "OBSERVATION_HIGH_FEMALE", "ALERT_HIGH_FEMALE"), hemoglobin.keys)
        assertTrue(catalog.variables.values.none { it.texts.values.any { texts -> HealthState.NORMAL.name in texts.guidance } })
    }

    @Test
    fun `shared texts cover every alarm sign, rhythm and combination in both languages`() {
        AlarmSign.entries.forEach { sign -> languages.forEach { assertNotNull(catalog.shared.alarmSigns[sign.name]?.get(it), "$sign $it") } }
        RhythmLabel.entries.forEach { label -> languages.forEach { assertNotNull(catalog.shared.rhythms[label.name]?.get(it), "$label $it") } }
        assertEquals(7, catalog.combinations.size)
        catalog.combinations.forEach { combination -> languages.forEach { assertNotNull(combination.texts[it], "${combination.code} $it") } }
    }

    @Test
    fun `derived variables are triggered by their inputs`() {
        assertEquals(
            setOf(Variable.HEART_RATE, Variable.ECG_RHYTHM),
            catalog.triggeredBy.getValue(Variable.HEART_RATE).map { it.variable }.toSet(),
        )
        assertEquals(
            setOf(Variable.MISSION_EFFECTIVE_DOSE, Variable.CAREER_EFFECTIVE_DOSE, Variable.SOLAR_EVENT_DOSE),
            catalog.triggeredBy.getValue(Variable.MISSION_EFFECTIVE_DOSE).map { it.variable }.toSet(),
        )
    }

    @Test
    fun `default expiry is one and a half times the interval`() {
        assertEquals(Duration.ofHours(36), catalog[Variable.SLEEP_HOURS]!!.effectiveExpiry)
        assertEquals(Duration.ofDays(21), catalog[Variable.MOOD_PHQ9]!!.effectiveExpiry)
        assertEquals(Duration.ofDays(45), catalog[Variable.SLEEP_QUALITY_PSQI]!!.effectiveExpiry)
        assertEquals(Duration.ofMinutes(1), catalog[Variable.HEART_RATE]!!.effectiveExpiry)
    }

    @Test
    fun `HRP sources are cited and in-flight averages are documented as a team decision`() {
        fun source(variable: Variable) = catalog[variable]!!.source
        assertTrue("OCHMO-MTB-004" in source(Variable.VOMITING))
        assertTrue("OCHMO-TB-041" in source(Variable.SLEEP_HOURS) && "[V1 6001]" in source(Variable.SLEEP_HOURS))
        listOf(Variable.BMD_HIP, Variable.BMD_SPINE, Variable.BMD_FEMORAL_NECK).forEach { bmd ->
            assertTrue(catalog[bmd]!!.notes.any { "only before and after flight" in it }, "$bmd")
        }
        val inFlightAverages = listOf(
            Variable.HEART_RATE, Variable.SYSTOLIC_BP, Variable.DIASTOLIC_BP, Variable.BODY_MASS, Variable.CALORIC_INTAKE,
            Variable.URINARY_CALCIUM, Variable.BONE_RESORPTION_MARKERS, Variable.HEMATOCRIT, Variable.MUSCLE_STRENGTH,
            Variable.PROTEIN_INTAKE, Variable.BODY_TEMPERATURE,
        )
        inFlightAverages.forEach { variable ->
            assertTrue(catalog[variable]!!.notes.any { "team decision (2026-11-15)" in it }, "$variable")
        }
        assertTrue(catalog[Variable.HEMATOCRIT]!!.notes.none { "incompatible" in it })
        assertTrue(catalog[Variable.BODY_TEMPERATURE]!!.notes.any { "Stahn 2017" in it && "37.7" in it })
    }

    @Test
    fun `conditions parse comparisons and inclusive ranges`() {
        assertTrue(Condition.parse("< 40").matches(39.9))
        assertFalse(Condition.parse("< 40").matches(40.0))
        assertTrue(Condition.parse("<= 91").matches(91.0))
        assertTrue(Condition.parse(">= 6.0").matches(6.0))
        assertTrue(Condition.parse("60..100").matches(60.0))
        assertTrue(Condition.parse("60..100").matches(100.0))
        assertFalse(Condition.parse("60..100").matches(100.5))
        assertEquals(Direction.HIGH, Condition.parse("> 130").direction)
        assertFailsWith<IllegalArgumentException> { Condition.parse("about 40") }
    }
}
