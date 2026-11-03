package kyrielie.blockbard.organ

import kyrielie.blockbard.util.BlockBardLogBuffer
import kyrielie.blockbard.util.DebugLog
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
 * Categorized failstates for noteblock tuning failures.
 */
enum class TuningFailState(val tag: String, val description: String) {
    OCCLUDED("OCCLUDED", "Blocked by intervening blocks; cannot tune through other blocks"),
    OUT_OF_REACH("OUT_OF_REACH", "Beyond survival reach distance"),
    UNRESPONSIVE("UNRESPONSIVE", "Note did not change after clicks (server anticheat dropped packet or region protected)"),
    INTERACT_REJECTED("INTERACT_REJECTED", "Interaction could not be initiated (no empty hotbar slot or invalid gamemode)"),
    BLOCK_MISSING("BLOCK_MISSING", "Block missing or destroyed in world"),
    DESYNC("DESYNC", "Note advanced but did not reach target within retry limit")
}

/**
 * Closed-loop, paced noteblock tuner.
 *
 * A click is counted as applied only when the server's block update shows the note changed
 * (see [TuningEngine]); the tuner never assumes clicks were delivered at tick rate.
 * - Initial clicks per block come from live world reads, not the scan snapshot.
 * - One focused block at a time; the next click is sent after the previous one is confirmed
 *   (or two in flight when [fastTuning] is on), with a minimum gap between clicks.
 * - A click unconfirmed after max(250 ms, 2 * ping + 250 ms) is treated as dropped: the block backs
 *   off, goes to the back of the queue, and fails after [maxTimeoutsPerBlock] consecutive misses.
 * - When every block reads at target, a final confirmation window re-reads all blocks; regressions
 *   (for example a late-delivered click) are re-tuned up to [maxRetries] times.
 * - Failures are classified from per-block evidence (clicks sent, confirmations, rejections).
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
    private val diagnoseFailState: ((BlockPos, Int?, Int) -> TuningFailState?)? = null,
    private val onBlockFailed: ((TuneTarget, TuningFailState, Int?, Int) -> Unit)? = null,
    /** Called for failed blocks that [TuningFailPolicy] says should be marked untunable. */
    private val onBlockUntunable: ((TuneTarget, TuningFailState) -> Unit)? = null,
    val onProgress: (done: Int, total: Int, msg: String) -> Unit = { _, _, _ -> },
    /** Minimum gap between clicks when [fastTuning] is off. */
    private val clickGapMs: Long = DEFAULT_CLICK_GAP_MS,
    /** Minimum gap between clicks when [fastTuning] is on. */
    private val fastClickGapMs: Long = FAST_CLICK_GAP_MS,
    private val maxTimeoutsPerBlock: Int = DEFAULT_MAX_TIMEOUTS_PER_BLOCK,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Receives one line per tuning event (click sent, confirmed, timed out, ...). */
    private val onTrace: ((String) -> Unit)? = null
) {
    private val logger = LoggerFactory.getLogger("BlockBard/NoteBlockTuner")

    var state: TunerState = TunerState.IDLE
        private set
    var failureReason: String? = null
        private set

    val failedTargets = mutableMapOf<TuneTarget, TuningFailState>()

    private val targetByPos: Map<BlockPos, TuneTarget> = targets.associateBy { it.pos }
    private lateinit var engine: TuningEngine<BlockPos>

    private var startedAtMs: Long = 0L
    private var verifyStartedAt: Long = -1L

    val total: Int = targets.size
    private var confirmedCount: Int = 0
    private var alreadyTunedAtStart: Int = 0
    private var retryCount: Int = 0

    var pendingRotationPos: BlockPos? = null
        private set

    private fun timeoutMs(): Long = maxOf(MIN_TIMEOUT_MS, pingMs() * 2L + 250L)

    fun start() {
        if (targets.isEmpty()) {
            logger.info("NoteBlockTuner: no targets -- already done")
            BlockBardLogBuffer.info("Tuner", "No targets to tune -- completed.")
            state = TunerState.DONE
            onProgress(0, 0, "Nothing to tune.")
            return
        }

        failedTargets.clear()
        failureReason = null
        val now = clock()
        startedAtMs = now

        engine = TuningEngine(
            keys = targets.map { it.pos },
            targetOf = { pos -> targetByPos.getValue(pos).targetNote },
            params = TuningEngine.Params(
                timeoutMs = { timeoutMs() },
                clickGapMs = { if (fastTuning) fastClickGapMs else clickGapMs },
                pipelineDepth = { if (fastTuning) 2 else 1 },
                stallLimitMs = { maxOf(MIN_STALL_LIMIT_MS, timeoutMs() * 4L) },
                maxTimeoutsPerBlock = maxTimeoutsPerBlock
            ),
            listener = { e -> traceEvent(e) }
        )

        var totalClicks = 0
        var needClicks = 0
        engine.begin(now) { pos ->
            val t = targetByPos.getValue(pos)
            val liveNote = worldNoteReader(pos) ?: t.snapshotNote
            val needed = clicksNeeded(liveNote, t.targetNote)
            if (needed > 0) {
                needClicks++
                totalClicks += needed
                val targetMidi = noteIndexToMidi(t.instrument, t.targetNote)
                val msg = "${t.pos} (${t.instrument.name}) liveNote=$liveNote -> target=${t.targetNote} (${midiNoteToName(targetMidi)}) needs $needed clicks"
                logger.debug("Tuner: $msg")
                BlockBardLogBuffer.debug("Tuner", msg)
            }
            liveNote
        }
        alreadyTunedAtStart = total - needClicks

        if (engine.isFinished) {
            logger.info("NoteBlockTuner: all $total targets already at target notes!")
            BlockBardLogBuffer.info("Tuner", "All $total blocks already in tune.")
            state = TunerState.DONE
            confirmedCount = total
            onProgress(total, total, "Organ is already in tune!")
            return
        }

        state = TunerState.TUNING
        verifyStartedAt = -1L
        confirmedCount = alreadyTunedAtStart
        retryCount = 0
        pendingRotationPos = null

        val startMsg = "$needClicks/$total blocks need tuning (~$totalClicks clicks, ping=${pingMs()}ms)"
        logger.info("NoteBlockTuner: starting -- $startMsg")
        BlockBardLogBuffer.info("Tuner", "Starting tuner: $startMsg")
    }

    fun onTick() {
        if (state == TunerState.IDLE) { start(); return }
        if (state == TunerState.DONE || state == TunerState.FAILED) return

        val now = clock()
        when (state) {
            TunerState.TUNING    -> tickTuning(now)
            TunerState.VERIFYING -> tickVerifying(now)
            else -> {}
        }
    }

    private fun tickTuning(now: Long) {
        engine.observe(now, worldNoteReader)
        if (engine.checkStall(now)) {
            val stallMsg = "No click or confirmation for ${maxOf(MIN_STALL_LIMIT_MS, timeoutMs() * 4L)}ms; failing remaining blocks"
            logger.warn("NoteBlockTuner: $stallMsg")
            BlockBardLogBuffer.warn("Tuner", stallMsg)
        }

        confirmedCount = engine.doneCount
        onProgress(confirmedCount, total, "$confirmedCount/$total in tune, ${engine.activeCount} pending")

        if (engine.isFinished) {
            finishTuning(now)
            return
        }

        val key = engine.nextClickKey(now)

        // Keep aiming at the block we are working on (or about to click), as the old per-tick gate did.
        val aim = key ?: pendingRotationPos
        if (aim != null && primeRotation != null) primeRotation.invoke(aim)
        if (key == null) return // waiting: click gap, confirmation, or backoff

        if (primeRotation != null && rotationConverged != null) {
            if (pendingRotationPos != key) {
                pendingRotationPos = key
                return // wait for the camera to converge on the new block
            }
            if (!rotationConverged.invoke(key)) return
        }

        if (interactBlock(key)) engine.onClickSent(key, now) else engine.onClickRejected(key, now)
    }

    /** All blocks are DONE or FAILED: confirm late arrivals, then verify or fail. */
    private fun finishTuning(now: Long) {
        // A block the engine gave up on may still have been applied late; trust the live note.
        engine.failedKeys.forEach { pos ->
            if (worldNoteReader(pos) == targetByPos.getValue(pos).targetNote) engine.markDone(pos)
        }
        val stillFailed = engine.failedKeys
        if (stillFailed.isNotEmpty()) {
            failTuning(stillFailed)
            return
        }
        val pingWait = verifyWindowMs()
        verifyStartedAt = now
        state = TunerState.VERIFYING
        val verifyMsg = "All blocks read in tune ($total blocks) -- confirming with server in ${pingWait}ms"
        logger.info("NoteBlockTuner: $verifyMsg")
        BlockBardLogBuffer.info("Tuner", verifyMsg)
        onProgress(total, total, "Verifying with server...")
    }

    private fun verifyWindowMs(): Long = maxOf(200L, pingMs() * 2L + 150L)

    private fun tickVerifying(now: Long) {
        if (now - verifyStartedAt < verifyWindowMs()) return

        val missing = mutableListOf<BlockPos>()
        val regressed = mutableListOf<Pair<BlockPos, Int>>()
        for (t in targets) {
            val live = worldNoteReader(t.pos)
            when {
                live == null -> missing.add(t.pos)
                live == t.targetNote -> NoteBlockRegistry.updateTunedNote(t.pos, live)
                else -> regressed.add(t.pos to live)
            }
        }
        confirmedCount = total - missing.size - regressed.size

        if (missing.isEmpty() && regressed.isEmpty()) {
            state = TunerState.DONE
            val skipped = alreadyTunedAtStart
            val doneMsg = "Tuning complete! All $total blocks confirmed in tune ($skipped already correct, ${total - skipped} tuned)."
            logger.info("NoteBlockTuner: $doneMsg")
            BlockBardLogBuffer.info("Tuner", doneMsg)
            onProgress(total, total, doneMsg)
            return
        }

        retryCount++
        if (missing.isNotEmpty() || retryCount > maxRetries) {
            failTuning(missing + regressed.map { it.first })
            return
        }

        val retryMsg = "Final check found ${regressed.size} block(s) out of tune, retrying (attempt $retryCount/$maxRetries): " +
            regressed.take(3).joinToString("; ") { (pos, live) ->
                "$pos got $live expected ${targetByPos.getValue(pos).targetNote}"
            }
        logger.warn("NoteBlockTuner: $retryMsg")
        BlockBardLogBuffer.warn("Tuner", retryMsg)
        regressed.forEach { (pos, live) -> engine.reactivate(pos, live, now) }
        pendingRotationPos = null
        state = TunerState.TUNING
        onProgress(confirmedCount, total, "Retrying ${regressed.size} blocks...")
    }

    private fun failTuning(positions: List<BlockPos>) {
        state = TunerState.FAILED
        failedTargets.clear()
        pendingRotationPos = null

        for (pos in positions) {
            val target = targetByPos.getValue(pos)
            val worldNote = worldNoteReader(pos)
            val failState = classify(target, worldNote, engine.evidence(pos))

            failedTargets[target] = failState
            onBlockFailed?.invoke(target, failState, worldNote, target.targetNote)
            if (TuningFailPolicy.marksUntunable(failState, engine.anyConfirmation)) {
                onBlockUntunable?.invoke(target, failState)
            }

            val targetName = midiNoteToName(noteIndexToMidi(target.instrument, target.targetNote))
            val currentDesc = if (worldNote != null) "$worldNote (${midiNoteToName(noteIndexToMidi(target.instrument, worldNote))})" else "missing"
            val ev = engine.evidence(pos)
            val evidenceDesc = if (ev != null) " [clicks=${ev.clicksSent} confirmed=${ev.confirmations} timeouts=${ev.timeouts} rejects=${ev.rejects}]" else ""
            val report = "[${failState.tag}] $pos (${target.instrument.name}) current=$currentDesc -> target=$targetName: ${failState.description}$evidenceDesc"
            logger.warn("NoteBlockTuner failstate: $report")
            BlockBardLogBuffer.error("Tuner", report)
        }

        confirmedCount = targets.count { worldNoteReader(it.pos) == it.targetNote }
        val failSummary = failedTargets.entries.groupBy { it.value }
            .map { (st, list) -> "${list.size} ${st.tag}" }.joinToString(", ")
        val failMsg = "Tuning stopped: $confirmedCount/$total tuned. ${failedTargets.size} block(s) require manual tuning ($failSummary)"
        failureReason = failMsg
        logger.warn("NoteBlockTuner: $failMsg")
        BlockBardLogBuffer.error("Tuner", failMsg)
        onProgress(confirmedCount, total, "Requires manual tuning: $failSummary")
    }

    /**
     * Classifies a failure from what actually happened to the block rather than from the end-of-run
     * pose: a block that ever advanced is a DESYNC (it is clickable), so geometry diagnosis only
     * runs for blocks that never responded.
     */
    private fun classify(target: TuneTarget, worldNote: Int?, ev: TuningEvidence?): TuningFailState = when {
        worldNote == null || ev?.missing == true -> TuningFailState.BLOCK_MISSING
        ev != null && ev.confirmations > 0 -> TuningFailState.DESYNC
        ev != null && ev.clicksSent == 0 && ev.rejects > 0 -> TuningFailState.INTERACT_REJECTED
        else -> diagnoseFailState?.invoke(target.pos, worldNote, target.targetNote)
            ?: if (ev == null || ev.clicksSent == 0) TuningFailState.INTERACT_REJECTED else TuningFailState.UNRESPONSIVE
    }

    private fun traceEvent(e: TuningEvent<BlockPos>) {
        val inst = targetByPos[e.key]?.instrument?.name ?: "?"
        val line = "+${e.nowMs - startedAtMs}ms ${e.type} ${e.key.x},${e.key.y},${e.key.z} $inst " +
            "note=${e.noteBefore}->${e.noteAfter} target=${e.target} inFlight=${e.inFlight}" +
            (e.latencyMs?.let { " ms=$it" } ?: "") + (e.detail?.let { " ($it)" } ?: "")
        DebugLog.info(logger) { "trace $line" }
        onTrace?.invoke(line)
    }

    val isDone: Boolean get() = state == TunerState.DONE
    val isFailed: Boolean get() = state == TunerState.FAILED
    val isActive: Boolean get() = state == TunerState.TUNING || state == TunerState.VERIFYING
    val confirmedBlocks: Int get() = confirmedCount

    companion object {
        const val DEFAULT_CLICK_GAP_MS = 100L
        const val FAST_CLICK_GAP_MS = 50L
        const val DEFAULT_MAX_TIMEOUTS_PER_BLOCK = 5
        private const val MIN_TIMEOUT_MS = 250L
        private const val MIN_STALL_LIMIT_MS = 10_000L
    }
}
