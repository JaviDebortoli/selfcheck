package io.selfcheck.api.ingest

import io.selfcheck.api.history.HistoryRepository
import io.selfcheck.api.snapshot.SnapshotStore
import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionControl
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.VariableState
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * Consumes every SelfCheck topic. Records pass the [MissionEpochGuard] first, so only the running mission is kept.
 *
 * - Measurements use a durable consumer group: each one is stored once and updates the snapshot.
 * - The engine's outputs, the profile and the medical history are small; a fresh group per start replays them
 *   from the beginning so the snapshot is complete after a restart. Their history rows are written by the
 *   durable `history` listeners.
 */
@Component
class EventListeners(
    private val snapshot: SnapshotStore,
    private val history: HistoryRepository,
    private val guard: MissionEpochGuard,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun restoreLatestValues() {
        val restored = history.latestValues()
        restored.forEach { (subjectId, type, view) -> snapshot.restoreValue(view, subjectId, type) }
        log.info("Restored {} latest values from history", restored.size)
    }

    @KafkaListener(topics = [Topics.MEASUREMENTS], groupId = "selfcheck-api-measurements", batch = "true")
    fun onMeasurements(records: List<ConsumerRecord<String, Measurement>>) {
        var batch = mutableListOf<Measurement>()
        for (record in records) {
            val before = snapshot.clearCount()
            if (!guard.accept(record)) continue
            // A new mission started inside this batch: what was collected before belongs to the old one.
            if (snapshot.clearCount() != before) batch = mutableListOf()
            batch += record.value()
        }
        history.saveMeasurements(batch)
        batch.forEach(snapshot::onMeasurement)
    }

    @KafkaListener(topics = [Topics.MISSION_PROFILE], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onProfile(record: ConsumerRecord<String, MissionProfile>) {
        if (guard.accept(record)) snapshot.onProfile(record.value())
    }

    @KafkaListener(topics = [Topics.MEDICAL_HISTORY], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onHistory(record: ConsumerRecord<String, MedicalHistory>) {
        if (guard.accept(record)) snapshot.onHistory(record.value())
    }

    @KafkaListener(topics = [Topics.VARIABLE_STATES], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onVariableState(record: ConsumerRecord<String, VariableState>) {
        if (guard.accept(record)) snapshot.onVariableState(record.value())
    }

    @KafkaListener(topics = [Topics.SUMMARY_STATES], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onSummary(record: ConsumerRecord<String, SummaryState>) {
        if (guard.accept(record)) snapshot.onSummary(record.value())
    }

    @KafkaListener(topics = [Topics.ALERTS], groupId = REPLAY_GROUP, properties = [EARLIEST])
    fun onAlert(record: ConsumerRecord<String, Alert>) {
        if (guard.accept(record)) snapshot.onAlert(record.value())
    }

    /** Mission reset (spec section 5): the guard clears the snapshot and the history on the new epoch. */
    @KafkaListener(topics = [Topics.CONTROL], groupId = REPLAY_GROUP, properties = [LATEST])
    fun onControl(record: ConsumerRecord<String, MissionControl>) {
        guard.accept(record)
    }

    @KafkaListener(topics = [Topics.VARIABLE_STATES], groupId = "selfcheck-api-history")
    fun storeVariableState(record: ConsumerRecord<String, VariableState>) {
        if (guard.accept(record)) history.saveVariableState(record.value())
    }

    @KafkaListener(topics = [Topics.SUMMARY_STATES], groupId = "selfcheck-api-history")
    fun storeSummary(record: ConsumerRecord<String, SummaryState>) {
        if (guard.accept(record)) history.saveSummary(record.value())
    }

    @KafkaListener(topics = [Topics.ALERTS], groupId = "selfcheck-api-history")
    fun storeAlert(record: ConsumerRecord<String, Alert>) {
        if (guard.accept(record)) history.saveAlert(record.value())
    }

    companion object {
        /** A new consumer group on every start, so these topics are always replayed from the beginning. */
        const val REPLAY_GROUP = "selfcheck-api-snapshot-#{T(java.util.UUID).randomUUID()}"
        const val EARLIEST = "auto.offset.reset=earliest"
        const val LATEST = "auto.offset.reset=latest"
    }
}
