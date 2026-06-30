package kyrielie.blockbard.util

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

/**
 * Maps each NoteBlockInstrument to its MIDI base note (the MIDI note produced at noteIndex=0).
 *
 * Ranges per wiki (all instruments span exactly 2 octaves = 25 semitones, index 0–24):
 *
 *   midiBase 30  → F#1–F#3   (BASS, DIDGERIDOO)
 *   midiBase 42  → F#2–F#4   (GUITAR, TRUMPET, TRUMPET_EXPOSED, TRUMPET_OXIDIZED, TRUMPET_WEATHERED)
 *   midiBase 54  → F#3–F#5   (HARP, BASEDRUM, SNARE, HAT, BANJO, BIT, IRON_XYLOPHONE, PLING)
 *   midiBase 66  → F#4–F#6   (FLUTE, COW_BELL)
 *   midiBase 78  → F#5–F#7   (BELL, CHIME, XYLOPHONE)
 *
 * Trumpet enum names confirmed against the decompiled NoteBlockInstrument.java for this
 * MC build: TRUMPET, TRUMPET_EXPOSED, TRUMPET_OXIDIZED, TRUMPET_WEATHERED (all Type.BASE_BLOCK,
 * same as HARP/BASS/etc. — fully tunable like any other noteblock instrument). All four are
 * grouped with GUITAR in the brass/copper family range.
 *
 * COW_BELL fix: was incorrectly mapped to 54 (F#3); wiki specifies F#4–F#6 → 66.
 */
val NoteBlockInstrument.midiBase: Int
    get() = when (this) {

        // ── F#1 base (midiBase 30) ────────────────────────────────────────────
        NoteBlockInstrument.BASS,
        NoteBlockInstrument.DIDGERIDOO -> 30

        // ── F#2 base (midiBase 42) ────────────────────────────────────────────
        // Trumpet variants confirmed via decompiled source: TRUMPET, TRUMPET_EXPOSED,
        // TRUMPET_OXIDIZED, TRUMPET_WEATHERED. Grouped with GUITAR (brass/copper family).
        NoteBlockInstrument.GUITAR,
        NoteBlockInstrument.TRUMPET,
        NoteBlockInstrument.TRUMPET_EXPOSED,
        NoteBlockInstrument.TRUMPET_OXIDIZED,
        NoteBlockInstrument.TRUMPET_WEATHERED -> 42

        // ── F#3 base (midiBase 54) ────────────────────────────────────────────
        NoteBlockInstrument.BASEDRUM,
        NoteBlockInstrument.SNARE,
        NoteBlockInstrument.HAT,
        NoteBlockInstrument.BANJO,
        NoteBlockInstrument.BIT,
        NoteBlockInstrument.IRON_XYLOPHONE,
        NoteBlockInstrument.PLING,
        NoteBlockInstrument.HARP -> 54

        // ── F#4 base (midiBase 66) ────────────────────────────────────────────
        // COW_BELL: wiki says F#4–F#6; was previously (incorrectly) mapped to 54.
        NoteBlockInstrument.FLUTE,
        NoteBlockInstrument.COW_BELL -> 66

        // ── F#5 base (midiBase 78) ────────────────────────────────────────────
        NoteBlockInstrument.BELL,
        NoteBlockInstrument.CHIME,
        NoteBlockInstrument.XYLOPHONE -> 78

        // Mob-head instruments (ZOMBIE, SKELETON, CREEPER, DRAGON, WITHER_SKELETON,
        // PIGLIN) and CUSTOM_HEAD are not pitch-tunable noteblock instruments — they
        // ignore noteIndex entirely, so a midiBase is meaningless for them. Default
        // to 54 only as an inert fallback; callers should be checking isTunable()
        // before relying on midiBase for these.
        else -> 54
    }

/**
 * Whether this instrument responds to noteIndex tuning at all.
 * SNARE, HAT, and BASEDRUM play a fixed sound regardless of noteIndex — right-clicking
 * changes the block state value but has no audible effect. All other vanilla instruments
 * are fully pitched across noteIndex 0–24.
 */
val NoteBlockInstrument.isPitched: Boolean
    get() = this != NoteBlockInstrument.SNARE &&
            this != NoteBlockInstrument.HAT &&
            this != NoteBlockInstrument.BASEDRUM

/**
 * Returns whether a MIDI note falls within this instrument's native range (0–24 semitones
 * from midiBase). Always false for unpitched instruments — they cannot be tuned to any
 * specific pitch, so no MIDI note should be "covered" by them in the assignment sense.
 */
fun NoteBlockInstrument.coversNatively(midiNote: Int): Boolean {
    if (!isPitched) return false
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