package kyrielie.blockbard.midi

import kyrielie.blockbard.organ.NoteBlockRegistry
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

    fun summary(): String = buildString {
        if (isFullyCovered) {
            appendLine("Organ fully covers this MIDI file ($totalRequired notes).")
        } else {
            appendLine("Coverage: $coveragePercent% (${ coveredNotes.size}/$totalRequired notes).")
            if (missingInstruments.isNotEmpty()) {
                appendLine("Missing instruments: ${missingInstruments.joinToString { it.name }}")
            }
            if (missingNotes.isNotEmpty()) {
                appendLine("${missingNotes.size} note(s) out of range for available instruments.")
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
        val missingInstr = mutableSetOf<NoteBlockInstrument>()
        val missingNotes = mutableSetOf<MidiNoteRequirement>()

        for (req in requirements) {
            when {
                req.instrument !in availableInstruments -> {
                    missingInstr.add(req.instrument)
                }
                req.midiNote !in (playableMidiNotes[req.instrument] ?: emptySet()) -> {
                    missingNotes.add(req)
                }
                else -> covered.add(req)
            }
        }

        val report = OrganReadinessReport(covered, missingInstr, missingNotes, requirements.size)
        logger.info("OrganReadiness: ${report.summary().trimEnd()}")
        return report
    }
}
