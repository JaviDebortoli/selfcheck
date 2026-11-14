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

    /** Empties every history table (mission reset). */
    fun clear() {
        jdbc.execute("TRUNCATE measurement, variable_state, summary_state, alert")
    }

    companion object {
        const val BATCH_SIZE = 500
    }
}
