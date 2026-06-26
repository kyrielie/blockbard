package kyrielie.blockbard.midi

import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.util.midiBase
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

/**
 * A remapped event ready for [kyrielie.blockbard.organ.ArpeggioScheduler].
 * [originalMidiNote] is kept for debugging; [remappedMidiNote] is what will
 * actually be looked up in [NoteBlockRegistry].
 */
data class RemappedNoteEvent(
    val tick: Long,
    val originalInstrument: NoteBlockInstrument,
    val originalMidiNote: Int,
    val remappedInstrument: NoteBlockInstrument,
    val remappedMidiNote: Int,
    /** True when the pitch was clamped to the edge of the nearest available range. */
    val wasClamped: Boolean = false
)

/**
 * Describes what the fallback mapper did so the GUI can show a meaningful warning.
 */
data class FallbackPlan(
    val events: List<RemappedNoteEvent>,
    /**
     * How many (instrument, midiNote) pairs were resolved by shifting to a
     * different instrument rather than changing the pitch.
     */
    val shiftedNotes: Int,
    /**
     * How many notes could not be covered by any available instrument even after
     * instrument shift, and were clamped to the nearest range edge instead.
     */
    val clampedNotes: Int
) {
    fun summary(): String = buildString {
        if (shiftedNotes == 0 && clampedNotes == 0) {
            appendLine("All notes covered by instrument shift without pitch change.")
        } else {
            if (shiftedNotes > 0)
                appendLine("$shiftedNotes note(s) shifted to a different instrument (pitch preserved).")
            if (clampedNotes > 0)
                appendLine("$clampedNotes note(s) pitch-clamped (no instrument covers that note).")
        }
    }
}

/**
 * Remaps [ResolvedNoteEvent]s to whatever [NoteBlockInstrument]s are actually
 * present in the organ, prioritizing pitch accuracy over timbre:
 *
 * ## Stage 1 — Instrument shift (pitch-preserving)
 * For each note, walk the [PITCH_LADDER] — an ascending list of all pitched MC
 * instruments ordered by their midiBase — and find the first instrument in the
 * ladder that:
 *   (a) is present in the organ, AND
 *   (b) covers the exact MIDI note (i.e. midiNote in [midiBase, midiBase + 24]).
 *
 * Walking upward from the note's original instrument finds the nearest higher
 * instrument first; walking downward finds the nearest lower one. The shift that
 * moves the fewest semitones in instrument-space wins.
 *
 * The ladder and midiBase values come from [kyrielie.blockbard.util.midiBase],
 * which documents the wiki-sourced per-instrument ranges for the actual
 * Minecraft `NoteBlockInstrument` enum. This is intentionally NOT modeled on
 * NoteBlockLib's `MinecraftDefinitions.instrumentShiftNote` — that method
 * operates on a different type (NoteBlockLib's own `MinecraftInstrument`),
 * assumes every instrument shares one fixed 24-semitone range, and substitutes
 * instruments via a hardcoded per-instrument table rather than checking what's
 * actually present in an organ. None of that fits this mod's model, where each
 * instrument has its own midiBase and "available" is determined dynamically
 * from [NoteBlockRegistry].
 *
 * ## Stage 2 — Octave clamp (pitch-approximate, last resort)
 * If no instrument on the ladder covers the note (the note is above all available
 * ranges or below all of them), clamp to the nearest edge of the closest available
 * instrument's range.
 *
 * ## Unpitched percussion
 * BASEDRUM, SNARE, and HAT are not on the pitch ladder. If the required instrument
 * is one of these and it is absent, the note is silently dropped — there is no
 * meaningful pitch-preserving substitute for an unpitched instrument.
 */
object FallbackMapper {

    private val logger = LoggerFactory.getLogger("BlockBard/FallbackMapper")

    /**
     * All pitched instruments in ascending midiBase order.
     * Each step up adds 12 semitones (one octave) to the base.
     *
     * Ranges per [kyrielie.blockbard.util.midiBase] (wiki-sourced, confirmed against
     * decompiled NoteBlockInstrument.java for this build):
     *
     *   midiBase 30  → BASS, DIDGERIDOO                                  (F#1–F#3)
     *   midiBase 42  → GUITAR, TRUMPET, TRUMPET_EXPOSED,
     *                  TRUMPET_OXIDIZED, TRUMPET_WEATHERED               (F#2–F#4)
     *   midiBase 54  → HARP, IRON_XYLOPHONE, BIT, BANJO, PLING           (F#3–F#5)
     *   midiBase 66  → FLUTE, COW_BELL                                  (F#4–F#6)
     *   midiBase 78  → BELL, CHIME, XYLOPHONE                           (F#5–F#7)
     *
     * BASEDRUM, SNARE, and HAT also share midiBase 54 but are excluded from this
     * ladder — see [UNPITCHED].
     *
     * Within a midiBase tier, order reflects timbral preference when multiple
     * instruments at the same pitch level are available. The ladder is used
     * directionally: walk up to find a higher-pitched substitute, walk down for
     * a lower-pitched one.
     */
    val PITCH_LADDER: List<NoteBlockInstrument> = listOf(
        // midiBase 30
        NoteBlockInstrument.BASS,
        NoteBlockInstrument.DIDGERIDOO,
        // midiBase 42
        NoteBlockInstrument.GUITAR,
        NoteBlockInstrument.TRUMPET,
        NoteBlockInstrument.TRUMPET_EXPOSED,
        NoteBlockInstrument.TRUMPET_WEATHERED,
        NoteBlockInstrument.TRUMPET_OXIDIZED,
        // midiBase 54
        NoteBlockInstrument.HARP,
        NoteBlockInstrument.IRON_XYLOPHONE,
        NoteBlockInstrument.BIT,
        NoteBlockInstrument.BANJO,
        NoteBlockInstrument.PLING,
        // midiBase 66
        NoteBlockInstrument.FLUTE,
        NoteBlockInstrument.COW_BELL,
        // midiBase 78
        NoteBlockInstrument.BELL,
        NoteBlockInstrument.CHIME,
        NoteBlockInstrument.XYLOPHONE
    )

    private val UNPITCHED: Set<NoteBlockInstrument> = setOf(
        NoteBlockInstrument.BASEDRUM,
        NoteBlockInstrument.SNARE,
        NoteBlockInstrument.HAT
    )

    fun plan(events: List<ResolvedNoteEvent>): FallbackPlan {
        // Query the organ once; build a fast lookup: instrument → set of MIDI notes present.
        val organNotes: Map<NoteBlockInstrument, Set<Int>> =
            NoteBlockRegistry.allPlayable()
                .groupBy { it.instrument }
                .mapValues { (_, entries) -> entries.map { it.midiNote }.toSet() }

        val availablePitched: List<NoteBlockInstrument> =
            PITCH_LADDER.filter { it in organNotes }

        if (organNotes.isEmpty()) {
            logger.warn("FallbackMapper: organ is empty — all notes will be dropped")
            return FallbackPlan(emptyList(), 0, 0)
        }

        val remapped  = mutableListOf<RemappedNoteEvent>()
        var shifted   = 0
        var clamped   = 0

        for (event in events) {

            // ── Unpitched percussion ──────────────────────────────────────────
            if (event.instrument in UNPITCHED) {
                val notes = organNotes[event.instrument]
                if (notes != null) {
                    // Unpitched — note index doesn't matter; use any available note.
                    remapped.add(event.toRemapped(event.instrument, notes.first()))
                }
                // else: drop silently — no meaningful substitute for unpitched
                continue
            }

            // ── Stage 1: instrument shift ─────────────────────────────────────
            val shifted1 = instrumentShift(event.midiNote, event.instrument, availablePitched, organNotes)
            if (shifted1 != null) {
                val wasShifted = shifted1.first != event.instrument
                if (wasShifted) shifted++
                remapped.add(event.toRemapped(shifted1.first, shifted1.second, wasClamped = false))
                continue
            }

            // ── Stage 2: octave clamp ─────────────────────────────────────────
            val clamped1 = octaveClamp(event.midiNote, availablePitched, organNotes)
            if (clamped1 != null) {
                clamped++
                remapped.add(event.toRemapped(clamped1.first, clamped1.second, wasClamped = true))
            }
            // else: no pitched instruments at all — note dropped
        }

        logger.info(
            "FallbackMapper: ${remapped.size} events — $shifted instrument-shifted, $clamped pitch-clamped"
        )
        return FallbackPlan(remapped, shifted, clamped)
    }

    // ── Core algorithms ───────────────────────────────────────────────────────

    /**
     * Finds the instrument on the [ladder] that covers [midiNote] exactly and is
     * closest to [original] in ladder position. Returns (instrument, midiNote) or
     * null if no instrument covers the note.
     *
     * Tie-breaking: when an instrument above and below the original are equidistant
     * on the ladder, prefer the one above. This is an arbitrary but consistent choice;
     * it is not based on any documented Minecraft or NoteBlockLib behavior.
     */
    private fun instrumentShift(
        midiNote: Int,
        original: NoteBlockInstrument,
        ladder: List<NoteBlockInstrument>,
        organNotes: Map<NoteBlockInstrument, Set<Int>>
    ): Pair<NoteBlockInstrument, Int>? {
        if (ladder.isEmpty()) return null

        // Find original's position; if not on the ladder, treat as the closest
        // ladder position by midiBase.
        val originIdx = ladder.indexOfFirst { it == original }
            .takeIf { it >= 0 }
            ?: ladder.indexOfFirst { it.midiBase >= original.midiBase }
                .takeIf { it >= 0 }
            ?: ladder.lastIndex

        // Walk outward from originIdx, checking up then down at each distance.
        // This returns the nearest instrument (in ladder steps) that covers the note.
        for (distance in 0..ladder.lastIndex) {
            val candidates = buildList {
                val up   = originIdx + distance
                val down = originIdx - distance
                if (distance == 0) {
                    add(up)
                } else {
                    if (up   <= ladder.lastIndex) add(up)
                    if (down >= 0)                add(down)
                }
            }
            for (idx in candidates) {
                val instr = ladder[idx]
                val notes = organNotes[instr] ?: continue
                if (midiNote in notes) return instr to midiNote
            }
        }
        return null
    }

    /**
     * Clamps [midiNote] to the nearest edge of any available instrument's range.
     * Returns (instrument, clampedNote) where clampedNote is the closest MIDI
     * note that exists in the organ: if the note is below all ranges, snaps to
     * the lowest available note; if above all ranges, snaps to the highest.
     */
    private fun octaveClamp(
        midiNote: Int,
        ladder: List<NoteBlockInstrument>,
        organNotes: Map<NoteBlockInstrument, Set<Int>>
    ): Pair<NoteBlockInstrument, Int>? {
        // Collect all (instrument, note) pairs and pick the one closest to midiNote.
        var bestInstr: NoteBlockInstrument? = null
        var bestNote  = 0
        var bestDist  = Int.MAX_VALUE

        for (instr in ladder) {
            val notes = organNotes[instr] ?: continue
            val closest = notes.minByOrNull { kotlin.math.abs(it - midiNote) } ?: continue
            val dist    = kotlin.math.abs(closest - midiNote)
            if (dist < bestDist) {
                bestDist  = dist
                bestNote  = closest
                bestInstr = instr
            }
        }

        return bestInstr?.let { it to bestNote }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ResolvedNoteEvent.toRemapped(
        instrument: NoteBlockInstrument,
        midiNote: Int,
        wasClamped: Boolean = false
    ) = RemappedNoteEvent(
        tick               = this.tick,
        originalInstrument = this.instrument,
        originalMidiNote   = this.midiNote,
        remappedInstrument = instrument,
        remappedMidiNote   = midiNote,
        wasClamped         = wasClamped
    )
}
