package kyrielie.blockbard.organ

import kyrielie.blockbard.util.coversNatively
import kyrielie.blockbard.util.midiBase
import net.minecraft.core.BlockPos

data class OrganAssignment(
    /** Maps each distinct MIDI note used in the song to a noteblock position. */
    val assignment: Map<Int, BlockPos>,
    /** MIDI notes that could not be assigned to any block. */
    val unplayable: List<Int>,
    /** Shifts applied: midiNote → semitone shift used. */
    val shifts: Map<Int, Int>
)

object MidiToOrganMapper {

    /**
     * Assigns MIDI notes to physical noteblocks.
     * [midiNoteUsageCounts] maps midiNote → how often it appears in the song (used to prioritize frequent notes).
     * [reachableBlocks] is the list of PLAYABLE blocks in the current OrganMap.
     */
    fun buildAssignment(
        midiNoteUsageCounts: Map<Int, Int>,
        reachableBlocks: List<NoteBlockEntry>
    ): OrganAssignment {
        val assignment = mutableMapOf<Int, BlockPos>()
        val unplayable = mutableListOf<Int>()
        val shifts = mutableMapOf<Int, Int>()
        val usedPositions = mutableSetOf<BlockPos>()

        // Process most-used notes first so they get best available blocks
        val sortedNotes = midiNoteUsageCounts.entries.sortedByDescending { it.value }.map { it.key }

        for (midiNote in sortedNotes) {
            val available = reachableBlocks.filter { it.pos !in usedPositions }

            // 1. Prefer a block whose instrument natively covers this note (no tuning offset required)
            val nativeCandidate = available
                .filter { it.instrument.coversNatively(midiNote) }
                .minByOrNull { it.distanceFromPlayer }

            if (nativeCandidate != null) {
                assignment[midiNote] = nativeCandidate.pos
                usedPositions.add(nativeCandidate.pos)
                shifts[midiNote] = 0
                continue
            }

            // 2. Fall back to InstrumentShifter
            val shiftResult = InstrumentShifter.findBest(midiNote, available)
            if (shiftResult != null) {
                assignment[midiNote] = shiftResult.entry.pos
                usedPositions.add(shiftResult.entry.pos)
                shifts[midiNote] = shiftResult.semitoneShift
                continue
            }

            unplayable.add(midiNote)
        }

        return OrganAssignment(assignment, unplayable, shifts)
    }

    /**
     * Computes the target note index for each assigned block.
     * Returns Map<BlockPos, targetNoteIndex> to feed into NoteBlockTuner.
     */
    fun computeTuneTargets(
        assignment: OrganAssignment,
        reachableBlocks: List<NoteBlockEntry>
    ): List<TuneTarget> {
        val blockByPos = reachableBlocks.associateBy { it.pos }
        return assignment.assignment.mapNotNull { (midiNote, pos) ->
            val entry = blockByPos[pos] ?: return@mapNotNull null
            val shift = assignment.shifts[midiNote] ?: 0
            val effectiveMidi = midiNote + shift
            val targetNoteIndex = (effectiveMidi - entry.instrument.midiBase).coerceIn(0, 24)
            TuneTarget(pos, entry.noteIndex, targetNoteIndex, entry.instrument)
        }
    }
}
