package kyrielie.blockbard.organ

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TimelineVoiceAllocatorTest {

    private val harpSpec = InstrumentSpec(midiBase = 54, isPitched = true)
    private val snareSpec = InstrumentSpec(midiBase = 54, isPitched = false)

    private fun instSpec(name: String): InstrumentSpec = when (name) {
        "SNARE" -> snareSpec
        else -> harpSpec
    }

    @BeforeEach
    fun setUp() {
        ArpeggioScheduler.clear()
    }

    @Test
    fun zeroRetunesWhenMaxClicksIsZero() {
        val blocks = listOf(
            VoiceBlockSpec(pos = "block_1", instrument = "HARP", noteIndex = 0)
        )
        val events = listOf(
            GenericTimelineNote(timeMs = 0L, midiNote = 54, instrument = "HARP"),
            GenericTimelineNote(timeMs = 1000L, midiNote = 56, instrument = "HARP")
        )

        val plan = TimelineVoiceAllocator.compileGenericPlan(
            events = events,
            reachableBlocks = blocks,
            instrumentSpec = ::instSpec,
            maxClicksPerRetune = 0
        )

        assertEquals(1, plan.playNotes.size)
        assertEquals(1, plan.unplayable.size)
        assertEquals(56, plan.unplayable.first().midiNote)
        assertTrue(plan.retuneTasks.isEmpty())
        assertEquals(0, plan.stats.lookaheadRetunesScheduled)
    }

    @Test
    fun lookaheadRetuneScheduledWhenRestPeriodAllows() {
        val blocks = listOf(
            VoiceBlockSpec(pos = "block_1", instrument = "HARP", noteIndex = 0)
        )
        val events = listOf(
            GenericTimelineNote(timeMs = 0L, midiNote = 54, instrument = "HARP"),
            GenericTimelineNote(timeMs = 1000L, midiNote = 56, instrument = "HARP")
        )

        val plan = TimelineVoiceAllocator.compileGenericPlan(
            events = events,
            reachableBlocks = blocks,
            instrumentSpec = ::instSpec,
            maxClicksPerRetune = 2
        )

        assertEquals(2, plan.playNotes.size)
        assertTrue(plan.unplayable.isEmpty())
        assertEquals(1, plan.retuneTasks.size)

        val task = plan.retuneTasks.first()
        assertEquals("block_1", task.pos)
        assertEquals(0, task.startNoteIndex)
        assertEquals(2, task.targetNoteIndex)
        assertEquals(2, task.clicks)
        assertTrue(task.startMs >= 75L, "Retune start should honor release + rest buffer")
        assertTrue(task.deadlineMs <= 980L, "Retune deadline should be before note strike")
        assertEquals(1, plan.stats.lookaheadRetunesScheduled)
    }

    @Test
    fun ghostNoteBudgetCapEnforced() {
        val blocks = listOf(
            VoiceBlockSpec(pos = "block_1", instrument = "HARP", noteIndex = 0)
        )
        // Note 57 is noteIndex 3 -> requires 3 clicks from noteIndex 0
        val events = listOf(
            GenericTimelineNote(timeMs = 0L, midiNote = 54, instrument = "HARP"),
            GenericTimelineNote(timeMs = 1000L, midiNote = 57, instrument = "HARP")
        )

        val plan = TimelineVoiceAllocator.compileGenericPlan(
            events = events,
            reachableBlocks = blocks,
            instrumentSpec = ::instSpec,
            maxClicksPerRetune = 2 // Cap at 2 clicks
        )

        assertEquals(1, plan.playNotes.size)
        assertEquals(1, plan.unplayable.size)
        assertEquals(57, plan.unplayable.first().midiNote)
        assertTrue(plan.retuneTasks.isEmpty())
    }

    @Test
    fun polyphonyTracksConcurrentVoices() {
        val blocks = listOf(
            VoiceBlockSpec(pos = "block_1", instrument = "HARP", noteIndex = 0),
            VoiceBlockSpec(pos = "block_2", instrument = "HARP", noteIndex = 2)
        )
        // Two simultaneous notes
        val events = listOf(
            GenericTimelineNote(timeMs = 200L, midiNote = 54, instrument = "HARP"),
            GenericTimelineNote(timeMs = 200L, midiNote = 56, instrument = "HARP")
        )

        val plan = TimelineVoiceAllocator.compileGenericPlan(
            events = events,
            reachableBlocks = blocks,
            instrumentSpec = ::instSpec,
            maxClicksPerRetune = 2
        )

        assertEquals(2, plan.playNotes.size)
        assertEquals(2, plan.stats.physicalBlocksUsed)
        assertEquals(2, plan.stats.maxConcurrentPolyphony["HARP"])
    }

    @Test
    fun unpitchedPercussionBindsWithoutRetune() {
        val blocks = listOf(
            VoiceBlockSpec(pos = "snare_1", instrument = "SNARE", noteIndex = 0)
        )
        val events = listOf(
            GenericTimelineNote(timeMs = 100L, midiNote = 54, instrument = "SNARE"),
            GenericTimelineNote(timeMs = 300L, midiNote = 60, instrument = "SNARE")
        )

        val plan = TimelineVoiceAllocator.compileGenericPlan(
            events = events,
            reachableBlocks = blocks,
            instrumentSpec = ::instSpec,
            maxClicksPerRetune = 2
        )

        assertEquals(2, plan.playNotes.size)
        assertTrue(plan.unplayable.isEmpty())
        assertTrue(plan.retuneTasks.isEmpty())
    }
}
