package io.selfcheck.simulator.control

import io.selfcheck.contracts.ControlCommand
import io.selfcheck.contracts.Topics
import io.selfcheck.simulator.SimulationLoop
import io.selfcheck.simulator.clock.MissionClock
import io.selfcheck.simulator.mission.MissionEpoch
import io.selfcheck.simulator.mission.MissionSettings
import io.selfcheck.simulator.publish.MeasurementPublisher
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.admin.RecordsToDelete
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Service

/**
 * Mission reset (spec section 5): stops the simulator, empties the mission data in Kafka, tells the engine and the
 * api to clear their state (engine store, TimescaleDB tables) and starts the mission again from day 1.
 */
@Service
class ResetService(
    private val clock: MissionClock,
    private val loop: SimulationLoop,
    private val publisher: MeasurementPublisher,
    private val epoch: MissionEpoch,
    private val kafkaAdmin: KafkaAdmin,
    @Value("\${selfcheck.simulator.autostart:true}") private val autostart: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Every start of the simulator begins a fresh mission on day 1, so no stale mission data is left behind. */
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        reset()
        if (!autostart) clock.pause()
    }

    @Synchronized
    fun reset() {
        clock.pause()
        publisher.flush()
        Thread.sleep(SETTLE_MS) // let in-flight measurements reach the engine before the reset command

        // New mission epoch: the engine and the api drop anything older, in whatever order records arrive.
        epoch.next()
        publisher.publishControl(ControlCommand.RESET)
        Thread.sleep(SETTLE_MS) // engine and api process the command
        deleteMissionRecords()

        loop.clearMission()
        clock.reset(MissionSettings.START_TIME_MS)
        loop.startMission()
        log.info("Mission reset: day 1 started again")
    }

    /** Empties the topics with mission data. Profile and medical history are compacted and simply overwritten. */
    private fun deleteMissionRecords() {
        AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
            val partitions = TOPICS.map { TopicPartition(it, 0) }
            val latest = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get()
            admin.deleteRecords(latest.mapValues { RecordsToDelete.beforeOffset(it.value.offset()) }).all().get()
        }
    }

    companion object {
        const val SETTLE_MS = 1_500L
        val TOPICS = listOf(Topics.MEASUREMENTS, Topics.VARIABLE_STATES, Topics.SUMMARY_STATES, Topics.ALERTS, Topics.CREW_EVENTS)
    }
}
