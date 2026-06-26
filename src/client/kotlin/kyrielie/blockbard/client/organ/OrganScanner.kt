
package kyrielie.blockbard.client.organ

import kyrielie.blockbard.organ.MOB_HEAD_BLOCKS
import kyrielie.blockbard.organ.NoteBlockEntry
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteBlockStatus
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.noteIndexToMidi
import kyrielie.blockbard.util.midiNoteToName
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

object OrganScanner {

    private val logger = LoggerFactory.getLogger("BlockBard/OrganScanner")

    /** Scan radius in blocks (1–10). Configurable via config screen. */
    var scanRadius: Int = 5

    /**
     * Scans for noteblocks in a cube of [scanRadius] around the player and updates [NoteBlockRegistry].
     * Must be called from the client thread (e.g. ClientTickEvents or button click).
     */
    fun scan() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("scan: no player available")
            return
        }
        val world = mc.level ?: run {
            logger.warn("scan: no world available")
            return
        }

        val playerPos = player.blockPosition()
        val eyePos = player.eyePosition
        val r = scanRadius
        val found = mutableListOf<NoteBlockEntry>()

        logger.info("scan: scanning radius=$r around $playerPos")

        for (x in -r..r) for (y in -r..r) for (z in -r..r) {
            val pos = playerPos.offset(x, y, z)
            val state = world.getBlockState(pos)
            if (state.block != Blocks.NOTE_BLOCK) continue

            val instrument: NoteBlockInstrument = state.getValue(NoteBlock.INSTRUMENT)
            val noteIndex: Int = state.getValue(NoteBlock.NOTE)
            val midiNote = noteIndexToMidi(instrument, noteIndex)

            val blockCenter = net.minecraft.world.phys.Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            val distance = eyePos.distanceTo(blockCenter)

            val aboveBlock = world.getBlockState(pos.above()).block
            val (status, mobHead) = when {
                aboveBlock in MOB_HEAD_BLOCKS -> Pair(NoteBlockStatus.MOB_HEAD, aboveBlock)
                !world.getBlockState(pos.above()).isAir -> Pair(NoteBlockStatus.SILENCED, null)
                else -> Pair(NoteBlockStatus.PLAYABLE, null)
            }

            logger.debug("scan: found NoteBlock at $pos — instrument=${instrument.name} noteIndex=$noteIndex midi=$midiNote (${midiNoteToName(midiNote)}) status=$status dist=${"%.1f".format(distance)}")

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

        // Check for instrument overflow
        val overflows = NoteBlockRegistry.detectInstrumentOverflows()
        if (overflows.isNotEmpty()) {
            overflows.forEach { o ->
                val msg = "⚠ ${o.instrument.name} has ${o.count} blocks (max 25) — ${o.excess} excess will be untunable"
                logger.warn("scan: instrument overflow — $msg")
                player.sendSystemMessage(Component.literal("§b[BlockBard] §e$msg"))
            }
        }

        val playable = found.count { it.status == NoteBlockStatus.PLAYABLE }
        val silenced = found.count { it.status == NoteBlockStatus.SILENCED }
        val mobHeads = found.count { it.status == NoteBlockStatus.MOB_HEAD }

        val summary = "Scan complete: ${found.size} total — $playable playable, $silenced silenced, $mobHeads mob-head"
        logger.info("scan: $summary")

        // Echo to chat for in-game visibility
        player.sendSystemMessage(Component.literal("§b[BlockBard] §f$summary"))

        if (playable > 0) {
            val playableEntries = found.filter { it.status == NoteBlockStatus.PLAYABLE }

            val noteRange = playableEntries.sortedBy { it.midiNote }
            val low = midiNoteToName(noteRange.first().midiNote)
            val high = midiNoteToName(noteRange.last().midiNote)
            logger.info("scan: playable MIDI range $low–$high")
            player.sendSystemMessage(Component.literal("§b[BlockBard] §7Playable range: $low – $high"))

            // Per-instrument breakdown: which notes (and how many blocks) are actually
            // available for each instrument. This is what tuning/playback logic needs —
            // the global range above can look fine while a specific instrument is missing
            // notes or has duplicate noteIndex collisions.
            logger.info("scan: per-instrument note coverage —")
            playableEntries
                .groupBy { it.instrument }
                .toSortedMap(compareBy { it.name })
                .forEach { (instrument, entries) ->
                    val sortedByIndex = entries.sortedBy { it.noteIndex }
                    val indices = sortedByIndex.map { it.noteIndex }
                    val duplicateIndices = indices.groupBy { it }.filter { it.value.size > 1 }.keys.sorted()
                    val instrLow = midiNoteToName(sortedByIndex.first().midiNote)
                    val instrHigh = midiNoteToName(sortedByIndex.last().midiNote)

                    val dupNote = if (duplicateIndices.isNotEmpty()) " ⚠ duplicate noteIndex(es): $duplicateIndices" else ""
                    logger.info("  ${instrument.name}: ${entries.size} block(s), noteIndex [${indices.joinToString()}], range $instrLow–$instrHigh$dupNote")
                }
        }
    }
}
