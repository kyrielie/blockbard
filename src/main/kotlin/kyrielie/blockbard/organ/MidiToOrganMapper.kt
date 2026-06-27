package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.coversNatively
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.midiNoteToName
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

/**
 * A distinct note to be assigned a noteblock: its pitch plus the instrument it was
 * authored for, if known. [instrument] is null for sources with no instrument
 * information (e.g. a bare MIDI note list with no channel/program resolved) — such
 * notes are assigned a block at the right pitch regardless of instrument, same as
 * the old pitch-only behavior.
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
    /** Shifts applied: note → semitone shift used. */
    val shifts: Map<NotePitch, Int>
)

object MidiToOrganMapper {

    private val logger = LoggerFactory.getLogger("BlockBard/MidiToOrganMapper")

    /**
     * @param noteUsageCounts how many times each (midiNote, instrument) pair occurs in
     * the song — used only to prioritize assignment order (most-used notes get first
     * pick of blocks). Pass instrument=null for sources without instrument information.
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

        for (note in sortedNotes) {
            val midiNote = note.midiNote
            val available = reachableBlocks.filter { it.pos !in usedPositions }
            // If the note specifies an instrument, only consider blocks of that
            // instrument as "native" candidates — otherwise the instrument the song
            // authored this note for would be ignored. If no instrument is specified
            // (note.instrument == null), any instrument covering the pitch qualifies,
            // same as the old pitch-only behavior.
            val instrumentFiltered = if (note.instrument != null) {
                available.filter { it.instrument == note.instrument }
            } else {
                available
            }

            // Among blocks whose instrument natively covers this note, prefer the one
            // needing the fewest tuning clicks (DiscJockey's bestBlockTuningSteps
            // pattern), tie-broken by distance. Picking by distance alone (the old
            // behavior) ignores how close each candidate's current note already is to
            // the target, so re-running buildAssignment (new song loaded, or a rescan
            // reorders candidates) can reassign an already-correctly-tuned block to a
            // different note than before, forcing avoidable re-tuning clicks even though
            // no physical noteblock changed.
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
                logger.debug("  ${note.displayName()} → ${nativeCandidate.pos} (${nativeCandidate.instrument.name}, native)")
                continue
            }

            // Octave-shift fallback. If the note specified an instrument and no native
            // block of that instrument was found, shifting only makes sense within that
            // same instrument — switching instruments here would defeat the point of
            // having specified one. If no instrument was specified, search all instruments.
            //
            // Note: when note.instrument is non-null, instrumentFiltered already only
            // contains that instrument's blocks, so InstrumentShifter's internal
            // INSTRUMENT_SHIFT step is a redundant no-op here (it would just re-run the
            // same native-coverage check we already did above on the same restricted
            // set) — only its OCTAVE_SHIFT step can find something new in that case.
            // This still goes through findBest() rather than calling findOctaveShift
            // directly so the user's configured ShiftMode (e.g. EXACT_ONLY) is respected.
            val shiftResult = InstrumentShifter.findBest(midiNote, instrumentFiltered)
            if (shiftResult != null) {
                assignment[note] = shiftResult.entry.pos
                usedPositions.add(shiftResult.entry.pos)
                shifts[note] = shiftResult.semitoneShift
                logger.debug("  ${note.displayName()} → ${shiftResult.entry.pos} (${shiftResult.entry.instrument.name}, shift=${shiftResult.semitoneShift})")
                continue
            }

            unplayable.add(note)
            logger.warn("  ${note.displayName()} UNPLAYABLE — no suitable block found")
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

        for ((note, pos) in assignment.assignment) {
            val entry = blockByPos[pos] ?: run {
                logger.warn("computeTuneTargets: no entry for pos $pos (note=${midiNoteToName(note.midiNote)}) — skipping")
                continue
            }
            val shift = assignment.shifts[note] ?: 0
            val effectiveMidi = note.midiNote + shift
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
