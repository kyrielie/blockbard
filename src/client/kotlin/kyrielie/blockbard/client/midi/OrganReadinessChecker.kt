package kyrielie.blockbard.midi

import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.util.coversNatively
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

/**
 * Result of comparing a MIDI file's instrument/note requirements against the
 * currently scanned organ.
 */
data class OrganReadinessReport(
    /** Notes that are fully covered: the required instrument and MIDI note exist in the organ. */
    val coveredNotes: Set<MidiNoteRequirement>,
    /** Notes missing because the correct instrument is absent entirely. */
    val missingInstruments: Set<NoteBlockInstrument>,
    /** Notes whose instrument exists but is not tuned to cover that specific MIDI note. */
    val missingNotes: Set<MidiNoteRequirement>,
    /** Total distinct (instrument, midiNote) pairs the MIDI file requires. */
    val totalRequired: Int
) {
    val isFullyCovered: Boolean get() = missingInstruments.isEmpty() && missingNotes.isEmpty()
    val coveragePercent: Int
        get() = if (totalRequired == 0) 100
                else (coveredNotes.size * 100) / totalRequired

    /**
     * Distinct missing (instrument, noteIndex) combinations per instrument, restricted
     * to ones genuinely fixable by adding a block — i.e. excludes any requirement whose
     * midiNote falls outside that instrument's 25-note native range (coversNatively),
     * since no amount of building more of that instrument can ever produce a note
     * outside its fixed range. The count per instrument is exactly the number of
     * additional physical blocks needed: NoteBlockTuner can retune any existing block
     * to any noteIndex, but it cannot create blocks that don't exist, so each distinct
     * missing noteIndex for a present-or-absent instrument needs one more block.
     *
     * Does NOT account for polyphony — MidiNoteRequirement collapses every note event
     * in the file down to a Set of distinct (instrument, midiNote) pairs with no timing
     * information (see MidiChannelResolver.resolveRequirements), so a pitch needed
     * twice simultaneously still only counts once here. The number returned is a floor,
     * not a guarantee — you may need more blocks than this if the song requires the
     * same note to ring more than once at the same time.
     */
    fun shortfallByInstrument(): Map<NoteBlockInstrument, Int> =
        missingNotes
            .filter { it.instrument.coversNatively(it.midiNote) }
            .groupBy { it.instrument }
            .mapValues { (_, reqs) -> reqs.map { it.midiNote }.distinct().size }

    fun summary(): String = buildString {
        if (isFullyCovered) {
            appendLine("Organ fully covers this MIDI file ($totalRequired notes).")
        } else {
            appendLine("Coverage: $coveragePercent% (${ coveredNotes.size}/$totalRequired notes).")
            val shortfall = shortfallByInstrument()
            if (shortfall.isNotEmpty()) {
                appendLine("Add: " + shortfall.entries.joinToString { (inst, count) -> "$count ${inst.name}" })
            }
            val unfixable = missingNotes.count { !it.instrument.coversNatively(it.midiNote) }
            if (unfixable > 0) {
                appendLine("$unfixable note(s) out of range for any available instrument (cannot be fixed by adding blocks).")
            }
        }
    }
}

data class MidiNoteRequirement(
    val instrument: NoteBlockInstrument,
    val midiNote: Int
)

/**
 * Checks whether the currently scanned organ (via [NoteBlockRegistry]) can play
 * everything a MIDI file requires.
 *
 * The check operates on the resolved (instrument, midiNote) pairs that
 * [MidiChannelResolver] produces — not raw GM programs — so it correctly handles
 * the per-channel instrument assignments.
 */
object OrganReadinessChecker {

    private val logger = LoggerFactory.getLogger("BlockBard/OrganReadiness")

    /**
     * Runs the readiness check.
     *
     * @param requirements Set of (instrument, midiNote) pairs the MIDI file needs.
     *                     Obtain this from [MidiChannelResolver.resolveRequirements].
     * @return An [OrganReadinessReport] describing coverage gaps.
     */
    fun check(requirements: Set<MidiNoteRequirement>): OrganReadinessReport {
        val playableMidiNotes: Map<NoteBlockInstrument, Set<Int>> =
            NoteBlockRegistry.allPlayable()
                .groupBy { it.instrument }
                .mapValues { (_, entries) -> entries.map { it.midiNote }.toSet() }

        val availableInstruments = playableMidiNotes.keys

        val covered      = mutableSetOf<MidiNoteRequirement>()
        val missingNotes = mutableSetOf<MidiNoteRequirement>()

        for (req in requirements) {
            // Every uncovered requirement goes into missingNotes regardless of whether
            // its instrument is entirely absent or just short a note — previously, a
            // fully-absent instrument's specific notes were dropped (only the
            // instrument name was kept, in a separate missingInstr set), which made it
            // impossible to later compute "how many of instrument X do I need to add"
            // for the common case of a missing instrument. missingInstruments below is
            // now derived from this same data rather than collected separately, so the
            // two can never disagree or lose information relative to each other.
            if (req.midiNote in (playableMidiNotes[req.instrument] ?: emptySet())) {
                covered.add(req)
            } else {
                missingNotes.add(req)
            }
        }
        val missingInstr = missingNotes.map { it.instrument }.filter { it !in availableInstruments }.toSet()

        val report = OrganReadinessReport(covered, missingInstr, missingNotes, requirements.size)
        logger.info("OrganReadiness: ${report.summary().trimEnd()}")
        return report
    }
}