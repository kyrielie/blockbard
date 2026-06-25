package kyrielie.blockbard.util

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

/** Maps each instrument to its MIDI base note (the MIDI note produced at noteIndex=0). */
val NoteBlockInstrument.midiBase: Int
    get() = when (this) {
        // F#1 — −2 octaves
        NoteBlockInstrument.BASS,
        NoteBlockInstrument.DIDGERIDOO -> 30

        // F#2 — −1 octave
        // Guitar, Weathered/Oxidized Trumpet share the 42 base.
        // NOTE: Verify TRUMPET_WEATHERED / TRUMPET_OXIDIZED enum names against
        //       `./gradlew genSources` for Minecraft 26.2.
        NoteBlockInstrument.GUITAR -> 42

        // F#3 — standard (0 offset)
        NoteBlockInstrument.BASEDRUM,
        NoteBlockInstrument.SNARE,
        NoteBlockInstrument.HAT,
        NoteBlockInstrument.BANJO,
        NoteBlockInstrument.COW_BELL,
        NoteBlockInstrument.BIT,
        NoteBlockInstrument.IRON_XYLOPHONE,
        NoteBlockInstrument.PLING,
        NoteBlockInstrument.HARP -> 54

        // F#4 — +1 octave
        NoteBlockInstrument.FLUTE -> 66

        // F#5 — +2 octaves
        NoteBlockInstrument.BELL,
        NoteBlockInstrument.CHIME,
        NoteBlockInstrument.XYLOPHONE -> 78

        // Default: standard range for any new instruments (trumpets etc. verified at genSources time)
        else -> 54
    }

/** Returns whether a MIDI note falls within this instrument's native range (0–24 semitones from midiBase). */
fun NoteBlockInstrument.coversNatively(midiNote: Int): Boolean {
    val min = midiBase
    val max = midiBase + 24
    return midiNote in min..max
}

/** Converts a noteblock note index (0–24) to a MIDI note number for the given instrument. */
fun noteIndexToMidi(instrument: NoteBlockInstrument, noteIndex: Int): Int =
    instrument.midiBase + noteIndex

/**
 * Given a target MIDI note and an instrument, returns the noteblock note index (0–24)
 * needed to produce that note, or null if out of this instrument's range.
 */
fun midiToNoteIndex(midiNote: Int, instrument: NoteBlockInstrument): Int? {
    val index = midiNote - instrument.midiBase
    return if (index in 0..24) index else null
}

// Noteblock note index 0–24 maps to these chromatic names (starting at F#)
private val NOTE_NAMES = arrayOf(
    "F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#", "E", "F",
    "F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#", "E", "F", "F#"
)

/** Returns the chromatic note name for a noteblock note index (0–24). */
fun noteIndexToName(index: Int): String = NOTE_NAMES.getOrElse(index) { "?" }

/** Returns the full note name with octave for a note index + instrument, e.g. "F#3". */
fun noteIndexToFullName(index: Int, instrument: NoteBlockInstrument): String {
    val midiNote = instrument.midiBase + index
    return midiNoteToName(midiNote)
}

/** Returns the note name + octave for a raw MIDI note number, e.g. MIDI 60 → "C4". */
fun midiNoteToName(midiNote: Int): String {
    val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val octave = (midiNote / 12) - 1
    val name = names[midiNote % 12]
    return "$name$octave"
}

/**
 * Number of clicks needed to advance a noteblock from [current] to [target].
 * The cycle only goes forward: 0 → 24 → 0. Result is always in [0, 24].
 */
fun clicksNeeded(current: Int, target: Int): Int =
    ((target - current) % 25 + 25) % 25
