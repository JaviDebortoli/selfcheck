package io.selfcheck.engine

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.ControlCommand
import io.selfcheck.contracts.MissionControl
import io.selfcheck.contracts.DailyTargets
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.PreflightBoneDensity
import io.selfcheck.contracts.Sex
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.Variable
import io.selfcheck.contracts.VariableState
import io.selfcheck.engine.stream.EngineTopology
import io.selfcheck.knowledge.RuleCatalog
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.TopologyTestDriver
import java.nio.file.Files
import java.util.Properties
import java.util.UUID

const val DAY = 86_400_000L
const val HOUR = 3_600_000L

/** Runs the real engine topology in memory and remembers everything it publishes. */
class EngineHarness : AutoCloseable {

    private val scope = "engine-" + UUID.randomUUID()
    private val registryUrl = "mock://$scope"
    private val driver: TopologyTestDriver
    private val measurements = mutableListOf<Measurement>()

    val states = mutableListOf<VariableState>()
    val summaries = mutableListOf<SummaryState>()
    val alerts = mutableListOf<Alert>()

    init {
        val builder = StreamsBuilder()
        EngineTopology(RuleCatalog.load(), registryUrl).build(builder)
        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, scope)
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("engine-test").toString())
        }
        driver = TopologyTestDriver(builder.build(), props)
    }

    private fun <T : SpecificRecord> serde() = SpecificAvroSerde<T>().apply {
        configure(mapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to registryUrl), false)
    }

    private val measurementInput by lazy { driver.createInputTopic(Topics.MEASUREMENTS, Serdes.String().serializer(), serde<Measurement>().serializer()) }
    private val outputs by lazy {
        listOf(Topics.VARIABLE_STATES, Topics.SUMMARY_STATES, Topics.ALERTS)
            .map { driver.createOutputTopic(it, Serdes.String().deserializer(), serde<SpecificRecord>().deserializer()) }
    }

    fun profile(environment: MissionEnvironment = MissionEnvironment.ORBITAL_STATION, plannedDays: Int = 180) {
        driver.createInputTopic(Topics.MISSION_PROFILE, Serdes.String().serializer(), serde<MissionProfile>().serializer())
            .pipeInput(Topics.MISSION_KEY, MissionProfile(environment, plannedDays, 0))
    }

    fun history(
        crew: String = "crew-1",
        sex: Sex = Sex.MALE,
        priorDose: Double = 0.0,
        strength: Double = 200.0,
        bodyMass: Double = 70.0,
        bmd: Triple<Double, Double, Double> = Triple(1.0, 1.0, 1.0),
        calories: Double = 2500.0,
        fluids: Double = 2500.0,
        activity: Double = 120.0,
    ) {
        driver.createInputTopic(Topics.MEDICAL_HISTORY, Serdes.String().serializer(), serde<MedicalHistory>().serializer())
            .pipeInput(
                crew,
                MedicalHistory(
                    crew, sex, priorDose, strength, bodyMass,
                    PreflightBoneDensity(bmd.first, bmd.second, bmd.third),
                    DailyTargets(calories, fluids, activity), 0,
                ),
            )
    }

    /** Mission reset command from the simulator's control API. */
    fun reset() {
        driver.createInputTopic(Topics.CONTROL, Serdes.String().serializer(), serde<MissionControl>().serializer())
            .pipeInput(Topics.MISSION_KEY, MissionControl(ControlCommand.RESET))
        collect()
    }

    fun send(m: Measurement) {
        measurements += m
        measurementInput.pipeInput(m.subjectId, m)
        collect()
    }

    private fun collect() {
        outputs.forEach { topic ->
            topic.readValuesToList().forEach {
                when (it) {
                    is VariableState -> states += it
                    is SummaryState -> summaries += it
                    is Alert -> alerts += it
                }
            }
        }
    }

    /** Latest published state of a variable. */
    fun state(variable: Variable, subject: String = "crew-1"): VariableState? =
        states.lastOrNull { it.variable == variable && it.subjectId == subject }

    fun stateOf(variable: Variable, subject: String = "crew-1"): HealthState? = state(variable, subject)?.state

    fun codeOf(variable: Variable, subject: String = "crew-1"): String? = state(variable, subject)?.reason?.code

    override fun close() {
        driver.close()
        MockSchemaRegistry.dropScope(scope)
    }
}
