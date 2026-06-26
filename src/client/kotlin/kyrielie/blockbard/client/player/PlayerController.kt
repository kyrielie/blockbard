
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

    // RESOLVED — checked against LocalPlayer.java in the decompiled source:
    // player.setPos() itself does NOT send any packet — it only mutates the client-side
    // entity. But LocalPlayer.tick() unconditionally calls sendPosition() every tick
    // (when isControlledCamera(), i.e. not spectating another entity, which is always
    // true for normal BlockBard usage), and sendPosition() compares the current position
    // against the last-sent one and fires a ServerboundMovePlayerPacket (Pos/PosRot)
    // whenever the delta exceeds a tiny threshold (2e-4 blocks squared). A setPos() call
    // from centerOnOrgan() therefore gets picked up and sent to the server automatically
    // on the very next client tick — no explicit packet send is needed here, and Baritone
    // (which also never calls setPos+send manually, only listens for the resulting
    // ServerboundMovePlayerPacket) confirms this is the normal way mods drive movement.
    //
    // The one way this CAN still go wrong: if interactWith() is ever called in the same
    // tick as centerOnOrgan() (before LocalPlayer.tick() has run again), the server would
    // see the click before it sees the new position. In the current design this can't
    // happen — centerOnOrgan() runs synchronously from a GUI button click, and all
    // interacts are dispatched later via ArpeggioScheduler.onTick() in END_CLIENT_TICK,
    // which is always at least one full tick later. So the original client/server position
    // desync hypothesis for items 2 and 4 is NOT confirmed by the source — no code fix is
    // needed here. If items 2/4 still reproduce on a public server after this logging pass,
    // the cause is something else (server-side anti-cheat reach checks, server-side click
    // rate limiting, an actual case of the same-tick ordering above, etc.) and the new
    // interactWith()/tickScale() logging from this round should make that visible.
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

        logEnvironmentBanner("centerOnOrgan")

        logger.info("centerOnOrgan: entry pos=${player.position()} yRot=${player.yRot} xRot=${player.xRot} ping=${currentPingMs()}")

        val playableBlocks = NoteBlockRegistry.allPlayable()
        logger.info("centerOnOrgan: ${playableBlocks.size} playable blocks found")
        if (playableBlocks.isEmpty()) return CenterResult.NoBlocks

        val playerPos = player.blockPosition()

        // Helper: count reachable blocks from a given stand position.
        // Uses player.getEyeHeight() (live, pose-aware) rather than a hardcoded 1.62 —
        // interactWith() uses player.eyePosition, which is also pose-aware. Using a
        // fixed constant here would silently disagree with interactWith if the player
        // is crouching (or otherwise not in the STANDING pose) at Center time, which is
        // a real candidate for the "wrong aim" bug in item 1: the precomputed reach/yaw/
        // pitch would be built from the wrong eye height and stay wrong until re-centered.
        fun countReachable(standPos: BlockPos): Int {
            val eyePos = Vec3(standPos.x + 0.5, standPos.y + player.getEyeHeight().toDouble(), standPos.z + 0.5)
            return playableBlocks.count { entry ->
                val target = Vec3(entry.pos.x + 0.5, entry.pos.y + 1.0, entry.pos.z + 0.5)
                eyePos.distanceTo(target) <= REACH_DISTANCE
            }
        }

        // Always start by evaluating the player's current block
        val currentBlockReachable = countReachable(playerPos)
        logger.info("centerOnOrgan: current block $playerPos covers $currentBlockReachable/${playableBlocks.size} blocks")

        // If the player's current block already covers everything, just snap to block center — no teleport
        if (currentBlockReachable == playableBlocks.size) {
            logger.info("centerOnOrgan: current block is optimal — snapping to block center only")
            val centeredX = playerPos.x + 0.5
            val centeredZ = playerPos.z + 0.5
            val currentX = player.x
            val currentZ = player.z
            logger.info("centerOnOrgan: [optimal branch] before setPos pos=${player.position()}")
            // Only move if not already within the block (within 0.4 of center)
            if (Math.abs(currentX - centeredX) > 0.05 || Math.abs(currentZ - centeredZ) > 0.05) {
                player.setPos(Vec3(centeredX, player.y, centeredZ))
                logger.info("centerOnOrgan: snapped to center of current block $playerPos")
            } else {
                logger.info("centerOnOrgan: already centered in block — no movement")
            }
            // Re-read rather than assume the setPos value took — something else (collision,
            // server correction, another mod) may override it same-tick.
            logger.info("centerOnOrgan: [optimal branch] after setPos pos=${player.position()} (re-read, not assumed)")
            buildOrganMap(playableBlocks, playerPos, player.getEyeHeight())
            return CenterResult.Centered(playerPos, currentBlockReachable, playableBlocks.size)
        }

        // Current block isn't optimal — search nearby for a better stand position
        val avgX = playableBlocks.map { it.pos.x + 0.5 }.average()
        val avgZ = playableBlocks.map { it.pos.z + 0.5 }.average()
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
                if (!floorState.canOcclude()) continue
                candidates.add(testPos)
            }
        }

        // Add current block as a candidate so it's always considered
        if (playerPos !in candidates) candidates.add(playerPos)
        logger.info("centerOnOrgan: ${candidates.size} stand position candidates")

        val best = candidates.maxByOrNull { countReachable(it) } ?: playerPos
        val bestReachable = countReachable(best)

        buildOrganMap(playableBlocks, best, player.getEyeHeight())

        logger.info("centerOnOrgan: [search branch] before setPos pos=${player.position()} chosenStand=$best")
        if (best == playerPos) {
            // Best position is the current block — just snap to center
            val centeredX = playerPos.x + 0.5
            val centeredZ = playerPos.z + 0.5
            player.setPos(Vec3(centeredX, player.y, centeredZ))
            logger.info("centerOnOrgan: current block is best ($bestReachable reachable) — snapped to center")
        } else {
            // Need to move to a different block
            player.setPos(Vec3(best.x + 0.5, best.y.toDouble(), best.z + 0.5))
            logger.info("centerOnOrgan: moved to $best ($bestReachable/${playableBlocks.size} reachable)")
        }
        // Re-read rather than assume — if this differs from what was just set, something
        // else (collision, server correction, another mod) is overriding the position.
        logger.info("centerOnOrgan: [search branch] after setPos pos=${player.position()} (re-read, not assumed)")

        return CenterResult.Centered(best, bestReachable, playableBlocks.size)
    }

    private fun buildOrganMap(playableBlocks: List<kyrielie.blockbard.organ.NoteBlockEntry>, standPos: BlockPos, eyeHeight: Float) {
        val eyePos = Vec3(standPos.x + 0.5, standPos.y + eyeHeight.toDouble(), standPos.z + 0.5)
        val reachMap = playableBlocks.associate { entry ->
            val target = Vec3(entry.pos.x + 0.5, entry.pos.y + 1.0, entry.pos.z + 0.5)
            val delta = target.subtract(eyePos)
            val distance = delta.length()
            val isReachable = distance <= REACH_DISTANCE
            val (yaw, pitch) = if (isReachable) vecToYawPitch(delta) else Pair(0f, 0f)
            entry.pos to ReachInfo(isReachable, yaw, pitch, distance)
        }
        organMap = OrganMap(standPos, reachMap)
        logger.info("buildOrganMap: ${reachMap.values.count { it.isReachable }}/${playableBlocks.size} reachable from $standPos (eyeHeight=$eyeHeight)")
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
        player.setYRot(yaw)
        player.setXRot(pitch)
        primedRotation = Triple(pos, yaw, pitch)
        logger.debug("primeRotation $pos: yaw=${"%.1f".format(yaw)} pitch=${"%.1f".format(pitch)}")
    }

    /**
     * Sends a right-click interact to a noteblock.
     * Uses Direction.UP always — noteblocks advance their note via useWithoutItem,
     * which doesn't care about hit face direction.
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

        // Phase 1: always use live distance — the organMap reachability flag was computed at
        // Center time from a stand position the server may have since corrected. A block marked
        // unreachable in the map can still be within actual reach if the player is at a slightly
        // different position, and blocking on the stale flag was silently dropping every note on
        // public servers where setPos() is overridden by server position correction.
        if (organMap == null) {
            logger.warn("interactWith $pos: organMap is null (Center not pressed or cleared) — using live distance only")
        }

        val eyePos = player.eyePosition
        val target = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        val delta = target.subtract(eyePos)
        val distance = delta.length()

        // Log map distance alongside live distance so stale-map divergence is visible in logs.
        val reach = organMap?.getReachInfo(pos)
        val mapDistStr = reach?.distance?.let { "%.2f".format(it) } ?: "n/a(no map)"
        if (distance > REACH_DISTANCE) {
            logger.warn(
                "interactWith $pos: too far liveDist=${"%.2f".format(distance)} mapDist=$mapDistStr " +
                    "clientPos=${player.position()} ping=${currentPingMs()}"
            )
            return false
        }

        // Phase 2: rotation was primed one tick earlier by primeRotation() — read it back
        // rather than recomputing, so the yaw/pitch sent in this tick's use packet matches
        // exactly what was in the previous tick's movement packet (which the server used for
        // hit validation). Fall back to live computation if primed rotation is stale/absent.
        val (yaw, pitch) = primedRotation?.takeIf { it.first == pos }
            ?.let { Pair(it.second, it.third) }
            ?: (reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta))
        primedRotation = null

        // Direction.UP is correct for noteblocks — useWithoutItem ignores hit face
        val hitResult = BlockHitResult(target, Direction.UP, pos, false)
        logger.debug(
            "interactWith $pos: useItemOn liveDist=${"%.2f".format(distance)} mapDist=$mapDistStr " +
                "yaw=${"%.1f".format(yaw)} pitch=${"%.1f".format(pitch)} ping=${currentPingMs()}"
        )
        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
        return true
    }

    fun interactWith(entry: NoteBlockEntry): Boolean = interactWith(entry.pos)

    fun clearOrganMap() {
        logger.info("clearOrganMap: organ map cleared")
        organMap = null
    }
}
