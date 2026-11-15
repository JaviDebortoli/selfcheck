package io.selfcheck.engine

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.HealthState.ALERT
import io.selfcheck.contracts.HealthState.NORMAL
import io.selfcheck.contracts.HealthState.NOT_EVALUABLE
import io.selfcheck.contracts.HealthState.OBSERVATION
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.Quality
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.Variable
import io.selfcheck.engine.rules.EvaluationInput
import io.selfcheck.engine.rules.ReasonCodes
import io.selfcheck.engine.rules.RuleEvaluator
import io.selfcheck.engine.rules.SubjectRuntime
import io.selfcheck.engine.rules.VariableRuntime
import io.selfcheck.knowledge.RuleCatalog
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec section 7.5: heart rate, SpO2, respiratory rate and blood pressure. */
class CardiovascularRulesTest {

    private val catalog = RuleCatalog.load()
    private val evaluator = RuleEvaluator()

    private fun evaluate(m: Measurement, variable: Variable = m.variable): VariableRuntime? =
        evaluator.evaluate(catalog[variable]!!, EvaluationInput(m, m.missionTimeMs, SubjectRuntime(SubjectType.CREW_MEMBER, "crew-1")), null)

    private fun evaluate(variable: Variable, value: Double): VariableRuntime = evaluate(measurement(variable, value))!!

    @ParameterizedTest
    @CsvSource("39,ALERT", "40,OBSERVATION", "44,OBSERVATION", "45,NORMAL", "100,NORMAL", "101,OBSERVATION", "130,OBSERVATION", "131,ALERT")
    fun `heart rate at rest - in-flight normal from 45 bpm`(value: Double, expected: HealthState) {
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

    @ParameterizedTest
    @CsvSource("71,ALERT", "72,OBSERVATION", "81,OBSERVATION", "82,NORMAL", "120,NORMAL", "121,OBSERVATION", "139,OBSERVATION", "140,ALERT")
    fun `systolic pressure - in-flight low edges 82 and 72`(value: Double, expected: HealthState) {
        assertEquals(expected, evaluate(Variable.SYSTOLIC_BP, value).state)
    }

    @ParameterizedTest
    @CsvSource("40,ALERT", "41,OBSERVATION", "50,OBSERVATION", "51,NORMAL", "80,NORMAL", "81,OBSERVATION", "89,OBSERVATION", "90,ALERT")
    fun `diastolic pressure - in-flight low edges 51 and 41`(value: Double, expected: HealthState) {
        assertEquals(expected, evaluate(Variable.DIASTOLIC_BP, value).state)
    }

    @Test
    fun `respiratory distress sign gives alert even with a normal rate`() {
        val result = evaluate(measurement(Variable.RESPIRATORY_RATE, 16.0, signs = listOf(AlarmSign.RESPIRATORY_DISTRESS)))!!
        assertEquals(ALERT, result.state)
        assertEquals(ReasonCodes.ALARM_SIGN, result.code)
        assertEquals("RESPIRATORY_DISTRESS", result.params["signs"])
    }

    @Test
    fun `heart rate during exercise is shown without state`() {
        val result = evaluate(measurement(Variable.HEART_RATE, 150.0, context = ActivityContext.EXERCISE))!!
        assertNull(result.state)
        assertEquals(ReasonCodes.CONTEXT_NOT_APPLICABLE, result.code)
    }

    @Test
    fun `poor quality is not evaluable and doubtful quality is evaluated but flagged`() {
        assertEquals(NOT_EVALUABLE, evaluate(measurement(Variable.SPO2, 97.0, quality = Quality.POOR))!!.state)
        val doubtful = evaluate(measurement(Variable.SPO2, 93.0, quality = Quality.DOUBTFUL))!!
        assertEquals(OBSERVATION, doubtful.state)
        assertTrue(doubtful.toConfirm)
    }

    @Test
    fun `reason carries value, unit and matched condition`() {
        val result = evaluate(Variable.HEART_RATE, 135.0)
        assertEquals(ReasonCodes.HIGH, result.code)
        assertEquals(mapOf("value" to "135", "unit" to "bpm", "condition" to "> 130"), result.params)
        assertEquals(NORMAL, evaluate(Variable.HEART_RATE, 72.0).state)
        assertEquals(ReasonCodes.IN_RANGE, evaluate(Variable.HEART_RATE, 72.0).code)
    }
}
