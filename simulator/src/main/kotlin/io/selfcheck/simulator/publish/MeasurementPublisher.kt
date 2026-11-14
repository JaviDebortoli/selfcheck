package io.selfcheck.simulator.publish

import io.selfcheck.contracts.ActivityContext
import io.selfcheck.contracts.AlarmSign
import io.selfcheck.contracts.AlarmSignReading
import io.selfcheck.contracts.BoneResorptionMarker
import io.selfcheck.contracts.ControlCommand
import io.selfcheck.contracts.DailyTargets
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionControl
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.PreflightBoneDensity
import io.selfcheck.contracts.Quality
import io.selfcheck.contracts.ReferenceRange
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.Variable
import io.selfcheck.simulator.crew.CrewMember
import io.selfcheck.simulator.mission.MissionEpoch
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.avro.specific.SpecificRecord
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** Builds the Avro events and sends them to Kafka. Every event carries the mission time. */
@Component
class MeasurementPublisher(private val kafka: KafkaTemplate<String, SpecificRecord>, private val epoch: MissionEpoch) {

    /** Every record carries the mission epoch header so consumers can tell missions apart. */
    private fun send(topic: String, key: String, value: SpecificRecord) =
        kafka.send(
            ProducerRecord<String, SpecificRecord>(topic, key, value).apply {
                headers().add(Topics.EPOCH_HEADER, epoch.value.toString().toByteArray())
            },
        )

    fun publishProfile(environment: MissionEnvironment, plannedDurationDays: Int, missionTimeMs: Long) {
        send(Topics.MISSION_PROFILE, Topics.MISSION_KEY, MissionProfile(environment, plannedDurationDays, missionTimeMs))
    }

    fun publishHistory(member: CrewMember, missionTimeMs: Long) {
        val history = MedicalHistory(
            member.id,
            member.sex,
            member.priorEffectiveDoseMsv,
            member.preflightMuscleStrength,
            member.preflightBodyMassKg,
            PreflightBoneDensity(member.preflightBmdHip, member.preflightBmdSpine, member.preflightBmdFemoralNeck),
            DailyTargets(member.dailyCaloriesKcal, member.dailyFluidsMl, member.dailyActivityMinutes),
            missionTimeMs,
        )
        send(Topics.MEDICAL_HISTORY, member.id, history)
    }

    /** Sends a control command and waits until Kafka has it. */
    fun publishControl(command: ControlCommand) {
        send(Topics.CONTROL, Topics.MISSION_KEY, MissionControl(command)).get()
    }

    fun flush() = kafka.flush()

    fun publish(
        subjectId: String,
        variable: Variable,
        value: Double,
        unit: String,
        missionTimeMs: Long,
        context: ActivityContext? = null,
        fasting: Boolean? = null,
        quality: Quality = Quality.GOOD,
        alarmSigns: Map<AlarmSign, Boolean> = emptyMap(),
        rhythm: RhythmLabel? = null,
        boneMarker: BoneResorptionMarker? = null,
        referenceRange: Pair<Double, Double>? = null,
    ) {
        val measurement = Measurement.newBuilder()
            .setSubjectType(if (subjectId == Topics.HABITAT_ID) SubjectType.HABITAT else SubjectType.CREW_MEMBER)
            .setSubjectId(subjectId)
            .setVariable(variable)
            .setValue(value)
            .setUnit(unit)
            .setMissionTimeMs(missionTimeMs)
            .setActivityContext(context)
            .setFasting(fasting)
            .setQuality(quality)
            .setAlarmSigns(alarmSigns.map { (sign, present) -> AlarmSignReading(sign, present) })
            .setRhythm(rhythm)
            .setBoneMarker(boneMarker)
            .setReferenceRange(referenceRange?.let { ReferenceRange(it.first, it.second) })
            .build()
        send(Topics.MEASUREMENTS, subjectId, measurement)
    }
}
