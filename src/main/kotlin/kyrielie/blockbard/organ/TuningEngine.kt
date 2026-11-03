package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded

enum class TuningEventType { SENT, REJECTED, CONFIRMED, TIMEOUT, DONE, FAILED }

/**
 * One observable step of the tuning loop, for tracing. [noteBefore]/[noteAfter] are the last note
 * read for the block before and after the event; [latencyMs] is set on CONFIRMED (time from the
 * oldest unconfirmed click to the server's block update).
 */
data class TuningEvent<K>(
    val type: TuningEventType,
    val key: K,
    val nowMs: Long,
    val noteBefore: Int?,
    val noteAfter: Int?,
    val target: Int,
    val inFlight: Int,
    val latencyMs: Long? = null,
    val detail: String? = null
)

/** Per-block counters used to classify why a block failed. */
data class TuningEvidence(
    val firstNote: Int?,
    val lastNote: Int?,
    val clicksSent: Int,
    val confirmations: Int,
    val timeouts: Int,
    val rejects: Int,
    val missing: Boolean
)

/**
 * Closed-loop click pacing for noteblock tuning. No Minecraft types: keys are opaque (BlockPos in
 * the mod, strings or ints in tests) and time is passed in, so the logic is unit-testable.
 *
 * Principle: a click is only counted as applied when the block's note is observed to change.
 * The loop never assumes tick-rate delivery.
 *
 * - Each block has up to [Params.pipelineDepth] unconfirmed clicks in flight.
 * - Remaining clicks to send = clicksNeeded(lastSeen, target) - inFlight, so in-flight clicks are
 *   never over-counted and the live note is always the source of truth.
 * - A click unconfirmed after [Params.timeoutMs] is treated as dropped: the block backs off,
 *   moves to the back of the queue (so it cannot block others), and fails after
 *   [Params.maxTimeoutsPerBlock] consecutive timeouts with no confirmation in between.
 * - The engine stays on one block until it is done, failed, or backing off, so the caller does
 *   not have to rotate between blocks on every click.
 *
 * Call order each tick: [observe], [checkStall], then [nextClickKey]; after the caller attempts the
 * click, [onClickSent] or [onClickRejected].
 */
class TuningEngine<K : Any>(
    keys: List<K>,
    private val targetOf: (K) -> Int,
    private val params: Params,
    private val listener: ((TuningEvent<K>) -> Unit)? = null
) {
    class Params(
        val timeoutMs: () -> Long,
        val clickGapMs: () -> Long,
        val pipelineDepth: () -> Int,
        val stallLimitMs: () -> Long,
        val maxTimeoutsPerBlock: Int = 5,
        val maxRejectsPerBlock: Int = 3
    )

    private enum class Outcome { ACTIVE, DONE, FAILED }

    private class Entry<K>(val key: K, val target: Int) {
        var outcome = Outcome.ACTIVE
        var lastSeen: Int? = null
        var firstNote: Int? = null
        val inFlight = ArrayDeque<Long>()
        var clicksSent = 0
        var confirmations = 0
        var timeouts = 0
        var rejects = 0
        var consecutiveTimeouts = 0
        var consecutiveRejects = 0
        var blockedUntil = 0L
        var missing = false
    }

    private val entries: Map<K, Entry<K>> = keys.associateWith { Entry(it, targetOf(it)) }
    private val order = ArrayDeque(keys)
    private var focus: K? = null
    private var lastDispatchAt: Long? = null
    private var lastProgressAt: Long = 0L

    val total: Int get() = entries.size
    val doneCount: Int get() = entries.values.count { it.outcome == Outcome.DONE }
    val activeCount: Int get() = entries.values.count { it.outcome == Outcome.ACTIVE }
    val isFinished: Boolean get() = activeCount == 0
    val failedKeys: List<K> get() = order.filter { entries[it]?.outcome == Outcome.FAILED }

    /** True once any block has been observed to advance after a click: the server is accepting clicks. */
    val anyConfirmation: Boolean get() = entries.values.any { it.confirmations > 0 }

    fun lastSeen(key: K): Int? = entries[key]?.lastSeen

    fun evidence(key: K): TuningEvidence? = entries[key]?.let {
        TuningEvidence(it.firstNote, it.lastSeen, it.clicksSent, it.confirmations, it.timeouts, it.rejects, it.missing)
    }

    /** Starts the run. [liveNote] gives each block's current note; blocks already at target are DONE. */
    fun begin(now: Long, liveNote: (K) -> Int) {
        lastProgressAt = now
        lastDispatchAt = null
        focus = null
        entries.values.forEach { e ->
            val live = liveNote(e.key)
            e.lastSeen = live
            e.firstNote = live
            e.outcome = if (live == e.target) Outcome.DONE else Outcome.ACTIVE
        }
    }

    /**
     * Re-opens [key] with a freshly read note, used by the final verification when a block regressed
     * (for example a late-delivered click). Counters are kept so evidence stays cumulative.
     */
    fun reactivate(key: K, live: Int, now: Long) {
        val e = entries[key] ?: return
        e.lastSeen = live
        e.inFlight.clear()
        e.consecutiveTimeouts = 0
        e.consecutiveRejects = 0
        e.blockedUntil = 0L
        e.outcome = if (live == e.target) Outcome.DONE else Outcome.ACTIVE
        lastProgressAt = now
    }

    /** Marks [key] DONE without a read (caller observed it at target). */
    fun markDone(key: K) {
        entries[key]?.let { it.outcome = Outcome.DONE; it.inFlight.clear() }
    }

    /**
     * Reads every active block, registering confirmations (note changed since the last read),
     * completions (note equals target) and timeouts (click unconfirmed too long).
     */
    fun observe(now: Long, read: (K) -> Int?) {
        val timeout = params.timeoutMs()
        for (key in order.toList()) {
            val e = entries.getValue(key)
            if (e.outcome != Outcome.ACTIVE) continue

            val live = read(key)
            if (live == null) {
                e.missing = true
                fail(e, now, "block missing")
                continue
            }

            val last = e.lastSeen ?: live
            if (live != last) {
                val advance = ((live - last) % 25 + 25) % 25
                var latency: Long? = null
                repeat(minOf(advance, e.inFlight.size)) {
                    val sentAt = e.inFlight.removeFirst()
                    if (latency == null) latency = now - sentAt
                }
                e.confirmations++
                e.consecutiveTimeouts = 0
                e.lastSeen = live
                lastProgressAt = now
                emit(TuningEventType.CONFIRMED, e, now, last, live, latency, null)
            } else {
                e.lastSeen = live
            }

            if (live == e.target) {
                e.outcome = Outcome.DONE
                e.inFlight.clear()
                if (focus == key) focus = null
                emit(TuningEventType.DONE, e, now, live, live, null, null)
                continue
            }

            while (e.inFlight.isNotEmpty() && now - e.inFlight.first() > timeout) {
                val age = now - e.inFlight.removeFirst()
                e.timeouts++
                e.consecutiveTimeouts++
                e.blockedUntil = now + backoffMs(e.consecutiveTimeouts)
                emit(TuningEventType.TIMEOUT, e, now, e.lastSeen, e.lastSeen, age, "consecutive=${e.consecutiveTimeouts}")
                order.remove(key)
                order.addLast(key)
                if (focus == key) focus = null
                if (e.consecutiveTimeouts >= params.maxTimeoutsPerBlock) {
                    fail(e, now, "no confirmation after ${e.consecutiveTimeouts} timeouts")
                    break
                }
            }
        }
    }

    /**
     * Fails every active block when nothing was clicked or confirmed for the stall limit (for
     * example the camera never converges on a block). Returns true if the stall guard fired.
     */
    fun checkStall(now: Long): Boolean {
        if (activeCount == 0) return false
        if (now - lastProgressAt <= params.stallLimitMs()) return false
        entries.values.filter { it.outcome == Outcome.ACTIVE }.forEach { fail(it, now, "stalled") }
        return true
    }

    /**
     * The block to click this tick, or null to wait (click gap not elapsed, waiting for a
     * confirmation on the focused block, or every active block is backing off).
     * Returns the same key until it is clicked, so the caller can rotate toward it.
     */
    fun nextClickKey(now: Long): K? {
        if (isFinished) return null
        val sentAt = lastDispatchAt
        if (sentAt != null && now - sentAt < params.clickGapMs() - GAP_TOLERANCE_MS) return null

        val depth = params.pipelineDepth().coerceAtLeast(1)

        val f = focus?.let { entries[it] }
        if (f != null && f.outcome == Outcome.ACTIVE && now >= f.blockedUntil) {
            // Sticky: click the focused block when it has room, otherwise wait for its confirmation.
            return if (canClick(f, now, depth)) f.key else null
        }
        focus = null

        for (key in order) {
            val e = entries.getValue(key)
            if (canClick(e, now, depth)) {
                focus = key
                return key
            }
        }
        return null
    }

    fun onClickSent(key: K, now: Long) {
        val e = entries[key] ?: return
        e.inFlight.addLast(now)
        e.clicksSent++
        e.consecutiveRejects = 0
        lastDispatchAt = now
        lastProgressAt = now
        focus = key
        emit(TuningEventType.SENT, e, now, e.lastSeen, e.lastSeen, null, null)
    }

    fun onClickRejected(key: K, now: Long) {
        val e = entries[key] ?: return
        e.rejects++
        e.consecutiveRejects++
        lastDispatchAt = now
        emit(TuningEventType.REJECTED, e, now, e.lastSeen, e.lastSeen, null, "consecutive=${e.consecutiveRejects}")
        if (e.consecutiveRejects >= params.maxRejectsPerBlock) {
            fail(e, now, "interaction rejected ${e.consecutiveRejects} times")
        }
    }

    private fun canClick(e: Entry<K>, now: Long, depth: Int): Boolean {
        if (e.outcome != Outcome.ACTIVE) return false
        if (now < e.blockedUntil) return false
        if (e.inFlight.size >= depth) return false
        val last = e.lastSeen ?: return false
        return clicksNeeded(last, e.target) - e.inFlight.size > 0
    }

    private fun fail(e: Entry<K>, now: Long, why: String) {
        e.outcome = Outcome.FAILED
        e.inFlight.clear()
        if (focus == e.key) focus = null
        emit(TuningEventType.FAILED, e, now, e.lastSeen, e.lastSeen, null, why)
    }

    private fun backoffMs(consecutiveTimeouts: Int): Long {
        val shift = (consecutiveTimeouts - 1).coerceIn(0, 4)
        return (BACKOFF_BASE_MS shl shift).coerceAtMost(BACKOFF_CAP_MS)
    }

    private fun emit(
        type: TuningEventType, e: Entry<K>, now: Long, before: Int?, after: Int?, latency: Long?, detail: String?
    ) {
        listener?.invoke(TuningEvent(type, e.key, now, before, after, e.target, e.inFlight.size, latency, detail))
    }

    companion object {
        /** Ticks are ~50 ms and jittery; allow a click one half tick early so a 100 ms gap means 2 ticks. */
        const val GAP_TOLERANCE_MS = 25L
        const val BACKOFF_BASE_MS = 250L
        const val BACKOFF_CAP_MS = 2000L
    }
}
