package kyrielie.blockbard.organ

import kyrielie.blockbard.util.BlockBardLogBuffer
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
 * Stateful, anticheat-safe noteblock tuner.
 *
 * Uses deterministic click tracking with verification:
 * - Initializes remaining clicks per block directly from live world state.
 * - Dispatches clicks within the anticheat token bucket rate limit (8 interacts / 310ms).
 * - Transitions to VERIFYING as soon as all planned clicks are dispatched.
 * - Re-reads true world blockstates after network latency wait (ping * 2 + 400ms).
 * - On any mismatch (dropped packet, lag), queues only the delta clicks and retries (up to maxRetries).
 */
class NoteBlockTuner(
    private val targets: List<TuneTarget>,
    private val worldNoteReader: (BlockPos) -> Int?,
    private val interactBlock: (BlockPos) -> Boolean,
    private val pingMs: () -> Int = { 0 },
    private val maxRetries: Int = 3,
    private val primeRotation: ((BlockPos) -> Unit)? = null,
    private val rotationConverged: ((BlockPos) -> Boolean)? = null,
    var fastTuning: Boolean = true,
    val onProgress: (done: Int, total: Int, msg: String) -> Unit = { _, _, _ -> }
) {
    private val logger = LoggerFactory.getLogger("BlockBard/NoteBlockTuner")

    var state: TunerState = TunerState.IDLE
        private set

    // Pos -> remaining clicks required to reach target note
    private val remainingClicks = mutableMapOf<BlockPos, Int>()

    // Token bucket (Paper limit: 8 interacts per 310ms)
    private var availableInteracts: Float = 8f
    private var lastInteractMs: Long = -1L

    // Verification countdown
    private var verifyStartedAt: Long = -1L

    val total: Int = targets.size
    private var confirmedCount: Int = 0
    private var alreadyTunedAtStart: Int = 0
    private var retryCount: Int = 0

    var pendingRotationPos: BlockPos? = null
        private set

    fun start() {
        if (targets.isEmpty()) {
            logger.info("NoteBlockTuner: no targets -- already done")
            BlockBardLogBuffer.info("Tuner", "No targets to tune -- completed.")
            state = TunerState.DONE
            onProgress(0, 0, "Nothing to tune.")
            return
        }

        remainingClicks.clear()
        alreadyTunedAtStart = 0

        targets.forEach { t ->
            val liveNote = worldNoteReader(t.pos) ?: t.snapshotNote
            val needed = clicksNeeded(liveNote, t.targetNote)
            if (needed == 0) {
                alreadyTunedAtStart++
            } else {
                remainingClicks[t.pos] = needed
                val targetMidi = noteIndexToMidi(t.instrument, t.targetNote)
                val msg = "${t.pos} (${t.instrument.name}) liveNote=$liveNote -> target=${t.targetNote} (${midiNoteToName(targetMidi)}) needs $needed clicks"
                logger.debug("Tuner: $msg")
                BlockBardLogBuffer.debug("Tuner", msg)
            }
        }

        if (remainingClicks.isEmpty()) {
            logger.info("NoteBlockTuner: all $total targets already at target notes!")
            BlockBardLogBuffer.info("Tuner", "All $total blocks already in tune.")
            state = TunerState.DONE
            confirmedCount = total
            onProgress(total, total, "Organ is already in tune!")
            return
        }

        state = TunerState.TUNING
        availableInteracts = 8f
        lastInteractMs = -1L
        verifyStartedAt = -1L
        confirmedCount = alreadyTunedAtStart
        retryCount = 0
        pendingRotationPos = null

        val needClicks = remainingClicks.size
        val totalClicks = remainingClicks.values.sum()
        val startMsg = "$needClicks/$total blocks need tuning (~$totalClicks clicks, ping=${pingMs()}ms)"
        logger.info("NoteBlockTuner: starting -- $startMsg")
        BlockBardLogBuffer.info("Tuner", "Starting tuner: $startMsg")
    }

    fun onTick() {
        if (state == TunerState.IDLE) { start(); return }
        if (state == TunerState.DONE || state == TunerState.FAILED) return

        val now = System.currentTimeMillis()

        // Refill token bucket (8 tokens per 310ms)
        if (lastInteractMs >= 0L) {
            val elapsed = now - lastInteractMs
            availableInteracts += elapsed / (310f / 8f)
            availableInteracts = availableInteracts.coerceIn(0f, 8f)
        } else {
            availableInteracts = 8f
        }

        // Rotation gate: if priming rotation toward target, wait for convergence
        val pending = pendingRotationPos
        if (pending != null && primeRotation != null && rotationConverged != null) {
            primeRotation.invoke(pending)
            if (!rotationConverged.invoke(pending)) {
                return // Still rotating -- continue next tick
            }
        }

        when (state) {
            TunerState.TUNING    -> tickTuning(now)
            TunerState.VERIFYING -> tickVerifying(now)
            else -> {}
        }
    }

    private fun tickTuning(now: Long) {
        val pendingTargets = targets.filter { (remainingClicks[it.pos] ?: 0) > 0 }
        confirmedCount = total - pendingTargets.size

        val progress = "$confirmedCount/$total dispatched, ${pendingTargets.size} pending"
        logger.debug("NoteBlockTuner TUNING: $progress")
        onProgress(confirmedCount, total, progress)

        if (pendingTargets.isEmpty()) {
            val pingWait = (pingMs() * 2 + 400L).coerceAtLeast(400L)
            verifyStartedAt = now
            state = TunerState.VERIFYING
            val verifyMsg = "All clicks dispatched ($total blocks) -- verifying with server in ${pingWait}ms"
            logger.info("NoteBlockTuner: $verifyMsg")
            BlockBardLogBuffer.info("Tuner", verifyMsg)
            onProgress(confirmedCount, total, "Verifying with server...")
            return
        }

        // Tune the current target until finished, then advance to next target.
        // Focusing on one block at a time ensures the player faces one block steadily
        // matching Mineflayer's activateBlock approach and preventing anticheat angle violations.
        val target = pendingTargets.first()
        val pos = target.pos
        val needed = remainingClicks[pos] ?: return

        if (primeRotation != null && rotationConverged != null) {
            if (pendingRotationPos != pos) {
                pendingRotationPos = pos
                primeRotation.invoke(pos)
                return // Wait for convergence next tick
            }
        }

        // Max clicks for this block this tick:
        // When fastTuning: up to 2 clicks per tick (safe for anticheats and server packet processing)
        // When fastTuning is off: 1 click per tick
        val maxThisTick = if (fastTuning) needed.coerceAtMost(2).coerceAtMost(availableInteracts.toInt().coerceAtLeast(1)) else 1

        for (c in 0 until maxThisTick) {
            if (availableInteracts < 1f) break
            val dispatched = interactBlock(pos)
            if (!dispatched) {
                val err = "FAILED: interactBlock returned false for $pos"
                logger.warn("NoteBlockTuner: $err")
                BlockBardLogBuffer.error("Tuner", err)
                state = TunerState.FAILED
                pendingRotationPos = null
                onProgress(confirmedCount, total, err)
                return
            }

            availableInteracts -= 1f
            lastInteractMs = now
            val rem = (remainingClicks[pos] ?: 1) - 1
            if (rem <= 0) {
                remainingClicks.remove(pos)
                break
            } else {
                remainingClicks[pos] = rem
            }
        }
        pendingRotationPos = null
    }

    private fun tickVerifying(now: Long) {
        val pingWait = (pingMs() * 2 + 500L).coerceAtLeast(500L)
        if (now - verifyStartedAt < pingWait) return

        var allGood = true
        val mismatches = mutableListOf<String>()
        confirmedCount = 0

        for (target in targets) {
            val worldNote = worldNoteReader(target.pos)
            if (worldNote == null) {
                allGood = false
                val msg = "${target.pos} missing from world"
                mismatches.add(msg)
                logger.warn("NoteBlockTuner: $msg")
                BlockBardLogBuffer.warn("Tuner", msg)
                continue
            }
            if (worldNote == target.targetNote) {
                confirmedCount++
                NoteBlockRegistry.updateTunedNote(target.pos, worldNote)
            } else {
                allGood = false
                val needed = clicksNeeded(worldNote, target.targetNote)
                remainingClicks[target.pos] = needed
                val desc = "${target.pos}: got $worldNote expected ${target.targetNote} ($needed clicks needed)"
                mismatches.add(desc)
                logger.warn("NoteBlockTuner: $desc")
            }
        }

        if (allGood) {
            state = TunerState.DONE
            val skipped = alreadyTunedAtStart
            val doneMsg = "Tuning complete! All $total blocks confirmed in tune ($skipped already correct, ${total - skipped} tuned)."
            logger.info("NoteBlockTuner: $doneMsg")
            BlockBardLogBuffer.info("Tuner", doneMsg)
            onProgress(total, total, doneMsg)
        } else {
            retryCount++
            if (retryCount > maxRetries) {
                state = TunerState.FAILED
                val failMsg = "Tuning failed after $maxRetries retries: ${mismatches.joinToString("; ")}"
                logger.warn("NoteBlockTuner: $failMsg")
                BlockBardLogBuffer.error("Tuner", failMsg)
                onProgress(confirmedCount, total, "FAILED: ${mismatches.size} blocks did not tune")
            } else {
                val retryMsg = "Verification mismatch (${mismatches.size} blocks), retrying (attempt $retryCount/$maxRetries): ${mismatches.take(3).joinToString("; ")}"
                logger.warn("NoteBlockTuner: $retryMsg")
                BlockBardLogBuffer.warn("Tuner", retryMsg)
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
