package io.selfcheck.api.ingest

import io.selfcheck.api.history.HistoryRepository
import io.selfcheck.api.snapshot.SnapshotStore
import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.ControlCommand
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionControl
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.VariableState
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Consumes every SelfCheck topic.
 *
 * - Measurements use a durable consumer group: each one is stored once and updates the snapshot.
 * - The engine's outputs, the profile and the medical history are small; a fresh group per start replays them
 *   from the beginning so the snapshot is complete after a restart. Their history rows are written by the
 *   durable `history` listeners.
 */
@Component
class EventListeners(private val snapshot: SnapshotStore, private val history: HistoryRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun restoreLatestValues() {
        val restored = history.latestValues()
        restored.forEach { (subjectId, type, view) -> snapshot.restoreValue(view, subjectId, type) }
        log.info("Restored {} latest values from history", restored.size)
    }

    @KafkaListener(topics = [Topics.MEASUREMENTS], groupId = "selfcheck-api-measurements", batch = "true")
    fun onMeasurements(measurements: List<Measurement>) {
        history.saveMeasurements(measurements)
        measurements.forEach(snapshot::onMeasurement)
    }

    @KafkaListener(topics = [Topics.MISSION_PROFILE], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onProfile(profile: MissionProfile) = snapshot.onProfile(profile)

    @KafkaListener(topics = [Topics.MEDICAL_HISTORY], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onHistory(medicalHistory: MedicalHistory) = snapshot.onHistory(medicalHistory)

    @KafkaListener(topics = [Topics.VARIABLE_STATES], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onVariableState(state: VariableState) = snapshot.onVariableState(state)

    @KafkaListener(topics = [Topics.SUMMARY_STATES], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onSummary(state: SummaryState) = snapshot.onSummary(state)

    @KafkaListener(topics = [Topics.ALERTS], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onAlert(alert: Alert) = snapshot.onAlert(alert)

    /** Mission reset (spec section 5): empties the snapshot and the TimescaleDB history. */
    @KafkaListener(topics = [Topics.CONTROL], groupId = REPLAY_GROUP, properties = ["auto.offset.reset=latest"])
    fun onControl(control: MissionControl) {
        if (control.command != ControlCommand.RESET) return
        history.clear()
        snapshot.clear()
        log.info("Mission reset: snapshot and history cleared")
    }

    @KafkaListener(topics = [Topics.VARIABLE_STATES], groupId = "selfcheck-api-history")
    fun storeVariableState(state: VariableState) = history.saveVariableState(state)

    @KafkaListener(topics = [Topics.SUMMARY_STATES], groupId = "selfcheck-api-history")
    fun storeSummary(state: SummaryState) = history.saveSummary(state)

    @KafkaListener(topics = [Topics.ALERTS], groupId = "selfcheck-api-history")
    fun storeAlert(alert: Alert) = history.saveAlert(alert)

    companion object {
        /** A new consumer group on every start, so these topics are always replayed from the beginning. */
        const val REPLAY_GROUP = "selfcheck-api-snapshot-#{T(java.util.UUID).randomUUID()}"
        const val EARLIEST = "auto.offset.reset=earliest"
    }
}
