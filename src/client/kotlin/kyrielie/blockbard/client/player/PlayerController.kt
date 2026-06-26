package kyrielie.blockbard.client.player

import kyrielie.blockbard.organ.NoteBlockEntry
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.OrganMap
import kyrielie.blockbard.organ.ReachInfo
import kyrielie.blockbard.util.vecToYawPitch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory

/** Survival reach distance from eye position (blocks). */
const val REACH_DISTANCE = 4.5

sealed class CenterResult {
    object NoPlayer : CenterResult()
    object NoBlocks : CenterResult()
    data class Centered(
        val standPos: BlockPos,
        val reachableCount: Int,
        val totalFound: Int
    ) : CenterResult()
}

object PlayerController {

    private val logger = LoggerFactory.getLogger("BlockBard/PlayerController")

    var organMap: OrganMap? = null
        private set

    fun centerOnOrgan(): CenterResult {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("centerOnOrgan: no player")
            return CenterResult.NoPlayer
        }
        val world = mc.level ?: run {
            logger.warn("centerOnOrgan: no world")
            return CenterResult.NoPlayer
        }

        val playableBlocks = NoteBlockRegistry.allPlayable()
        logger.info("centerOnOrgan: ${playableBlocks.size} playable blocks found")
        if (playableBlocks.isEmpty()) return CenterResult.NoBlocks

        val avgX = playableBlocks.map { it.pos.x + 0.5 }.average()
        val avgZ = playableBlocks.map { it.pos.z + 0.5 }.average()
        val playerPos = player.blockPosition()
        val cX = avgX.toInt()
        val cZ = avgZ.toInt()
        val searchRadius = 6

        val candidates = mutableListOf<BlockPos>()
        for (x in -searchRadius..searchRadius) {
            for (z in -searchRadius..searchRadius) {
                val testPos = BlockPos(cX + x, playerPos.y, cZ + z)
                val feetState = world.getBlockState(testPos)
                val headState = world.getBlockState(testPos.above())
                val floorState = world.getBlockState(testPos.below())
                if (!feetState.isAir || !headState.isAir) continue
                if (!floorState.isSolid) continue
                candidates.add(testPos)
            }
        }
        logger.info("centerOnOrgan: ${candidates.size} stand position candidates")
        if (candidates.isEmpty()) candidates.add(playerPos)

        val best = candidates.maxByOrNull { standPos ->
            val eyePos = Vec3(standPos.x + 0.5, standPos.y + 1.62, standPos.z + 0.5)
            playableBlocks.count { entry ->
                val target = Vec3(entry.pos.x + 0.5, entry.pos.y + 1.0, entry.pos.z + 0.5)
                eyePos.distanceTo(target) <= REACH_DISTANCE
            }
        } ?: playerPos

        val eyePos = Vec3(best.x + 0.5, best.y + 1.62, best.z + 0.5)
        val reachMap = playableBlocks.associate { entry ->
            val target = Vec3(entry.pos.x + 0.5, entry.pos.y + 1.0, entry.pos.z + 0.5)
            val delta = target.subtract(eyePos)
            val distance = delta.length()
            val isReachable = distance <= REACH_DISTANCE
            val (yaw, pitch) = if (isReachable) vecToYawPitch(delta) else Pair(0f, 0f)
            entry.pos to ReachInfo(isReachable, yaw, pitch, distance)
        }

        organMap = OrganMap(best, reachMap)

        val reachable = reachMap.values.count { it.isReachable }
        logger.info("centerOnOrgan: standing at $best, $reachable/${playableBlocks.size} blocks reachable")

        if (best != playerPos) {
            player.setPos(Vec3(best.x + 0.5, best.y.toDouble(), best.z + 0.5))
            player.setYRot(player.getYRot())
            player.setXRot(player.getXRot())
            logger.info("centerOnOrgan: teleported player to $best")
        } else {
            logger.info("centerOnOrgan: player already at best position")
        }

        return CenterResult.Centered(best, reachable, playableBlocks.size)
    }

    fun interactWith(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("interactWith $pos: no player")
            return false
        }

        // ── Creative mode guard ──
        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE) {
            val msg = "§e[BlockBard] §cSkipping interact — Creative mode would break noteblocks!"
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg))
            logger.warn("interactWith $pos: blocked — player is in CREATIVE mode")
            return false
        }

        val reach = organMap?.getReachInfo(pos)
        if (reach != null && !reach.isReachable) {
            logger.warn("interactWith $pos: block is not reachable (distance=${reach.distance})")
            return false
        }

        val eyePos = player.eyePosition
        val target = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        val delta = target.subtract(eyePos)
        val distance = delta.length()
        if (distance > REACH_DISTANCE) {
            logger.warn("interactWith $pos: too far (distance=${"%.2f".format(distance)} > $REACH_DISTANCE)")
            return false
        }

        val (yaw, pitch) = reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta)
        player.setYRot(yaw)
        player.setXRot(pitch)

        // Compute the actual hit face from the approach direction
        val hitFace = when {
            Math.abs(delta.x) > Math.abs(delta.z) -> if (delta.x > 0) Direction.WEST else Direction.EAST
            else -> if (delta.z > 0) Direction.NORTH else Direction.SOUTH
        }

        val hitResult = BlockHitResult(target, hitFace, pos, false)
        logger.debug("interactWith $pos: firing useItemOn (dist=${"%.2f".format(distance)}, face=$hitFace, yaw=${"%.1f".format(yaw)}, pitch=${"%.1f".format(pitch)})")
        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
        return true
    }

    fun interactWith(entry: NoteBlockEntry): Boolean = interactWith(entry.pos)

    fun clearOrganMap() {
        logger.info("clearOrganMap: organ map cleared")
        organMap = null
    }
}
