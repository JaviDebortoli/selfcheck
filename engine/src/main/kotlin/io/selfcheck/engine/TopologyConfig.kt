package io.selfcheck.engine

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.Topics
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams

/** Phase 0 skeleton topology: reads measurements and logs them. The rule engine arrives in phases 1 and 2. */
@Configuration
@EnableKafkaStreams
class TopologyConfig(@Value("\${selfcheck.schema-registry-url}") private val schemaRegistryUrl: String) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun measurementSerde(): SpecificAvroSerde<Measurement> =
        SpecificAvroSerde<Measurement>().apply {
            configure(mapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl), false)
        }

    @Bean
    fun measurementStream(
        builder: StreamsBuilder,
        measurementSerde: SpecificAvroSerde<Measurement>,
    ): KStream<String, Measurement> =
        builder.stream(Topics.MEASUREMENTS, Consumed.with(Serdes.String(), measurementSerde))
            .peek { key, value -> log.info("Engine received measurement for {}: {}", key, value.variable) }
}
