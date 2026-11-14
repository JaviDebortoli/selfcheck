package io.selfcheck.api

import io.selfcheck.api.snapshot.SnapshotStore
import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.Quality
import io.selfcheck.contracts.Reason
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.Variable
import io.selfcheck.contracts.VariableState
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotStoreTest {

    private fun hr(value: Double, t: Long) = Measurement.newBuilder()
        .setSubjectType(SubjectType.CREW_MEMBER).setSubjectId("crew-1").setVariable(Variable.HEART_RATE)
        .setValue(value).setUnit("bpm").setMissionTimeMs(t).setQuality(Quality.GOOD).build()

    private fun state(state: HealthState, t: Long) = VariableState(
        SubjectType.CREW_MEMBER, "crew-1", Variable.HEART_RATE, state, false, Reason("HIGH", mapOf("value" to "140")), t,
    )

    @Test
    fun `combines values, states, summaries and alerts and ignores stale updates`() {
        val store = SnapshotStore()
        store.onMeasurement(hr(140.0, 2_000))
        store.onMeasurement(hr(70.0, 1_000))
        store.onVariableState(state(HealthState.ALERT, 2_000))
        store.onVariableState(state(HealthState.NORMAL, 1_000))
        store.onSummary(SummaryState(SubjectType.CREW_MEMBER, "crew-1", null, HealthState.ALERT, Variable.HEART_RATE, 2_000))
        store.onSummary(SummaryState(SubjectType.CREW_MEMBER, "crew-1", MeasurementGroup.CARDIOVASCULAR_RESPIRATORY, HealthState.ALERT, Variable.HEART_RATE, 2_000))
        store.onAlert(Alert("HIGH", mapOf("value" to "140"), SubjectType.CREW_MEMBER, "crew-1", Variable.HEART_RATE, HealthState.ALERT, 2_000))

        val view = store.snapshot()
        val crew = view.subjects.getValue("crew-1")
        val heartRate = crew.variables.getValue("HEART_RATE")
        assertEquals(2_000, view.missionTimeMs)
        assertEquals(140.0, heartRate.value)
        assertEquals("ALERT", heartRate.state)
        assertEquals("ALERT", crew.overall?.state)
        assertEquals("HEART_RATE", crew.groups.getValue("CARDIOVASCULAR_RESPIRATORY").triggerVariable)
        assertEquals(1, view.alerts.size)
    }
}
