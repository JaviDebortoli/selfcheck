package io.selfcheck.engine

import io.selfcheck.engine.stream.EngineTopology
import io.selfcheck.knowledge.RuleCatalog
import org.apache.kafka.streams.StreamsBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafkaStreams

@Configuration
@EnableKafkaStreams
class EngineConfig {

    @Bean
    fun ruleCatalog(): RuleCatalog = RuleCatalog.load()

    @Bean
    fun engineTopology(
        builder: StreamsBuilder,
        catalog: RuleCatalog,
        @Value("\${selfcheck.schema-registry-url}") schemaRegistryUrl: String,
    ): EngineTopology = EngineTopology(catalog, schemaRegistryUrl).also { it.build(builder) }
}
