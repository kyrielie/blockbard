package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos

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
        if (queue.isEmpty()) return
        val request = queue.removeFirst()
        val age = System.currentTimeMillis() - request.enqueuedAtMs
        if (age > staleTimeoutMs) {
            onTick()  // skip stale, try next
            return
        }
        val pos = request.resolvedPos
            ?: assignment[request.midiNote]
            ?: NoteBlockRegistry.findBestForMidi(request.midiNote)?.pos
            ?: return
        interactDelegate?.invoke(pos)
    }

    fun enqueue(request: NoteRequest) { queue.addLast(request) }

    fun clear() = queue.clear()

    fun isEmpty(): Boolean = queue.isEmpty()

    val queueSize: Int get() = queue.size
}
