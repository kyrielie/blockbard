package kyrielie.blockbard.organ

/** A block chosen for a live note, and the semitone shift that was applied to reach it. */
data class LiveChoice<E>(val block: E, val shift: Int)

/**
 * Pure rules for live (MIDI keyboard) notes. Live play never retunes, so only blocks that are
 * already tuned to the needed pitch qualify. The order mirrors file playback
 * (see MidiToOrganMapper and InstrumentShifter):
 *  1. At the exact pitch, a block of the preferred instrument (the keyboard acts as GM piano,
 *     which resolves to HARP), else a block of any other pitched instrument.
 *  2. If octave shifting is allowed, repeat step 1 at -12, +12, then -24, +24 semitones, up to
 *     [maxOctaveShift] octaves.
 * Within a step the nearest block to the player wins. Unpitched instruments never qualify.
 *
 * Generic over the block type so it can be tested without Minecraft classes.
 */
object LiveNoteResolver {

    fun <E, I> resolve(
        midiNote: Int,
        preferred: I?,
        candidates: List<E>,
        allowOctaveShift: Boolean,
        maxOctaveShift: Int,
        instrumentOf: (E) -> I,
        tunedPitchOf: (E) -> Int,
        isPitched: (E) -> Boolean,
        distanceOf: (E) -> Double
    ): LiveChoice<E>? {
        val shifts = buildList {
            add(0)
            if (allowOctaveShift) {
                if (maxOctaveShift >= 1) { add(-12); add(12) }
                if (maxOctaveShift >= 2) { add(-24); add(24) }
            }
        }
        for (shift in shifts) {
            val target = midiNote + shift
            val tuned = candidates.filter { isPitched(it) && tunedPitchOf(it) == target }
            if (tuned.isEmpty()) continue
            val pick = tuned.filter { preferred != null && instrumentOf(it) == preferred }
                .minByOrNull(distanceOf)
                ?: tuned.minByOrNull(distanceOf)
            if (pick != null) return LiveChoice(pick, shift)
        }
        return null
    }
}
