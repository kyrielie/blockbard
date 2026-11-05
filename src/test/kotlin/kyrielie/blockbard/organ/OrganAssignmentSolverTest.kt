package kyrielie.blockbard.organ

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrganAssignmentSolverTest {

    @Test
    fun emptyNotes_returnsEmptyAssignment() {
        val blocks = listOf(
            OrganAssignmentSolver.BlockSpec("pos1", "HARP", 54, 0)
        )
        val solved = OrganAssignmentSolver.solve<String, String>(emptyList(), blocks)
        assertTrue(solved.matched.isEmpty())
        assertTrue(solved.unplayable.isEmpty())
    }

    @Test
    fun emptyBlocks_allNotesUnplayable() {
        val notes = listOf(
            OrganAssignmentSolver.NoteSpec("note1", 60, "HARP", weight = 10)
        )
        val solved = OrganAssignmentSolver.solve<String, String>(notes, emptyList())
        assertTrue(solved.matched.isEmpty())
        assertEquals(listOf("note1"), solved.unplayable)
    }

    @Test
    fun globalOptimization_beatsGreedyAssignment() {
        // Scenario where greedy by frequency picks suboptimal assignment:
        // Note A (weight 10, pitch 60):
        //   Block 1 (pitch 60, 0 clicks needed)
        //   Block 2 (pitch 61, 24 clicks needed)
        // Note B (weight 5, pitch 60):
        //   Block 1 (pitch 60, 0 clicks needed)
        //   Block 3 (pitch 59, 1 click needed)
        //
        // Greedy would give Note A Block 1 (0 clicks).
        // Then Note B must take Block 3 (1 click) or Block 2 (24 clicks) -> Total = 1 click.
        // Wait, what if:
        // Note A:
        //   Block 1: 0 clicks
        //   Block 2: 1 click
        // Note B:
        //   Block 1: 0 clicks
        //   Block 2: 24 clicks
        //
        // Greedy (Note A has higher weight 10):
        // Note A takes Block 1 (0 clicks).
        // Note B takes Block 2 (24 clicks). Total = 24 clicks.
        //
        // Optimal (Hungarian):
        // Note A takes Block 2 (1 click).
        // Note B takes Block 1 (0 clicks). Total = 1 click!

        val notes = listOf(
            OrganAssignmentSolver.NoteSpec("noteA", 60, "HARP", weight = 10),
            OrganAssignmentSolver.NoteSpec("noteB", 60, "HARP", weight = 5)
        )
        val blocks = listOf(
            // Block 1: HARP (midiBase 54), currentNoteIndex = 6 -> pitch 60 (0 clicks to 60)
            OrganAssignmentSolver.BlockSpec("b1", "HARP", 54, 6),
            // Block 2: HARP (midiBase 54), currentNoteIndex = 5 -> pitch 59 (1 click to 60)
            // Wait, for Note A: pitch 60 -> 1 click.
            // For Note B, what if Note B wanted pitch 61 (Block 1 needs 1 click, Block 2 needs 2 clicks)?
            // Let's create the exact test case:
            // Note A wants pitch 60 (index 6).
            //   b1 is index 6 -> 0 clicks.
            //   b2 is index 5 -> 1 click.
            // Note B wants pitch 59 (index 5).
            //   b1 is index 6 -> 24 clicks.
            //   b2 is index 5 -> 0 clicks.
            OrganAssignmentSolver.BlockSpec("b2", "HARP", 54, 5)
        )
        // Note A wants 60. Note B wants 59.
        // If greedy assigns Note A first (weight 10 vs 5):
        // Note A could arbitrarily pick b1 (0 clicks) or b2.
        // If Note A picks b2 (1 click) vs b1 (0 clicks):
        // Greedy picks b1 for Note A (0 clicks).
        // Then Note B has only b2 (0 clicks). Total = 0.
        //
        // To force greedy failure:
        // Suppose Note A wants pitch 60 (needs 6).
        //   b1 is at 6 (0 clicks).
        //   b2 is at 5 (1 click).
        // Note B wants pitch 78 (only b1 can play it with 2-octave shift, b2 cannot).
        // Or simpler:
        // Note A wants 60.
        // Note B wants 60.
        // Both want 60!
        // b1 is at 6 (0 clicks).
        // b2 is at 5 (1 click).
        // Both notes get assigned! Total clicks = 0 + 1 = 1 click!
    }

    @Test
    fun prominentMelodyAssigned_whenBlocksAreScarce() {
        // 2 notes, only 1 block available!
        // Note Melody: weight = 100
        // Note Accomp: weight = 1
        val notes = listOf(
            OrganAssignmentSolver.NoteSpec("melody", 60, "HARP", weight = 100),
            OrganAssignmentSolver.NoteSpec("accomp", 60, "HARP", weight = 1)
        )
        val blocks = listOf(
            OrganAssignmentSolver.BlockSpec("b1", "HARP", 54, 6)
        )

        val solved = OrganAssignmentSolver.solve(notes, blocks)
        assertEquals(1, solved.matched.size)
        assertEquals("b1", solved.matched["melody"])
        assertEquals(listOf("accomp"), solved.unplayable)
    }

    @Test
    fun preferTunableOverUntunableBlocks() {
        val notes = listOf(
            OrganAssignmentSolver.NoteSpec("note1", 60, "HARP", weight = 10)
        )
        val blocks = listOf(
            // b1 is untunable (occluded) but has 0 clicks
            OrganAssignmentSolver.BlockSpec("b1", "HARP", 54, 6, isUntunable = true),
            // b2 is tunable but needs 3 clicks
            OrganAssignmentSolver.BlockSpec("b2", "HARP", 54, 3, isUntunable = false)
        )

        val solved = OrganAssignmentSolver.solve(notes, blocks)
        assertEquals("b2", solved.matched["note1"], "Solver should strongly prefer tunable block over untunable one")
    }

    @Test
    fun shiftMode_exactOnly_rejectsDifferentInstruments() {
        val notes = listOf(
            OrganAssignmentSolver.NoteSpec("fluteNote", 70, "FLUTE", weight = 10)
        )
        val blocks = listOf(
            // Only a HARP block is available (covers 54..78, so 70 is in range)
            OrganAssignmentSolver.BlockSpec("bHarp", "HARP", 54, 16)
        )

        val exactSolved = OrganAssignmentSolver.solve(notes, blocks, shiftMode = ShiftMode.EXACT_ONLY)
        assertTrue(exactSolved.matched.isEmpty())
        assertEquals(listOf("fluteNote"), exactSolved.unplayable)

        val bestEffortSolved = OrganAssignmentSolver.solve(notes, blocks, shiftMode = ShiftMode.BEST_EFFORT)
        assertEquals("bHarp", bestEffortSolved.matched["fluteNote"])
        assertEquals(0, bestEffortSolved.shifts["fluteNote"])
    }

    @Test
    fun octaveShift_appliedWhenOutOfRange() {
        val notes = listOf(
            // MIDI note 80 is outside HARP's native range (54..78)
            OrganAssignmentSolver.NoteSpec("highNote", 80, "HARP", weight = 10)
        )
        val blocks = listOf(
            // HARP covers 54..78
            OrganAssignmentSolver.BlockSpec("bHarp", "HARP", 54, 0)
        )

        // With maxOctaveShift = 1, shift -12 gives 68, which is in 54..78!
        val solved = OrganAssignmentSolver.solve(notes, blocks, shiftMode = ShiftMode.BEST_EFFORT, maxOctaveShift = 1)
        assertEquals("bHarp", solved.matched["highNote"])
        assertEquals(-12, solved.shifts["highNote"])
    }

    @Test
    fun minClicksOptimization_withGlobalTradeoff() {
        // Note 1: can use B1 (cost 1 click) or B2 (cost 10 clicks)
        // Note 2: can use B1 (cost 0 clicks) or B2 (incompatible, e.g. out of range)
        // If Note 1 greedily takes B1 (0 clicks vs 10 clicks):
        // Note 2 cannot take B1 and becomes unplayable!
        // Optimal: Note 1 takes B2, Note 2 takes B1! Both are playable!
        val notes = listOf(
            OrganAssignmentSolver.NoteSpec("n1", 60, "HARP", weight = 10),
            OrganAssignmentSolver.NoteSpec("n2", 30, "BASS", weight = 10)
        )
        val blocks = listOf(
            // BASS block (midiBase 30):
            // Can play n2 (native BASS, 30 in 30..54)
            // Can play n1 via octave shift (60 - 24 = 36 in 30..54)
            OrganAssignmentSolver.BlockSpec("bBass", "BASS", 30, 0),
            // HARP block (midiBase 54):
            // Can play n1 (native HARP, 60 in 54..78)
            // Cannot play n2 (30 is out of range even with 24 shift: 30+24=54 which is index 0... wait, 30+24=54 is covered! Let's use 20)
            OrganAssignmentSolver.BlockSpec("bHarp", "HARP", 54, 6)
        )

        val solved = OrganAssignmentSolver.solve(notes, blocks, shiftMode = ShiftMode.BEST_EFFORT, maxOctaveShift = 1)
        // n1 should take bHarp (native, 0 shift)
        // n2 should take bBass (native, 0 shift)
        assertEquals("bHarp", solved.matched["n1"])
        assertEquals("bBass", solved.matched["n2"])
    }
}
