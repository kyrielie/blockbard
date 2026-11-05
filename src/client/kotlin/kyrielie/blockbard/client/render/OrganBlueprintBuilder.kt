package kyrielie.blockbard.client.render

import kyrielie.blockbard.util.blockBelowHint
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
import kotlin.math.abs

data class BlueprintNode(
    val pos: BlockPos,
    val instrument: NoteBlockInstrument,
    val noteIndex: Int
)

/**
 * Organ Blueprint & Stage Builder Guide.
 *
 * Generates an optimal 3D spherical noteblock stage layout centered on the player
 * (adapted from SongPlayer's spherical stage design). Renders ghost outlines and foundation
 * material hints for unbuilt blocks to guide players in constructing complete organs in survival.
 */
object OrganBlueprintBuilder {

    private val logger = LoggerFactory.getLogger("BlockBard/OrganBlueprintBuilder")

    var isEnabled: Boolean = false
    var anchorPos: BlockPos? = null

    private val blueprintNodes = mutableListOf<BlueprintNode>()

    fun register() {
        LevelRenderEvents.BEFORE_GIZMOS.register { context: LevelRenderContext ->
            if (!isEnabled || blueprintNodes.isEmpty()) return@register
            render(context)
        }
        logger.info("OrganBlueprintBuilder registered with LevelRenderEvents.BEFORE_GIZMOS")
    }

    fun toggle(): Boolean {
        isEnabled = !isEnabled
        if (isEnabled && blueprintNodes.isEmpty()) {
            generateBlueprint()
        }
        logger.info("Organ blueprint toggled: {}", if (isEnabled) "ENABLED" else "DISABLED")
        return isEnabled
    }

    fun clear() {
        isEnabled = false
        blueprintNodes.clear()
        anchorPos = null
        logger.info("Organ blueprint cleared")
    }

    fun totalPlanned(): Int = blueprintNodes.size

    fun generateBlueprint(center: BlockPos? = null) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val origin = center ?: player.blockPosition()
        anchorPos = origin
        blueprintNodes.clear()

        val rawPositions = computeSphericalLocations(origin)
        logger.info("Calculated ${rawPositions.size} reachable spherical stage positions around $origin")

        // Distribute vanilla playable instruments across the spherical layout
        val instruments = NoteBlockInstrument.entries.filter { it.isTunable }
        var posIndex = 0

        for (inst in instruments) {
            for (note in 0..24) {
                if (posIndex >= rawPositions.size) break
                val pos = rawPositions[posIndex++]
                blueprintNodes.add(BlueprintNode(pos, inst, note))
            }
            if (posIndex >= rawPositions.size) break
        }

        logger.info("Generated blueprint with ${blueprintNodes.size} planned noteblocks across ${instruments.size} instruments")
    }

    private fun computeSphericalLocations(origin: BlockPos): List<BlockPos> {
        val positions = mutableListOf<BlockPos>()
        val yLayers = intArrayOf(-4, -2, -1, 0, 1, 2, 3, 4, 5, 6)

        for (dx in -5..5) {
            for (dz in -5..5) {
                val adx = abs(dx)
                val adz = abs(dz)
                for (dy in yLayers) {
                    when (dy) {
                        -4 -> {
                            if (adx < 3 && adz < 3) positions.add(origin.offset(dx, dy, dz))
                            else if ((adx == 3 != (adz == 3)) && (adx == 0 != (adz == 0))) positions.add(origin.offset(dx, dy, dz))
                        }
                        -2 -> {
                            if (adz == 0 && adx == 0) continue
                            if (adz * adx > 9) continue
                            if (adz + adx == 5 && adx != 0 && adz != 0) {
                                positions.add(origin.offset(dx, dy + 1, dz))
                                positions.add(origin.offset(dx, dy - 1, dz))
                            } else if (adz * adx == 3) {
                                positions.add(origin.offset(dx, dy + 1, dz))
                                positions.add(origin.offset(dx, dy - 1, dz))
                            } else if (adx < 3 && adz < 3 && adx + adz > 0) {
                                positions.add(origin.offset(dx, dy, dz))
                            } else if ((adz == 0) != (adx == 0)) {
                                positions.add(origin.offset(dx, dy, dz))
                            } else if (adz * adx == 10 || adz + adx == 6) {
                                positions.add(origin.offset(dx, dy, dz))
                            }
                        }
                        -1 -> {
                            if (adx + adz == 7 || adx + adz == 0) positions.add(origin.offset(dx, dy, dz))
                        }
                        0 -> {
                            val check = adx + adz
                            if ((check == 8 || check == 6) && adx * adz > 5) positions.add(origin.offset(dx, dy, dz))
                        }
                        1 -> {
                            val check = adx + adz
                            if (check == 7 || check == 3 || check == 2) positions.add(origin.offset(dx, dy, dz))
                            else if (((adx == 5) != (adz == 5)) && check < 7) positions.add(origin.offset(dx, dy, dz))
                            else if (check == 4 && adx * adz != 0) positions.add(origin.offset(dx, dy, dz))
                        }
                        2 -> {
                            if (adx != 5 && adz != 5) {
                                val check = adx + adz
                                if (check == 8 || check == 6 || check == 5 || check == 1) positions.add(origin.offset(dx, dy, dz))
                                else if (check == 4 && ((adx == 0) != (adz == 0))) positions.add(origin.offset(dx, dy, dz))
                            }
                        }
                        3 -> {
                            if (adx * adz == 12 || adx + adz == 0) positions.add(origin.offset(dx, dy, dz))
                            else if (((adx == 5) != (adz == 5)) && ((adx < 2) != (adz < 2))) positions.add(origin.offset(dx, dy, dz))
                            else if (adx <= 3 && adz <= 3 && adx + adz > 1 && adx + adz < 5) positions.add(origin.offset(dx, dy, dz))
                        }
                        4 -> {
                            if (adx != 5 && adz != 5) {
                                val check = adx + adz
                                if (check == 4 && adx * adz == 0) positions.add(origin.offset(dx, dy, dz))
                                else if (check == 1 || check == 5 || check == 6) positions.add(origin.offset(dx, dy, dz))
                            }
                        }
                        5 -> {
                            if (adx <= 3 && adz <= 3) {
                                val check = adx + adz
                                if (check in 2..4) positions.add(origin.offset(dx, dy, dz))
                            }
                        }
                        6 -> {
                            if (adx + adz < 2) positions.add(origin.offset(dx, dy, dz))
                        }
                    }
                }
            }
        }

        // Sort by distance from player eye
        val eyeVec = Vec3(origin.x + 0.5, origin.y + 1.62, origin.z + 0.5)
        return positions.distinct().sortedBy { Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5).distanceTo(eyeVec) }
    }

    private fun render(context: LevelRenderContext) {
        val collector = context.submitNodeCollector()
        val camera = context.levelState().cameraRenderState
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val primitives = DrawableGizmoPrimitives()

        val ghostStyle = GizmoStyle.strokeAndFill(0xCC00FFAA.toInt(), 1.5f, 0x1800FFAA.toInt())
        val textStyle = TextGizmo.Style.whiteAndCentered().withScale(0.85f)

        var renderedCount = 0
        val maxRender = 100 // Render closest missing blocks to avoid visual clutter

        for (node in blueprintNodes) {
            val state = level.getBlockState(node.pos)
            // If already a noteblock, check if foundation matches
            if (state.`is`(Blocks.NOTE_BLOCK)) {
                val currentInst = state.getValue(NoteBlock.INSTRUMENT)
                if (currentInst == node.instrument) continue // already built!
            }

            if (renderedCount++ > maxRender) break

            val box = AABB(node.pos)
            CuboidGizmo(box, ghostStyle, false).emit(primitives, 1.0f)

            val textCenter = Vec3(node.pos.x + 0.5, node.pos.y + 1.15, node.pos.z + 0.5)
            val label = "§b${node.instrument.name}\n§eBase: ${node.instrument.blockBelowHint}"
            TextGizmo(textCenter, label, textStyle).emit(primitives, 1.0f)
        }

        primitives.submit(collector, camera, false)
    }
}
