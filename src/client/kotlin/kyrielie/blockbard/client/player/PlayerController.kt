
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
import net.minecraft.world.phys.HitResult
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

    /** Returns current server round-trip ping in ms, or 0 if unavailable. */
    fun currentPingMs(): Int {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0
        return mc.connection?.getPlayerInfo(player.gameProfile.id)?.latency ?: 0
    }

    /**
     * Logged once at scan/center/tune start so logs are self-describing about which
     * environment (singleplayer vs. a real server) produced them — needed to correlate
     * the client/server desync hypothesis against local-test vs public-server logs.
     *
     * Verified against Minecraft.java / ClientCommonPacketListenerImpl.java in the
     * decompiled source: Minecraft.isLocalServer() and
     * connection.serverBrand() are both real, current APIs.
     */
    fun logEnvironmentBanner(context: String) {
        val mc = Minecraft.getInstance()
        val brand = mc.player?.connection?.serverBrand()
        logger.info(
            "[$context] environment banner: singleplayer=${mc.isLocalServer} serverBrand=$brand " +
                "ping=${currentPingMs()} dimension=${mc.level?.dimension()?.identifier()}"
        )
    }

    /**
     * Builds the OrganMap from the player's current actual position.
     *
     * Does NOT move the player. setPos() is rejected by public servers (Netherite-Paper /
     * Velocity rubber-bands the player back within one tick), so any position we set is
     * immediately overwritten before the first interact fires. The map is built from
     * wherever the player is standing right now — which is where they'll actually be
     * during playback.
     */
    fun centerOnOrgan(): CenterResult {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("centerOnOrgan: no player")
            return CenterResult.NoPlayer
        }

        logEnvironmentBanner("centerOnOrgan")

        val playableBlocks = NoteBlockRegistry.allPlayable()
        logger.info("centerOnOrgan: ${playableBlocks.size} playable blocks, player at ${player.position()} eyeHeight=${player.getEyeHeight()} ping=${currentPingMs()}")
        if (playableBlocks.isEmpty()) return CenterResult.NoBlocks

        val playerPos = player.blockPosition()
        buildOrganMap(playableBlocks, playerPos, player.getEyeHeight())

        val map = organMap!!
        val reachableCount = map.reachableCount()
        val unreachable = playableBlocks.filter { !map.isReachable(it.pos) }
        if (unreachable.isNotEmpty()) {
            logger.warn("centerOnOrgan: ${unreachable.size} blocks out of reach from $playerPos:")
            unreachable.forEach { e ->
                val dist = player.eyePosition.distanceTo(Vec3(e.pos.x + 0.5, e.pos.y + 1.0, e.pos.z + 0.5))
                logger.warn("  unreachable: ${e.pos} dist=${"%.2f".format(dist)} instrument=${e.instrument.name}")
            }
        }

        logger.info("centerOnOrgan: map built — $reachableCount/${playableBlocks.size} reachable from player position")
        return CenterResult.Centered(playerPos, reachableCount, playableBlocks.size)
    }

    private fun buildOrganMap(playableBlocks: List<kyrielie.blockbard.organ.NoteBlockEntry>, standPos: BlockPos, eyeHeight: Float) {
        // Use actual player position for the eye, not block-center. The player is never
        // exactly at block center on a public server (setPos is rubber-banded), so computing
        // reach from block-center produces distances that disagree with the live check in
        // interactWith(). We still receive standPos for the BlockPos record in OrganMap.
        val mc = Minecraft.getInstance()
        val actualEyePos = mc.player?.eyePosition
            ?: Vec3(standPos.x + 0.5, standPos.y + eyeHeight.toDouble(), standPos.z + 0.5)
        val reachMap = playableBlocks.associate { entry ->
            val target = Vec3(entry.pos.x + 0.5, entry.pos.y + 1.0, entry.pos.z + 0.5)
            val delta = target.subtract(actualEyePos)
            val distance = delta.length()
            val isReachable = distance <= REACH_DISTANCE
            val (yaw, pitch) = if (isReachable) vecToYawPitch(delta) else Pair(0f, 0f)
            entry.pos to ReachInfo(isReachable, yaw, pitch, distance)
        }
        organMap = OrganMap(standPos, reachMap)
        logger.info("buildOrganMap: ${reachMap.values.count { it.isReachable }}/${playableBlocks.size} reachable from actualEye=$actualEyePos (standPos=$standPos)")
    }

    /**
     * Phase 2: holds the rotation computed for the next pending note.
     * Triple is (targetPos, yaw, pitch). Set by primeRotation() one tick before
     * interactWith() fires, so the rotation packet reaches the server before the
     * use packet — matching Baritone's PRE/POST player-update hook pattern.
     * Consumed and cleared by interactWith(); null if no note is pending.
     */
    private var primedRotation: Triple<BlockPos, Float, Float>? = null

    /**
     * Aims the player at [pos] and records the rotation for this tick's movement packet.
     * Call this BEFORE ArpeggioScheduler.onTick() in END_CLIENT_TICK so the server
     * receives the rotation in the movement packet one tick before the use packet.
     */
    fun primeRotation(pos: BlockPos) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val eyePos = player.eyePosition
        val target = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        val delta = target.subtract(eyePos)
        val reach = organMap?.getReachInfo(pos)
        val (yaw, pitch) = reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta)
        val yRotBefore = player.yRot
        val xRotBefore = player.xRot
        player.setYRot(yaw)
        player.setXRot(pitch)
        val yRotAfter = player.yRot
        val xRotAfter = player.xRot
        primedRotation = Triple(pos, yaw, pitch)
        // Rotation readback: if after != what we set, something is overriding us this tick.
        if (yRotAfter != yaw || xRotAfter != pitch) {
            logger.warn(
                "primeRotation $pos: rotation did not take! wanted yaw=${"%.2f".format(yaw)} pitch=${"%.2f".format(pitch)}" +
                    " got yaw=${"%.2f".format(yRotAfter)} pitch=${"%.2f".format(xRotAfter)}" +
                    " (before: yaw=${"%.2f".format(yRotBefore)} pitch=${"%.2f".format(xRotBefore)})"
            )
        } else {
            logger.debug(
                "primeRotation $pos: yaw ${"%.2f".format(yRotBefore)}->${"%.2f".format(yRotAfter)}" +
                    " pitch ${"%.2f".format(xRotBefore)}->${"%.2f".format(xRotAfter)}"
            )
        }
    }

    /**
     * Sends a right-click interact to a noteblock.
     *
     * IMPORTANT: The player must have empty hands for useWithoutItem to be reached.
     * If the player holds any item, performUseItemOn() may short-circuit before
     * calling useWithoutItem, and the note will NOT advance.
     */
    fun interactWith(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("interactWith $pos: no player")
            return false
        }

        // ── Creative / Spectator guard ──
        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            val msg = "§e[BlockBard] §cCannot interact in ${gameMode.getName()} mode"
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg))
            logger.warn("interactWith $pos: blocked — player is in $gameMode mode")
            return false
        }

        if (organMap == null) {
            logger.warn("interactWith $pos: organMap is null — Center not pressed yet")
        }

        val eyePos = player.eyePosition
        val target = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        val delta = target.subtract(eyePos)
        val distance = delta.length()
        val reach = organMap?.getReachInfo(pos)
        val mapDistStr = reach?.distance?.let { "%.2f".format(it) } ?: "n/a"

        if (distance > REACH_DISTANCE) {
            logger.warn(
                "interactWith $pos: too far liveDist=${"%.2f".format(distance)} mapDist=$mapDistStr " +
                    "clientPos=${player.position()} ping=${currentPingMs()}"
            )
            return false
        }

        // Determine target rotation — prefer primed value (set one tick earlier in START_CLIENT_TICK),
        // fall back to map precompute, fall back to live computation.
        val (yaw, pitch) = primedRotation?.takeIf { it.first == pos }
            ?.let { Pair(it.second, it.third) }
            ?: (reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta))
        primedRotation = null

        // Re-apply rotation immediately before firing useItemOn — same approach as the old
        // working version. The primeRotation in START_CLIENT_TICK gets the rotation into the
        // movement packet; re-applying here ensures the rotation is correct for the use packet
        // regardless of whether anything overwrote it between START and END tick.
        val yRotBefore = player.yRot
        val xRotBefore = player.xRot
        player.setYRot(yaw)
        player.setXRot(pitch)
        val yRotAfter = player.yRot
        val xRotAfter = player.xRot
        if (yRotAfter != yaw || xRotAfter != pitch) {
            logger.warn(
                "interactWith $pos: rotation override detected — wanted yaw=${"%.2f".format(yaw)} pitch=${"%.2f".format(pitch)}" +
                    " got yaw=${"%.2f".format(yRotAfter)} pitch=${"%.2f".format(xRotAfter)}"
            )
        }
        logger.info(
            "interactWith $pos: rot ${"%.2f".format(yRotBefore)},${"%.2f".format(xRotBefore)}" +
                " -> ${"%.2f".format(yRotAfter)},${"%.2f".format(xRotAfter)}" +
                " liveDist=${"%.2f".format(distance)} mapDist=$mapDistStr ping=${currentPingMs()}"
        )

        // Derive hit face from approach direction — same as old working version.
        // Direction.UP is wrong for blocks approached horizontally.
        val hitFace = when {
            Math.abs(delta.x) > Math.abs(delta.y) && Math.abs(delta.x) > Math.abs(delta.z) ->
                if (delta.x > 0) Direction.WEST else Direction.EAST
            Math.abs(delta.y) > Math.abs(delta.z) ->
                if (delta.y > 0) Direction.DOWN else Direction.UP
            else ->
                if (delta.z > 0) Direction.NORTH else Direction.SOUTH
        }

        // Use mc.hitResult when it's pointing at our block — the server-side validation
        // may check that the hit position is consistent with the player's facing.
        val liveHit = mc.hitResult
        val hitResult: BlockHitResult = if (
            liveHit is BlockHitResult &&
            liveHit.blockPos == pos &&
            liveHit.type == HitResult.Type.BLOCK
        ) {
            logger.debug("interactWith $pos: using live hitResult face=${liveHit.direction}")
            liveHit
        } else {
            val liveDesc = when (liveHit) {
                is BlockHitResult -> "BlockHit(${liveHit.blockPos},${liveHit.direction})"
                null -> "null"
                else -> liveHit.type.name
            }
            logger.debug("interactWith $pos: hitResult not on target ($liveDesc) — using synthetic face=$hitFace")
            BlockHitResult(target, hitFace, pos, false)
        }

        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
        return true
    }

    fun interactWith(entry: NoteBlockEntry): Boolean = interactWith(entry.pos)

    fun clearOrganMap() {
        logger.info("clearOrganMap: organ map cleared")
        organMap = null
    }
}
