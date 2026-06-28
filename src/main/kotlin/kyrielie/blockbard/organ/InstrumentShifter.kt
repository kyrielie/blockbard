package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.coversNatively
import kyrielie.blockbard.util.midiBase

enum class ShiftMode {
    EXACT_ONLY,       // Skip the note if no exact match
    INSTRUMENT_SHIFT, // Use a different instrument that natively covers this note
    OCTAVE_SHIFT,     // Transpose by ±1 or ±2 octaves until a match is found
    BEST_EFFORT       // Try INSTRUMENT_SHIFT first, then OCTAVE_SHIFT as fallback
}

data class ShiftResult(
    val entry: NoteBlockEntry,
    val semitoneShift: Int  // 0 if exact, ±12/±24 if octave shifted
)

object InstrumentShifter {

    var mode: ShiftMode = ShiftMode.BEST_EFFORT
    var maxOctaveShift: Int = 1  // max octaves to transpose (1 or 2)

    /**
     * Finds the best available noteblock entry for a given MIDI note,
     * considering the configured ShiftMode.
     *
     * [candidates] is a list of all PLAYABLE entries to search in
     * (allows the caller to exclude already-assigned blocks).
     */
    fun findBest(midiNote: Int, candidates: List<NoteBlockEntry>): ShiftResult? {
        return when (mode) {
            ShiftMode.EXACT_ONLY -> findExact(midiNote, candidates)?.let { ShiftResult(it, 0) }
            ShiftMode.INSTRUMENT_SHIFT -> findInstrumentShift(midiNote, candidates)
            ShiftMode.OCTAVE_SHIFT -> findOctaveShift(midiNote, candidates)
            ShiftMode.BEST_EFFORT -> findInstrumentShift(midiNote, candidates)
                ?: findOctaveShift(midiNote, candidates)
        }
    }

    /** Exact MIDI note match across any instrument. */
    fun findExact(midiNote: Int, candidates: List<NoteBlockEntry>): NoteBlockEntry? =
        candidates
            .filter { it.midiNote == midiNote }
            .minByOrNull { it.distanceFromPlayer }

    /**
     * Instrument shift: find any block whose instrument natively covers this MIDI note,
     * regardless of what the block's current tuning is (it will be re-tuned).
     * This is distinct from findExact — it checks the instrument's *range*, not the current note.
     *
     * Among candidates, prefers the one needing the fewest tuning clicks to reach the
     * target note (DiscJockey's SongPlayer.onStartTick does the same — see
     * bestBlockTuningSteps), tie-broken by distance. Picking by distance alone (the old
     * behavior) ignores how close each candidate's current note already is to the
     * target, so re-running assignment (e.g. loading a different song, or after a
     * rescan reorders candidates by distance) can reassign an already-correctly-tuned
     * block to a different note than before, forcing real, avoidable re-tuning clicks
     * even though nothing about the physical noteblocks changed.
     */
    private fun findInstrumentShift(midiNote: Int, candidates: List<NoteBlockEntry>): ShiftResult? =
        candidates
            .filter { it.instrument.coversNatively(midiNote) }
            .minWithOrNull(
                compareBy(
                    { clicksNeeded(it.noteIndex, midiNote - it.instrument.midiBase) },
                    { it.distanceFromPlayer }
                )
            )
            ?.let { ShiftResult(it, 0) }

    /** Octave shift: try ±12 then ±24 semitones until a match is found. */
    private fun findOctaveShift(midiNote: Int, candidates: List<NoteBlockEntry>): ShiftResult? {
        val shifts = buildList {
            add(0)
            if (maxOctaveShift >= 1) { add(-12); add(12) }
            if (maxOctaveShift >= 2) { add(-24); add(24) }
        }
        for (shift in shifts) {
            val shifted = midiNote + shift
            val entry = candidates
                .filter { it.midiNote == shifted }
                .minByOrNull { it.distanceFromPlayer }
            if (entry != null) return ShiftResult(entry, shift)
        }
        return null
    }
}