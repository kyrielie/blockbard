package kyrielie.blockbard.organ

import kyrielie.blockbard.util.DebugLog
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

data class NoteRequest(
    val midiNote: Int,
    /**
     * The instrument this note was authored for (NBS instrument byte, or MIDI channel
     * program resolved via MidiChannelResolver), if known. Null means "any instrument
     * at this pitch is fine" -- used by callers with no instrument source, like
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
 * block interactions governed by a token-bucket rate limiter.
 *
 * [interactDelegate] is wired by BlockBardClient to PlayerController.playNoteAt().
 */
object ArpeggioScheduler {

    private val logger = LoggerFactory.getLogger("BlockBard/ArpeggioScheduler")

    /**
     * Populated by MidiToOrganMapper: NotePitch (midiNote, instrument) -> assigned BlockPos.
     * Keyed by instrument as well as pitch so two notes at the same pitch but
     * different instruments don't collide on one assignment slot.
     */
    var assignment: Map<NotePitch, BlockPos> = emptyMap()

    /** Requests older than this are dropped to prevent note build-up after lag. */
    var staleTimeoutMs: Long = 200L

    /**
     * Safety cap for a note that is actively converging rotation.
     * Sized above worst-case turn time at MAX_ROTATION_DEGREES_PER_TICK so it only
     * fires if rotation genuinely got stuck.
     */
    var rotationInProgressTimeoutMs: Long = 1500L

    /**
     * Burst cap for the token bucket: the maximum number of interact packets that
     * may be sent within a single onTick() call.
     *
     * The bucket refills at the same rate as NoteBlockTuner's limiter (8 tokens per
     * 310 ms, matching Paper's documented server-side interact limit). This cap bounds
     * how many of those accumulated tokens may be spent in one tick.
     *
     * 1 (recommended for strict servers): at most one interact per tick. Every note
     *   waits for rotation convergence before firing, so the server always sees a
     *   rotation packet between successive interacts. Chord notes spill across
     *   consecutive ticks (50 ms apart) -- musically transparent at any realistic tempo.
     *
     * 2-8 (lenient servers / testing): allows a short burst within one tick when the
     *   bucket has accrued enough tokens. Every note still goes through the rotation
     *   convergence gate, unlike the old "notes 2-N skip the gate" behaviour.
     *
     * Set from BlockBardConfig.maxNotesPerTick at init in BlockBardClient.
     */
    var maxNotesPerTick: Int = 1

    /**
     * Wired to PlayerController.playNoteAt(pos, request) by the client entrypoint.
     * Takes the target BlockPos plus the NoteRequest being dispatched so
     * PlayerController can hand the full (midiNote, instrument) pair to SoundVerifier.
     */
    var interactDelegate: ((BlockPos, NoteRequest) -> Boolean)? = null

    /**
     * Wired to PlayerController.rotationConverged() by the client entrypoint. Gates
     * dispatch so interactWith() never fires while the eased rotation is still mid-turn.
     * If unset, dispatch proceeds unconditionally (back-compat / tests).
     */
    var rotationConvergedDelegate: ((BlockPos) -> Boolean)? = null

    private val queue: ArrayDeque<NoteRequest> = ArrayDeque()

    /**
     * Guards [queue], [interactBudget], and [lastTickMs].
     * enqueue() is called from a javax.sound.midi device thread while
     * onTick()/peekNextPos() run on the Minecraft client thread.
     */
    private val lock = Any()

    // ── Token bucket ──────────────────────────────────────────────────────────
    // Mirrors NoteBlockTuner's rate limiter: Paper allows 8 block-interact packets
    // per 310 ms. The bucket refills continuously based on wall-clock elapsed time
    // and is capped at maxNotesPerTick so the user can dial it down for strict servers.
    // Budget is consumed one token per dispatched note; the loop stops when the bucket
    // is empty even if maxNotesPerTick hasn't been reached yet.
    private var interactBudget: Float = 1f
    private var lastTickMs: Long = -1L

    /**
     * Resolves a request to a target BlockPos, trying in order:
     * 1. resolvedPos, if the caller already knows the exact block.
     * 2. assignment for the exact (midiNote, instrument) pair.
     * 3. assignment for (midiNote, null) -- instrument-agnostic fallback.
     * 4. NoteBlockRegistry.findBestFor(midiNote, instrument) -- live lookup.
     */
    private fun resolvePos(request: NoteRequest): BlockPos? =
        request.resolvedPos
            ?: assignment[NotePitch(request.midiNote, request.instrument)]
            ?: assignment[NotePitch(request.midiNote, null)]
            ?: NoteBlockRegistry.findBestFor(request.midiNote, request.instrument)?.pos

    /**
     * Called every client tick. Dispatches queued notes subject to two constraints:
     *
     * 1. Token bucket: at most [maxNotesPerTick] interacts may be sent per tick, and
     *    only while the bucket (refilling at 8/310 ms) has tokens. This mirrors the
     *    server-side Paper limit and prevents packet-rate kicks even at high chord density.
     *
     * 2. Rotation convergence gate: applied to EVERY note, not just the first.
     *    Each note waits until the player is facing its target block before the interact
     *    packet fires, ensuring the server always sees a rotation packet between
     *    successive interacts. The loop exits when the head note is not yet converged
     *    and resumes next tick.
     *
     * Stale backlog pruning runs once at the start (not once per dispatched note).
     */
    fun onTick(): Unit = synchronized(lock) {
        if (queue.isNotEmpty() && interactDelegate == null) {
            logger.warn("onTick: interactDelegate is null -- notes will never be dispatched (check BlockBardClient init)")
            return@synchronized
        }

        // Refill token bucket based on time elapsed since last tick.
        val now = System.currentTimeMillis()
        if (lastTickMs >= 0L) {
            val elapsed = now - lastTickMs
            interactBudget += elapsed / (310f / 8f)   // 8 tokens per 310 ms
            interactBudget = interactBudget.coerceIn(0f, maxNotesPerTick.toFloat())
        } else {
            interactBudget = maxNotesPerTick.toFloat()
        }
        lastTickMs = now

        // Prune stale backlog sitting behind the head. The head note itself is exempt
        // from staleTimeoutMs once convergence waiting has begun -- see rotationInProgressTimeoutMs.
        while (queue.size > 1) {
            val behindHead = queue[1]
            val behindAge = now - behindHead.enqueuedAtMs
            if (behindAge > staleTimeoutMs) {
                queue.removeAt(1)
                logger.warn("dropping stale backlogged MIDI ${behindHead.midiNote} (age ${behindAge}ms > ${staleTimeoutMs}ms)")
            } else {
                break
            }
        }

        var dispatched = 0
        while (dispatched < maxNotesPerTick && interactBudget >= 1f && queue.isNotEmpty()) {
            val request = queue.first()
            val age = now - request.enqueuedAtMs

            val pos = resolvePos(request)
            if (pos == null) {
                queue.removeFirst()
                logger.warn("no block found for MIDI ${request.midiNote} -- skipping (assignment has ${assignment.size} entries, resolvedPos=${request.resolvedPos})")
                // Count as a dispatch slot consumed so we don't spin through the entire
                // queue in one tick if many consecutive notes are unresolvable.
                dispatched++
                continue
            }

            // Rotation convergence gate applies to every note. If the player is not yet
            // facing this block, stop dispatching for this tick and wait. The next tick
            // the loop resumes from the same head note once rotation has caught up.
            // (Previously notes 2-N skipped this gate, which caused rapid-fire interacts
            // without intervening rotation packets -- the pattern that triggers anticheat.)
            val converged = rotationConvergedDelegate?.invoke(pos) ?: true
            if (!converged) {
                if (age > rotationInProgressTimeoutMs) {
                    queue.removeFirst()
                    logger.warn("dropping MIDI ${request.midiNote} at $pos -- rotation did not converge within ${rotationInProgressTimeoutMs}ms (age ${age}ms)")
                    continue
                }
                DebugLog.info(logger) { "waiting for rotation convergence on $pos (MIDI ${request.midiNote}, age ${age}ms)" }
                return@synchronized
            }

            queue.removeFirst()
            val ok = interactDelegate?.invoke(pos, request) ?: false
            interactBudget -= 1f
            DebugLog.info(logger) { "dispatch MIDI ${request.midiNote} -> $pos result=$ok age=${age}ms dispatched=${dispatched + 1}/${maxNotesPerTick} budget=${"%.2f".format(interactBudget)} queueRemaining=${queue.size}" }
            if (!ok) {
                logger.warn("interactDelegate returned false for $pos (MIDI ${request.midiNote})")
            }
            dispatched++
        }
    }

    /**
     * Returns the BlockPos the next pending note will be dispatched to, without
     * consuming it. Used by BlockBardClient to drive PlayerController.primeRotation()
     * every tick a note is pending.
     *
     * Does NOT apply staleTimeoutMs -- see the onTick kdoc for why primeRotation must
     * continue for a note that has started converging even if it crosses staleTimeoutMs.
     */
    fun peekNextPos(): BlockPos? = synchronized(lock) {
        val request = queue.firstOrNull() ?: return@synchronized null
        val pos = resolvePos(request)
        DebugLog.info(logger) { "peekNextPos: MIDI ${request.midiNote} instrument=${request.instrument?.name ?: "any"} -> $pos" }
        pos
    }

    fun enqueue(request: NoteRequest) = synchronized(lock) {
        DebugLog.info(logger) { "enqueue MIDI ${request.midiNote} instrument=${request.instrument?.name ?: "any"} resolvedPos=${request.resolvedPos} queueSize=${queue.size + 1}" }
        queue.addLast(request)
    }

    fun clear() = synchronized(lock) {
        logger.info("queue cleared (had ${queue.size} items)")
        queue.clear()
        interactBudget = 1f
        lastTickMs = -1L
    }

    fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }
    val queueSize: Int get() = synchronized(lock) { queue.size }
}
