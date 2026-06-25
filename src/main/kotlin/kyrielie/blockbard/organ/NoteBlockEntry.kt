package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

enum class NoteBlockStatus { PLAYABLE, SILENCED, MOB_HEAD }

data class NoteBlockEntry(
    val pos: BlockPos,
    val instrument: NoteBlockInstrument,
    val noteIndex: Int,           // 0–24 (Minecraft note index / block state)
    val midiNote: Int,            // instrument.midiBase + noteIndex
    val distanceFromPlayer: Double,
    val status: NoteBlockStatus,
    val mobHeadType: Block? = null  // non-null only when status == MOB_HEAD
)
