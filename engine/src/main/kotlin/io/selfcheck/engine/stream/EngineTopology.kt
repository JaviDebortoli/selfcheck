package io.selfcheck.engine.stream

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionControl
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.VariableState
import io.selfcheck.engine.rules.SubjectRuntime
import io.selfcheck.knowledge.RuleCatalog
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Produced
import org.apache.kafka.streams.processor.TimestampExtractor
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.Stores

/**
 * measurements -> SubjectProcessor -> variable-states, summary-states, alerts.
 * The medical history is a global store the processor reads.
 */
class EngineTopology(private val catalog: RuleCatalog, private val schemaRegistryUrl: String) {

    fun build(builder: StreamsBuilder) {
        val measurementSerde = avroSerde<Measurement>()
        val outputSerde = avroSerde<SpecificRecord>()

        builder.addGlobalStore(
            Stores.keyValueStoreBuilder(Stores.inMemoryKeyValueStore(HISTORY_STORE), Serdes.String(), avroSerde<MedicalHistory>())
                .withLoggingDisabled(),
            Topics.MEDICAL_HISTORY,
            Consumed.with(Serdes.String(), avroSerde<MedicalHistory>()),
            ProcessorSupplier<String, MedicalHistory, Void, Void> { GlobalStoreWriter(HISTORY_STORE) },
        )

        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(SUBJECT_STORE),
                Serdes.String(),
                JsonSerde(SubjectRuntime::class.java),
            ),
        )

        val measurements = builder
            .stream(Topics.MEASUREMENTS, Consumed.with(Serdes.String(), measurementSerde).withTimestampExtractor(MissionTimeExtractor()))
            .mapValues { value -> value as SpecificRecord }
        // Control commands share the processor (and its store) so a reset is applied in order with measurements.
        val control = builder
            .stream(Topics.CONTROL, Consumed.with(Serdes.String(), avroSerde<MissionControl>()))
            .mapValues { value -> value as SpecificRecord }

        val outputs = measurements.merge(control)
            .process(ProcessorSupplier<String, SpecificRecord, String, SpecificRecord> { SubjectProcessor(catalog) }, SUBJECT_STORE)

        outputs.filter { _, v -> v is VariableState }.to(Topics.VARIABLE_STATES, Produced.with(Serdes.String(), outputSerde))
        outputs.filter { _, v -> v is SummaryState }.to(Topics.SUMMARY_STATES, Produced.with(Serdes.String(), outputSerde))
        outputs.filter { _, v -> v is Alert }.to(Topics.ALERTS, Produced.with(Serdes.String(), outputSerde))
    }

    private fun <T : SpecificRecord> avroSerde(): SpecificAvroSerde<T> =
        SpecificAvroSerde<T>().apply {
            configure(mapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl), false)
        }

    companion object {
        const val SUBJECT_STORE = "subject-runtime"
        const val HISTORY_STORE = "medical-history"
    }
}

/** Uses the mission time carried by each measurement as the record timestamp (spec section 5). */
class MissionTimeExtractor : TimestampExtractor {
    override fun extract(record: ConsumerRecord<Any, Any>, partitionTime: Long): Long =
        (record.value() as? Measurement)?.missionTimeMs ?: partitionTime
}

/** Keeps the latest value of a compacted topic in a global store. */
class GlobalStoreWriter<V>(private val storeName: String) : Processor<String, V, Void, Void> {
    private lateinit var store: KeyValueStore<String, V>

    override fun init(context: ProcessorContext<Void, Void>) {
        store = context.getStateStore(storeName)
    }

    override fun process(record: Record<String, V>) {
        store.put(record.key(), record.value())
    }
}
