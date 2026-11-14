package io.selfcheck.engine

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.HealthState.ALERT
import io.selfcheck.contracts.HealthState.NORMAL
import io.selfcheck.contracts.HealthState.NOT_EVALUABLE
import io.selfcheck.contracts.HealthState.OBSERVATION
import io.selfcheck.contracts.Quality
import io.selfcheck.contracts.Variable
import io.selfcheck.engine.rules.EvaluationContext
import io.selfcheck.engine.rules.Evaluation
import io.selfcheck.engine.rules.ReasonCodes
import io.selfcheck.engine.rules.RuleEvaluator
import io.selfcheck.knowledge.RuleCatalog
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec section 7.5: heart rate, SpO2 and respiratory rate. */
class CardiovascularRulesTest {

    private val catalog = RuleCatalog.load()
    private val evaluator = RuleEvaluator()

    private fun evaluate(variable: Variable, value: Double, build: (Double) -> io.selfcheck.contracts.Measurement = { measurement(variable, it) }): Evaluation =
        evaluator.evaluate(catalog[variable]!!, build(value), EvaluationContext())

    @ParameterizedTest
    @CsvSource("39,ALERT", "40,OBSERVATION", "59,OBSERVATION", "60,NORMAL", "100,NORMAL", "101,OBSERVATION", "130,OBSERVATION", "131,ALERT")
    fun `heart rate at rest`(value: Double, expected: HealthState) {
        assertEquals(expected, evaluate(Variable.HEART_RATE, value).state)
    }

    @ParameterizedTest
    @CsvSource("91,ALERT", "92,OBSERVATION", "94,OBSERVATION", "95,NORMAL", "100,NORMAL")
    fun spo2(value: Double, expected: HealthState) {
        assertEquals(expected, evaluate(Variable.SPO2, value).state)
    }

    @ParameterizedTest
    @CsvSource("7,ALERT", "8,OBSERVATION", "11,OBSERVATION", "12,NORMAL", "20,NORMAL", "21,OBSERVATION", "30,OBSERVATION", "31,ALERT")
    fun `respiratory rate`(value: Double, expected: HealthState) {
        assertEquals(expected, evaluate(Variable.RESPIRATORY_RATE, value).state)
    }

    @Test
    fun `respiratory distress sign gives alert even with a normal rate`() {
        val result = evaluate(Variable.RESPIRATORY_RATE, 16.0) {
            measurement(Variable.RESPIRATORY_RATE, it, signs = listOf(AlarmSign.RESPIRATORY_DISTRESS))
        }
        assertEquals(ALERT, result.state)
        assertEquals(ReasonCodes.ALARM_SIGN, result.code)
        assertEquals("RESPIRATORY_DISTRESS", result.params["signs"])
    }

    @Test
    fun `heart rate during exercise is shown without state`() {
        val result = evaluate(Variable.HEART_RATE, 150.0) { measurement(Variable.HEART_RATE, it, context = ActivityContext.EXERCISE) }
        assertNull(result.state)
        assertEquals(ReasonCodes.CONTEXT_NOT_APPLICABLE, result.code)
    }

    @Test
    fun `poor quality is not evaluable and doubtful quality is evaluated but flagged`() {
        val poor = evaluate(Variable.SPO2, 97.0) { measurement(Variable.SPO2, it, quality = Quality.POOR) }
        assertEquals(NOT_EVALUABLE, poor.state)

        val doubtful = evaluate(Variable.SPO2, 93.0) { measurement(Variable.SPO2, it, quality = Quality.DOUBTFUL) }
        assertEquals(OBSERVATION, doubtful.state)
        assertTrue(doubtful.toConfirm)
    }

    @Test
    fun `reason carries value, unit and matched condition`() {
        val result = evaluate(Variable.HEART_RATE, 135.0)
        assertEquals(ReasonCodes.HIGH, result.code)
        assertEquals(mapOf("value" to "135", "unit" to "bpm", "condition" to "> 130"), result.params)
        assertEquals(NORMAL, evaluate(Variable.HEART_RATE, 72.0).state)
    }
}
