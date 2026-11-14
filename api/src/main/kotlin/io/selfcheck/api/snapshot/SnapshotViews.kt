package io.selfcheck.api.snapshot

/** JSON views of the live mission state served by REST and SSE. Codes, never sentences (spec section 4.4). */

data class ReasonView(val code: String, val params: Map<String, String>)

data class VariableView(
    val variable: String,
    val value: Double? = null,
    val unit: String? = null,
    val measuredAtMs: Long? = null,
    val quality: String? = null,
    val context: String? = null,
    val fasting: Boolean? = null,
    val rhythm: String? = null,
    val alarmSigns: List<String> = emptyList(),
    val boneMarker: String? = null,
    val referenceRange: List<Double>? = null,
    /** Null when the value is shown as data without state. */
    val state: String? = null,
    val toConfirm: Boolean = false,
    val reason: ReasonView? = null,
    val stateAtMs: Long? = null,
)

data class SummaryView(val state: String, val triggerVariable: String?, val atMs: Long)

data class SubjectView(
    val id: String,
    val type: String,
    val overall: SummaryView?,
    val groups: Map<String, SummaryView>,
    val variables: Map<String, VariableView>,
)

data class AlertView(
    val subjectId: String,
    val subjectType: String,
    val variable: String?,
    val state: String,
    val code: String,
    val params: Map<String, String>,
    val missionTimeMs: Long,
)

data class CrewMemberView(
    val id: String,
    val sex: String,
    val priorEffectiveDoseMsv: Double,
    val preflightMuscleStrength: Double,
    val preflightBodyMassKg: Double,
    val preflightBoneDensity: Map<String, Double>,
    val dailyTargets: Map<String, Double>,
)

data class MissionView(val environment: String?, val plannedDurationDays: Int?)

data class SnapshotView(
    val version: Long,
    val missionTimeMs: Long,
    val mission: MissionView,
    val crew: List<CrewMemberView>,
    val subjects: Map<String, SubjectView>,
    val alerts: List<AlertView>,
)
