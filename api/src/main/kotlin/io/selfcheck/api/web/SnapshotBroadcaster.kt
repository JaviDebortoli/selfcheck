package io.selfcheck.api.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.selfcheck.api.snapshot.SnapshotStore
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pushes the snapshot to every SSE client when it changes. Changes are coalesced every [INTERVAL_MS] so an
 * accelerated mission does not flood the browser.
 */
@Component
class SnapshotBroadcaster(private val snapshot: SnapshotStore, private val json: ObjectMapper) {

    private val emitters = CopyOnWriteArrayList<SseEmitter>()
    private var lastSentVersion = -1L

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(0L)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        emitters += emitter
        send(emitter, json.writeValueAsString(snapshot.snapshot()))
        return emitter
    }

    @Scheduled(fixedDelay = INTERVAL_MS)
    fun broadcast() {
        if (emitters.isEmpty()) return
        val version = snapshot.currentVersion()
        if (version == lastSentVersion) return
        val payload = json.writeValueAsString(snapshot.snapshot())
        lastSentVersion = version
        emitters.forEach { send(it, payload) }
    }

    /** Keeps idle connections open through proxies while the mission is paused. */
    @Scheduled(fixedDelay = 15_000)
    fun heartbeat() {
        emitters.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"))
            } catch (e: IOException) {
                emitters.remove(emitter)
            }
        }
    }

    private fun send(emitter: SseEmitter, payload: String) {
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(payload))
        } catch (e: IOException) {
            emitters.remove(emitter)
        } catch (e: IllegalStateException) {
            emitters.remove(emitter)
        }
    }

    companion object {
        const val INTERVAL_MS = 250L
    }
}
