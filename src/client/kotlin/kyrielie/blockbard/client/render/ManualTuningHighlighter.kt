package kyrielie.blockbard.client.render

import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.TuningFailState
import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.midiNoteToName
import kyrielie.blockbard.util.noteIndexToMidi
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.CuboidGizmo
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class ManualTuneInfo(
    val pos: BlockPos,
    val instrument: NoteBlockInstrument,
    val targetNote: Int,
    val failState: TuningFailState
)

/**
 * In-world 3D visual highlighter for noteblocks that failed automatic tuning (e.g. occluded
 * by other blocks, out of reach, or dropped by server protection).
 *
 * Renders an always-on-top bounding box and floating billboard text displaying the target note,
 * clicks needed, and fail reason directly over the block so the player can tune it manually.
 */
object ManualTuningHighlighter {

    private val logger = LoggerFactory.getLogger("BlockBard/ManualTuningHighlighter")
    private val highlightedBlocks = ConcurrentHashMap<BlockPos, ManualTuneInfo>()

    var isEnabled: Boolean = true

    fun register() {
        LevelRenderEvents.BEFORE_GIZMOS.register { context: LevelRenderContext ->
            if (!isEnabled || highlightedBlocks.isEmpty()) return@register
            render(context)
        }
        logger.info("ManualTuningHighlighter registered with LevelRenderEvents.BEFORE_GIZMOS")
    }

    fun add(pos: BlockPos, instrument: NoteBlockInstrument, targetNote: Int, failState: TuningFailState) {
        highlightedBlocks[pos] = ManualTuneInfo(pos, instrument, targetNote, failState)
        logger.debug("Added manual tuning highlight for $pos (${instrument.name}) -> note $targetNote [${failState.tag}]")
    }

    fun remove(pos: BlockPos) {
        highlightedBlocks.remove(pos)
    }

    fun clear() {
        highlightedBlocks.clear()
        NoteBlockRegistry.clearUntunable()
        logger.info("Manual tuning highlights cleared")
    }

    fun count(): Int = highlightedBlocks.size

    fun all(): Map<BlockPos, ManualTuneInfo> = highlightedBlocks.toMap()

    /**
     * Checks world state and automatically clears highlights for blocks that have reached
     * their target note via manual player interaction.
     */
    fun updateWorldState() {
        if (highlightedBlocks.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val iterator = highlightedBlocks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pos = entry.key
            val info = entry.value
            val state = level.getBlockState(pos)
            if (state.`is`(Blocks.NOTE_BLOCK)) {
                val liveNote = state.getValue(NoteBlock.NOTE)
                if (liveNote == info.targetNote) {
                    val targetMidi = noteIndexToMidi(info.instrument, info.targetNote)
                    logger.info("Manual tuning completed for $pos (${info.instrument.name}) at ${midiNoteToName(targetMidi)}!")
                    NoteBlockRegistry.updateTunedNote(pos, liveNote)
                    iterator.remove()
                }
            } else {
                iterator.remove()
            }
        }
    }

    private fun render(context: LevelRenderContext) {
        val collector = context.submitNodeCollector()
        val camera = context.levelState().cameraRenderState
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val primitives = DrawableGizmoPrimitives()

        // Vibrant amber/red outline + translucent fill for maximum visibility through blocks
        val boxStyle = GizmoStyle.strokeAndFill(
            0xFFFF3333.toInt(), // vivid red outline
            2.5f,
            0x33FF2222.toInt()  // subtle translucent red fill
        )

        for ((pos, info) in highlightedBlocks) {
            val state = level.getBlockState(pos)
            val liveNote = if (state.`is`(Blocks.NOTE_BLOCK)) state.getValue(NoteBlock.NOTE) else null
            if (liveNote == info.targetNote) continue

            val box = AABB(pos)
            CuboidGizmo(box, boxStyle, false).emit(primitives, 1.0f)

            val needed = if (liveNote != null) clicksNeeded(liveNote, info.targetNote) else 0
            val targetMidi = noteIndexToMidi(info.instrument, info.targetNote)
            val targetName = midiNoteToName(targetMidi)
            val textCenter = Vec3(pos.x + 0.5, pos.y + 1.25, pos.z + 0.5)

            val label = "Tune to $targetName (+$needed clicks) [${info.failState.tag}]"
            val textStyle = TextGizmo.Style.forColorAndCentered(0xFFFFFF33.toInt()).withScale(1.0f)
            TextGizmo(textCenter, label, textStyle).emit(primitives, 1.0f)
        }

        // alwaysOnTop = true allows seeing the occluded noteblock through intervening walls
        primitives.submit(collector, camera, true)
    }
}
