package io.selfcheck.api.web

import io.selfcheck.api.history.DailyPoint
import io.selfcheck.api.history.HistoryRepository
import io.selfcheck.api.history.SeriesMath
import io.selfcheck.api.history.VariableSeries
import io.selfcheck.api.snapshot.SnapshotStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** History for the control center: daily trends and the chart of one variable. */
@RestController
@RequestMapping("/api/history")
class HistoryController(private val history: HistoryRepository, private val snapshot: SnapshotStore) {

    /** Daily means of the last [days] mission days. */
    @GetMapping("/{subjectId}")
    fun daily(@PathVariable subjectId: String, @RequestParam(defaultValue = "14") days: Int): Map<String, List<DailyPoint>> {
        val now = snapshot.snapshot().missionTimeMs
        val from = (now / HistoryRepository.DAY_MS - days.coerceIn(1, 60) + 1) * HistoryRepository.DAY_MS
        return history.dailyMeans(subjectId, from.coerceAtLeast(0))
    }

    /**
     * One variable in [from, to] (mission time; default the whole mission, from its first measurement): about 200
     * buckets of values whatever the range, so zooming in gives more detail, plus the periods of each state.
     * The career dose has no measurements of its own: it is the mission effective dose plus the dose of previous
     * missions from the medical history, the same sum the engine evaluates (spec section 7.4).
     */
    @GetMapping("/{subjectId}/{variable}")
    fun variable(
        @PathVariable subjectId: String,
        @PathVariable variable: String,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
    ): VariableSeries {
        val view = snapshot.snapshot()
        val toMs = (to ?: view.missionTimeMs).coerceIn(0, view.missionTimeMs)
        val fromMs = (from ?: history.firstMeasurementMs() ?: 0).coerceIn(0, toMs)
        val bucketMs = SeriesMath.bucketMs(fromMs, toMs)
        val points = if (variable == CAREER_DOSE) {
            val prior = view.crew.find { it.id == subjectId }?.priorEffectiveDoseMsv
            if (prior == null) {
                emptyList()
            } else {
                history.series(subjectId, MISSION_DOSE, fromMs, toMs, bucketMs).map { it.shifted(prior) }
            }
        } else {
            history.series(subjectId, variable, fromMs, toMs, bucketMs)
        }
        val states = SeriesMath.periods(history.stateChanges(subjectId, variable, toMs), fromMs, toMs)
        return VariableSeries(fromMs, toMs, bucketMs, points, states)
    }

    private companion object {
        const val CAREER_DOSE = "CAREER_EFFECTIVE_DOSE"
        const val MISSION_DOSE = "MISSION_EFFECTIVE_DOSE"
    }
}
