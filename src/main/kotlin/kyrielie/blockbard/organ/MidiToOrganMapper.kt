package kyrielie.blockbard.organ

import kyrielie.blockbard.util.coversNatively
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.midiNoteToName
import net.minecraft.core.BlockPos
import org.slf4j.LoggerFactory

data class OrganAssignment(
    /** Maps each distinct MIDI note used in the song to a noteblock position. */
    val assignment: Map<Int, BlockPos>,
    /** MIDI notes that could not be assigned to any block. */
    val unplayable: List<Int>,
    /** Shifts applied: midiNote → semitone shift used. */
    val shifts: Map<Int, Int>
)

object MidiToOrganMapper {

    private val logger = LoggerFactory.getLogger("BlockBard/MidiToOrganMapper")

    fun buildAssignment(
        midiNoteUsageCounts: Map<Int, Int>,
        reachableBlocks: List<NoteBlockEntry>
    ): OrganAssignment {
        val assignment = mutableMapOf<Int, BlockPos>()
        val unplayable = mutableListOf<Int>()
        val shifts = mutableMapOf<Int, Int>()
        val usedPositions = mutableSetOf<BlockPos>()

        val sortedNotes = midiNoteUsageCounts.entries.sortedByDescending { it.value }.map { it.key }
        logger.info("buildAssignment: ${sortedNotes.size} distinct notes, ${reachableBlocks.size} reachable blocks")

        for (midiNote in sortedNotes) {
            val available = reachableBlocks.filter { it.pos !in usedPositions }

            val nativeCandidate = available
                .filter { it.instrument.coversNatively(midiNote) }
                .minByOrNull { it.distanceFromPlayer }

            if (nativeCandidate != null) {
                assignment[midiNote] = nativeCandidate.pos
                usedPositions.add(nativeCandidate.pos)
                shifts[midiNote] = 0
                logger.debug("  ${midiNoteToName(midiNote)} → ${nativeCandidate.pos} (${nativeCandidate.instrument.name}, native)")
                continue
            }

            val shiftResult = InstrumentShifter.findBest(midiNote, available)
            if (shiftResult != null) {
                assignment[midiNote] = shiftResult.entry.pos
                usedPositions.add(shiftResult.entry.pos)
                shifts[midiNote] = shiftResult.semitoneShift
                logger.debug("  ${midiNoteToName(midiNote)} → ${shiftResult.entry.pos} (${shiftResult.entry.instrument.name}, shift=${shiftResult.semitoneShift})")
                continue
            }

            unplayable.add(midiNote)
            logger.warn("  ${midiNoteToName(midiNote)} UNPLAYABLE — no suitable block found")
        }

        logger.info("buildAssignment: ${assignment.size} assigned, ${unplayable.size} unplayable")
        return OrganAssignment(assignment, unplayable, shifts)
    }

    /**
     * Computes the target note index for each assigned block.
     * Returns null for any assignment where the effective MIDI note falls outside
     * the instrument's range (0–24) — these are silently dropped with a warning.
     */
    fun computeTuneTargets(
        assignment: OrganAssignment,
        reachableBlocks: List<NoteBlockEntry>
    ): List<TuneTarget> {
        val blockByPos = reachableBlocks.associateBy { it.pos }
        val targets = mutableListOf<TuneTarget>()

        for ((midiNote, pos) in assignment.assignment) {
            val entry = blockByPos[pos] ?: run {
                logger.warn("computeTuneTargets: no entry for pos $pos (midiNote=${midiNoteToName(midiNote)}) — skipping")
                continue
            }
            val shift = assignment.shifts[midiNote] ?: 0
            val effectiveMidi = midiNote + shift
            val rawIndex = effectiveMidi - entry.instrument.midiBase

            // Validate range — do NOT silently clamp
            if (rawIndex !in 0..24) {
                logger.warn("computeTuneTargets: MIDI ${midiNoteToName(effectiveMidi)} (raw=$effectiveMidi) is out of range for ${entry.instrument.name} (midiBase=${entry.instrument.midiBase}) — dropping")
                continue
            }

            val target = TuneTarget(pos, entry.noteIndex, rawIndex, entry.instrument)
            logger.debug("  TuneTarget ${entry.pos} ${entry.instrument.name} ${entry.noteIndex}→${rawIndex} (${midiNoteToName(effectiveMidi)}) — ${target.estimatedClicks} clicks")
            targets.add(target)
        }

        val totalClicks = targets.sumOf { it.estimatedClicks }
        logger.info("computeTuneTargets: ${targets.size} targets, $totalClicks total clicks")
        return targets
    }
}
