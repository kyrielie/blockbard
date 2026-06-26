
package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory

data class NoteRequest(
    val midiNote: Int,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
    /**
     * Pre-resolved block position. When set, bypasses the assignment map entirely.
     * Used by the scale test and any direct-play path that already knows which block to hit.
     */
    val resolvedPos: BlockPos? = null
)

/**
 * Tick-based queue that serialises simultaneous note requests into sequential
 * block interactions. One note is dispatched per tick via [onTick].
 *
 * [interactDelegate] is wired by BlockBardClient to PlayerController.interactWith().
 */
object ArpeggioScheduler {

    private val logger = LoggerFactory.getLogger("BlockBard/ArpeggioScheduler")

    /** Populated by MidiToOrganMapper: midiNote → assigned BlockPos. */
    var assignment: Map<Int, BlockPos> = emptyMap()

    /** Requests older than this are dropped to prevent note build-up after lag. */
    var staleTimeoutMs: Long = 200L

    /** Wired to PlayerController.interactWith() by the client entrypoint. */
    var interactDelegate: ((BlockPos) -> Boolean)? = null

    private val queue: ArrayDeque<NoteRequest> = ArrayDeque()

    /** Called every client tick. Dispatches one queued note. */
    fun onTick() {
        // Iterative stale-skip — never recursive
        while (queue.isNotEmpty()) {
            val request = queue.first()
            val age = System.currentTimeMillis() - request.enqueuedAtMs

            if (age > staleTimeoutMs) {
                queue.removeFirst()
                logger.warn("dropping stale MIDI ${request.midiNote} (age ${age}ms > ${staleTimeoutMs}ms) — increase staleTimeoutMs if this fires during normal playback")
                continue
            }

            // Fresh note — find the block
            queue.removeFirst()
            val pos = request.resolvedPos
                ?: assignment[request.midiNote]
                ?: NoteBlockRegistry.findBestForMidi(request.midiNote)?.pos

            if (pos == null) {
                logger.warn("no block found for MIDI ${request.midiNote} — skipping (assignment has ${assignment.size} entries, resolvedPos=${request.resolvedPos})")
                return
            }

            val dispatched = interactDelegate?.invoke(pos) ?: false
            logger.info("dispatch MIDI ${request.midiNote} -> $pos result=$dispatched age=${age}ms queueRemaining=${queue.size}")
            if (!dispatched) {
                logger.warn("interactDelegate returned false for $pos (MIDI ${request.midiNote}) — check PlayerController logs above")
            }
            return
        }
    }

    /**
     * Returns the BlockPos the next pending (non-stale) note will be dispatched to,
     * without consuming it. Used by BlockBardClient to prime the player rotation one
     * tick before the interact fires (Phase 2).
     */
    fun peekNextPos(): BlockPos? {
        val request = queue.firstOrNull() ?: return null
        val age = System.currentTimeMillis() - request.enqueuedAtMs
        if (age > staleTimeoutMs) return null  // will be dropped on next onTick
        return request.resolvedPos
            ?: assignment[request.midiNote]
            ?: NoteBlockRegistry.findBestForMidi(request.midiNote)?.pos
    }

    fun enqueue(request: NoteRequest) {
        logger.info("enqueue MIDI ${request.midiNote} resolvedPos=${request.resolvedPos} queueSize=${queue.size + 1}")
        queue.addLast(request)
    }

    fun clear() {
        logger.info("queue cleared (had ${queue.size} items)")
        queue.clear()
    }

    fun isEmpty(): Boolean = queue.isEmpty()
    val queueSize: Int get() = queue.size
}
