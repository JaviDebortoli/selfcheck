package io.selfcheck.api.ingest

import io.selfcheck.api.history.HistoryRepository
import io.selfcheck.api.snapshot.SnapshotStore
import io.selfcheck.contracts.Topics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Keeps the api on one mission at a time (spec section 5, reset). Every record carries the mission epoch header:
 * records of an older epoch are dropped; the first record of a newer epoch clears the snapshot and the history.
 * The epoch is stored in TimescaleDB so restarting the api never clears the history of the running mission.
 */
@Component
class MissionEpochGuard(
    private val jdbc: JdbcTemplate,
    private val history: HistoryRepository,
    private val snapshot: SnapshotStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var current: Long? = null
    private var loaded = false

    /** Epoch of the running mission, to stamp records the api publishes itself. */
    @Synchronized
    fun currentEpoch(): Long? = current

    /** Whether the record belongs to the running mission (switching to it first if it is newer). */
    @Synchronized
    fun accept(record: ConsumerRecord<*, *>): Boolean {
        val epoch = record.headers().lastHeader(Topics.EPOCH_HEADER)?.value()?.decodeToString()?.toLongOrNull()
            ?: return true
        if (!loaded) {
            current = jdbc.queryForList("SELECT epoch FROM mission_epoch WHERE id = 1", Long::class.java).firstOrNull()
            loaded = true
        }
        val running = current
        if (running != null && epoch < running) return false
        if (running == null || epoch > running) {
            if (running != null) {
                history.clear()
                snapshot.clear()
                log.info("New mission epoch {}: snapshot and history cleared", epoch)
            }
            jdbc.update(
                "INSERT INTO mission_epoch (id, epoch) VALUES (1, ?) ON CONFLICT (id) DO UPDATE SET epoch = EXCLUDED.epoch",
                epoch,
            )
            current = epoch
        }
        return true
    }
}
