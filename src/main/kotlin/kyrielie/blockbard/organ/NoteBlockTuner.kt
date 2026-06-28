package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.midiNoteToName
import kyrielie.blockbard.util.noteIndexToMidi
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

data class TuneTarget(
    val pos: BlockPos,
    val snapshotNote: Int,  // noteIndex at scan time — used only for initial click estimate
    val targetNote: Int,    // 0–24, required noteIndex after tuning
    val instrument: NoteBlockInstrument
) {
    /** Estimated clicks from snapshot. Actual clicks are driven by live world reads. */
    val estimatedClicks: Int get() = clicksNeeded(snapshotNote, targetNote)
    val appearsAlreadyTuned: Boolean get() = snapshotNote == targetNote
}

enum class TunerState { IDLE, TUNING, VERIFYING, DONE, FAILED }

/**
 * Stateful noteblock tuner modelled on DiscJockey's approach:
 *
 * - Re-reads the actual world blockstate every tick for every target.
 *   The snapshot note in TuneTarget is only used for the initial click estimate;
 *   the live world read is always authoritative for deciding whether to click.
 * - Skips blocks that are already at their target note (live world read).
 * - Maintains [notePredictions] for optimistic in-flight tracking (TTL = ping*2 + 150ms).
 * - Token-bucket rate limiter: 8 tokens / 310ms (matches Paper's server-side limit).
 * - Verification phase: waits ping*2 + 100ms then re-reads all blocks before declaring done.
 * - On mismatch during verification, clears predictions and retries only the failed blocks.
 */
class NoteBlockTuner(
    private val targets: List<TuneTarget>,
    private val worldNoteReader: (BlockPos) -> Int?,
    private val interactBlock: (BlockPos) -> Boolean,
    private val pingMs: () -> Int = { 0 },
    private val maxRetries: Int = 3,
    val onProgress: (done: Int, total: Int, msg: String) -> Unit = { _, _, _ -> }
) {
    private val logger = LoggerFactory.getLogger("BlockBard/NoteBlockTuner")

    var state: TunerState = TunerState.IDLE
        private set

    // Optimistic predictions: pos → (predicted note index, expires at ms)
    private val notePredictions = mutableMapOf<BlockPos, Pair<Int, Long>>()

    // Token bucket (Paper limit: 8 interacts per 310ms)
    private var availableInteracts: Float = 8f
    private var lastInteractMs: Long = -1L

    // Verification countdown
    private var verifyStartedAt: Long = -1L

    val total: Int = targets.size
    private var confirmedCount: Int = 0

    // How many targets were already at their target note when tuning started
    private var alreadyTunedAtStart: Int = 0

    // Number of TUNING<->VERIFYING retry cycles caused by a verification mismatch.
    // Without this cap, a block that can never converge (anticheat dropping packets
    // server-side, a block destroyed mid-tune and respawned elsewhere, persistent lag)
    // would cycle TUNING <-> VERIFYING forever.
    private var retryCount: Int = 0

    fun start() {
        if (targets.isEmpty()) {
            logger.info("NoteBlockTuner: no targets — already done")
            state = TunerState.DONE
            onProgress(0, 0, "Nothing to tune.")
            return
        }

        // Count blocks already at target using LIVE world reads, not snapshot
        alreadyTunedAtStart = targets.count { t ->
            worldNoteReader(t.pos) == t.targetNote
        }

        state = TunerState.TUNING
        availableInteracts = 8f
        lastInteractMs = -1L
        notePredictions.clear()
        verifyStartedAt = -1L
        confirmedCount = 0
        retryCount = 0

        val needClicks = total - alreadyTunedAtStart
        logger.info("NoteBlockTuner: starting — $total targets, $alreadyTunedAtStart already correct, $needClicks need clicks")
        targets.forEach { t ->
            val liveNote = worldNoteReader(t.pos) ?: t.snapshotNote
            val clicks = clicksNeeded(liveNote, t.targetNote)
            val targetMidi = noteIndexToMidi(t.instrument, t.targetNote)
            if (clicks > 0) {
                logger.debug("  NEEDS TUNE: ${t.pos} ${t.instrument.name} liveNote=$liveNote → target=${t.targetNote} (${midiNoteToName(targetMidi)}) = $clicks clicks")
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

        when (state) {
            TunerState.TUNING    -> tickTuning(now)
            TunerState.VERIFYING -> tickVerifying(now)
            else -> {}
        }
    }

    private fun tickTuning(now: Long) {
        val untuned = mutableListOf<Pair<BlockPos, Int>>() // pos → effective current note
        confirmedCount = 0

        for (target in targets) {
            // Always read live world state
            val worldNote = worldNoteReader(target.pos)
            if (worldNote == null) {
                logger.warn("NoteBlockTuner: ${target.pos} is no longer a noteblock!")
                state = TunerState.FAILED
                onProgress(confirmedCount, total, "FAILED: ${target.pos} is no longer a noteblock")
                return
            }

            // Use in-flight prediction if fresher than world (prediction not yet expired)
            val effectiveNote = notePredictions[target.pos]?.first ?: worldNote

            when {
                // Both world and effective agree it's correct → confirmed
                effectiveNote == target.targetNote && worldNote == target.targetNote -> confirmedCount++
                // Prediction says it's on the way, world hasn't caught up → wait (don't click again)
                effectiveNote == target.targetNote && worldNote != target.targetNote -> { /* wait */ }
                // Not at target → needs a click
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
            logger.info("NoteBlockTuner: all confirmed — verifying (waiting ${pingWait}ms for server round-trip)")
            onProgress(confirmedCount, total, "Verifying...")
            return
        }

        // Dispatch clicks within rate limit
        for ((pos, effectiveNote) in untuned) {
            if (availableInteracts < 1f) {
                logger.debug("NoteBlockTuner: rate limited — ${untuned.size} blocks deferred")
                break
            }
            val dispatched = interactBlock(pos)
            if (!dispatched) {
                logger.warn("NoteBlockTuner: interactBlock false for $pos — aborting")
                state = TunerState.FAILED
                onProgress(confirmedCount, total, "FAILED: could not interact with $pos")
                return
            }
            val predicted = (effectiveNote + 1) % 25
            val expires = now + pingMs() * 2 + 150L
            notePredictions[pos] = Pair(predicted, expires)
            logger.debug("NoteBlockTuner: clicked $pos note $effectiveNote → predicted $predicted")
            availableInteracts -= 1f
            lastInteractMs = now
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
            logger.info("NoteBlockTuner: DONE — $total verified ($skipped were already correct)")
            onProgress(confirmedCount, total, "Complete! ($skipped already correct, ${total - skipped} tuned)")
        } else {
            retryCount++
            if (retryCount > maxRetries) {
                state = TunerState.FAILED
                logger.warn("NoteBlockTuner: ${mismatches.size} mismatches after $retryCount retries — giving up")
                onProgress(confirmedCount, total, "FAILED: ${mismatches.size} blocks never converged: ${mismatches.joinToString("; ")}")
            } else {
                logger.warn("NoteBlockTuner: ${mismatches.size} mismatches — retrying (attempt $retryCount/$maxRetries)")
                notePredictions.clear()
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