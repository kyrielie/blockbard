package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

data class NoteRequest(
    val midiNote: Int,
    /**
     * The instrument this note was authored for (NBS instrument byte, or MIDI channel
     * program resolved via MidiChannelResolver), if known. Null means "any instrument
     * at this pitch is fine" — used by callers with no instrument source, like
     * KeyboardInputHandler's direct 1-9 key presses or the chromatic-scale test.
     * Without this, two notes at the same pitch but different instruments (e.g. a
     * harp middle-C and a bell middle-C) would be indistinguishable and could each
     * land on whichever block happens to match the pitch, ignoring the instrument
     * the source file actually specified.
     */
    val instrument: NoteBlockInstrument? = null,
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
 * [interactDelegate] is wired by BlockBardClient to PlayerController.playNoteAt().
 */
object ArpeggioScheduler {

    private val logger = LoggerFactory.getLogger("BlockBard/ArpeggioScheduler")

    /**
     * Populated by MidiToOrganMapper: NotePitch (midiNote, instrument) → assigned BlockPos.
     * Keyed by instrument as well as pitch so two notes at the same pitch but
     * different instruments don't collide on one assignment slot — see NoteRequest
     * kdoc. A null instrument in the key means "any instrument" and is only ever
     * looked up as a fallback when no instrument-specific entry exists (see resolvePos).
     * Must match OrganAssignment.assignment's key type (NotePitch) exactly, since
     * MainScreen assigns ArpeggioScheduler.assignment = result.assignment directly.
     */
    var assignment: Map<NotePitch, BlockPos> = emptyMap()

    /** Requests older than this are dropped to prevent note build-up after lag. */
    var staleTimeoutMs: Long = 200L

    /**
     * Safety cap for a note that is actively converging rotation (see onTick kdoc).
     * Sized generously above the worst-case turn time at MAX_ROTATION_DEGREES_PER_TICK
     * (a full 180 deg yaw turn takes ~6 ticks / 300ms at the default 35 deg/tick cap)
     * so it only fires if rotation genuinely got stuck (e.g. overridden by something
     * else, or organMap stale for this pos), not on a normal large turn.
     */
    var rotationInProgressTimeoutMs: Long = 1500L

    /**
     * Wired to PlayerController.playNoteAt(pos, request) by the client entrypoint — a
     * left-click/attack interaction, deliberately not interactWith()'s right-click
     * (which cycles the block's tuning by one semitone; see PlayerController.playNoteAt
     * kdoc for why playback must never use that path). Takes the target BlockPos plus
     * the NoteRequest being dispatched — the request (not just its midiNote) is
     * threaded through so PlayerController can hand the full intended (midiNote,
     * instrument) pair to SoundVerifier for ground-truth comparison against the actual
     * SoundInstance the client plays, without SoundVerifier needing to duplicate
     * ArpeggioScheduler's own bookkeeping.
     */
    var interactDelegate: ((BlockPos, NoteRequest) -> Boolean)? = null

    /**
     * Wired to PlayerController.rotationConverged() by the client entrypoint. Gates
     * dispatch so interactWith() never fires while the eased rotation (see
     * PlayerController.MAX_ROTATION_DEGREES_PER_TICK) is still mid-turn — firing early
     * would reproduce the same instant-rotation-plus-click signature the easing was
     * added to avoid. If unset, dispatch proceeds unconditionally (back-compat / tests).
     */
    var rotationConvergedDelegate: ((BlockPos) -> Boolean)? = null

    private val queue: ArrayDeque<NoteRequest> = ArrayDeque()

    /**
     * Guards [queue]. enqueue() is called from a javax.sound.midi device thread
     * (see MidiInputHandler.connect()'s Receiver.send()) while onTick()/peekNextPos()
     * run on the Minecraft client thread via START_CLIENT_TICK/END_CLIENT_TICK — without
     * this, queue is a kotlin.collections.ArrayDeque (not thread-safe) shared across
     * threads with no synchronization.
     */
    private val lock = Any()

    /**
     * Resolves a request to a target BlockPos, trying in order:
     * 1. resolvedPos, if the caller already knows the exact block (scale test).
     * 2. assignment for the exact (midiNote, instrument) pair, if instrument is known.
     * 3. assignment for (midiNote, null) — an instrument-agnostic assignment entry.
     * 4. NoteBlockRegistry.findBestFor(midiNote, instrument) — live lookup, preferring
     *    an exact instrument match but falling back to any instrument at that pitch.
     */
    private fun resolvePos(request: NoteRequest): BlockPos? =
        request.resolvedPos
            ?: assignment[NotePitch(request.midiNote, request.instrument)]
            ?: assignment[NotePitch(request.midiNote, null)]
            ?: NoteBlockRegistry.findBestFor(request.midiNote, request.instrument)?.pos

    /**
     * Called every client tick. Dispatches one queued note once rotation has converged
     * on its target. The head of the queue blocks until converged (deliberately serial
     * — see class doc) and is re-checked next tick; staleTimeoutMs prunes backlog
     * sitting behind the head so a burst of enqueued notes can't build up unboundedly
     * while the head note is turning, but does NOT retroactively cancel the head note
     * itself once its convergence wait has begun — see rotationInProgressTimeoutMs for
     * the cap that applies to the head note instead. Large-angle turns (observed up to
     * ~180 deg yaw in practice) can legitimately take several ticks longer than
     * staleTimeoutMs to converge under MAX_ROTATION_DEGREES_PER_TICK easing, and a turn
     * that successfully converges should still play even if it took a while — it was
     * never idle, the player was visibly turning toward it the whole time.
     */
    fun onTick(): Unit = synchronized(lock) {
        // Catch misconfiguration early — interactDelegate should be wired at init
        if (queue.isNotEmpty() && interactDelegate == null) {
            logger.warn("onTick: interactDelegate is null — notes will never be dispatched (check BlockBardClient init)")
            return@synchronized
        }

        // Prune stale backlog sitting behind the head first — these notes haven't started
        // converging yet and may have been waiting the entire time the head note was
        // turning. The head note itself is handled separately below and is exempt from
        // staleTimeoutMs once its convergence wait has begun (see kdoc above).
        while (queue.size > 1) {
            val behindHead = queue[1]
            val behindAge = System.currentTimeMillis() - behindHead.enqueuedAtMs
            if (behindAge > staleTimeoutMs) {
                queue.removeAt(1)
                logger.warn("dropping stale backlogged MIDI ${behindHead.midiNote} (age ${behindAge}ms > ${staleTimeoutMs}ms) — queue building up faster than notes can be dispatched")
            } else {
                break
            }
        }

        // Iterative stale-skip — never recursive
        while (queue.isNotEmpty()) {
            val request = queue.first()
            val age = System.currentTimeMillis() - request.enqueuedAtMs

            val pos = resolvePos(request)

            if (pos == null) {
                queue.removeFirst()
                logger.warn("no block found for MIDI ${request.midiNote} — skipping (assignment has ${assignment.size} entries, resolvedPos=${request.resolvedPos})")
                return@synchronized
            }

            val converged = rotationConvergedDelegate?.invoke(pos) ?: true

            if (!converged) {
                if (age > rotationInProgressTimeoutMs) {
                    queue.removeFirst()
                    logger.warn("dropping MIDI ${request.midiNote} at $pos — rotation did not converge within ${rotationInProgressTimeoutMs}ms (age ${age}ms); check for a stuck/overridden rotation")
                    continue
                }
                // Still turning toward this note — leave it at the head of the queue
                // and try again next tick. staleTimeoutMs does not apply to the head
                // note once convergence waiting has begun — see kdoc above.
                logger.debug("waiting for rotation convergence on $pos (MIDI ${request.midiNote}, age ${age}ms)")
                return@synchronized
            }

            // Converged — consume and dispatch regardless of age. A note that took
            // a while to converge because of a large turn is not stale; it was never
            // idle. staleTimeoutMs prunes idle backlog elsewhere (see kdoc above), not
            // a note that just finished a legitimate in-progress turn.
            queue.removeFirst()
            val dispatched = interactDelegate?.invoke(pos, request) ?: false
            logger.info("dispatch MIDI ${request.midiNote} -> $pos result=$dispatched age=${age}ms queueRemaining=${queue.size}")
            if (!dispatched) {
                logger.warn("interactDelegate returned false for $pos (MIDI ${request.midiNote}) — check PlayerController logs above")
            }
            return@synchronized
        }
    }

    /**
     * Returns the BlockPos the next pending note will be dispatched to, without
     * consuming it. Used by BlockBardClient to drive PlayerController.primeRotation()
     * every tick a note is pending.
     *
     * Deliberately does NOT apply staleTimeoutMs here. onTick() exempts a note from the
     * staleness drop once rotation convergence is in progress for it (see onTick kdoc);
     * if this method instead started returning null for that same note once it crossed
     * staleTimeoutMs, primeRotation() would stop being called and the rotation would
     * freeze mid-turn — convergence would then never complete and the note would sit
     * forever, since onTick() is waiting on a convergence that's no longer advancing.
     * staleTimeoutMs is still enforced in onTick() for notes that haven't started
     * converging.
     */
    fun peekNextPos(): BlockPos? = synchronized(lock) {
        val request = queue.firstOrNull() ?: return@synchronized null
        val pos = resolvePos(request)
        logger.debug("peekNextPos: MIDI ${request.midiNote} instrument=${request.instrument?.name ?: "any"} -> $pos")
        pos
    }

    fun enqueue(request: NoteRequest) = synchronized(lock) {
        logger.info("enqueue MIDI ${request.midiNote} instrument=${request.instrument?.name ?: "any"} resolvedPos=${request.resolvedPos} queueSize=${queue.size + 1}")
        queue.addLast(request)
    }

    fun clear() = synchronized(lock) {
        logger.info("queue cleared (had ${queue.size} items)")
        queue.clear()
    }

    fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }
    val queueSize: Int get() = synchronized(lock) { queue.size }
}