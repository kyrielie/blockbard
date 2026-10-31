package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.coversNatively
import kyrielie.blockbard.util.isPitched
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.midiNoteToName
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

/**
 * A distinct note to be assigned a noteblock: its pitch plus the instrument it was
 * authored for, if known. [instrument] is null for sources with no instrument
 * information -- such notes are assigned a block at the right pitch regardless of
 * instrument.
 */
data class NotePitch(val midiNote: Int, val instrument: NoteBlockInstrument? = null) {
    val isPitched: Boolean get() = instrument?.isPitched ?: true
    val coversNatively: Boolean get() = instrument?.coversNatively(midiNote) ?: true
}

/** Human-readable display name, e.g. "C4" or "C4 (BELL)" if an instrument is specified. */
fun NotePitch.displayName(): String =
    midiNoteToName(midiNote) + (instrument?.let { " (${it.name})" } ?: "")

data class OrganAssignment(
    /** Maps each distinct (midiNote, instrument) pair used in the song to a noteblock position. */
    val assignment: Map<NotePitch, BlockPos>,
    /** Notes that could not be assigned to any block. */
    val unplayable: List<NotePitch>,
    /** Shifts applied: note -> semitone shift used. */
    val shifts: Map<NotePitch, Int>,
    /** Multi-voice pools: note -> all reachable blocks assigned to this pitch. */
    val voicePools: Map<NotePitch, List<BlockPos>> = assignment.mapValues { listOf(it.value) }
)

object MidiToOrganMapper {

    private val logger = LoggerFactory.getLogger("BlockBard/MidiToOrganMapper")

    /**
     * @param noteUsageCounts how many times each (midiNote, instrument) pair occurs in
     * the song -- used only to prioritize assignment order (most-used notes get first
     * pick of blocks).
     */
    fun buildAssignment(
        noteUsageCounts: Map<NotePitch, Int>,
        reachableBlocks: List<NoteBlockEntry>
    ): OrganAssignment {
        val assignment = mutableMapOf<NotePitch, BlockPos>()
        val unplayable = mutableListOf<NotePitch>()
        val shifts = mutableMapOf<NotePitch, Int>()
        val usedPositions = mutableSetOf<BlockPos>()

        val sortedNotes = noteUsageCounts.entries.sortedByDescending { it.value }.map { it.key }
        logger.info("buildAssignment: ${sortedNotes.size} distinct notes, ${reachableBlocks.size} reachable blocks")

        // Pass 1: Exact matches (0 clicks needed).
        // If an available reachable block is ALREADY tuned to the exact pitch needed for a note,
        // assign it immediately. This preserves the organ's existing tuning and prevents
        // unnecessary retuning across songs (minimal tuning like DiscJockey).
        val unassignedNotes = mutableListOf<NotePitch>()
        for (note in sortedNotes) {
            val midiNote = note.midiNote
            val available = reachableBlocks.filter { it.pos !in usedPositions }
            val instrumentFiltered = if (note.instrument != null) {
                available.filter { it.instrument == note.instrument }
            } else {
                available
            }

            val exactMatch = instrumentFiltered
                .filter { it.instrument.coversNatively(midiNote) && it.noteIndex == (midiNote - it.instrument.midiBase) }
                .minByOrNull { it.distanceFromPlayer }

            if (exactMatch != null) {
                assignment[note] = exactMatch.pos
                usedPositions.add(exactMatch.pos)
                shifts[note] = 0
                logger.debug("  ${note.displayName()} -> ${exactMatch.pos} (${exactMatch.instrument.name}, exact 0-click match)")
            } else {
                unassignedNotes.add(note)
            }
        }

        // Pass 2: Minimal-click assignment for notes that didn't have an exact match.
        // For each remaining note, find the available block of matching instrument that requires
        // the fewest clicks, tie-broken by distance.
        for (note in unassignedNotes) {
            val midiNote = note.midiNote
            val available = reachableBlocks.filter { it.pos !in usedPositions }
            val instrumentFiltered = if (note.instrument != null) {
                available.filter { it.instrument == note.instrument }
            } else {
                available
            }

            val nativeCandidate = instrumentFiltered
                .filter { it.instrument.coversNatively(midiNote) }
                .minWithOrNull(
                    compareBy(
                        { clicksNeeded(it.noteIndex, midiNote - it.instrument.midiBase) },
                        { it.distanceFromPlayer }
                    )
                )

            if (nativeCandidate != null) {
                assignment[note] = nativeCandidate.pos
                usedPositions.add(nativeCandidate.pos)
                shifts[note] = 0
                val clicks = clicksNeeded(nativeCandidate.noteIndex, midiNote - nativeCandidate.instrument.midiBase)
                logger.debug("  ${note.displayName()} -> ${nativeCandidate.pos} (${nativeCandidate.instrument.name}, native, clicks=$clicks)")
                continue
            }

            val shiftResult = InstrumentShifter.findBest(midiNote, instrumentFiltered)
            if (shiftResult != null) {
                assignment[note] = shiftResult.entry.pos
                usedPositions.add(shiftResult.entry.pos)
                shifts[note] = shiftResult.semitoneShift
                logger.debug("  ${note.displayName()} -> ${shiftResult.entry.pos} (${shiftResult.entry.instrument.name}, shift=${shiftResult.semitoneShift})")
                continue
            }

            unplayable.add(note)
            logger.warn("  ${note.displayName()} UNPLAYABLE -- no suitable block found")
        }

        val voicePools = mutableMapOf<NotePitch, MutableList<BlockPos>>()
        assignment.forEach { (note, pos) -> voicePools[note] = mutableListOf(pos) }

        // Polyphonic voice expansion:
        // Allocate remaining reachable blocks to duplicate voice pools ONLY IF the block
        // is ALREADY tuned to that note (0 clicks needed).
        // NEVER retune an unassigned block just to make it a duplicate voice!
        val unassignedBlocks = reachableBlocks.filter { it.pos !in usedPositions }
        for (unused in unassignedBlocks) {
            val matchingNote = sortedNotes.firstOrNull { note ->
                note.instrument == unused.instrument &&
                assignment.containsKey(note) &&
                unused.instrument.coversNatively(note.midiNote + (shifts[note] ?: 0)) &&
                unused.noteIndex == (note.midiNote + (shifts[note] ?: 0) - unused.instrument.midiBase)
            }
            if (matchingNote != null) {
                voicePools.getOrPut(matchingNote) { mutableListOf() }.add(unused.pos)
                usedPositions.add(unused.pos)
                logger.debug("  Duplicate voice (existing pitch): ${matchingNote.displayName()} pool += ${unused.pos}")
            }
        }

        logger.info("buildAssignment: ${assignment.size} assigned (${usedPositions.size} total blocks in pools), ${unplayable.size} unplayable")
        return OrganAssignment(assignment, unplayable, shifts, voicePools)
    }



    /**
     * Computes the target note index for each assigned block.
     * Returns null for any assignment where the effective MIDI note falls outside
     * the instrument's range (0-24) -- these are dropped with a warning.
     */
    fun computeTuneTargets(
        assignment: OrganAssignment,
        reachableBlocks: List<NoteBlockEntry>
    ): List<TuneTarget> {
        val blockByPos = reachableBlocks.associateBy { it.pos }
        val targets = mutableListOf<TuneTarget>()
        val seenPos = mutableSetOf<BlockPos>()

        for ((note, pool) in assignment.voicePools) {
            for (pos in pool) {
                if (!seenPos.add(pos)) continue
                val entry = blockByPos[pos] ?: run {
                    logger.warn("computeTuneTargets: no entry for pos $pos (note=${midiNoteToName(note.midiNote)}) -- skipping")
                    continue
                }

                // Unpitched instruments (SNARE, HAT, BASEDRUM) ignore noteIndex in-game.
                if (!entry.instrument.isPitched) {
                    targets.add(TuneTarget(pos, entry.noteIndex, entry.noteIndex, entry.instrument))
                    logger.debug("  TuneTarget ${entry.pos} ${entry.instrument.name} (unpitched, no tuning needed)")
                    continue
                }

                val shift = assignment.shifts[note] ?: 0
                val effectiveMidi = note.midiNote + shift
                val rawIndex = effectiveMidi - entry.instrument.midiBase

                if (rawIndex !in 0..24) {
                    logger.warn("computeTuneTargets: MIDI ${midiNoteToName(effectiveMidi)} (raw=$effectiveMidi) is out of range for ${entry.instrument.name} (midiBase=${entry.instrument.midiBase}) -- dropping")
                    continue
                }

                val target = TuneTarget(pos, entry.noteIndex, rawIndex, entry.instrument)
                logger.debug("  TuneTarget ${entry.pos} ${entry.instrument.name} ${entry.noteIndex}->${rawIndex} (${midiNoteToName(effectiveMidi)}) -- ${target.estimatedClicks} clicks")
                targets.add(target)
            }
        }

        val totalClicks = targets.sumOf { it.estimatedClicks }
        logger.info("computeTuneTargets: ${targets.size} targets across voice pools, $totalClicks total clicks")
        return targets
    }
}
