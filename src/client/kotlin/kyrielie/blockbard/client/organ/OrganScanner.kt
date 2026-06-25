package kyrielie.blockbard.client.organ

import kyrielie.blockbard.organ.MOB_HEAD_BLOCKS
import kyrielie.blockbard.organ.NoteBlockEntry
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteBlockStatus
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.noteIndexToMidi
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

object OrganScanner {

    /** Scan radius in blocks (1–10). Configurable via config screen. */
    var scanRadius: Int = 5

    /**
     * Scans for noteblocks in a cube of [scanRadius] around the player and updates [NoteBlockRegistry].
     * Must be called from the client thread (e.g. ClientTickEvents or button click).
     */
    fun scan() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val world = mc.level ?: return

        val playerPos = player.blockPosition()
        val eyePos = player.eyePosition
        val r = scanRadius
        val found = mutableListOf<NoteBlockEntry>()

        for (x in -r..r) for (y in -r..r) for (z in -r..r) {
            val pos = playerPos.offset(x, y, z)
            val state = world.getBlockState(pos)
            if (state.block != Blocks.NOTE_BLOCK) continue

            val instrument: NoteBlockInstrument = state.getValue(NoteBlock.INSTRUMENT)
            val noteIndex: Int = state.getValue(NoteBlock.NOTE)
            val midiNote = noteIndexToMidi(instrument, noteIndex)

            val blockCenter = net.minecraft.world.phys.Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            val distance = eyePos.distanceTo(blockCenter)

            // Determine status from block above
            val aboveBlock = world.getBlockState(pos.above()).block
            val (status, mobHead) = when {
                aboveBlock in MOB_HEAD_BLOCKS -> Pair(NoteBlockStatus.MOB_HEAD, aboveBlock)
                !world.getBlockState(pos.above()).isAir -> Pair(NoteBlockStatus.SILENCED, null)
                else -> Pair(NoteBlockStatus.PLAYABLE, null)
            }

            found.add(
                NoteBlockEntry(
                    pos = pos,
                    instrument = instrument,
                    noteIndex = noteIndex,
                    midiNote = midiNote,
                    distanceFromPlayer = distance,
                    status = status,
                    mobHeadType = mobHead
                )
            )
        }

        NoteBlockRegistry.update(found)
    }
}
