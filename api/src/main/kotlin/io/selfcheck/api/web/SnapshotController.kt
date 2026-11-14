package io.selfcheck.api.web

import io.selfcheck.api.snapshot.SnapshotStore
import io.selfcheck.api.snapshot.SnapshotView
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api")
class SnapshotController(private val snapshot: SnapshotStore, private val broadcaster: SnapshotBroadcaster) {

    /** Current state of the whole mission. */
    @GetMapping("/snapshot")
    fun snapshot(): SnapshotView = snapshot.snapshot()

    /** Live updates: a `snapshot` event whenever something changes, at most every 250 ms. */
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = broadcaster.subscribe()
}
