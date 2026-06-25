package kyrielie.blockbard.organ

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

    /** Instrument shift: find any block whose instrument range natively covers this note. */
    private fun findInstrumentShift(midiNote: Int, candidates: List<NoteBlockEntry>): ShiftResult? =
        candidates
            .filter { it.midiNote == midiNote }
            .minByOrNull { it.distanceFromPlayer }
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
