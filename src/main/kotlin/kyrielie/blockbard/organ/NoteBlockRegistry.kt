package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import kyrielie.blockbard.util.midiBase
import org.slf4j.LoggerFactory

/** Represents a detected instrument overflow: more than 24 noteblocks share one instrument. */
data class InstrumentOverflow(
    val instrument: NoteBlockInstrument,
    val count: Int,
    val maxUsable: Int = 25  // noteIndex 0–24
) {
    val excess: Int get() = count - maxUsable
    override fun toString() = "${instrument.name}: $count blocks (max $maxUsable, $excess excess)"
}

/**
 * Central in-memory registry mapping BlockPos → NoteBlockEntry.
 * Updated by OrganScanner on each scan; queried by the mapper and scheduler.
 */
object NoteBlockRegistry {

    private val logger = LoggerFactory.getLogger("BlockBard/NoteBlockRegistry")
    private val entries: MutableMap<BlockPos, NoteBlockEntry> = LinkedHashMap()

    fun update(newEntries: List<NoteBlockEntry>) {
        entries.clear()
        newEntries.forEach { entries[it.pos] = it }
        checkInstrumentOverflow()
    }

    fun clear() = entries.clear()

    fun all(): List<NoteBlockEntry> = entries.values.toList()

    fun allPlayable(): List<NoteBlockEntry> =
        entries.values.filter { it.status == NoteBlockStatus.PLAYABLE }

    fun allMobHeadEntries(): List<NoteBlockEntry> =
        entries.values.filter { it.status == NoteBlockStatus.MOB_HEAD }

    /** Find the closest PLAYABLE noteblock with exactly the given MIDI note, any instrument. */
    fun findBestForMidi(midiNote: Int): NoteBlockEntry? =
        allPlayable()
            .filter { it.midiNote == midiNote }
            .minByOrNull { it.distanceFromPlayer }

    /**
     * Find the closest PLAYABLE noteblock with exactly the given MIDI note AND
     * instrument, if [instrument] is non-null; falls back to any instrument
     * (same as [findBestForMidi]) if [instrument] is null or no exact-instrument
     * match exists. Used by playback paths that know which instrument a note was
     * authored for (NBS instrument byte, MIDI channel program) — without this,
     * a song's instrument choice is ignored entirely and any block at the right
     * pitch is used regardless of its instrument timbre.
     */
    fun findBestFor(midiNote: Int, instrument: NoteBlockInstrument?): NoteBlockEntry? {
        if (instrument == null) return findBestForMidi(midiNote)
        val exact = allPlayable()
            .filter { it.midiNote == midiNote && it.instrument == instrument }
            .minByOrNull { it.distanceFromPlayer }
        return exact ?: findBestForMidi(midiNote)
    }

    /** All distinct MIDI notes that are currently playable in the organ. */
    fun allPlayableMidiNotes(): Set<Int> =
        allPlayable().map { it.midiNote }.toSet()

    /** Counts per instrument (playable only). */
    fun countPerInstrument(): Map<NoteBlockInstrument, Int> =
        allPlayable().groupBy { it.instrument }.mapValues { it.value.size }

    /**
     * Returns any instruments that have more than 25 noteblocks (indices 0–24).
     * More than 25 blocks per instrument means some will be duplicates and can never
     * all be assigned unique notes. This is a configuration error in the noteblock organ.
     */
    fun detectInstrumentOverflows(): List<InstrumentOverflow> =
        countPerInstrument()
            .filter { (_, count) -> count > 25 }
            .map { (inst, count) -> InstrumentOverflow(inst, count) }

    /** Update a specific entry's noteIndex after tuning. */
    fun updateTunedNote(pos: BlockPos, newNoteIndex: Int) {
        val existing = entries[pos] ?: return
        entries[pos] = existing.copy(
            noteIndex = newNoteIndex,
            midiNote = existing.instrument.midiBase + newNoteIndex
        )
    }

    fun size(): Int = entries.size

    private fun checkInstrumentOverflow() {
        val overflows = detectInstrumentOverflows()
        if (overflows.isEmpty()) return
        overflows.forEach { o ->
            logger.warn("NoteBlockRegistry: INSTRUMENT OVERFLOW — ${o.instrument.name} has ${o.count} blocks but max unique notes is ${o.maxUsable} (${o.excess} excess blocks will be unreachable)")
        }
    }
}
