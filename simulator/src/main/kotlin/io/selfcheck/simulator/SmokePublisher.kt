package io.selfcheck.simulator

import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MissionEnvironment
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.Quality
import io.selfcheck.contracts.RhythmLabel
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.Variable
import org.apache.avro.specific.SpecificRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * Phase 0 smoke test: publishes the mission profile and one Avro measurement at start-up
 * to prove the simulator -> Kafka -> api path. Replaced by the mission clock in phase 1.
 */
@Component
class SmokePublisher(private val kafka: KafkaTemplate<String, SpecificRecord>) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val profile = MissionProfile.newBuilder()
            .setEnvironment(MissionEnvironment.ORBITAL_STATION)
            .setPlannedDurationDays(180)
            .setMissionTimeMs(0)
            .build()
        kafka.send(Topics.MISSION_PROFILE, Topics.MISSION_KEY, profile).get()

        val measurement = Measurement.newBuilder()
            .setSubjectType(SubjectType.CREW_MEMBER)
            .setSubjectId("crew-1")
            .setVariable(Variable.HEART_RATE)
            .setValue(72.0)
            .setUnit("bpm")
            .setMissionTimeMs(0)
            .setQuality(Quality.GOOD)
            .setRhythm(RhythmLabel.SINUS)
            .build()
        kafka.send(Topics.MEASUREMENTS, measurement.subjectId, measurement).get()
        log.info("Smoke measurement published: {}", measurement)
    }
}
