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
data class NotePitch(val midiNote: Int, val instrument: NoteBlockInstrument? = null)

/** Human-readable display name, e.g. "C4" or "C4 (BELL)" if an instrument is specified. */
fun NotePitch.displayName(): String =
    midiNoteToName(midiNote) + (instrument?.let { " (${it.name})" } ?: "")

data class OrganAssignment(
    /** Maps each distinct (midiNote, instrument) pair used in the song to a noteblock position. */
    val assignment: Map<NotePitch, BlockPos>,
    /** Notes that could not be assigned to any block. */
    val unplayable: List<NotePitch>,
    /** Shifts applied: note -> semitone shift used. */
    val shifts: Map<NotePitch, Int>
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
        // Prune before assignment: if any instrument has more than 25 blocks, only keep
        // the 25 most useful (by distinct noteIndex, then closest distance). Excess blocks
        // beyond 25 can never hold a unique pitch and would silently waste assignment slots.
        val prunedBlocks = pruneOverflowBlocks(reachableBlocks)

        val assignment = mutableMapOf<NotePitch, BlockPos>()
        val unplayable = mutableListOf<NotePitch>()
        val shifts = mutableMapOf<NotePitch, Int>()
        val usedPositions = mutableSetOf<BlockPos>()

        val sortedNotes = noteUsageCounts.entries.sortedByDescending { it.value }.map { it.key }
        logger.info("buildAssignment: ${sortedNotes.size} distinct notes, ${prunedBlocks.size} reachable blocks (${reachableBlocks.size} before overflow pruning)")

        for (note in sortedNotes) {
            val midiNote = note.midiNote
            val available = prunedBlocks.filter { it.pos !in usedPositions }
            val instrumentFiltered = if (note.instrument != null) {
                available.filter { it.instrument == note.instrument }
            } else {
                available
            }

            // Among blocks whose instrument natively covers this note, prefer the one
            // needing the fewest tuning clicks, tie-broken by distance.
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
                logger.debug("  ${note.displayName()} -> ${nativeCandidate.pos} (${nativeCandidate.instrument.name}, native)")
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

        logger.info("buildAssignment: ${assignment.size} assigned, ${unplayable.size} unplayable")
        return OrganAssignment(assignment, unplayable, shifts)
    }

    /**
     * Removes blocks beyond 25 per instrument from the assignment pool.
     *
     * When an instrument has more than 25 blocks, not all of them can hold unique
     * pitches (noteIndex 0-24 are the only 25 available). The excess blocks would
     * compete for assignment slots but could never be tuned to a note the first 25
     * can't already cover. Keeping them in the pool allows the mapper to accidentally
     * assign them and waste a slot, leaving a genuinely useful block of a different
     * instrument unassigned.
     *
     * Pruning strategy: sort by noteIndex (spread coverage across the range), then by
     * distance (prefer closer blocks for equal noteIndex). Take distinct noteIndex values
     * first (one block per pitch), then fill remaining slots up to 25 with closest
     * duplicates in case some blocks have the same current note and can be retuned.
     */
    private fun pruneOverflowBlocks(blocks: List<NoteBlockEntry>): List<NoteBlockEntry> {
        val byInstrument = blocks.groupBy { it.instrument }
        return byInstrument.flatMap { (instrument, entries) ->
            if (entries.size <= 25) {
                entries
            } else {
                // First pass: one block per distinct noteIndex, closest wins.
                val byNoteIndex = entries
                    .sortedBy { it.distanceFromPlayer }
                    .groupBy { it.noteIndex }
                val distinctBest = byNoteIndex.values.map { it.first() } // closest per noteIndex

                val kept = if (distinctBest.size >= 25) {
                    // More than 25 distinct noteIndex values is impossible (0-24 only),
                    // but defensively take the first 25 sorted by noteIndex.
                    distinctBest.sortedBy { it.noteIndex }.take(25)
                } else {
                    // Fewer than 25 distinct noteIndex values -- fill remaining slots
                    // with closest duplicates so the mapper has 25 candidates to work with.
                    val remaining = entries
                        .filter { it !in distinctBest }
                        .sortedBy { it.distanceFromPlayer }
                    (distinctBest + remaining).take(25)
                }

                val dropped = entries.size - kept.size
                logger.warn("pruneOverflowBlocks: dropped $dropped excess ${instrument.name} blocks (had ${entries.size}, keeping ${kept.size})")
                kept
            }
        }
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

        for ((note, pos) in assignment.assignment) {
            val entry = blockByPos[pos] ?: run {
                logger.warn("computeTuneTargets: no entry for pos $pos (note=${midiNoteToName(note.midiNote)}) -- skipping")
                continue
            }

            // Unpitched instruments (SNARE, HAT, BASEDRUM) ignore noteIndex in-game.
            // Emit a TuneTarget with targetNote == snapshotNote so clicksNeeded returns 0
            // and the tuner skips this block entirely without sending any clicks.
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

        val totalClicks = targets.sumOf { it.estimatedClicks }
        logger.info("computeTuneTargets: ${targets.size} targets, $totalClicks total clicks")
        return targets
    }
}
