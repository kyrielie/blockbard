package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.midiNoteToName
import kyrielie.blockbard.util.noteIndexToMidi
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

data class TuneTarget(
    val pos: BlockPos,
    val snapshotNote: Int,  // noteIndex at scan time -- used only for initial click estimate
    val targetNote: Int,    // 0-24, required noteIndex after tuning
    val instrument: NoteBlockInstrument
) {
    /** Estimated clicks from snapshot. Actual clicks are driven by live world reads. */
    val estimatedClicks: Int get() = clicksNeeded(snapshotNote, targetNote)
    val appearsAlreadyTuned: Boolean get() = snapshotNote == targetNote
}

enum class TunerState { IDLE, TUNING, VERIFYING, DONE, FAILED }

/**
 * Stateful noteblock tuner.
 *
 * - Re-reads the actual world blockstate every tick for every target.
 * - Skips blocks that are already at their target note (live world read).
 * - Maintains [notePredictions] for optimistic in-flight tracking (TTL = ping*2 + 150ms).
 * - Token-bucket rate limiter: 8 tokens / 310ms (matches Paper's server-side limit).
 * - Verification phase: waits ping*2 + 100ms then re-reads all blocks before declaring done.
 * - On mismatch during verification, clears predictions and retries only the failed blocks.
 * - Rotation gate: if [primeRotation] and [rotationConverged] are provided, each click
 *   waits for the player to be facing the target block before firing. This prevents the
 *   instant-rotation-then-click signature that anticheat plugins flag. Without these
 *   delegates the tuner fires clicks immediately (back-compat, singleplayer use).
 */
class NoteBlockTuner(
    private val targets: List<TuneTarget>,
    private val worldNoteReader: (BlockPos) -> Int?,
    private val interactBlock: (BlockPos) -> Boolean,
    private val pingMs: () -> Int = { 0 },
    private val maxRetries: Int = 3,
    /**
     * Called before each click to start easing the player's view toward the target
     * block. Must be called every tick while waiting, not just once -- same contract
     * as PlayerController.primeRotation(). If null, no rotation priming is performed
     * and clicks fire without any facing check.
     */
    private val primeRotation: ((BlockPos) -> Unit)? = null,
    /**
     * Returns true when the player's rotation is within the convergence threshold of
     * the target block. If null, the tuner fires clicks without any convergence wait
     * (back-compat -- existing callers that don't pass this get the old behavior).
     */
    private val rotationConverged: ((BlockPos) -> Boolean)? = null,
    val onProgress: (done: Int, total: Int, msg: String) -> Unit = { _, _, _ -> }
) {
    private val logger = LoggerFactory.getLogger("BlockBard/NoteBlockTuner")

    var state: TunerState = TunerState.IDLE
        private set

    // Optimistic predictions: pos -> (predicted note index, expires at ms)
    private val notePredictions = mutableMapOf<BlockPos, Pair<Int, Long>>()

    // Token bucket (Paper limit: 8 interacts per 310ms)
    private var availableInteracts: Float = 8f
    private var lastInteractMs: Long = -1L

    // Verification countdown
    private var verifyStartedAt: Long = -1L

    val total: Int = targets.size
    private var confirmedCount: Int = 0
    private var alreadyTunedAtStart: Int = 0
    private var retryCount: Int = 0

    /**
     * Rotation gate state for the current tick's pending click.
     *
     * When the tuner selects a block to click, it sets this to that block's pos and
     * calls primeRotation(pos). The click does not fire until rotationConverged(pos)
     * returns true. Once the click fires, this is cleared.
     *
     * Public so BlockBardClient's START_CLIENT_TICK handler (src/client module) can
     * read it and call PlayerController.primeRotation() every tick while a rotation is
     * pending -- the same pattern used for ArpeggioScheduler.peekNextPos().
     * NoteBlockTuner lives in src/main; with splitEnvironmentSourceSets the client
     * module can read main's public members but not internal ones.
     */
    var pendingRotationPos: BlockPos? = null
        private set

    fun start() {
        if (targets.isEmpty()) {
            logger.info("NoteBlockTuner: no targets -- already done")
            state = TunerState.DONE
            onProgress(0, 0, "Nothing to tune.")
            return
        }

        alreadyTunedAtStart = targets.count { t -> worldNoteReader(t.pos) == t.targetNote }

        state = TunerState.TUNING
        availableInteracts = 8f
        lastInteractMs = -1L
        notePredictions.clear()
        verifyStartedAt = -1L
        confirmedCount = 0
        retryCount = 0
        pendingRotationPos = null

        val needClicks = total - alreadyTunedAtStart
        logger.info("NoteBlockTuner: starting -- $total targets, $alreadyTunedAtStart already correct, $needClicks need clicks")
        targets.forEach { t ->
            val liveNote = worldNoteReader(t.pos) ?: t.snapshotNote
            val clicks = clicksNeeded(liveNote, t.targetNote)
            val targetMidi = noteIndexToMidi(t.instrument, t.targetNote)
            if (clicks > 0) {
                logger.debug("  NEEDS TUNE: ${t.pos} ${t.instrument.name} liveNote=$liveNote -> target=${t.targetNote} (${midiNoteToName(targetMidi)}) = $clicks clicks")
            } else {
                logger.debug("  SKIP (already correct): ${t.pos} ${t.instrument.name} note=$liveNote")
            }
        }
    }

    fun onTick() {
        if (state == TunerState.IDLE) { start(); return }
        if (state == TunerState.DONE || state == TunerState.FAILED) return

        val now = System.currentTimeMillis()

        // Expire stale predictions
        notePredictions.entries.removeAll { (_, v) -> v.second < now }

        // Refill token bucket
        if (lastInteractMs >= 0L) {
            val elapsed = now - lastInteractMs
            availableInteracts += elapsed / (310f / 8f)
            availableInteracts = availableInteracts.coerceIn(0f, 8f)
        } else {
            availableInteracts = 8f
        }

        // If rotation gate delegates are set and a rotation is pending, keep priming
        // and wait for convergence before doing any tuning work this tick.
        val pending = pendingRotationPos
        if (pending != null && primeRotation != null && rotationConverged != null) {
            primeRotation.invoke(pending)
            if (!rotationConverged.invoke(pending)) {
                // Still turning -- check TUNING or VERIFYING state handling
                // will resume next tick once converged.
                return
            }
            // Converged -- the actual click dispatch happens in tickTuning() below.
            // pendingRotationPos is cleared there after the click fires.
        }

        when (state) {
            TunerState.TUNING    -> tickTuning(now)
            TunerState.VERIFYING -> tickVerifying(now)
            else -> {}
        }
    }

    private fun tickTuning(now: Long) {
        val untuned = mutableListOf<Pair<BlockPos, Int>>() // pos -> effective current note
        confirmedCount = 0

        for (target in targets) {
            val worldNote = worldNoteReader(target.pos)
            if (worldNote == null) {
                logger.warn("NoteBlockTuner: ${target.pos} is no longer a noteblock!")
                state = TunerState.FAILED
                onProgress(confirmedCount, total, "FAILED: ${target.pos} is no longer a noteblock")
                return
            }

            val effectiveNote = notePredictions[target.pos]?.first ?: worldNote

            when {
                effectiveNote == target.targetNote && worldNote == target.targetNote -> confirmedCount++
                effectiveNote == target.targetNote && worldNote != target.targetNote -> { /* wait for server */ }
                else -> untuned.add(target.pos to effectiveNote)
            }
        }

        val progress = "$confirmedCount/$total confirmed, ${untuned.size} pending"
        logger.debug("NoteBlockTuner TUNING: $progress")
        onProgress(confirmedCount, total, progress)

        if (untuned.isEmpty() && confirmedCount == total) {
            val pingWait = pingMs() * 2 + 100L
            verifyStartedAt = now
            state = TunerState.VERIFYING
            logger.info("NoteBlockTuner: all confirmed -- verifying (waiting ${pingWait}ms for server round-trip)")
            onProgress(confirmedCount, total, "Verifying...")
            return
        }

        // Dispatch clicks within rate limit, with rotation gate.
        for ((pos, effectiveNote) in untuned) {
            if (availableInteracts < 1f) {
                logger.debug("NoteBlockTuner: rate limited -- ${untuned.size} blocks deferred")
                break
            }

            // Rotation gate: if delegates are set and we don't have a pending rotation
            // for this block yet, start priming and defer the click to a future tick.
            if (primeRotation != null && rotationConverged != null) {
                if (pendingRotationPos != pos) {
                    // Start rotating toward this block. The click will fire once
                    // convergence is reached (handled at the top of onTick()).
                    pendingRotationPos = pos
                    primeRotation.invoke(pos)
                    logger.debug("NoteBlockTuner: priming rotation toward $pos")
                    return  // wait for convergence next tick
                }
                // pendingRotationPos == pos and we already passed the convergence check
                // at the top of onTick() -- safe to click now.
            }

            val dispatched = interactBlock(pos)
            if (!dispatched) {
                logger.warn("NoteBlockTuner: interactBlock false for $pos -- aborting")
                state = TunerState.FAILED
                pendingRotationPos = null
                onProgress(confirmedCount, total, "FAILED: could not interact with $pos")
                return
            }
            val predicted = (effectiveNote + 1) % 25
            val expires = now + pingMs() * 2 + 150L
            notePredictions[pos] = Pair(predicted, expires)
            logger.debug("NoteBlockTuner: clicked $pos note $effectiveNote -> predicted $predicted")
            availableInteracts -= 1f
            lastInteractMs = now
            pendingRotationPos = null  // click fired, rotation no longer pending
        }
    }

    private fun tickVerifying(now: Long) {
        val pingWait = pingMs() * 2 + 100L
        if (now - verifyStartedAt < pingWait) return

        var allGood = true
        val mismatches = mutableListOf<String>()
        confirmedCount = 0

        for (target in targets) {
            val worldNote = worldNoteReader(target.pos)
            if (worldNote == null) {
                allGood = false
                mismatches.add("${target.pos} missing")
                logger.warn("NoteBlockTuner: ${target.pos} disappeared during verification")
                continue
            }
            if (worldNote == target.targetNote) {
                confirmedCount++
                NoteBlockRegistry.updateTunedNote(target.pos, worldNote)
            } else {
                allGood = false
                mismatches.add("${target.pos}: got $worldNote expected ${target.targetNote}")
                logger.warn("NoteBlockTuner: mismatch ${target.pos}: got $worldNote expected ${target.targetNote}")
            }
        }

        if (allGood) {
            state = TunerState.DONE
            val skipped = alreadyTunedAtStart
            logger.info("NoteBlockTuner: DONE -- $total verified ($skipped were already correct)")
            onProgress(confirmedCount, total, "Complete! ($skipped already correct, ${total - skipped} tuned)")
        } else {
            retryCount++
            if (retryCount > maxRetries) {
                state = TunerState.FAILED
                logger.warn("NoteBlockTuner: ${mismatches.size} mismatches after $retryCount retries -- giving up")
                onProgress(confirmedCount, total, "FAILED: ${mismatches.size} blocks never converged: ${mismatches.joinToString("; ")}")
            } else {
                logger.warn("NoteBlockTuner: ${mismatches.size} mismatches -- retrying (attempt $retryCount/$maxRetries)")
                notePredictions.clear()
                pendingRotationPos = null
                state = TunerState.TUNING
                onProgress(confirmedCount, total, "Retrying ${mismatches.size} mismatched blocks...")
            }
        }
    }

    val isDone: Boolean get() = state == TunerState.DONE
    val isFailed: Boolean get() = state == TunerState.FAILED
    val isActive: Boolean get() = state == TunerState.TUNING || state == TunerState.VERIFYING
    val confirmedBlocks: Int get() = confirmedCount
}
