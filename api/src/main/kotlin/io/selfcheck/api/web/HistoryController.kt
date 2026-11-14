package io.selfcheck.api.web

import io.selfcheck.api.history.DailyPoint
import io.selfcheck.api.history.HistoryRepository
import io.selfcheck.api.snapshot.SnapshotStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Trends for the control center: daily means of the last [days] mission days. */
@RestController
@RequestMapping("/api/history")
class HistoryController(private val history: HistoryRepository, private val snapshot: SnapshotStore) {

    @GetMapping("/{subjectId}")
    fun daily(@PathVariable subjectId: String, @RequestParam(defaultValue = "14") days: Int): Map<String, List<DailyPoint>> {
        val now = snapshot.snapshot().missionTimeMs
        val from = (now / HistoryRepository.DAY_MS - days.coerceIn(1, 60) + 1) * HistoryRepository.DAY_MS
        return history.dailyMeans(subjectId, from.coerceAtLeast(0))
    }
}
