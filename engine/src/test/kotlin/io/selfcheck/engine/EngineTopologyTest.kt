package io.selfcheck.engine

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.Variable
import io.selfcheck.contracts.VariableState
import io.selfcheck.engine.rules.ReasonCodes
import io.selfcheck.engine.stream.EngineTopology
import io.selfcheck.knowledge.RuleCatalog
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TestInputTopic
import org.apache.kafka.streams.TestOutputTopic
import org.apache.kafka.streams.TopologyTestDriver
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTopologyTest {

    private val registryUrl = "mock://engine-topology-test"
    private lateinit var driver: TopologyTestDriver
    private lateinit var input: TestInputTopic<String, Measurement>
    private lateinit var states: TestOutputTopic<String, SpecificRecord>
    private lateinit var summaries: TestOutputTopic<String, SpecificRecord>
    private lateinit var alerts: TestOutputTopic<String, SpecificRecord>

    @BeforeTest
    fun setUp() {
        val builder = StreamsBuilder()
        EngineTopology(RuleCatalog.load(), registryUrl).build(builder)
        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "engine-test")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("engine-test").toString())
        }
        driver = TopologyTestDriver(builder.build(), props)
        input = driver.createInputTopic(Topics.MEASUREMENTS, Serdes.String().serializer(), serde<Measurement>().serializer())
        states = driver.createOutputTopic(Topics.VARIABLE_STATES, Serdes.String().deserializer(), serde<SpecificRecord>().deserializer())
        summaries = driver.createOutputTopic(Topics.SUMMARY_STATES, Serdes.String().deserializer(), serde<SpecificRecord>().deserializer())
        alerts = driver.createOutputTopic(Topics.ALERTS, Serdes.String().deserializer(), serde<SpecificRecord>().deserializer())
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        MockSchemaRegistry.dropScope("engine-topology-test")
    }

    private fun <T : SpecificRecord> serde() = SpecificAvroSerde<T>().apply {
        configure(mapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to registryUrl), false)
    }

    private fun send(m: Measurement) = input.pipeInput(m.subjectId, m)

    private fun sendVitals(t: Long, hr: Double = 70.0, spo2: Double = 98.0, rr: Double = 14.0) {
        send(measurement(Variable.HEART_RATE, hr, t))
        send(measurement(Variable.SPO2, spo2, t))
        send(measurement(Variable.RESPIRATORY_RATE, rr, t))
    }

    private fun overallStates(): List<HealthState> =
        summaries.readValuesToList().map { it as SummaryState }.filter { it.group == null }.map { it.state }

    @Test
    fun `crew member turns red when heart rate is forced high, and the alert is not repeated`() {
        sendVitals(1_000)
        assertEquals(listOf(HealthState.NOT_EVALUABLE, HealthState.NOT_EVALUABLE, HealthState.NORMAL), overallStates())

        sendVitals(2_000, hr = 140.0)
        sendVitals(3_000, hr = 145.0)

        val raised = alerts.readValuesToList().map { it as Alert }
        assertEquals(1, raised.size)
        assertEquals(HealthState.ALERT, raised.single().state)
        assertEquals(Variable.HEART_RATE, raised.single().variable)

        val summary = summaries.readValuesToList().map { it as SummaryState }
        val overall = summary.last { it.group == null }
        assertEquals(HealthState.ALERT, overall.state)
        assertEquals(Variable.HEART_RATE, overall.triggerVariable)
        assertTrue(summary.any { it.group == MeasurementGroup.CARDIOVASCULAR_RESPIRATORY && it.state == HealthState.ALERT })
    }

    @Test
    fun `values expire after one mission minute without data`() {
        sendVitals(0)
        states.readValuesToList()
        // Only the habitat keeps sending: stream (mission) time moves on, crew-1 vitals go stale.
        send(measurement(Variable.CO2, 2.0, 61_000, subjectId = Topics.HABITAT_ID, context = null))
        send(measurement(Variable.CO2, 2.0, 66_000, subjectId = Topics.HABITAT_ID, context = null))

        val expired = states.readValuesToList().map { it as VariableState }.filter { it.subjectId == "crew-1" }
        assertEquals(setOf(Variable.HEART_RATE, Variable.SPO2, Variable.RESPIRATORY_RATE), expired.map { it.variable }.toSet())
        assertTrue(expired.all { it.state == HealthState.NOT_EVALUABLE && it.reason.code == ReasonCodes.DATA_EXPIRED })
        assertEquals(HealthState.NOT_EVALUABLE, overallStates().last())
    }

    @Test
    fun `measurements older than the processed mission time are discarded`() {
        sendVitals(10_000)
        states.readValuesToList()
        send(measurement(Variable.HEART_RATE, 150.0, 5_000))
        assertTrue(states.isEmpty)
        assertTrue(alerts.isEmpty)
    }

    @Test
    fun `heart rate during exercise has no state and does not change the group`() {
        sendVitals(1_000)
        summaries.readValuesToList()
        send(measurement(Variable.HEART_RATE, 160.0, 2_000, context = ActivityContext.EXERCISE))
        val state = states.readValuesToList().map { it as VariableState }.last()
        assertEquals(null, state.state)
        assertTrue(alerts.isEmpty)
        assertTrue(summaries.isEmpty)
    }
}
