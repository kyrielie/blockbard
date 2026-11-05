package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoteBlockTunerResilienceTest {

    @Test
    fun singleBlockFailureYieldsDonePartial() {
        val posGood = BlockPos(10, 64, 10)
        val posBad = BlockPos(20, 64, 20)

        val targets = listOf(
            TuneTarget(posGood, snapshotNote = 0, targetNote = 1),
            TuneTarget(posBad, snapshotNote = 0, targetNote = 2)
        )

        val notes = mutableMapOf(posGood to 0, posBad to 0)
        var simTime = 1000L

        val tuner = NoteBlockTuner(
            targets = targets,
            worldNoteReader = { notes[it] },
            interactBlock = { pos ->
                if (pos == posGood) {
                    // Good block advances
                    notes[pos] = 1
                }
                // Bad block does nothing (unresponsive/dropped)
                true
            },
            pingMs = { 20 },
            reachDistance = { 4.5 },
            isCreative = { false },
            maxTimeoutsPerBlock = 2,
            clock = { simTime }
        )

        tuner.start()
        assertTrue(tuner.isActive)

        // Advance ticks
        repeat(50) {
            simTime += 200L
            tuner.onTick()
        }

        assertFalse(tuner.isActive)
        assertEquals(TunerState.DONE_PARTIAL, tuner.state)
        assertTrue(tuner.isPartial)
        assertFalse(tuner.isDone)
        assertTrue(tuner.isSuccessful)
        assertFalse(tuner.isFailed)
        assertEquals(1, tuner.confirmedBlocks)
        assertEquals(1, tuner.failedTargets.size)
        assertTrue(tuner.failedTargets.keys.any { it.pos == posBad })
    }

    @Test
    fun allBlocksFailingYieldsFailed() {
        val pos1 = BlockPos(10, 64, 10)
        val pos2 = BlockPos(20, 64, 20)

        val targets = listOf(
            TuneTarget(pos1, snapshotNote = 0, targetNote = 2),
            TuneTarget(pos2, snapshotNote = 0, targetNote = 2)
        )

        val notes = mutableMapOf(pos1 to 0, pos2 to 0)
        var simTime = 1000L

        val tuner = NoteBlockTuner(
            targets = targets,
            worldNoteReader = { notes[it] },
            interactBlock = { true }, // clicks accepted but notes never change (unresponsive)
            pingMs = { 20 },
            maxTimeoutsPerBlock = 2,
            clock = { simTime }
        )

        tuner.start()
        assertTrue(tuner.isActive)

        repeat(50) {
            simTime += 200L
            tuner.onTick()
        }

        assertFalse(tuner.isActive)
        assertEquals(TunerState.FAILED, tuner.state)
        assertTrue(tuner.isFailed)
        assertFalse(tuner.isPartial)
        assertFalse(tuner.isDone)
        assertFalse(tuner.isSuccessful)
        assertEquals(0, tuner.confirmedBlocks)
        assertEquals(2, tuner.failedTargets.size)
    }

    @Test
    fun allBlocksSucceedingYieldsDone() {
        val pos1 = BlockPos(10, 64, 10)
        val pos2 = BlockPos(20, 64, 20)

        val targets = listOf(
            TuneTarget(pos1, snapshotNote = 0, targetNote = 1),
            TuneTarget(pos2, snapshotNote = 0, targetNote = 1)
        )

        val notes = mutableMapOf(pos1 to 0, pos2 to 0)
        var simTime = 1000L

        val tuner = NoteBlockTuner(
            targets = targets,
            worldNoteReader = { notes[it] },
            interactBlock = { pos ->
                notes[pos] = 1
                true
            },
            pingMs = { 20 },
            clock = { simTime }
        )

        tuner.start()
        assertTrue(tuner.isActive)

        repeat(20) {
            simTime += 100L
            tuner.onTick()
        }

        assertFalse(tuner.isActive)
        assertEquals(TunerState.DONE, tuner.state)
        assertTrue(tuner.isDone)
        assertFalse(tuner.isPartial)
        assertTrue(tuner.isSuccessful)
        assertFalse(tuner.isFailed)
        assertEquals(2, tuner.confirmedBlocks)
        assertTrue(tuner.failedTargets.isEmpty())
    }
}
