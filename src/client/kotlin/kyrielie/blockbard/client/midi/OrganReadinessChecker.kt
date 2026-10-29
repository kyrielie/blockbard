package kyrielie.blockbard.client.midi

import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NotePitch
import kyrielie.blockbard.util.coversNatively
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

/**
 * Result of comparing a MIDI or NBS file's instrument/note requirements against the
 * currently scanned organ.
 */
data class OrganReadinessReport(
    /** Notes that are fully covered: the required instrument and MIDI note exist in the organ. */
    val coveredNotes: Set<NotePitch>,
    /** Notes missing because the correct instrument is absent entirely. */
    val missingInstruments: Set<NoteBlockInstrument>,
    /** Notes whose instrument exists but is not tuned to cover that specific MIDI note. */
    val missingNotes: Set<NotePitch>,
    /** Total distinct NotePitch requirements the song requires. */
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
     * additional physical blocks needed.
     */
    fun shortfallByInstrument(): Map<NoteBlockInstrument, Int> =
        missingNotes
            .mapNotNull { req -> req.instrument?.takeIf { it.coversNatively(req.midiNote) }?.let { it to req.midiNote } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, notes) -> notes.distinct().size }

    fun summary(): String = buildString {
        if (isFullyCovered) {
            appendLine("Organ fully covers this track ($totalRequired notes).")
        } else {
            appendLine("Coverage: $coveragePercent% (${coveredNotes.size}/$totalRequired notes).")
            val shortfall = shortfallByInstrument()
            if (shortfall.isNotEmpty()) {
                appendLine("Add: " + shortfall.entries.joinToString { (inst, count) -> "$count ${inst.name}" })
            }
            val unfixable = missingNotes.count { req ->
                val inst = req.instrument
                inst != null && !inst.coversNatively(req.midiNote)
            }
            if (unfixable > 0) {
                appendLine("$unfixable note(s) out of range for any available instrument (cannot be fixed by adding blocks).")
            }
        }
    }
}

/**
 * Checks whether the currently scanned organ (via [NoteBlockRegistry]) can play
 * everything a song file requires.
 */
object OrganReadinessChecker {

    private val logger = LoggerFactory.getLogger("BlockBard/OrganReadiness")

    /**
     * Runs the readiness check.
     *
     * @param requirements Set of [NotePitch] (instrument, midiNote) pairs the file needs.
     * @return An [OrganReadinessReport] describing coverage gaps.
     */
    fun check(requirements: Set<NotePitch>): OrganReadinessReport {
        val playableMidiNotes: Map<NoteBlockInstrument, Set<Int>> =
            NoteBlockRegistry.allPlayable()
                .groupBy { it.instrument }
                .mapValues { (_, entries) -> entries.map { it.midiNote }.toSet() }

        val availableInstruments = playableMidiNotes.keys

        val covered      = mutableSetOf<NotePitch>()
        val missingNotes = mutableSetOf<NotePitch>()

        for (req in requirements) {
            val inst = req.instrument
            val isCovered = if (inst != null) {
                req.midiNote in (playableMidiNotes[inst] ?: emptySet())
            } else {
                NoteBlockRegistry.allPlayableMidiNotes().contains(req.midiNote)
            }

            if (isCovered) {
                covered.add(req)
            } else {
                missingNotes.add(req)
            }
        }
        val missingInstr = missingNotes.mapNotNull { it.instrument }.filter { it !in availableInstruments }.toSet()

        val report = OrganReadinessReport(covered, missingInstr, missingNotes, requirements.size)
        logger.info("OrganReadiness: ${report.summary().trimEnd()}")
        return report
    }
}
