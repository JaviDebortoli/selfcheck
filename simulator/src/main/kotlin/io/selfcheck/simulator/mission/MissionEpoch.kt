package io.selfcheck.simulator.mission

import org.springframework.stereotype.Component

/**
 * Identifier of the running mission, sent as a Kafka header with every record. It grows with every new or reset
 * mission (wall-clock milliseconds are used only as an increasing identifier, never to decide anything).
 */
@Component
class MissionEpoch {
    @Volatile
    private var current: Long = System.currentTimeMillis()

    val value: Long get() = current

    @Synchronized
    fun next(): Long {
        current = maxOf(System.currentTimeMillis(), current + 1)
        return current
    }
}
