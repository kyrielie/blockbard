package kyrielie.blockbard.midi

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

/**
 * Maps General MIDI program numbers (0–127) and channel 10 percussion
 * to the NoteBlockInstrument(s) used by the minecraft3.sf2 soundfont.
 *
 * Multi-instrument entries reflect layered zones in the SF2 — the soundfont
 * splits those programs across pitch ranges, each zone pointing to a different
 * MC sample. The list is ordered by how central that instrument is to the
 * timbre: index 0 is the "primary" instrument used when only one is available.
 *
 * Source: SF2 instrument→sample chain parsed from minecraft3.sf2.
 * Percussion (MIDI channel 10, program ignored) maps via [percussionInstrument].
 */
object MidiInstrumentMap {

    /**
     * Returns the ordered list of NoteBlockInstruments that the SF2 uses for
     * [program] (0-indexed General MIDI program number, bank 0).
     * The first element is the best single-instrument substitute.
     */
    fun forProgram(program: Int): List<NoteBlockInstrument> = GM_MAP.getOrElse(program) {
        listOf(NoteBlockInstrument.HARP) // safe fallback for any unmapped program
    }

    /**
     * Single best-fit instrument for a GM program — used when the organ does not
     * have all layers available and needs to pick one.
     */
    fun primaryFor(program: Int): NoteBlockInstrument = forProgram(program).first()

    /**
     * Channel 10 percussion is a bank-128 preset in the SF2 with 32 zones mapped
     * across the standard GM drum map. Returns the most appropriate pitched
     * MC instrument for [midiNote] on the drum channel.
     *
     * Percussion instruments that are not pitched (snare, hat, basedrum) are
     * returned here anyway — the caller can choose to skip non-melodic notes.
     */
    fun percussionInstrument(midiNote: Int): NoteBlockInstrument = when (midiNote) {
        // Kick / Bass Drum
        35, 36 -> NoteBlockInstrument.BASEDRUM
        // Snare family
        38, 40 -> NoteBlockInstrument.SNARE
        // Hi-hat family (open and closed)
        42, 44, 46 -> NoteBlockInstrument.HAT
        // Toms — map to snare (closest unpitched equivalent)
        41, 43, 45, 47, 48, 50 -> NoteBlockInstrument.SNARE
        // Cowbell
        56 -> NoteBlockInstrument.COW_BELL
        // Xylophone / woodblock family
        76, 77 -> NoteBlockInstrument.XYLOPHONE
        // Iron xylophone / agogo / anvil
        67, 68, 113 -> NoteBlockInstrument.IRON_XYLOPHONE
        // Bell / triangle / chime
        80, 81 -> NoteBlockInstrument.BELL
        // Everything else: basedrum
        else -> NoteBlockInstrument.BASEDRUM
    }

    // ── General MIDI bank 0 map ────────────────────────────────────────────────
    // Lists are ordered: [primary, secondary, ...] matching SF2 zone layout.
    // Programs with a single-sample SF2 preset have a one-element list.
    private val GM_MAP: Map<Int, List<NoteBlockInstrument>> = mapOf(
        // ── Piano ──────────────────────────────────────────────────────────────
        // SF2 layers: harp (mid), bell (high), guitar (low-mid), bass (low)
        0  to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Acoustic Grand Piano
        1  to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Bright Acoustic Piano
        2  to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.BIT,
                     NoteBlockInstrument.CHIME, NoteBlockInstrument.BASS),    // Electric Grand Piano
        3  to listOf(NoteBlockInstrument.BANJO, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.GUITAR), // Honky-tonk Piano
        4  to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.BIT,
                     NoteBlockInstrument.CHIME, NoteBlockInstrument.BASS),    // Electric Piano 1
        5  to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.BIT,
                     NoteBlockInstrument.CHIME, NoteBlockInstrument.BASS),    // Electric Piano 2
        6  to listOf(NoteBlockInstrument.BANJO, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.GUITAR), // Harpsichord
        7  to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Clavinet

        // ── Chromatic Percussion ───────────────────────────────────────────────
        8  to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Celesta
        9  to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Glockenspiel
        10 to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Music Box
        11 to listOf(NoteBlockInstrument.IRON_XYLOPHONE, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.FLUTE, NoteBlockInstrument.BASS),    // Vibraphone
        12 to listOf(NoteBlockInstrument.IRON_XYLOPHONE, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.FLUTE, NoteBlockInstrument.BASS),    // Marimba
        13 to listOf(NoteBlockInstrument.IRON_XYLOPHONE, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.FLUTE, NoteBlockInstrument.BASS),    // Xylophone
        14 to listOf(NoteBlockInstrument.BELL),                               // Tubular Bells
        15 to listOf(NoteBlockInstrument.BELL),                               // Dulcimer

        // ── Organ ──────────────────────────────────────────────────────────────
        16 to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.FLUTE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.CHIME), // Drawbar Organ
        17 to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.FLUTE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.CHIME), // Percussive Organ
        18 to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.FLUTE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.CHIME), // Rock Organ
        19 to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.FLUTE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.CHIME), // Church Organ
        20 to listOf(NoteBlockInstrument.PLING, NoteBlockInstrument.FLUTE,
                     NoteBlockInstrument.CHIME, NoteBlockInstrument.BASS),    // Reed Organ
        21 to listOf(NoteBlockInstrument.BANJO, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.GUITAR), // Accordion
        22 to listOf(NoteBlockInstrument.BANJO, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.GUITAR), // Harmonica
        23 to listOf(NoteBlockInstrument.BANJO, NoteBlockInstrument.XYLOPHONE,
                     NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.GUITAR), // Tango Accordion

        // ── Guitar ─────────────────────────────────────────────────────────────
        24 to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Nylon Guitar
        25 to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Steel Guitar
        26 to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Jazz Guitar
        27 to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Clean Guitar
        28 to listOf(NoteBlockInstrument.HARP, NoteBlockInstrument.BELL,
                     NoteBlockInstrument.GUITAR, NoteBlockInstrument.BASS),   // Muted Guitar
        29 to listOf(NoteBlockInstrument.GUITAR),                             // Overdriven Guitar
        30 to listOf(NoteBlockInstrument.GUITAR),                             // Distortion Guitar
        31 to listOf(NoteBlockInstrument.GUITAR),                             // Guitar Harmonics

        // ── Bass ───────────────────────────────────────────────────────────────
        32 to listOf(NoteBlockInstrument.BASS),                               // Acoustic Bass
        33 to listOf(NoteBlockInstrument.BASS),                               // Finger Bass
        34 to listOf(NoteBlockInstrument.BASS),                               // Pick Bass
        35 to listOf(NoteBlockInstrument.BASS),                               // Fretless Bass
        36 to listOf(NoteBlockInstrument.BASS),                               // Slap Bass 1
        37 to listOf(NoteBlockInstrument.BASS),                               // Slap Bass 2
        38 to listOf(NoteBlockInstrument.BASS),                               // Synth Bass 1
        39 to listOf(NoteBlockInstrument.BASS),                               // Synth Bass 2

        // ── Strings ────────────────────────────────────────────────────────────
        40 to listOf(NoteBlockInstrument.FLUTE),                              // Violin
        41 to listOf(NoteBlockInstrument.FLUTE),                              // Viola
        42 to listOf(NoteBlockInstrument.FLUTE),                              // Cello
        43 to listOf(NoteBlockInstrument.FLUTE),                              // Contrabass
        44 to listOf(NoteBlockInstrument.FLUTE),                              // Tremolo Strings
        45 to listOf(NoteBlockInstrument.FLUTE),                              // Pizzicato Strings
        46 to listOf(NoteBlockInstrument.HARP),                               // Orchestral Harp
        47 to listOf(NoteBlockInstrument.SNARE),                              // Timpani

        // ── Ensemble ───────────────────────────────────────────────────────────
        48 to listOf(NoteBlockInstrument.FLUTE),                              // String Ensemble 1
        49 to listOf(NoteBlockInstrument.FLUTE),                              // String Ensemble 2
        50 to listOf(NoteBlockInstrument.FLUTE),                              // Synth Strings 1
        51 to listOf(NoteBlockInstrument.FLUTE),                              // Synth Strings 2
        52 to listOf(NoteBlockInstrument.FLUTE),                              // Choir Aahs
        53 to listOf(NoteBlockInstrument.FLUTE),                              // Voice Oohs
        54 to listOf(NoteBlockInstrument.FLUTE),                              // Synth Voice
        55 to listOf(NoteBlockInstrument.FLUTE),                              // Orchestra Hit

        // ── Brass ──────────────────────────────────────────────────────────────
        56 to listOf(NoteBlockInstrument.TRUMPET),                            // Trumpet
        57 to listOf(NoteBlockInstrument.TRUMPET_WEATHERED),                  // Trombone
        58 to listOf(NoteBlockInstrument.TRUMPET_WEATHERED),                  // Tuba
        59 to listOf(NoteBlockInstrument.TRUMPET_WEATHERED),                  // Muted Trumpet
        60 to listOf(NoteBlockInstrument.TRUMPET_WEATHERED),                  // French Horn
        61 to listOf(NoteBlockInstrument.TRUMPET),                            // Brass Section
        62 to listOf(NoteBlockInstrument.TRUMPET),                            // Synth Brass 1
        63 to listOf(NoteBlockInstrument.TRUMPET_EXPOSED),                    // Synth Brass 2

        // ── Reed ───────────────────────────────────────────────────────────────
        64 to listOf(NoteBlockInstrument.TRUMPET),                            // Soprano Sax
        65 to listOf(NoteBlockInstrument.TRUMPET),                            // Alto Sax
        66 to listOf(NoteBlockInstrument.TRUMPET),                            // Tenor Sax
        67 to listOf(NoteBlockInstrument.TRUMPET),                            // Baritone Sax
        68 to listOf(NoteBlockInstrument.DIDGERIDOO),                         // Oboe
        69 to listOf(NoteBlockInstrument.DIDGERIDOO),                         // English Horn
        70 to listOf(NoteBlockInstrument.DIDGERIDOO),                         // Bassoon
        71 to listOf(NoteBlockInstrument.DIDGERIDOO),                         // Clarinet

        // ── Pipe ───────────────────────────────────────────────────────────────
        72 to listOf(NoteBlockInstrument.FLUTE),                              // Piccolo
        73 to listOf(NoteBlockInstrument.FLUTE),                              // Flute
        74 to listOf(NoteBlockInstrument.FLUTE),                              // Recorder
        75 to listOf(NoteBlockInstrument.FLUTE),                              // Pan Flute
        76 to listOf(NoteBlockInstrument.FLUTE),                              // Blown Bottle
        77 to listOf(NoteBlockInstrument.FLUTE),                              // Shakuhachi
        78 to listOf(NoteBlockInstrument.FLUTE),                              // Whistle
        79 to listOf(NoteBlockInstrument.FLUTE),                              // Ocarina

        // ── Synth Lead ─────────────────────────────────────────────────────────
        80 to listOf(NoteBlockInstrument.BIT),                               // Square Wave
        81 to listOf(NoteBlockInstrument.BIT),                               // Sawtooth Wave
        82 to listOf(NoteBlockInstrument.FLUTE),                             // Calliope
        83 to listOf(NoteBlockInstrument.FLUTE),                             // Chiff
        84 to listOf(NoteBlockInstrument.BIT),                               // Charang
        85 to listOf(NoteBlockInstrument.FLUTE),                             // Voice
        86 to listOf(NoteBlockInstrument.BIT),                               // Fifths
        87 to listOf(NoteBlockInstrument.BIT),                               // Bass + Lead

        // ── Synth Pad ──────────────────────────────────────────────────────────
        88 to listOf(NoteBlockInstrument.FLUTE),                             // New Age Pad
        89 to listOf(NoteBlockInstrument.FLUTE),                             // Warm Pad
        90 to listOf(NoteBlockInstrument.BIT),                               // Polysynth Pad
        91 to listOf(NoteBlockInstrument.FLUTE),                             // Choir Pad
        92 to listOf(NoteBlockInstrument.FLUTE),                             // Bowed Pad
        93 to listOf(NoteBlockInstrument.FLUTE),                             // Metallic Pad
        94 to listOf(NoteBlockInstrument.FLUTE),                             // Halo Pad
        95 to listOf(NoteBlockInstrument.FLUTE),                             // Sweep Pad

        // ── Synth FX ───────────────────────────────────────────────────────────
        96  to listOf(NoteBlockInstrument.DIDGERIDOO),                       // Rain FX
        97  to listOf(NoteBlockInstrument.HARP),                             // Soundtrack FX
        98  to listOf(NoteBlockInstrument.BELL),                             // Crystal FX (orb sample)
        99  to listOf(NoteBlockInstrument.GUITAR),                           // Atmosphere FX
        100 to listOf(NoteBlockInstrument.HARP),                             // Brightness FX
        101 to listOf(NoteBlockInstrument.HARP),                             // Goblins FX
        102 to listOf(NoteBlockInstrument.HARP),                             // Echoes FX
        103 to listOf(NoteBlockInstrument.HARP),                             // Sci-fi FX

        // ── Ethnic ─────────────────────────────────────────────────────────────
        104 to listOf(NoteBlockInstrument.BANJO),                            // Sitar
        105 to listOf(NoteBlockInstrument.BANJO),                            // Banjo
        106 to listOf(NoteBlockInstrument.BANJO),                            // Shamisen
        107 to listOf(NoteBlockInstrument.BANJO),                            // Koto
        108 to listOf(NoteBlockInstrument.IRON_XYLOPHONE),                   // Kalimba
        109 to listOf(NoteBlockInstrument.FLUTE),                            // Bagpipe
        110 to listOf(NoteBlockInstrument.FLUTE),                            // Fiddle
        111 to listOf(NoteBlockInstrument.FLUTE),                            // Shanai

        // ── Percussive ─────────────────────────────────────────────────────────
        112 to listOf(NoteBlockInstrument.BELL),                             // Tinkle Bell
        113 to listOf(NoteBlockInstrument.HARP),                             // Agogo
        114 to listOf(NoteBlockInstrument.IRON_XYLOPHONE),                   // Steel Drums
        115 to listOf(NoteBlockInstrument.BASEDRUM),                         // Woodblock (wood sample)
        116 to listOf(NoteBlockInstrument.SNARE),                            // Taiko Drum
        117 to listOf(NoteBlockInstrument.SNARE),                            // Melodic Tom
        118 to listOf(NoteBlockInstrument.SNARE),                            // Synth Drum
        119 to listOf(NoteBlockInstrument.SNARE),                            // Reverse Cymbal

        // ── Sound Effects ──────────────────────────────────────────────────────
        120 to listOf(NoteBlockInstrument.HARP),                             // Guitar Fret Noise
        121 to listOf(NoteBlockInstrument.HARP),                             // Breath Noise
        122 to listOf(NoteBlockInstrument.HARP),                             // Seashore
        123 to listOf(NoteBlockInstrument.HARP),                             // Bird Tweet
        124 to listOf(NoteBlockInstrument.BELL),                             // Telephone Ring (orb)
        125 to listOf(NoteBlockInstrument.HARP),                             // Helicopter
        126 to listOf(NoteBlockInstrument.HARP),                             // Applause
        127 to listOf(NoteBlockInstrument.HARP)                              // Gunshot
    )
}
