package io.selfcheck.engine.stream

import io.selfcheck.contracts.Alert
import io.selfcheck.contracts.ControlCommand
import io.selfcheck.contracts.HealthState
import io.selfcheck.contracts.MeasurementGroup
import io.selfcheck.contracts.Measurement
import io.selfcheck.contracts.MedicalHistory
import io.selfcheck.contracts.MissionControl
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
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.streams.processor.PunctuationType
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
    private lateinit var histories: KeyValueStore<String, MedicalHistory>

    /** Next mission time at which values are checked for expiry. Mission time, never wall-clock time. */
    private var nextExpiryCheckAt = Long.MIN_VALUE

    /**
     * Working copy of every subject. The state store is written in the background (write-behind) so an
     * accelerated mission does not pay a JSON round trip per measurement.
     */
    private val cache = HashMap<String, SubjectRuntime>()
    private val dirty = HashSet<String>()

    /** Mission epoch being processed (Kafka header); records of an older epoch are dropped. */
    private var epoch = Long.MIN_VALUE

    override fun init(context: ProcessorContext<String, SpecificRecord>) {
        this.context = context
        subjects = context.getStateStore(EngineTopology.SUBJECT_STORE)
        histories = context.getStateStore(EngineTopology.HISTORY_STORE)
        epoch = subjects.get(EPOCH_KEY)?.lastMissionTimeMs ?: Long.MIN_VALUE
        // Persistence only (not a decision), so wall-clock time is fine here.
        context.schedule(FLUSH_INTERVAL, PunctuationType.WALL_CLOCK_TIME) { flush() }
    }

    override fun close() = flush()

    private fun load(subjectId: String): SubjectRuntime? = cache[subjectId] ?: subjects.get(subjectId)?.also { cache[subjectId] = it }

    private fun save(runtime: SubjectRuntime) {
        cache[runtime.subjectId] = runtime
        dirty += runtime.subjectId
    }

    private fun flush() {
        dirty.forEach { id -> cache[id]?.let { subjects.put(id, it) } }
        dirty.clear()
    }

    override fun process(record: Record<String, SpecificRecord>) {
        val recordEpoch = record.headers().lastHeader(Topics.EPOCH_HEADER)?.value()?.decodeToString()?.toLongOrNull()
        if (recordEpoch != null && recordEpoch < epoch) return // a previous mission, delayed
        if (recordEpoch != null && recordEpoch > epoch) {
            // A new or reset mission, whether its reset command or its first measurement arrives first.
            reset()
            epoch = recordEpoch
            subjects.put(EPOCH_KEY, SubjectRuntime(SubjectType.HABITAT, EPOCH_KEY, lastMissionTimeMs = recordEpoch))
        }
        when (val value = record.value()) {
            is Measurement -> process(value)
            // A reset command without an epoch (tests, manual use) still clears the mission.
            is MissionControl -> if (value.command == ControlCommand.RESET && recordEpoch == null) reset()
            else -> Unit
        }
    }

    /**
     * Mission reset (spec section 5): forgets every subject. Deleting the keys also clears the store's changelog,
     * so the state does not come back after a restart.
     */
    private fun reset() {
        cache.clear()
        dirty.clear()
        val keys = subjects.all().use { iterator -> iterator.asSequence().map { it.key }.toList() }
        keys.forEach(subjects::delete)
        nextExpiryCheckAt = Long.MIN_VALUE
        log.info("Mission reset: cleared {} subjects", keys.size)
    }

    private fun process(measurement: Measurement) {
        val subjectId = measurement.subjectId
        val runtime = load(subjectId) ?: SubjectRuntime(measurement.subjectType, subjectId)
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
        )
        val evaluated = mutableSetOf<Variable>()
        for (definition in catalog.triggeredBy[measurement.variable].orEmpty()) {
            val updated = evaluator.evaluate(definition, input, runtime.variables[definition.variable]) ?: continue
            applyVariable(runtime, definition.variable, updated, now)
            evaluated += definition.variable
        }
        refreshMirrors(runtime, evaluated, now)
        publishCombinations(runtime, now)
        publishSummaries(runtime, now)
        save(runtime)

        // Expiry follows the mission time carried by the records, so it stops while the simulator is paused
        // and starts over after a reset (stream time in Kafka Streams never goes back).
        if (now >= nextExpiryCheckAt) {
            expireStaleValues(now)
            nextExpiryCheckAt = now + EXPIRY_CHECK_INTERVAL.toMillis()
        }
    }

    /**
     * A variable that copies another variable's state (dose rate, mission dose) follows it as soon as it changes,
     * without waiting for its own next measurement. Its value and measurement time stay as they are.
     */
    private fun refreshMirrors(runtime: SubjectRuntime, evaluated: Set<Variable>, now: Long) {
        for (mirror in catalog.mirrors) {
            if (mirror.stateFrom !in evaluated) continue
            val previous = runtime.variables[mirror.variable] ?: continue
            if (previous.code == ReasonCodes.DATA_EXPIRED) continue
            val copied = evaluator.mirrored(mirror, runtime)
            applyVariable(runtime, mirror.variable, previous.copy(state = copied.state, code = copied.code, params = previous.params + copied.params), now)
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
        val raisesAlerts = catalog[variable]?.raisesAlerts != false
        if (raisesAlerts && (state == HealthState.OBSERVATION || state == HealthState.ALERT) && state != previous?.state) {
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
        run {
            cache.values.toList().forEach { runtime ->
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
            save(runtime)
        }
    }

    private fun forward(key: String, value: SpecificRecord, timestamp: Long) {
        val headers = RecordHeaders().apply { if (epoch != Long.MIN_VALUE) add(Topics.EPOCH_HEADER, epoch.toString().toByteArray()) }
        context.forward(Record(key, value, timestamp, headers))
    }

    companion object {
        val EXPIRY_CHECK_INTERVAL: Duration = Duration.ofSeconds(5)
        val FLUSH_INTERVAL: Duration = Duration.ofSeconds(1)

        /** Store key holding the current mission epoch (in lastMissionTimeMs). */
        const val EPOCH_KEY = "__mission_epoch"
    }
}
