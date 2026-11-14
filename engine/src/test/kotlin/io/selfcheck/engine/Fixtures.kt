package io.selfcheck.engine

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.AlarmSignReading
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.Quality
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.Variable

/** Builds measurements for tests with sensible defaults. */
fun measurement(
    variable: Variable,
    value: Double,
    missionTimeMs: Long = 0,
    subjectId: String = "crew-1",
    context: ActivityContext? = ActivityContext.REST,
    quality: Quality = Quality.GOOD,
    signs: List<AlarmSign> = emptyList(),
    fasting: Boolean? = null,
    unit: String = "",
): Measurement = Measurement.newBuilder()
    .setSubjectType(if (subjectId == Topics.HABITAT_ID) SubjectType.HABITAT else SubjectType.CREW_MEMBER)
    .setSubjectId(subjectId)
    .setVariable(variable)
    .setValue(value)
    .setUnit(unit)
    .setMissionTimeMs(missionTimeMs)
    .setActivityContext(context)
    .setFasting(fasting)
    .setQuality(quality)
    .setAlarmSigns(signs.map { AlarmSignReading(it, true) })
    .build()
