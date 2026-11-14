package io.selfcheck.knowledge

import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.Variable
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleCatalogTest {

    private val catalog = RuleCatalog.load()

    @Test
    fun `loads cardiovascular rules with texts in both languages`() {
        val heartRate = assertNotNull(catalog[Variable.HEART_RATE])
        assertEquals(MeasurementGroup.CARDIOVASCULAR_RESPIRATORY, catalog.groupOf[Variable.HEART_RATE])
        assertEquals(Duration.ofMinutes(1), heartRate.effectiveExpiry)
        assertTrue(heartRate.texts.keys.containsAll(listOf("es", "en")))
    }

    @Test
    fun `default expiry is one and a half times the interval`() {
        val daily = VariableDefinition(Variable.SLEEP_HOURS, "h", Duration.ofDays(1))
        assertEquals(Duration.ofHours(36), daily.effectiveExpiry)
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
