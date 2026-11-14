package io.selfcheck.engine.stream

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer

/** JSON serde for the engine's internal state stores. */
class JsonSerde<T : Any>(private val type: Class<T>) : Serde<T> {

    override fun serializer(): Serializer<T> = Serializer { _, data -> data?.let(mapper::writeValueAsBytes) }

    override fun deserializer(): Deserializer<T> = Deserializer { _, bytes -> bytes?.let { mapper.readValue(it, type) } }

    companion object {
        private val mapper = ObjectMapper()
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
