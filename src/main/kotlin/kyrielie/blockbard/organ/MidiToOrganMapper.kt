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
     * the song -- used to weight assignment priority (prominent melody lines receive
     * priority physical note blocks over infrequent background notes).
     */
    fun buildAssignment(
        noteUsageCounts: Map<NotePitch, Int>,
        reachableBlocks: List<NoteBlockEntry>
    ): OrganAssignment {
        val sortedNotes = noteUsageCounts.entries.sortedByDescending { it.value }.map { it.key }
        logger.info("buildAssignment: ${sortedNotes.size} distinct notes, ${reachableBlocks.size} reachable blocks")

        if (noteUsageCounts.isEmpty()) {
            return OrganAssignment(emptyMap(), emptyList(), emptyMap())
        }
        if (reachableBlocks.isEmpty()) {
            return OrganAssignment(emptyMap(), sortedNotes, emptyMap())
        }

        val noteSpecs = sortedNotes.map { pitch ->
            OrganAssignmentSolver.NoteSpec(
                key = pitch,
                midiNote = pitch.midiNote,
                instrumentName = pitch.instrument?.name,
                weight = noteUsageCounts[pitch] ?: 1
            )
        }

        val blockSpecs = reachableBlocks.map { entry ->
            OrganAssignmentSolver.BlockSpec(
                id = entry.pos,
                instrumentName = entry.instrument.name,
                midiBase = entry.instrument.midiBase,
                currentNoteIndex = entry.noteIndex,
                distanceFromPlayer = entry.distanceFromPlayer,
                isUntunable = NoteBlockRegistry.isUntunable(entry.pos)
            )
        }

        val solved = OrganAssignmentSolver.solve(
            notes = noteSpecs,
            blocks = blockSpecs,
            shiftMode = InstrumentShifter.mode,
            maxOctaveShift = InstrumentShifter.maxOctaveShift
        )

        val assignment = solved.matched.toMutableMap()
        val unplayable = solved.unplayable.toMutableList()
        val shifts = solved.shifts.toMutableMap()
        val usedPositions = assignment.values.toMutableSet()

        for ((note, pos) in assignment) {
            val shift = shifts[note] ?: 0
            val entry = reachableBlocks.firstOrNull { it.pos == pos }
            val clicks = if (entry != null && entry.instrument.coversNatively(note.midiNote + shift)) {
                clicksNeeded(entry.noteIndex, (note.midiNote + shift) - entry.instrument.midiBase)
            } else 0
            logger.debug("  ${note.displayName()} -> $pos (${entry?.instrument?.name ?: "?"}, shift=$shift, clicks=$clicks)")
        }
        for (note in unplayable) {
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
                val shift = shifts[note] ?: 0
                val effectiveMidi = note.midiNote + shift
                val noteInst = note.instrument
                (noteInst == null || unused.instrument == noteInst) &&
                assignment.containsKey(note) &&
                unused.instrument.coversNatively(effectiveMidi) &&
                unused.noteIndex == (effectiveMidi - unused.instrument.midiBase)
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
