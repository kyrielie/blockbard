package kyrielie.blockbard.organ

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TuningEngineTest {

    /** Minimal server: clicks arrive after [latency] ms, may be dropped or ignored. */
    private class Sim(
        val notes: MutableMap<String, Int>,
        val latency: Long = 60L,
        val dropIf: (key: String, seq: Int) -> Boolean = { _, _ -> false },
        val ignore: Set<String> = emptySet()
    ) {
        var now = 0L
        private val arrivals = mutableListOf<Pair<Long, String>>()
        private var seq = 0
        val sent = mutableListOf<Pair<Long, String>>()

        fun click(key: String) {
            seq++
            sent += now to key
            if (key in ignore || dropIf(key, seq)) return
            arrivals += (now + latency) to key
        }

        fun deliver() {
            val due = arrivals.filter { it.first <= now }
            arrivals.removeAll(due)
            due.forEach { (_, key) -> notes[key] = ((notes[key] ?: 0) + 1) % 25 }
        }
    }

    private fun params(
        timeout: Long = 300L,
        gap: Long = 100L,
        depth: Int = 1,
        stall: Long = 10_000L,
        maxTimeouts: Int = 5,
        maxRejects: Int = 3
    ) = TuningEngine.Params(
        timeoutMs = { timeout }, clickGapMs = { gap }, pipelineDepth = { depth },
        stallLimitMs = { stall }, maxTimeoutsPerBlock = maxTimeouts, maxRejectsPerBlock = maxRejects
    )

    private fun engine(
        targets: Map<String, Int>,
        p: TuningEngine.Params = params(),
        events: MutableList<TuningEvent<String>>? = null
    ) = TuningEngine(targets.keys.toList(), { targets.getValue(it) }, p, events?.let { l -> { e -> l += e } })

    /** Runs the loop the way NoteBlockTuner does. Returns true if it ended by the stall guard. */
    private fun run(e: TuningEngine<String>, sim: Sim, maxMs: Long = 120_000L, tickMs: Long = 50L): Boolean {
        e.begin(0L) { sim.notes.getValue(it) }
        while (!e.isFinished && sim.now < maxMs) {
            sim.now += tickMs
            sim.deliver()
            e.observe(sim.now) { sim.notes[it] }
            if (e.checkStall(sim.now)) return true
            val key = e.nextClickKey(sim.now) ?: continue
            sim.click(key)
            e.onClickSent(key, sim.now)
        }
        return false
    }

    @Test
    fun allBlocksReachTarget_withExactlyTheNeededClicks_includingWrap() {
        val targets = mapOf("A" to 5, "B" to 7, "C" to 0)
        val sim = Sim(mutableMapOf("A" to 0, "B" to 0, "C" to 1)) // C needs 24 clicks (wrap)
        val e = engine(targets)
        run(e, sim)
        assertTrue(e.isFinished)
        assertEquals(3, e.doneCount)
        assertTrue(e.failedKeys.isEmpty())
        assertEquals(5 + 7 + 24, sim.sent.size)
        targets.forEach { (k, t) -> assertEquals(t, sim.notes[k]) }
    }

    @Test
    fun blocksAlreadyAtTarget_areDoneWithoutClicks() {
        val targets = mapOf("A" to 3, "B" to 9)
        val sim = Sim(mutableMapOf("A" to 3, "B" to 9))
        val e = engine(targets)
        e.begin(0L) { sim.notes.getValue(it) }
        assertTrue(e.isFinished)
        assertEquals(2, e.doneCount)
        assertEquals(null, e.nextClickKey(1_000L))
    }

    @Test
    fun randomDrops_convergeAndRetriesAreOnlyForUnconfirmedClicks() {
        val targets = mapOf("A" to 5, "B" to 7, "C" to 12)
        val sim = Sim(mutableMapOf("A" to 0, "B" to 0, "C" to 0), dropIf = { _, seq -> seq % 3 == 0 })
        val e = engine(targets)
        run(e, sim)
        assertEquals(3, e.doneCount)
        assertTrue(e.failedKeys.isEmpty())
        assertTrue(sim.sent.size > 24)
        targets.forEach { (k, t) -> assertEquals(t, sim.notes[k]) }
    }

    @Test
    fun permanentlyIgnoredBlock_failsWithEvidence_andDoesNotBlockOthers() {
        val targets = mapOf("D" to 4, "A" to 5, "B" to 6)
        val sim = Sim(mutableMapOf("D" to 0, "A" to 0, "B" to 0), ignore = setOf("D"))
        val e = engine(targets)
        run(e, sim)
        assertEquals(listOf("D"), e.failedKeys)
        assertEquals(2, e.doneCount)
        assertEquals(5, sim.notes["A"])
        assertEquals(6, sim.notes["B"])
        val ev = e.evidence("D")!!
        assertTrue(ev.clicksSent > 0)
        assertEquals(0, ev.confirmations)
        assertEquals(5, ev.timeouts)
    }

    @Test
    fun lateDelivery_afterTimeout_isConfirmedWithoutResending() {
        // Click arrives after the timeout but before the backoff expires: no second click, no overshoot.
        val sim = Sim(mutableMapOf("A" to 0), latency = 400L)
        val e = engine(mapOf("A" to 1), params(timeout = 300L))
        run(e, sim)
        assertEquals(1, e.doneCount)
        assertEquals(1, sim.sent.size)
        val ev = e.evidence("A")!!
        assertEquals(1, ev.timeouts)
        assertEquals(1, ev.confirmations)
        assertEquals(1, sim.notes["A"])
    }

    @Test
    fun pipelineDepthTwo_hasTwoInFlight_butNeverMoreThanNeeded() {
        val events = mutableListOf<TuningEvent<String>>()
        val sim = Sim(mutableMapOf("A" to 0), latency = 120L)
        val e = engine(mapOf("A" to 3), params(depth = 2, gap = 50L), events)
        run(e, sim)
        assertEquals(1, e.doneCount)
        assertEquals(3, sim.sent.size)
        assertEquals(2, events.filter { it.type == TuningEventType.SENT }.maxOf { it.inFlight })
        assertEquals(3, sim.notes["A"])
    }

    @Test
    fun clickGapIsRespected() {
        val sim = Sim(mutableMapOf("A" to 0, "B" to 0))
        val e = engine(mapOf("A" to 4, "B" to 4), params(gap = 100L))
        run(e, sim)
        sim.sent.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first - a.first >= 100L - TuningEngine.GAP_TOLERANCE_MS, "gap ${b.first - a.first}")
        }
    }

    @Test
    fun staysOnFocusedBlockUntilDone() {
        val sim = Sim(mutableMapOf("A" to 0, "B" to 0))
        val e = engine(mapOf("A" to 3, "B" to 3))
        run(e, sim)
        assertEquals(listOf("A", "A", "A", "B", "B", "B"), sim.sent.map { it.second })
    }

    @Test
    fun timedOutBlock_movesToBackSoOthersProceed() {
        val sim = Sim(mutableMapOf("A" to 0, "B" to 0), dropIf = { k, seq -> k == "A" && seq == 1 })
        val e = engine(mapOf("A" to 2, "B" to 2))
        run(e, sim)
        assertEquals("A", sim.sent[0].second)
        assertEquals("B", sim.sent[1].second)
        assertEquals(2, e.doneCount)
    }

    @Test
    fun stallGuard_failsActiveBlocksWhenNothingIsClicked() {
        val e = engine(mapOf("A" to 5, "B" to 6), params(stall = 10_000L))
        e.begin(0L) { 0 }
        var now = 0L
        var fired = false
        while (now < 30_000L && !fired) {
            now += 50L
            e.observe(now) { 0 }
            fired = e.checkStall(now)
            e.nextClickKey(now) // caller never manages to click (camera never converges)
        }
        assertTrue(fired)
        assertTrue(now in 10_000L..10_100L)
        assertEquals(listOf("A", "B"), e.failedKeys.sorted())
        assertEquals(0, e.evidence("A")!!.clicksSent)
    }

    @Test
    fun rejectedClicks_failBlockAfterLimit_andRecordEvidence() {
        val e = engine(mapOf("A" to 5, "B" to 6), params(maxRejects = 3, gap = 0L))
        e.begin(0L) { 0 }
        repeat(3) { e.onClickRejected("A", it * 50L) }
        assertEquals(listOf("A"), e.failedKeys)
        val ev = e.evidence("A")!!
        assertEquals(3, ev.rejects)
        assertEquals(0, ev.clicksSent)
        assertFalse(e.isFinished)
    }

    @Test
    fun missingBlock_failsWithMissingFlag() {
        val e = engine(mapOf("A" to 5))
        e.begin(0L) { 0 }
        e.observe(50L) { null }
        assertEquals(listOf("A"), e.failedKeys)
        assertTrue(e.evidence("A")!!.missing)
    }

    @Test
    fun reactivate_reopensBlockThatRegressedAfterDone() {
        val sim = Sim(mutableMapOf("A" to 0))
        val e = engine(mapOf("A" to 2))
        run(e, sim)
        assertEquals(1, e.doneCount)
        sim.notes["A"] = 1 // regression, e.g. a late duplicate click
        e.reactivate("A", 1, sim.now)
        assertFalse(e.isFinished)
        sim.sent.clear()
        run2(e, sim)
        assertEquals(2, sim.notes["A"])
        assertEquals(1, sim.sent.size)
    }

    /** Continues an already begun engine. */
    private fun run2(e: TuningEngine<String>, sim: Sim) {
        val limit = sim.now + 30_000L
        while (!e.isFinished && sim.now < limit) {
            sim.now += 50L
            sim.deliver()
            e.observe(sim.now) { sim.notes[it] }
            val key = e.nextClickKey(sim.now) ?: continue
            sim.click(key)
            e.onClickSent(key, sim.now)
        }
    }

    @Test
    fun tracedEvents_includeConfirmationLatency() {
        val events = mutableListOf<TuningEvent<String>>()
        val sim = Sim(mutableMapOf("A" to 0), latency = 120L)
        val e = engine(mapOf("A" to 1), events = events)
        run(e, sim)
        val confirmed = events.single { it.type == TuningEventType.CONFIRMED }
        assertEquals(0, confirmed.noteBefore)
        assertEquals(1, confirmed.noteAfter)
        assertTrue(confirmed.latencyMs!! in 120L..200L)
        assertTrue(events.any { it.type == TuningEventType.DONE })
    }
}
