package io.selfcheck.engine.stream

import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.ControlCommand
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionControl
import io.selfcheck.contracts.MissionProfile
import io.selfcheck.contracts.Reason
import io.selfcheck.contracts.SummaryState
import io.selfcheck.contracts.SubjectType
import io.selfcheck.contracts.Topics
import io.selfcheck.contracts.Variable
import io.selfcheck.contracts.VariableState
import io.selfcheck.engine.rules.Combinations
import io.selfcheck.engine.rules.EvaluationInput
import io.selfcheck.engine.rules.ReasonCodes
import io.selfcheck.engine.rules.RuleEvaluator
import io.selfcheck.engine.rules.SubjectRuntime
import io.selfcheck.engine.rules.Summarizer
import io.selfcheck.engine.rules.VariableRuntime
import io.selfcheck.knowledge.RuleCatalog
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Applies the rules to each measurement of one subject and publishes what changed:
 * variable states, group/crew member summaries and alerts. Time is always mission time.
 */
class SubjectProcessor(
    private val catalog: RuleCatalog,
    private val evaluator: RuleEvaluator = RuleEvaluator(),
    private val summarizer: Summarizer = Summarizer(catalog),
    private val combinations: Combinations = Combinations(catalog),
) : Processor<String, SpecificRecord, String, SpecificRecord> {

    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var context: ProcessorContext<String, SpecificRecord>
    private lateinit var subjects: KeyValueStore<String, SubjectRuntime>
    private lateinit var profiles: KeyValueStore<String, MissionProfile>
    private lateinit var histories: KeyValueStore<String, MedicalHistory>

    /** Next mission time at which values are checked for expiry. Mission time, never wall-clock time. */
    private var nextExpiryCheckAt = Long.MIN_VALUE

    override fun init(context: ProcessorContext<String, SpecificRecord>) {
        this.context = context
        subjects = context.getStateStore(EngineTopology.SUBJECT_STORE)
        profiles = context.getStateStore(EngineTopology.PROFILE_STORE)
        histories = context.getStateStore(EngineTopology.HISTORY_STORE)
    }

    override fun process(record: Record<String, SpecificRecord>) {
        when (val value = record.value()) {
            is Measurement -> process(value)
            is MissionControl -> if (value.command == ControlCommand.RESET) reset()
            else -> Unit
        }
    }

    /**
     * Mission reset (spec section 5): forgets every subject. Deleting the keys also clears the store's changelog,
     * so the state does not come back after a restart.
     */
    private fun reset() {
        val keys = subjects.all().use { iterator -> iterator.asSequence().map { it.key }.toList() }
        keys.forEach(subjects::delete)
        nextExpiryCheckAt = Long.MIN_VALUE
        log.info("Mission reset: cleared {} subjects", keys.size)
    }

    private fun process(measurement: Measurement) {
        val subjectId = measurement.subjectId
        val runtime = subjects.get(subjectId) ?: SubjectRuntime(measurement.subjectType, subjectId)
        val now = measurement.missionTimeMs

        if (now < runtime.lastMissionTimeMs) {
            log.debug("Discarding {} for {}: mission time {} < {}", measurement.variable, subjectId, now, runtime.lastMissionTimeMs)
            return
        }
        runtime.lastMissionTimeMs = now

        // A measurement updates its own rule and every rule derived from it (rhythm, career dose, solar event dose).
        val input = EvaluationInput(
            measurement = measurement,
            now = now,
            runtime = runtime,
            history = histories.get(subjectId),
            profile = profiles.get(Topics.MISSION_KEY),
        )
        for (definition in catalog.triggeredBy[measurement.variable].orEmpty()) {
            val updated = evaluator.evaluate(definition, input, runtime.variables[definition.variable]) ?: continue
            applyVariable(runtime, definition.variable, updated, now)
        }
        publishCombinations(runtime, now)
        publishSummaries(runtime, now)
        subjects.put(subjectId, runtime)

        // Expiry follows the mission time carried by the records, so it stops while the simulator is paused
        // and starts over after a reset (stream time in Kafka Streams never goes back).
        if (now >= nextExpiryCheckAt) {
            expireStaleValues(now)
            nextExpiryCheckAt = now + EXPIRY_CHECK_INTERVAL.toMillis()
        }
    }

    /** One high-priority alert per clinical combination when it becomes active (spec section 7.13). */
    private fun publishCombinations(runtime: SubjectRuntime, now: Long) {
        if (runtime.subjectType != SubjectType.CREW_MEMBER) return
        val active = combinations.active(runtime)
        for ((code, state) in active) {
            if (code in runtime.activeCombinations) continue
            forward(runtime.subjectId, Alert(code, emptyMap(), runtime.subjectType, runtime.subjectId, null, state, now, true), now)
        }
        runtime.activeCombinations.retainAll(active.keys)
        runtime.activeCombinations.addAll(active.keys)
    }

    private fun applyVariable(runtime: SubjectRuntime, variable: Variable, updated: VariableRuntime, now: Long) {
        val previous = runtime.variables.put(variable, updated)
        if (!updated.differsFrom(previous)) return

        val reason = Reason(updated.code, updated.params)
        forward(
            runtime.subjectId,
            VariableState(runtime.subjectType, runtime.subjectId, variable, updated.state, updated.toConfirm, reason, now),
            now,
        )
        val state = updated.state
        if ((state == HealthState.OBSERVATION || state == HealthState.ALERT) && state != previous?.state) {
            forward(
                runtime.subjectId,
                Alert(updated.code, updated.params, runtime.subjectType, runtime.subjectId, variable, state, now, false),
                now,
            )
        }
    }

    private fun publishSummaries(runtime: SubjectRuntime, now: Long) {
        for ((key, summary) in summarizer.summarize(runtime)) {
            if (runtime.summaries[key] == summary) continue
            runtime.summaries[key] = summary
            val group = if (key == SubjectRuntime.OVERALL) null else MeasurementGroup.valueOf(key)
            forward(
                runtime.subjectId,
                SummaryState(runtime.subjectType, runtime.subjectId, group, summary.state, summary.triggerVariable, now),
                now,
            )
        }
    }

    /** A value older than its rule's expiry is NOT_EVALUABLE (spec section 7.2). */
    private fun expireStaleValues(missionNow: Long) {
        val changed = mutableListOf<SubjectRuntime>()
        subjects.all().use { iterator ->
            iterator.forEachRemaining { entry ->
                val runtime = entry.value
                var touched = false
                for ((variable, current) in runtime.variables.toMap()) {
                    val definition = catalog[variable] ?: continue
                    if (!definition.hasState || current.code == ReasonCodes.DATA_EXPIRED) continue
                    if (missionNow - current.measuredAtMs <= definition.effectiveExpiry.toMillis()) continue
                    val expired = current.copy(
                        state = HealthState.NOT_EVALUABLE,
                        code = ReasonCodes.DATA_EXPIRED,
                        params = current.params + ("measuredAtMs" to current.measuredAtMs.toString()),
                        toConfirm = false,
                    )
                    applyVariable(runtime, variable, expired, missionNow)
                    touched = true
                }
                if (touched) changed += runtime
            }
        }
        changed.forEach { runtime ->
            publishSummaries(runtime, missionNow)
            subjects.put(runtime.subjectId, runtime)
        }
    }

    private fun forward(key: String, value: SpecificRecord, timestamp: Long) {
        context.forward(Record(key, value, timestamp))
    }

    companion object {
        val EXPIRY_CHECK_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
