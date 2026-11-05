package kyrielie.blockbard.client.render

import kyrielie.blockbard.client.organ.OrganScanner
import kyrielie.blockbard.client.player.PlayerController
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
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory

/**
 * In-world augmented reality hologram overlay for all organ noteblocks around the player.
 * Color-codes each block by its musical and tuning state, distinguishing working vs non-working:
 *
 * Non-Working:
 * - Orange: Silenced (obstructed by a solid block directly above).
 * - Crimson / Dark Red: Out of reach from player's eye/standing position.
 * - Red: Untunable / occluded line of sight.
 *
 * Working:
 * - Green: Assigned to active song and confirmed in tune.
 * - Amber / Yellow: Assigned to active song, needs retuning clicks.
 * - Cyan / Blue: Spare / available unassigned playable block in reach.
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
        if (isEnabled) {
            OrganScanner.scan()
        }
        logger.info("Organ overlay toggled: {}", if (isEnabled) "ENABLED" else "DISABLED")
        return isEnabled
    }

    private fun render(context: LevelRenderContext) {
        val collector = context.submitNodeCollector()
        val camera = context.levelState().cameraRenderState
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        var allEntries = NoteBlockRegistry.all()
        if (allEntries.isEmpty()) {
            OrganScanner.scan()
            allEntries = NoteBlockRegistry.all()
            if (allEntries.isEmpty()) return
        }

        val primitivesDepth = DrawableGizmoPrimitives()
        val primitivesOnTop = DrawableGizmoPrimitives()

        // Working block color styles
        val styleGreen = GizmoStyle.strokeAndFill(0xFF44FF44.toInt(), 2.0f, 0x2233FF33.toInt())
        val styleAmber = GizmoStyle.strokeAndFill(0xFFFFAA00.toInt(), 2.0f, 0x33FFAA00.toInt())
        val styleBlue  = GizmoStyle.strokeAndFill(0xFF33CCFF.toInt(), 1.5f, 0x181199DD.toInt())
        val stylePurple= GizmoStyle.strokeAndFill(0xFFFF55FF.toInt(), 1.5f, 0x18FF33FF.toInt())

        // Non-working block color styles
        val styleOrange     = GizmoStyle.strokeAndFill(0xFFFF6600.toInt(), 2.5f, 0x33FF6600.toInt())
        val styleOutOfReach = GizmoStyle.strokeAndFill(0xFF882222.toInt(), 2.0f, 0x33551111.toInt())
        val styleRed        = GizmoStyle.strokeAndFill(0xFFFF2222.toInt(), 2.5f, 0x33FF2222.toInt())

        val textStyleCenter = TextGizmo.Style.whiteAndCentered().withScale(0.9f)

        val eyePos = player.eyePosition
        val reachLimit = (player.blockInteractionRange()) + 0.5
        val organMap = PlayerController.organMap

        for (entry in allEntries) {
            val pos = entry.pos
            val blockState = level.getBlockState(pos)
            if (!blockState.`is`(Blocks.NOTE_BLOCK)) {
                continue
            }

            val liveNote = blockState.getValue(NoteBlock.NOTE)
            if (liveNote != entry.noteIndex) {
                NoteBlockRegistry.updateTunedNote(pos, liveNote)
            }

            val box = AABB(pos)
            val textCenter = Vec3(pos.x + 0.5, pos.y + 1.25, pos.z + 0.5)
            val targetCenter = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            val dist = eyePos.distanceTo(targetCenter)

            val abovePos = pos.above()
            val aboveState = level.getBlockState(abovePos)
            val aboveBlock = aboveState.block
            val isMobHead = entry.status == NoteBlockStatus.MOB_HEAD || aboveBlock in kyrielie.blockbard.organ.MOB_HEAD_BLOCKS
            val isSilenced = !isMobHead && !aboveState.isAir
            val isReachable = if (organMap != null) organMap.isReachable(pos) else (dist <= reachLimit)
            val isUntunable = NoteBlockRegistry.isUntunable(pos)

            when {
                // Non-Working 1: Silenced (obstruction directly above)
                isSilenced -> {
                    CuboidGizmo(box, styleOrange, false).emit(primitivesDepth, 1.0f)
                    val blockerName = aboveState.block.name.string
                    val label = "§6[NON-WORKING: SILENCED] §cBlocked: $blockerName"
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 1.0f)
                }

                // Non-Working 2: Out of reach from player
                !isReachable -> {
                    CuboidGizmo(box, styleOutOfReach, false).emit(primitivesOnTop, 1.0f)
                    val label = "§c[NON-WORKING: OUT OF REACH] §7(${String.format(java.util.Locale.US, "%.1f", dist)}m > ${String.format(java.util.Locale.US, "%.1f", reachLimit)}m)"
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesOnTop, 1.0f)
                }

                // Non-Working 3: Occluded or tuning failed
                isUntunable -> {
                    val reason = NoteBlockRegistry.getUntunableReason(pos)
                    val tag = reason?.tag ?: "UNTUNABLE"
                    CuboidGizmo(box, styleRed, false).emit(primitivesOnTop, 1.0f)
                    val target = activeTargets[pos]
                    val label = if (target != null) {
                        val needed = clicksNeeded(liveNote, target)
                        val targetName = midiNoteToName(noteIndexToMidi(entry.instrument, target))
                        "§c[NON-WORKING: $tag] §e${entry.instrument.name} §f$targetName §7(+$needed)"
                    } else {
                        "§c[NON-WORKING: $tag] §e${entry.instrument.name} §7(${midiNoteToName(entry.midiNote)})"
                    }
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesOnTop, 1.0f)
                }

                // Working 1: Mob head ambient block
                entry.status == NoteBlockStatus.MOB_HEAD -> {
                    CuboidGizmo(box, stylePurple, false).emit(primitivesDepth, 1.0f)
                    val label = "§d[WORKING] ${entry.instrument.name} §7(Mob Head)"
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 1.0f)
                }

                // Working 2: Assigned to active song
                activeTargets.containsKey(pos) -> {
                    val targetNote = activeTargets[pos]!!
                    val isTuned = liveNote == targetNote
                    if (isTuned) {
                        CuboidGizmo(box, styleGreen, false).emit(primitivesDepth, 1.0f)
                        val noteName = midiNoteToName(entry.midiNote)
                        val label = "§a[WORKING] ${entry.instrument.name} §f$noteName §7(✓ In Tune)"
                        TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 1.0f)
                    } else {
                        CuboidGizmo(box, styleAmber, false).emit(primitivesDepth, 1.0f)
                        val needed = clicksNeeded(liveNote, targetNote)
                        val targetName = midiNoteToName(noteIndexToMidi(entry.instrument, targetNote))
                        val label = "§e[WORKING] ${entry.instrument.name} §f$targetName §6(+$needed clicks)"
                        TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 1.0f)
                    }
                }

                // Working 3: Spare / Idle playable noteblock
                else -> {
                    CuboidGizmo(box, styleBlue, false).emit(primitivesDepth, 1.0f)
                    val noteName = midiNoteToName(entry.midiNote)
                    val label = "§b[WORKING] ${entry.instrument.name} §f$noteName §7(Spare)"
                    TextGizmo(textCenter, label, textStyleCenter).emit(primitivesDepth, 1.0f)
                }
            }
        }

        // Standing spot indicator
        val standPos = organMap?.standPos
        if (standPos != null) {
            val standBox = AABB(standPos)
            val standCenter = Vec3(standPos.x + 0.5, standPos.y + 1.25, standPos.z + 0.5)
            val isCentered = Math.abs(eyePos.x - (standPos.x + 0.5)) < 0.75 && Math.abs(eyePos.z - (standPos.z + 0.5)) < 0.75
            if (isCentered) {
                val styleCenterGreen = GizmoStyle.strokeAndFill(0xFF00FFCC.toInt(), 2.5f, 0x2200FFCC.toInt())
                CuboidGizmo(standBox, styleCenterGreen, false).emit(primitivesDepth, 1.0f)
                TextGizmo(standCenter, "§b[ORGAN CENTER] §aStand Spot (Centered)", textStyleCenter).emit(primitivesDepth, 1.0f)
            } else {
                val styleCenterWarning = GizmoStyle.strokeAndFill(0xFFFF4444.toInt(), 3.0f, 0x33FF4444.toInt())
                CuboidGizmo(standBox, styleCenterWarning, false).emit(primitivesOnTop, 1.0f)
                TextGizmo(standCenter, "§c[ORGAN CENTER] §eDRIFTED (Stand Here)", textStyleCenter).emit(primitivesOnTop, 1.0f)
            }
        }

        // Standard depth testing for normal blocks
        primitivesDepth.submit(collector, camera, false)
        // Always on top for occluded/problematic blocks so they are visible through walls
        primitivesOnTop.submit(collector, camera, true)
    }
}
