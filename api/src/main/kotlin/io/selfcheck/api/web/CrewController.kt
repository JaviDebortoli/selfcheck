package io.selfcheck.api.web

import io.selfcheck.api.ingest.MissionEpochGuard
import io.selfcheck.api.snapshot.SnapshotStore
import io.selfcheck.contracts.CrewEvent
import io.selfcheck.contracts.CrewEventType
import io.selfcheck.contracts.Topics
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Crew actions from the quick view: start or stop physical training, and ask for (or cancel) medical assistance.
 * Each action is published as a [CrewEvent] so the control center and the simulator see it.
 */
@RestController
@RequestMapping("/api/crew")
class CrewController(
    private val kafka: KafkaTemplate<String, SpecificRecord>,
    private val snapshot: SnapshotStore,
    private val guard: MissionEpochGuard,
) {

    data class ActiveRequest(val active: Boolean)

    @PostMapping("/{crewMemberId}/training")
    fun training(@PathVariable crewMemberId: String, @RequestBody request: ActiveRequest): Map<String, Any> =
        publish(crewMemberId, CrewEventType.TRAINING, request.active)

    @PostMapping("/{crewMemberId}/assistance")
    fun assistance(@PathVariable crewMemberId: String, @RequestBody request: ActiveRequest): Map<String, Any> =
        publish(crewMemberId, CrewEventType.MEDICAL_ASSISTANCE, request.active)

    private fun publish(crewMemberId: String, type: CrewEventType, active: Boolean): Map<String, Any> {
        val view = snapshot.snapshot()
        if (view.crew.none { it.id == crewMemberId }) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown crew member $crewMemberId")
        }
        val event = CrewEvent(crewMemberId, type, active, view.missionTimeMs)
        val record = ProducerRecord<String, SpecificRecord>(Topics.CREW_EVENTS, crewMemberId, event).apply {
            guard.currentEpoch()?.let { headers().add(Topics.EPOCH_HEADER, it.toString().toByteArray()) }
        }
        kafka.send(record).get()
        return mapOf("crewMemberId" to crewMemberId, "type" to type.name, "active" to active)
    }
}
