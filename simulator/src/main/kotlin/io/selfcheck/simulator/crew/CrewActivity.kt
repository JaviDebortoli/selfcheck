package io.selfcheck.simulator.crew

import io.selfcheck.contracts.CrewEvent
import io.selfcheck.contracts.CrewEventType
import io.selfcheck.contracts.Topics
import io.selfcheck.simulator.mission.MissionEpoch
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Training sessions started from the quick view. While one runs, the ship's sensors see the crew member exercising:
 * vital signs carry the EXERCISE context and follow exercise physiology.
 */
@Component
class CrewActivity(private val epoch: MissionEpoch) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val training = ConcurrentHashMap.newKeySet<String>()

    fun isTraining(crewMemberId: String): Boolean = crewMemberId in training

    fun clear() = training.clear()

    @KafkaListener(
        topics = [Topics.CREW_EVENTS],
        groupId = "selfcheck-simulator-#{T(java.util.UUID).randomUUID()}",
        properties = ["auto.offset.reset=latest"],
    )
    fun onCrewEvent(record: ConsumerRecord<String, CrewEvent>) {
        val recordEpoch = record.headers().lastHeader(Topics.EPOCH_HEADER)?.value()?.decodeToString()?.toLongOrNull()
        if (recordEpoch != null && recordEpoch != epoch.value) return // another mission
        val event = record.value()
        if (event.type != CrewEventType.TRAINING) return
        if (event.active) training += event.crewMemberId else training -= event.crewMemberId
        log.info("Training {} for {}", if (event.active) "started" else "stopped", event.crewMemberId)
    }
}
