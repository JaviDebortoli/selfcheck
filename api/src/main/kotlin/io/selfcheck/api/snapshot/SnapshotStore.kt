package io.selfcheck.api.snapshot

import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.VariableState
import org.springframework.stereotype.Component

/**
 * Latest known state of the mission, kept in memory and served as a snapshot (REST) and live (SSE).
 * Every change bumps [version] so the SSE broadcaster only sends when something changed.
 */
@Component
class SnapshotStore {

    private val lock = Any()
    private var version = 0L
    private var missionTimeMs = 0L
    private var mission = MissionView(null, null)
    private val crew = sortedMapOf<String, CrewMemberView>()
    private val subjects = sortedMapOf<String, MutableSubject>()
    private val alerts = ArrayDeque<AlertView>()

    private class MutableSubject(val id: String, val type: String) {
        var overall: SummaryView? = null
        val groups = sortedMapOf<String, SummaryView>()
        val variables = sortedMapOf<String, VariableView>()

        fun view() = SubjectView(id, type, overall, groups.toMap(), variables.toMap())
    }

    fun currentVersion(): Long = synchronized(lock) { version }

    fun snapshot(): SnapshotView = synchronized(lock) {
        SnapshotView(
            version = version,
            missionTimeMs = missionTimeMs,
            mission = mission,
            crew = crew.values.toList(),
            subjects = subjects.mapValues { it.value.view() },
            alerts = alerts.toList(),
        )
    }

    fun onProfile(profile: MissionProfile) = change {
        mission = MissionView(profile.environment.name, profile.plannedDurationDays)
    }

    fun onHistory(history: MedicalHistory) = change {
        crew[history.crewMemberId] = CrewMemberView(
            id = history.crewMemberId,
            sex = history.sex.name,
            priorEffectiveDoseMsv = history.priorEffectiveDoseMsv,
            preflightMuscleStrength = history.preflightMuscleStrength,
            preflightBodyMassKg = history.preflightBodyMassKg,
            preflightBoneDensity = history.preflightBoneDensity.let {
                mapOf("hip" to it.hip, "spine" to it.spine, "femoralNeck" to it.femoralNeck)
            },
            dailyTargets = history.dailyTargets.let {
                mapOf("caloriesKcal" to it.caloriesKcal, "fluidsMl" to it.fluidsMl, "activityMinutes" to it.activityMinutes)
            },
        )
        subject(history.crewMemberId, SubjectType.CREW_MEMBER.name)
    }

    fun onMeasurement(m: Measurement) = change {
        missionTimeMs = maxOf(missionTimeMs, m.missionTimeMs)
        val subject = subject(m.subjectId, m.subjectType.name)
        val key = m.variable.name
        val current = subject.variables[key] ?: VariableView(key)
        if ((current.measuredAtMs ?: Long.MIN_VALUE) > m.missionTimeMs) return@change
        subject.variables[key] = current.copy(
            value = m.value,
            unit = m.unit,
            measuredAtMs = m.missionTimeMs,
            quality = m.quality.name,
            context = m.activityContext?.name,
            fasting = m.fasting,
            rhythm = m.rhythm?.name,
            alarmSigns = m.alarmSigns.filter { it.present }.map { it.sign.name },
            boneMarker = m.boneMarker?.name,
            referenceRange = m.referenceRange?.let { listOf(it.low, it.high) },
        )
    }

    /** Restores the latest value of a variable from the history after a restart. */
    fun restoreValue(view: VariableView, subjectId: String, subjectType: String) = change {
        val subject = subject(subjectId, subjectType)
        val current = subject.variables[view.variable]
        if (current?.measuredAtMs != null && current.measuredAtMs >= (view.measuredAtMs ?: 0)) return@change
        subject.variables[view.variable] = (current ?: VariableView(view.variable)).copy(
            value = view.value, unit = view.unit, measuredAtMs = view.measuredAtMs, quality = view.quality,
            context = view.context, fasting = view.fasting, rhythm = view.rhythm, alarmSigns = view.alarmSigns,
        )
        missionTimeMs = maxOf(missionTimeMs, view.measuredAtMs ?: 0)
    }

    fun onVariableState(s: VariableState) = change {
        val subject = subject(s.subjectId, s.subjectType.name)
        val key = s.variable.name
        val current = subject.variables[key] ?: VariableView(key)
        if ((current.stateAtMs ?: Long.MIN_VALUE) > s.missionTimeMs) return@change
        subject.variables[key] = current.copy(
            state = s.state?.name,
            toConfirm = s.toConfirm,
            reason = ReasonView(s.reason.code, s.reason.params),
            stateAtMs = s.missionTimeMs,
        )
    }

    fun onSummary(s: SummaryState) = change {
        val subject = subject(s.subjectId, s.subjectType.name)
        val view = SummaryView(s.state.name, s.triggerVariable?.name, s.missionTimeMs)
        val group = s.group
        if (group == null) subject.overall = view else subject.groups[group.name] = view
    }

    fun onAlert(a: Alert) = change {
        alerts.addFirst(
            AlertView(a.subjectId, a.subjectType.name, a.variable?.name, a.state.name, a.code, a.params, a.missionTimeMs),
        )
        while (alerts.size > MAX_ALERTS) alerts.removeLast()
    }

    /** Clears everything (mission reset). */
    fun clear() = change {
        missionTimeMs = 0
        mission = MissionView(null, null)
        crew.clear()
        subjects.clear()
        alerts.clear()
    }

    private fun subject(id: String, type: String): MutableSubject = subjects.getOrPut(id) { MutableSubject(id, type) }

    private inline fun change(block: () -> Unit) {
        synchronized(lock) {
            block()
            version++
        }
    }

    companion object {
        const val MAX_ALERTS = 200
    }
}
