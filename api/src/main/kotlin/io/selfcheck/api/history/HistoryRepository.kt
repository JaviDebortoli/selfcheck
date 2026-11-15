package io.selfcheck.api.history

import com.fasterxml.jackson.databind.ObjectMapper
import io.selfcheck.api.snapshot.VariableView
import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.VariableState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Types

/** Writes the mission history to TimescaleDB. */
@Repository
class HistoryRepository(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {

    fun saveMeasurements(measurements: List<Measurement>) {
        if (measurements.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO measurement (mission_time_ms, subject_id, variable, value, unit, quality, activity_context, fasting, rhythm, alarm_signs)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            measurements,
            BATCH_SIZE,
        ) { ps, m ->
            ps.setLong(1, m.missionTimeMs)
            ps.setString(2, m.subjectId)
            ps.setString(3, m.variable.name)
            ps.setDouble(4, m.value)
            ps.setString(5, m.unit)
            ps.setString(6, m.quality.name)
            ps.setString(7, m.activityContext?.name)
            if (m.fasting == null) ps.setNull(8, Types.BOOLEAN) else ps.setBoolean(8, m.fasting)
            ps.setString(9, m.rhythm?.name)
            ps.setString(10, m.alarmSigns.filter { it.present }.joinToString(",") { it.sign.name }.ifEmpty { null })
        }
    }

    fun saveVariableState(s: VariableState) {
        jdbc.update(
            "INSERT INTO variable_state VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
            s.missionTimeMs, s.subjectId, s.variable.name, s.state?.name, s.toConfirm, s.reason.code, json.writeValueAsString(s.reason.params),
        )
    }

    fun saveSummary(s: SummaryState) {
        jdbc.update(
            "INSERT INTO summary_state VALUES (?, ?, ?, ?, ?)",
            s.missionTimeMs, s.subjectId, s.group?.name, s.state.name, s.triggerVariable?.name,
        )
    }

    fun saveAlert(a: Alert) {
        jdbc.update(
            "INSERT INTO alert (mission_time_ms, subject_id, variable, state, code, params) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
            a.missionTimeMs, a.subjectId, a.variable?.name, a.state.name, a.code, json.writeValueAsString(a.params),
        )
    }

    /** Latest value of every variable of every subject, used to rebuild the snapshot after a restart. */
    fun latestValues(): List<Triple<String, String, VariableView>> = jdbc.query(
        """
        SELECT DISTINCT ON (subject_id, variable) *
        FROM measurement
        ORDER BY subject_id, variable, mission_time_ms DESC
        """.trimIndent(),
    ) { rs, _ ->
        val subjectId = rs.getString("subject_id")
        val view = VariableView(
            variable = rs.getString("variable"),
            value = rs.getDouble("value"),
            unit = rs.getString("unit"),
            measuredAtMs = rs.getLong("mission_time_ms"),
            quality = rs.getString("quality"),
            context = rs.getString("activity_context"),
            fasting = rs.getObject("fasting") as Boolean?,
            rhythm = rs.getString("rhythm"),
            alarmSigns = rs.getString("alarm_signs")?.split(",").orEmpty(),
        )
        Triple(subjectId, if (subjectId == Topics.HABITAT_ID) "HABITAT" else "CREW_MEMBER", view)
    }

    /**
     * Daily means of every variable of a subject since [fromMissionTimeMs], for the trend sparklines.
     * Buckets are mission days (TimescaleDB time_bucket over the mission-time column).
     */
    fun dailyMeans(subjectId: String, fromMissionTimeMs: Long): Map<String, List<DailyPoint>> = jdbc.query(
        """
        SELECT variable, time_bucket($DAY_MS, mission_time_ms) AS day_start, avg(value) AS value
        FROM measurement
        WHERE subject_id = ? AND mission_time_ms >= ?
        GROUP BY variable, day_start
        ORDER BY variable, day_start
        """.trimIndent(),
        { rs, _ -> rs.getString("variable") to DailyPoint(rs.getLong("day_start") / DAY_MS + 1, rs.getDouble("value")) },
        subjectId,
        fromMissionTimeMs,
    ).groupBy({ it.first }, { it.second })

    /**
     * Values of one variable in [fromMs, toMs] grouped in buckets of [bucketMs] (TimescaleDB time_bucket). Each point
     * sits at the mean time of its measurements, so a bucket with one measurement is that measurement.
     */
    fun series(subjectId: String, variable: String, fromMs: Long, toMs: Long, bucketMs: Long): List<SeriesPoint> = jdbc.query(
        """
        SELECT avg(mission_time_ms)::bigint AS t, avg(value) AS avg, min(value) AS min, max(value) AS max, count(*) AS n
        FROM measurement
        WHERE subject_id = ? AND variable = ? AND mission_time_ms BETWEEN ? AND ?
        GROUP BY time_bucket(?, mission_time_ms)
        ORDER BY t
        """.trimIndent(),
        { rs, _ -> SeriesPoint(rs.getLong("t"), rs.getDouble("avg"), rs.getDouble("min"), rs.getDouble("max"), rs.getInt("n")) },
        subjectId,
        variable,
        fromMs,
        toMs,
        bucketMs,
    )

    /** Mission time of the first measurement (the mission start), null before any measurement. */
    fun firstMeasurementMs(): Long? = jdbc.queryForObject("SELECT min(mission_time_ms) FROM measurement", Long::class.javaObjectType)

    /** Every state change of one variable up to [toMs], oldest first. */
    fun stateChanges(subjectId: String, variable: String, toMs: Long): List<StateChange> = jdbc.query(
        """
        SELECT mission_time_ms, state
        FROM variable_state
        WHERE subject_id = ? AND variable = ? AND mission_time_ms <= ?
        ORDER BY mission_time_ms
        """.trimIndent(),
        { rs, _ -> StateChange(rs.getLong("mission_time_ms"), rs.getString("state")) },
        subjectId,
        variable,
        toMs,
    )

    /** Empties every history table (mission reset). */
    fun clear() {
        jdbc.execute("TRUNCATE measurement, variable_state, summary_state, alert")
    }

    companion object {
        const val BATCH_SIZE = 500
        const val DAY_MS = 86_400_000L
    }
}

/** Mean value of one mission day (day 1 is the first). */
data class DailyPoint(val day: Long, val value: Double)
