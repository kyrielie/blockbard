package kyrielie.blockbard.client.render

import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteBlockStatus
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
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory

/**
 * In-world augmented reality hologram overlay for all organ noteblocks around the player.
 * Color-codes each block by its musical and tuning state:
 * - Green: Assigned to active song and confirmed in tune.
 * - Amber / Yellow: Assigned to active song, needs retuning clicks.
 * - Red: Untunable / occluded from the player's standing spot.
 * - Cyan / Blue: Spare / available unassigned playable block.
 * - Orange: Silenced (obstructed above).
 * - Magenta: Mob head ambient block.
 */
object OrganOverlayRenderer {

    private val logger = LoggerFactory.getLogger("BlockBard/OrganOverlayRenderer")

    var isEnabled: Boolean = false

    /** Maps assigned positions to their required target noteIndex (0-24) for the active song. */
    var activeTargets: Map<BlockPos, Int> = emptyMap()

    fun register() {
        LevelRenderEvents.BEFORE_GIZMOS.register { context: LevelRenderContext ->
            if (!isEnabled) return@register
            render(context)
        }
        logger.info("OrganOverlayRenderer registered with LevelRenderEvents.BEFORE_GIZMOS")
    }

    fun toggle(): Boolean {
        isEnabled = !isEnabled
        logger.info("Organ overlay toggled: {}", if (isEnabled) "ENABLED" else "DISABLED")
        return isEnabled
    }

    private fun render(context: LevelRenderContext) {
        val collector = context.submitNodeCollector()
        val camera = context.levelState().cameraRenderState
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val primitivesDepth = DrawableGizmoPrimitives()
        val primitivesOnTop = DrawableGizmoPrimitives()

        val allEntries = NoteBlockRegistry.all()
        if (allEntries.isEmpty()) return

        // Color styles (stroke and fill)
        val styleGreen = GizmoStyle.strokeAndFill(0xFF44FF44.toInt(), 2.0f, 0x2233FF33.toInt())
        val styleAmber = GizmoStyle.strokeAndFill(0xFFFFAA00.toInt(), 2.0f, 0x33FFAA00.toInt())
        val styleRed   = GizmoStyle.strokeAndFill(0xFFFF3333.toInt(), 2.5f, 0x33FF2222.toInt())
        val styleBlue  = GizmoStyle.strokeAndFill(0xFF33CCFF.toInt(), 1.5f, 0x181199DD.toInt())
        val styleOrange= GizmoStyle.strokeAndFill(0xFFFF6600.toInt(), 2.0f, 0x22FF6600.toInt())
        val stylePurple= GizmoStyle.strokeAndFill(0xFFFF55FF.toInt(), 1.5f, 0x18FF33FF.toInt())

        val textStyleCenter = TextGizmo.Style.whiteAndCentered().withScale(0.9f)

        for (entry in allEntries) {
            val pos = entry.pos
            val box = AABB(pos)
            val textCenter = Vec3(pos.x + 0.5, pos.y + 1.25, pos.z + 0.5)

            when {
                // 1. Silenced
                entry.status == NoteBlockStatus.SILENCED -> {
                    CuboidGizmo(box, styleOrange, false).emit(primitivesDepth, 0f)
                    val label = "§6[SILENCED] §7Blocked Above"
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 0f)
                }

                // 2. Mob head
                entry.status == NoteBlockStatus.MOB_HEAD -> {
                    CuboidGizmo(box, stylePurple, false).emit(primitivesDepth, 0f)
                    val label = "§d${entry.instrument.name} §7(Mob Head)"
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 0f)
                }

                // 3. Untunable / occluded
                NoteBlockRegistry.isUntunable(pos) -> {
                    val reason = NoteBlockRegistry.getUntunableReason(pos)
                    val tag = reason?.tag ?: "UNTUNABLE"
                    CuboidGizmo(box, styleRed, false).emit(primitivesOnTop, 0f)
                    val target = activeTargets[pos]
                    val label = if (target != null) {
                        val needed = clicksNeeded(entry.noteIndex, target)
                        val targetName = midiNoteToName(noteIndexToMidi(entry.instrument, target))
                        "§c[$tag] §e${entry.instrument.name} §f$targetName §7(+$needed)"
                    } else {
                        "§c[$tag] §e${entry.instrument.name} §7(${midiNoteToName(entry.midiNote)})"
                    }
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesOnTop, 0f)
                }

                // 4. Assigned to active song
                activeTargets.containsKey(pos) -> {
                    val targetNote = activeTargets[pos]!!
                    val isTuned = entry.noteIndex == targetNote
                    if (isTuned) {
                        CuboidGizmo(box, styleGreen, false).emit(primitivesDepth, 0f)
                        val noteName = midiNoteToName(entry.midiNote)
                        val label = "§a${entry.instrument.name} §f$noteName §7(✓ In Tune)"
                        TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 0f)
                    } else {
                        CuboidGizmo(box, styleAmber, false).emit(primitivesDepth, 0f)
                        val needed = clicksNeeded(entry.noteIndex, targetNote)
                        val targetName = midiNoteToName(noteIndexToMidi(entry.instrument, targetNote))
                        val label = "§e${entry.instrument.name} §f$targetName §6(+$needed clicks)"
                        TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 0f)
                    }
                }

                // 5. Spare / Idle playable noteblock
                else -> {
                    CuboidGizmo(box, styleBlue, false).emit(primitivesDepth, 0f)
                    val noteName = midiNoteToName(entry.midiNote)
                    val label = "§b${entry.instrument.name} §f$noteName §7(Spare)"
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 0f)
                }
            }
        }

        // Standard depth testing for normal blocks
        primitivesDepth.submit(collector, camera, false)
        // Always on top for occluded/problematic blocks so they are visible through walls
        primitivesOnTop.submit(collector, camera, true)
    }
}
