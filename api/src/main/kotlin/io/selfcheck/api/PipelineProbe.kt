package io.selfcheck.api

import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.Topics
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Phase 0 smoke probe: proves Avro measurements reach the api. Replaced by persistence and SSE in phase 1. */
@RestController
@RequestMapping("/api/pipeline")
class PipelineProbe {

    private val log = LoggerFactory.getLogger(javaClass)
    private val received = AtomicLong()
    private val last = AtomicReference<Map<String, Any?>>()

    @KafkaListener(topics = [Topics.MEASUREMENTS], groupId = "selfcheck-api")
    fun onMeasurement(measurement: Measurement) {
        received.incrementAndGet()
        last.set(
            mapOf(
                "subjectId" to measurement.subjectId,
                "variable" to measurement.variable.name,
                "value" to measurement.value,
                "unit" to measurement.unit,
                "missionTimeMs" to measurement.missionTimeMs,
                "quality" to measurement.quality.name,
                "rhythm" to measurement.rhythm?.name,
            ),
        )
        log.info("Api received measurement: {}", measurement)
    }

    @GetMapping
    fun status(): Map<String, Any?> = mapOf("received" to received.get(), "lastMeasurement" to last.get())
}
