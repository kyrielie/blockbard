package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import kyrielie.blockbard.util.midiBase

/**
 * Central in-memory registry mapping BlockPos → NoteBlockEntry.
 * Updated by OrganScanner on each scan; queried by the mapper and scheduler.
 */
object NoteBlockRegistry {
    private val entries: MutableMap<BlockPos, NoteBlockEntry> = LinkedHashMap()

    fun update(newEntries: List<NoteBlockEntry>) {
        entries.clear()
        newEntries.forEach { entries[it.pos] = it }
    }

    fun clear() = entries.clear()

    fun all(): List<NoteBlockEntry> = entries.values.toList()

    fun allPlayable(): List<NoteBlockEntry> =
        entries.values.filter { it.status == NoteBlockStatus.PLAYABLE }

    fun allMobHeadEntries(): List<NoteBlockEntry> =
        entries.values.filter { it.status == NoteBlockStatus.MOB_HEAD }

    /** Find the closest PLAYABLE noteblock with exactly the given MIDI note. */
    fun findBestForMidi(midiNote: Int): NoteBlockEntry? =
        allPlayable()
            .filter { it.midiNote == midiNote }
            .minByOrNull { it.distanceFromPlayer }

    /** All distinct MIDI notes that are currently playable in the organ. */
    fun allPlayableMidiNotes(): Set<Int> =
        allPlayable().map { it.midiNote }.toSet()

    /** Counts per instrument (playable only). */
    fun countPerInstrument(): Map<NoteBlockInstrument, Int> =
        allPlayable().groupBy { it.instrument }.mapValues { it.value.size }

    /** Update a specific entry's noteIndex after tuning. */
    fun updateTunedNote(pos: BlockPos, newNoteIndex: Int) {
        val existing = entries[pos] ?: return
        entries[pos] = existing.copy(
            noteIndex = newNoteIndex,
            midiNote = existing.instrument.midiBase + newNoteIndex
        )
    }

    fun size(): Int = entries.size
}
