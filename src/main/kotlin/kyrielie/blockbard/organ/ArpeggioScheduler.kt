package kyrielie.blockbard.organ

import kyrielie.blockbard.util.DebugLog
import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.isPitched
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.noteIndexToMidi
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
    val resolvedPos: BlockPos? = null,
    val isRetuneStep: Boolean = false,
    /**
     * True for notes played live (MIDI keyboard). They skip the song assignment maps and are
     * resolved against the blocks currently tuned, with instrument preference and octave
     * shifting (see [LiveNoteResolver]). [instrument] is then the preferred instrument.
     */
    val live: Boolean = false
)

/**
 * One dispatched interact, recorded for post-disconnect diagnostics.
 * See [ArpeggioScheduler.recentDispatches] / KickDiagnostics.
 */
data class DispatchRecord(
    val atMs: Long,
    val pos: BlockPos,
    val midiNote: Int,
    val budgetBefore: Float,
    val budgetAfter: Float
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

    /**
     * Multi-voice pools: NotePitch -> list of all available blocks for this pitch.
     * Allows polyphony and rapid note repetitions to alternate round-robin across physical blocks.
     */
    var voicePools: Map<NotePitch, List<BlockPos>> = emptyMap()
        set(value) = synchronized(lock) {
            field = value
            voiceIndices.clear()
        }

    private val voiceIndices = mutableMapOf<NotePitch, Int>()

    /**
     * Removes failed block positions from active assignment and voice pools so discordant notes
     * are not played on blocks that failed to reach their target pitch.
     */
    fun unassign(failedPositions: Set<BlockPos>) = synchronized(lock) {
        if (failedPositions.isEmpty()) return@synchronized
        assignment = assignment.filterValues { it !in failedPositions }
        voicePools = voicePools.mapValues { (_, pool) -> pool.filter { it !in failedPositions } }
            .filterValues { it.isNotEmpty() }
        voiceIndices.clear()
    }

    /** Requests older than this are dropped to prevent note build-up after lag. */
    var staleTimeoutMs: Long = 200L

    /**
     * Safety cap for a note that is actively converging rotation.
     * Sized above worst-case turn time at MAX_ROTATION_DEGREES_PER_TICK so it only
     * fires if rotation genuinely got stuck.
     */
    var rotationInProgressTimeoutMs: Long = 1500L

    // Sliding 100ms packet budget circuit breaker (Disc-Jockey anticheat defense)
    private var last100MsSpanAt: Long = -1L
    private var packetsInCurrent100MsSpan: Int = 0
    const val SOFT_PACKET_LIMIT_100MS: Int = 30
    const val HARD_PACKET_LIMIT_100MS: Int = 45
    private var pausePacketsUntilMs: Long = -1L

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
     * Above 8: the sustained rate is unchanged (the refill rate is fixed at 8 tokens
     *   per 310 ms), but the bucket ceiling equals this value, so after an idle period
     *   a burst of up to this many interacts can be sent at once. That exceeds the
     *   8-per-310 ms limit this limiter is modelled on and can trigger server kicks.
     *   The config accepts up to 64; values above 8 are only for servers known to have
     *   no interact rate limit.
     *
     * Set from BlockBardConfig.maxNotesPerTick at init in BlockBardClient.
     */
    var maxNotesPerTick: Int = 8

    /**
     * Wired to PlayerController.playNoteAt(pos, request) by the client entrypoint.
     * Takes the target BlockPos plus the NoteRequest being dispatched so
     * PlayerController can hand the full (midiNote, instrument) pair to SoundVerifier.
     */
    var interactDelegate: ((BlockPos, NoteRequest) -> Boolean)? = null

    /**
     * Wired to PlayerController.retuneAndPlayNoteAt(pos, request) for dynamic retuning.
     */
    var dynamicRetuneDelegate: ((BlockPos, NoteRequest) -> Boolean)? = null
    var dynamicRetuningEnabled: Boolean = false

    var onNoteDispatched: ((midiNote: Int, instrument: NoteBlockInstrument?) -> Unit)? = null

    /**
     * Wired to PlayerController.rotationConverged() by the client entrypoint. Gates
     * dispatch so interactWith() never fires while the eased rotation is still mid-turn.
     * If unset, dispatch proceeds unconditionally (back-compat / tests).
     */
    var rotationConvergedDelegate: ((BlockPos) -> Boolean)? = null

    private val queue: ArrayDeque<NoteRequest> = ArrayDeque()
    private val lookaheadRetunes: ArrayDeque<ScheduledRetuneTask> = ArrayDeque()

    fun loadRetuneSchedule(tasks: List<ScheduledRetuneTask>) = synchronized(lock) {
        lookaheadRetunes.clear()
        lookaheadRetunes.addAll(tasks.sortedBy { it.startMs })
        logger.info("loadRetuneSchedule: loaded ${tasks.size} lookahead retune tasks")
    }

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

    // ── Dispatch history (for KickDiagnostics) ──────────────────────────────────
    // Fixed-size ring buffer of the last RECENT_DISPATCH_CAPACITY interacts. Cheap
    // (single pre-allocated ArrayDeque with a hard cap, no per-dispatch allocation
    // beyond the DispatchRecord itself) and gives KickDiagnostics a precise account
    // of exactly what was sent in the moments before a disconnect -- see kdoc on
    // recentDispatches() for why this is the only reliable way to answer "where is
    // the real limit" after the fact, since SoundVerifier only confirms sound played,
    // not that the server's rate limiter was untroubled by the send.
    private const val RECENT_DISPATCH_CAPACITY = 200
    private val dispatchHistory: ArrayDeque<DispatchRecord> = ArrayDeque()

    /** [adjusted] is the request as it will actually sound (shifted pitch, chosen instrument) for live notes. */
    data class TargetResolution(val pos: BlockPos, val isRetuneStep: Boolean = false, val adjusted: NoteRequest? = null)

    /**
     * Resolves a request to a target BlockPos, trying in order:
     * 1. resolvedPos, if the caller already knows the exact block.
     * 2. voicePools for the exact (midiNote, instrument) pair (round-robin multi-voice).
     * 3. voicePools for (midiNote, null) (round-robin instrument-agnostic).
     * 4. assignment for the exact (midiNote, instrument) pair.
     * 5. assignment for (midiNote, null) -- instrument-agnostic fallback.
     * 6. NoteBlockRegistry.findBestFor(midiNote, instrument) -- live lookup.
     * 7. Dynamic retuning: candidate noteblock at noteIndex - 1 (JIT right-click step).
     */
    private fun resolveTarget(request: NoteRequest): TargetResolution? {
        if (request.resolvedPos != null) return TargetResolution(request.resolvedPos, request.isRetuneStep)

        if (request.live) {
            val allowOctave = InstrumentShifter.mode == ShiftMode.OCTAVE_SHIFT || InstrumentShifter.mode == ShiftMode.BEST_EFFORT
            val choice = LiveNoteResolver.resolve(
                request.midiNote, request.instrument, NoteBlockRegistry.allPlayable(),
                allowOctave, InstrumentShifter.maxOctaveShift,
                { it.instrument }, { it.midiNote }, { it.instrument.isPitched }, { it.distanceFromPlayer }
            ) ?: return null
            val e = choice.block
            return TargetResolution(e.pos, adjusted = request.copy(midiNote = e.midiNote, instrument = e.instrument))
        }

        // Unpitched instruments (SNARE, HAT, BASEDRUM) produce identical sounds across all notes;
        // normalize to midiBase so they match the single canonical block assignment.
        val effectiveMidi = if (request.instrument != null && !request.instrument.isPitched)
            request.instrument.midiBase
        else request.midiNote

        val exactPitch = NotePitch(effectiveMidi, request.instrument)
        val exactPool = voicePools[exactPitch]
        if (!exactPool.isNullOrEmpty()) {
            val idx = voiceIndices.getOrDefault(exactPitch, 0) % exactPool.size
            voiceIndices[exactPitch] = (idx + 1) % exactPool.size
            return TargetResolution(exactPool[idx])
        }

        val agnosticPitch = NotePitch(effectiveMidi, null)
        val agnosticPool = voicePools[agnosticPitch]
        if (!agnosticPool.isNullOrEmpty()) {
            val idx = voiceIndices.getOrDefault(agnosticPitch, 0) % agnosticPool.size
            voiceIndices[agnosticPitch] = (idx + 1) % agnosticPool.size
            return TargetResolution(agnosticPool[idx])
        }

        val assigned = assignment[exactPitch] ?: assignment[agnosticPitch]
        if (assigned != null) return TargetResolution(assigned)

        val liveMatch = NoteBlockRegistry.findBestFor(effectiveMidi, request.instrument)?.pos
        if (liveMatch != null) return TargetResolution(liveMatch)

        // Dynamic On-the-fly retuning:
        if (dynamicRetuningEnabled && request.instrument != null) {
            val targetNoteIndex = request.midiNote - request.instrument.midiBase
            if (targetNoteIndex in 0..24) {
                val candidateNoteIndex = (targetNoteIndex - 1 + 25) % 25
                val candidate = NoteBlockRegistry.allPlayable()
                    .filter { it.instrument == request.instrument && it.noteIndex == candidateNoteIndex }
                    .minByOrNull { it.distanceFromPlayer }
                if (candidate != null) {
                    return TargetResolution(candidate.pos, isRetuneStep = true)
                }
            }
        }

        return null
    }

    private fun resolvePos(request: NoteRequest): BlockPos? = resolveTarget(request)?.pos

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
        if (now < pausePacketsUntilMs) {
            DebugLog.info(logger) { "onTick: 100ms packet breaker active, pausing dispatch for ${pausePacketsUntilMs - now}ms" }
            return@synchronized
        }

        // Rolling 100ms window tracking (Disc-Jockey anticheat circuit breaker)
        if (last100MsSpanAt < 0L || now - last100MsSpanAt >= 100L) {
            last100MsSpanAt = now
            packetsInCurrent100MsSpan = 0
        }

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
            if (packetsInCurrent100MsSpan >= HARD_PACKET_LIMIT_100MS) {
                val waitMs = (100L - (now - last100MsSpanAt)).coerceAtLeast(10L)
                pausePacketsUntilMs = now + waitMs
                logger.warn("Hard packet limit ($HARD_PACKET_LIMIT_100MS/100ms) reached! Pausing dispatches for ${waitMs}ms to prevent anticheat kick.")
                break
            }

            val request = queue.first()
            val age = now - request.enqueuedAtMs

            val target = resolveTarget(request)
            if (target == null) {
                queue.removeFirst()
                logger.warn("no block found for MIDI ${request.midiNote} -- skipping (assignment has ${assignment.size} entries, resolvedPos=${request.resolvedPos})")
                // Count as a dispatch slot consumed so we don't spin through the entire
                // queue in one tick if many consecutive notes are unresolvable.
                dispatched++
                continue
            }
            val pos = target.pos
            val dispatchRequest = target.adjusted ?: request

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
            val budgetBefore = interactBudget
            val ok = if (target.isRetuneStep) {
                dynamicRetuneDelegate?.invoke(pos, dispatchRequest) ?: interactDelegate?.invoke(pos, dispatchRequest) ?: false
            } else {
                interactDelegate?.invoke(pos, dispatchRequest) ?: false
            }
            onNoteDispatched?.invoke(request.midiNote, request.instrument)
            interactBudget -= 1f
            dispatchHistory.addLast(DispatchRecord(now, pos, request.midiNote, budgetBefore, interactBudget))
            if (dispatchHistory.size > RECENT_DISPATCH_CAPACITY) dispatchHistory.removeFirst()
            DebugLog.info(logger) { "dispatch MIDI ${request.midiNote} -> $pos result=$ok age=${age}ms dispatched=${dispatched + 1}/${maxNotesPerTick} budget=${"%.2f".format(interactBudget)} queueRemaining=${queue.size}" }
            if (!ok) {
                logger.warn("interactDelegate returned false for $pos (MIDI ${request.midiNote})")
            }
            dispatched++
            packetsInCurrent100MsSpan++
            if (packetsInCurrent100MsSpan >= SOFT_PACKET_LIMIT_100MS) {
                DebugLog.info(logger) { "approaching 100ms soft packet ceiling: $packetsInCurrent100MsSpan/$SOFT_PACKET_LIMIT_100MS" }
            }
        }

        // Rest-period lookahead dynamic retuning
        if (queue.isEmpty() && dynamicRetuningEnabled && lookaheadRetunes.isNotEmpty()) {
            val task = lookaheadRetunes.first()
            val currentNote = NoteBlockRegistry.get(task.pos)?.noteIndex ?: task.startNoteIndex
            val needed = clicksNeeded(currentNote, task.targetNoteIndex)

            if (needed <= 0 || now > task.deadlineMs) {
                lookaheadRetunes.removeFirst()
            } else if (now >= task.startMs && interactBudget >= 1f) {
                val converged = rotationConvergedDelegate?.invoke(task.pos) ?: true
                if (converged) {
                    val retuneRequest = NoteRequest(
                        midiNote = noteIndexToMidi(task.instrument, (currentNote + 1) % 25),
                        instrument = task.instrument,
                        resolvedPos = task.pos,
                        isRetuneStep = true
                    )
                    val ok = dynamicRetuneDelegate?.invoke(task.pos, retuneRequest) ?: false
                    if (ok) {
                        interactBudget -= 1f
                        val newNote = (currentNote + 1) % 25
                        NoteBlockRegistry.updateTunedNote(task.pos, newNote)
                        logger.debug("lookahead retune click on ${task.pos} (${task.instrument.name}): $currentNote -> $newNote (target ${task.targetNoteIndex})")
                        if (newNote == task.targetNoteIndex) {
                            lookaheadRetunes.removeFirst()
                        }
                    }
                }
            }
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
        val request = queue.firstOrNull()
        if (request != null) {
            val pos = resolvePos(request)
            DebugLog.info(logger) { "peekNextPos: MIDI ${request.midiNote} instrument=${request.instrument?.name ?: "any"} -> $pos" }
            return@synchronized pos
        }
        if (dynamicRetuningEnabled && lookaheadRetunes.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val nextRetune = lookaheadRetunes.firstOrNull()?.takeIf { now >= it.startMs && now <= it.deadlineMs }
            if (nextRetune != null) return@synchronized nextRetune.pos
        }
        null
    }

    fun enqueue(request: NoteRequest) = synchronized(lock) {
        DebugLog.info(logger) { "enqueue MIDI ${request.midiNote} instrument=${request.instrument?.name ?: "any"} resolvedPos=${request.resolvedPos} queueSize=${queue.size + 1}" }
        queue.addLast(request)
    }

    /**
     * Snapshot of the last [RECENT_DISPATCH_CAPACITY] dispatched interacts, oldest first.
     * Read by KickDiagnostics on disconnect to report exactly what was sent in the
     * window leading up to a kick -- timestamps, target blocks, and the token bucket's
     * value immediately before/after each send. A negative budgetAfter on the most
     * recent entries is the clearest direct evidence that the configured ceiling
     * exceeded what the server actually tolerated.
     */
    fun recentDispatches(): List<DispatchRecord> = synchronized(lock) { dispatchHistory.toList() }

    fun clear() = synchronized(lock) {
        logger.info("queue cleared (had ${queue.size} items, ${lookaheadRetunes.size} retune tasks)")
        queue.clear()
        lookaheadRetunes.clear()
        interactBudget = maxNotesPerTick.toFloat()
        lastTickMs = -1L
        dispatchHistory.clear()
        voiceIndices.clear()
        last100MsSpanAt = -1L
        packetsInCurrent100MsSpan = 0
        pausePacketsUntilMs = -1L
    }

    fun isEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }
    val queueSize: Int get() = synchronized(lock) { queue.size }
}
