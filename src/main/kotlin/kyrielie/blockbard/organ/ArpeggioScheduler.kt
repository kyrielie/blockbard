package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory

data class NoteRequest(
    val midiNote: Int,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
    /** Pre-resolved block position (null = resolve from assignment map at dispatch time). */
    val resolvedPos: BlockPos? = null
)

/**
 * Tick-based queue that serialises simultaneous note requests into sequential block interactions.
 * One note is dispatched per tick via [onTick]. The actual block interaction is delegated to
 * [interactDelegate], which is wired by BlockBardClient on the client side to PlayerController.
 */
object ArpeggioScheduler {

    private val logger = LoggerFactory.getLogger("BlockBard/ArpeggioScheduler")

    /** Set by MidiToOrganMapper to map midiNote → the assigned BlockPos. */
    var assignment: Map<Int, BlockPos> = emptyMap()

    /** Requests older than this are dropped to avoid note build-up during lag. */
    var staleTimeoutMs: Long = 200L

    /**
     * Wired by the client entrypoint to the actual block interaction function.
     * Receives the BlockPos to right-click; returns true if dispatched.
     */
    var interactDelegate: ((BlockPos) -> Boolean)? = null

    private val queue: ArrayDeque<NoteRequest> = ArrayDeque()

    /** Called every ClientTickEvent. Dispatches one queued note per tick. */
    fun onTick() {
        // Iterative stale-skip — no recursion risk
        while (queue.isNotEmpty()) {
            val request = queue.removeFirst()
            val age = System.currentTimeMillis() - request.enqueuedAtMs
            if (age > staleTimeoutMs) {
                logger.debug("ArpeggioScheduler: dropping stale note ${request.midiNote} (age ${age}ms > ${staleTimeoutMs}ms)")
                continue
            }
            val pos = request.resolvedPos
                ?: assignment[request.midiNote]
                ?: NoteBlockRegistry.findBestForMidi(request.midiNote)?.pos

            if (pos == null) {
                logger.warn("ArpeggioScheduler: no block found for MIDI note ${request.midiNote} — skipping")
                return
            }

            logger.debug("ArpeggioScheduler: dispatching MIDI ${request.midiNote} → $pos")
            val dispatched = interactDelegate?.invoke(pos) ?: false
            if (!dispatched) {
                logger.warn("ArpeggioScheduler: interactDelegate returned false for $pos (MIDI ${request.midiNote})")
            }
            return
        }
    }

    fun enqueue(request: NoteRequest) {
        logger.debug("ArpeggioScheduler: enqueue MIDI ${request.midiNote} resolvedPos=${request.resolvedPos}")
        queue.addLast(request)
    }

    fun clear() {
        logger.info("ArpeggioScheduler: queue cleared (had ${queue.size} items)")
        queue.clear()
    }

    fun isEmpty(): Boolean = queue.isEmpty()

    val queueSize: Int get() = queue.size
}
