package kyrielie.blockbard.client.player

import kyrielie.blockbard.organ.NoteBlockEntry
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.organ.OrganMap
import kyrielie.blockbard.organ.ReachInfo
import kyrielie.blockbard.util.BlockBardLogBuffer
import kyrielie.blockbard.util.DebugLog
import kyrielie.blockbard.util.vecToYawPitch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import kotlin.math.abs

/** Survival reach distance from eye position (blocks). */
const val REACH_DISTANCE = 4.5

/**
 * Maximum yaw/pitch change allowed in a single tick while priming rotation toward a
 * target. Many anticheat plugins flag single-tick rotation deltas beyond a threshold
 * as a "rotation hack" signature. Easing toward the target at a capped rate removes
 * the signature without slowing down playback overall, since interactWith() is only
 * invoked once the eased rotation has converged (see rotationConverged()).
 *
 * var, not const val -- set from BlockBardConfig.maxRotationDegreesPerTick at init.
 */
var MAX_ROTATION_DEGREES_PER_TICK = 35f

/**
 * How close yaw/pitch must be to the target (degrees) before a note is considered
 * aimed. var -- set from BlockBardConfig.rotationConvergenceThresholdDegrees at init.
 */
var ROTATION_CONVERGENCE_THRESHOLD_DEGREES = 2f

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

    fun logEnvironmentBanner(context: String) {
        val mc = Minecraft.getInstance()
        val brand = mc.player?.connection?.serverBrand()
        logger.info(
            "[$context] environment banner: singleplayer=${mc.isLocalServer} serverBrand=$brand " +
                "ping=${currentPingMs()} dimension=${mc.level?.dimension()?.identifier()}"
        )
    }

    /**
     * Checks if the cached organMap is still valid for the player's current position.
     * Returns false if organMap is null or player has moved from standPos.
     */
    fun isOrganMapValid(): Boolean {
        val map = organMap ?: return false
        val player = Minecraft.getInstance().player ?: return false
        val currentPos = player.blockPosition()
        return currentPos.distManhattan(map.standPos) <= 1
    }

    /**
     * Reconfirms noteblocks around the player:
     * 1. Performs a live scan of blockstates around the player's current position.
     * 2. Rebuilds the OrganMap from the player's live eye position (without moving the player).
     * Returns true if playable blocks were found and at least one is reachable.
     */
    fun reconfirmOrgan(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("reconfirmOrgan: no player")
            return false
        }
        kyrielie.blockbard.client.organ.OrganScanner.scan()
        val result = centerOnOrgan(movePlayer = false)
        return when (result) {
            is CenterResult.Centered -> result.reachableCount > 0
            else -> false
        }
    }

    /**
     * Builds or recalibrates the OrganMap from the player's position.
     * When [movePlayer] is true (governed by ConfigManager.config.centerMovesPlayer),
     * physically snaps the player to the center of the standing block.
     * When false (default), calibrates the reach map from the player's current position without moving.
     */
    fun centerOnOrgan(movePlayer: Boolean = kyrielie.blockbard.client.config.ConfigManager.config.centerMovesPlayer): CenterResult {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            val err = "centerOnOrgan: no player found"
            logger.warn(err)
            BlockBardLogBuffer.warn("PlayerController", err)
            return CenterResult.NoPlayer
        }

        logEnvironmentBanner("centerOnOrgan")

        val playableBlocks = NoteBlockRegistry.allPlayable()
        if (playableBlocks.isEmpty()) {
            val err = "centerOnOrgan: no playable noteblocks found in registry"
            logger.warn(err)
            BlockBardLogBuffer.warn("PlayerController", err)
            return CenterResult.NoBlocks
        }

        val playerPos = player.blockPosition()
        val eyeHeight = player.getEyeHeight()
        val standPos: BlockPos

        if (movePlayer) {
            // Search candidate positions in a 5x5 horizontal grid around player
            // to find the position that maximizes reachable playable noteblocks.
            var bestPos = playerPos
            var maxReachable = -1
            var bestDistSq = Double.MAX_VALUE

            for (dx in -2..2) {
                for (dz in -2..2) {
                    val candidate = BlockPos(playerPos.x + dx, playerPos.y, playerPos.z + dz)
                    val candEye = Vec3(candidate.x + 0.5, candidate.y + eyeHeight.toDouble(), candidate.z + 0.5)
                    val reachable = playableBlocks.count { entry ->
                        val target = Vec3(entry.pos.x + 0.5, entry.pos.y + 0.5, entry.pos.z + 0.5)
                        target.distanceTo(candEye) <= (REACH_DISTANCE + 0.5)
                    }
                    val distSq = (dx * dx + dz * dz).toDouble()
                    if (reachable > maxReachable || (reachable == maxReachable && distSq < bestDistSq)) {
                        maxReachable = reachable
                        bestDistSq = distSq
                        bestPos = candidate
                    }
                }
            }

            val targetX = bestPos.x + 0.5
            val targetZ = bestPos.z + 0.5
            val moved = player.x != targetX || player.z != targetZ
            player.setPos(targetX, player.y, targetZ)
            player.setDeltaMovement(0.0, 0.0, 0.0)
            standPos = bestPos

            val moveDesc = if (moved) "Centered player at ($targetX, ${player.y}, $targetZ)" else "Player already at center ($targetX, ${player.y}, $targetZ)"
            logger.info("centerOnOrgan: $moveDesc")
            BlockBardLogBuffer.info("PlayerController", moveDesc)
        } else {
            standPos = playerPos
            logger.info("centerOnOrgan: calibrated from player pos $standPos (player not moved)")
            BlockBardLogBuffer.info("PlayerController", "Calibrated from player pos $standPos (player not moved)")
        }

        buildOrganMap(playableBlocks, standPos, eyeHeight)

        val map = organMap!!
        val reachableCount = map.reachableCount()
        val unreachable = playableBlocks.filter { !map.isReachable(it.pos) }

        val statusMsg = "Reachable: $reachableCount/${playableBlocks.size} from $standPos"
        logger.info("centerOnOrgan: $statusMsg")
        BlockBardLogBuffer.info("PlayerController", statusMsg)

        if (unreachable.isNotEmpty()) {
            val unreachSummary = unreachable.joinToString("; ") { e ->
                val dist = player.eyePosition.distanceTo(Vec3(e.pos.x + 0.5, e.pos.y + 0.5, e.pos.z + 0.5))
                "${e.pos} (${e.instrument.name}, dist=${"%.2f".format(dist)})"
            }
            logger.warn("centerOnOrgan: ${unreachable.size} blocks out of reach: $unreachSummary")
            BlockBardLogBuffer.warn("PlayerController", "${unreachable.size} blocks out of reach: $unreachSummary")
        }

        return CenterResult.Centered(standPos, reachableCount, playableBlocks.size)
    }

    private fun buildOrganMap(playableBlocks: List<kyrielie.blockbard.organ.NoteBlockEntry>, standPos: BlockPos, eyeHeight: Float) {
        val mc = Minecraft.getInstance()
        val actualEyePos = mc.player?.eyePosition
            ?: Vec3(standPos.x + 0.5, standPos.y + eyeHeight.toDouble(), standPos.z + 0.5)
        val reachMap = playableBlocks.associate { entry ->
            val target = Vec3(entry.pos.x + 0.5, entry.pos.y + 0.5, entry.pos.z + 0.5)
            val delta = target.subtract(actualEyePos)
            val distance = delta.length()
            val isReachable = distance <= (REACH_DISTANCE + 0.5)
            val (yaw, pitch) = if (isReachable) vecToYawPitch(delta) else Pair(0f, 0f)
            entry.pos to ReachInfo(isReachable, yaw, pitch, distance)
        }
        organMap = OrganMap(standPos, reachMap)
        logger.info("buildOrganMap: ${reachMap.values.count { it.isReachable }}/${playableBlocks.size} reachable from actualEye=$actualEyePos (standPos=$standPos)")
    }

    /**
     * Phase 2: holds the rotation computed for the next pending note.
     * Triple is (targetPos, yaw, pitch). Set/updated by primeRotation() every tick the
     * note is pending, eased toward the target. Consumed and cleared by aimAt().
     */
    private var primedRotation: Triple<BlockPos, Float, Float>? = null

    /**
     * Eases yaw/pitch toward [pos] by at most MAX_ROTATION_DEGREES_PER_TICK per call.
     * Call every tick a note is pending (START_CLIENT_TICK, before ArpeggioScheduler.onTick()).
     * Use rotationConverged(pos) to check whether it is safe to fire the interact yet.
     */
    fun primeRotation(pos: BlockPos) {
        if (!kyrielie.blockbard.client.config.ConfigManager.config.smoothCameraRotation) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val eyePos = player.eyePosition
        val target = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        val delta = target.subtract(eyePos)
        val reach = organMap?.getReachInfo(pos)
        val (targetYaw, targetPitch) = reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta)

        val yRotBefore = player.yRot
        val xRotBefore = player.xRot

        val yawStep = clampedStep(yRotBefore, targetYaw)
        val pitchStep = clampedStep(xRotBefore, targetPitch)
        val newYaw = Mth.wrapDegrees(yRotBefore + yawStep)
        val newPitch = (xRotBefore + pitchStep).coerceIn(-90f, 90f)

        player.setYRot(newYaw)
        player.setXRot(newPitch)
        val yRotAfter = player.yRot
        val xRotAfter = player.xRot
        primedRotation = Triple(pos, targetYaw, targetPitch)

        if (yRotAfter != newYaw || xRotAfter != newPitch) {
            logger.warn(
                "primeRotation $pos: rotation did not take! wanted yaw=${"%.2f".format(newYaw)} pitch=${"%.2f".format(newPitch)}" +
                    " got yaw=${"%.2f".format(yRotAfter)} pitch=${"%.2f".format(xRotAfter)}" +
                    " (before: yaw=${"%.2f".format(yRotBefore)} pitch=${"%.2f".format(xRotBefore)})"
            )
        } else {
            DebugLog.info(logger) {
                "primeRotation $pos: yaw ${"%.2f".format(yRotBefore)}->${"%.2f".format(yRotAfter)}" +
                    " (target ${"%.2f".format(targetYaw)}) pitch ${"%.2f".format(xRotBefore)}->${"%.2f".format(xRotAfter)}" +
                    " (target ${"%.2f".format(targetPitch)})"
            }
        }
    }

    /** Signed shortest-path step from [from] toward [to], capped at MAX_ROTATION_DEGREES_PER_TICK. */
    private fun clampedStep(from: Float, to: Float): Float {
        val diff = Mth.wrapDegrees(to - from)
        return diff.coerceIn(-MAX_ROTATION_DEGREES_PER_TICK, MAX_ROTATION_DEGREES_PER_TICK)
    }

    /**
     * Returns true once the player's current rotation is within
     * ROTATION_CONVERGENCE_THRESHOLD_DEGREES of [pos]'s target rotation.
     */
    fun rotationConverged(pos: BlockPos): Boolean {
        if (!kyrielie.blockbard.client.config.ConfigManager.config.smoothCameraRotation) return true
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val primed = primedRotation?.takeIf { it.first == pos } ?: return false
        val (_, targetYaw, targetPitch) = primed
        val yawDiff = abs(Mth.wrapDegrees(targetYaw - player.yRot))
        val pitchDiff = abs(targetPitch - player.xRot)
        return yawDiff <= ROTATION_CONVERGENCE_THRESHOLD_DEGREES &&
            pitchDiff <= ROTATION_CONVERGENCE_THRESHOLD_DEGREES
    }

    /**
     * Determines the optimal visible face and hit target vector on [pos] from [eyePos],
     * testing visible candidates using line-of-sight raycasts.
     *
     * Only faces geometrically facing [eyePos] are considered candidates to ensure
     * back-face interactions (such as clicking the top face from below) are rejected.
     */
    private fun findBestVisibleFace(
        world: net.minecraft.world.level.Level,
        player: net.minecraft.world.entity.player.Player,
        eyePos: Vec3,
        pos: BlockPos
    ): Pair<Vec3, Direction>? {
        val cx = pos.x + 0.5
        val cy = pos.y + 0.5
        val cz = pos.z + 0.5

        // Determine which faces can geometrically face eyePos
        val candidateFaces = mutableListOf<Direction>()
        if (eyePos.y > pos.y + 1.0) candidateFaces.add(Direction.UP)
        if (eyePos.y < pos.y.toDouble()) candidateFaces.add(Direction.DOWN)
        if (eyePos.x > pos.x + 1.0) candidateFaces.add(Direction.EAST)
        if (eyePos.x < pos.x.toDouble()) candidateFaces.add(Direction.WEST)
        if (eyePos.z > pos.z + 1.0) candidateFaces.add(Direction.SOUTH)
        if (eyePos.z < pos.z.toDouble()) candidateFaces.add(Direction.NORTH)

        if (candidateFaces.isEmpty()) {
            val delta = Vec3(cx, cy, cz).subtract(eyePos)
            candidateFaces.addAll(Direction.entries.sortedByDescending { face ->
                Vec3(face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble()).dot(delta)
            })
        } else {
            val deltaToEye = eyePos.subtract(Vec3(cx, cy, cz))
            candidateFaces.sortByDescending { face ->
                val normal = Vec3(face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble())
                normal.dot(deltaToEye)
            }
        }

        val maxReach = REACH_DISTANCE + 0.5

        // First attempt: direct ray towards the center of the block.
        // If clear, it hits the optimal front-facing face directly.
        val centerTarget = Vec3(cx, cy, cz)
        if (centerTarget.distanceTo(eyePos) <= maxReach) {
            val clipCtx = ClipContext(
                eyePos,
                centerTarget,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            )
            val clipHit = world.clip(clipCtx)
            if (clipHit.type == HitResult.Type.BLOCK && clipHit.blockPos == pos && clipHit.direction in candidateFaces) {
                return Pair(clipHit.location, clipHit.direction)
            }
        }

        // Second attempt: test raycasts through candidate faces at various offsets (center and edges)
        // to bypass partial corner obstructions by neighboring blocks.
        val offsets = doubleArrayOf(0.5, 0.25, 0.75)
        for (face in candidateFaces) {
            for (u in offsets) {
                for (v in offsets) {
                    val facePoint = when (face) {
                        Direction.UP    -> Vec3(pos.x + u, pos.y + 1.0, pos.z + v)
                        Direction.DOWN  -> Vec3(pos.x + u, pos.y.toDouble(), pos.z + v)
                        Direction.NORTH -> Vec3(pos.x + u, pos.y + v, pos.z.toDouble())
                        Direction.SOUTH -> Vec3(pos.x + u, pos.y + v, pos.z + 1.0)
                        Direction.WEST  -> Vec3(pos.x.toDouble(), pos.y + u, pos.z + v)
                        Direction.EAST  -> Vec3(pos.x + 1.0, pos.y + u, pos.z + v)
                    }
                    if (facePoint.distanceTo(eyePos) > maxReach) continue

                    val inwardStep = Vec3(face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble()).scale(-0.1)
                    val rayTarget = facePoint.add(inwardStep)

                    val clipCtx = ClipContext(
                        eyePos,
                        rayTarget,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player
                    )
                    val clipHit = world.clip(clipCtx)
                    if (clipHit.type == HitResult.Type.BLOCK && clipHit.blockPos == pos) {
                        return Pair(clipHit.location, clipHit.direction)
                    }
                }
            }
        }
        return null
    }

    /**
     * Aims at [pos] -- shared by interactWith() (tuning, right-click) and
     * playNoteAt() (playback, left-click). Returns the BlockHitResult, or null if
     * the block is out of reach.
     */
    private fun aimAt(pos: BlockPos, caller: String): BlockHitResult? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("$caller $pos: no player")
            return null
        }

        if (organMap == null) {
            logger.warn("$caller $pos: organMap is null -- Center not pressed yet")
        }

        val eyePos = player.eyePosition
        val world = mc.level ?: run {
            logger.warn("$caller $pos: no level")
            return null
        }

        val maxReach = REACH_DISTANCE + 0.5

        // Multi-face visible raycasting inspired by Mineflayer (ray_trace & digging)
        val (target, hitFace) = findBestVisibleFace(world, player, eyePos, pos) ?: run {
            val cx = pos.x + 0.5
            val cy = pos.y + 0.5
            val cz = pos.z + 0.5
            val defaultTarget = Vec3(cx, cy, cz)
            val dist = defaultTarget.distanceTo(eyePos)
            if (dist > maxReach) {
                val warn = "$caller $pos: beyond maximum reach (dist=${"%.2f".format(dist)} max=$maxReach)"
                logger.warn(warn)
                BlockBardLogBuffer.warn("PlayerController", warn)
                return null
            }
            // Fallback directional face: find face facing eyePos
            val delta = defaultTarget.subtract(eyePos)
            val face = when {
                Math.abs(delta.x) >= Math.abs(delta.y) && Math.abs(delta.x) >= Math.abs(delta.z) ->
                    if (delta.x > 0) Direction.WEST else Direction.EAST
                Math.abs(delta.y) >= Math.abs(delta.z) ->
                    if (delta.y > 0) Direction.DOWN else Direction.UP
                else ->
                    if (delta.z > 0) Direction.NORTH else Direction.SOUTH
            }
            val surfaceTarget = when (face) {
                Direction.UP    -> Vec3(cx, pos.y + 1.0, cz)
                Direction.DOWN  -> Vec3(cx, pos.y.toDouble(), cz)
                Direction.NORTH -> Vec3(cx, cy, pos.z.toDouble())
                Direction.SOUTH -> Vec3(cx, cy, pos.z + 1.0)
                Direction.WEST  -> Vec3(pos.x.toDouble(), cy, cz)
                Direction.EAST  -> Vec3(pos.x + 1.0, cy, cz)
            }
            Pair(surfaceTarget, face)
        }

        val delta = target.subtract(eyePos)
        val distance = delta.length()
        val reach = organMap?.getReachInfo(pos)
        val mapDistStr = reach?.distance?.let { "%.2f".format(it) } ?: "n/a"

        val (yaw, pitch) = primedRotation?.takeIf { it.first == pos }
            ?.let { Pair(it.second, it.third) }
            ?: (reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta))
        primedRotation = null

        // Snap or ease rotation and send to server so server raycast validation matches
        player.setYRot(yaw)
        player.setXRot(pitch)
        player.connection.send(
            net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(
                yaw,
                pitch,
                player.onGround(),
                player.horizontalCollision
            )
        )

        val liveHit = mc.hitResult
        return if (
            liveHit is BlockHitResult &&
            liveHit.blockPos == pos &&
            liveHit.type == HitResult.Type.BLOCK
        ) {
            logger.debug("$caller $pos: using live hitResult face=${liveHit.direction}")
            liveHit
        } else {
            logger.debug("$caller $pos: using target face=$hitFace")
            BlockHitResult(target, hitFace, pos, false)
        }
    }

    /**
     * Sends a right-click interact to a noteblock. Used by tuning only.
     * NoteBlock.useWithoutItem cycles the block's NOTE property by one -- the tuning
     * gesture. Must never be called during playback (see playNoteAt).
     *
     * The player must have empty hands for useWithoutItem to be reached.
     */
    fun interactWith(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("interactWith $pos: no player")
            return false
        }

        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            val msg = "§e[BlockBard] §cCannot interact in ${gameMode.getName()} mode"
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg))
            logger.warn("interactWith $pos: blocked -- player is in $gameMode mode")
            return false
        }

        // Ensure main hand is empty so NoteBlock.useWithoutItem is triggered instead of item usage
        if (!player.mainHandItem.isEmpty) {
            val emptySlot = (0..8).firstOrNull { player.inventory.getItem(it).isEmpty }
            if (emptySlot != null) {
                player.inventory.selectedSlot = emptySlot
                player.connection.send(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(emptySlot))
                logger.info("interactWith: switched to empty hotbar slot $emptySlot")
            } else {
                val warn = "Cannot interact with $pos: hotbar has no empty slots"
                logger.warn("interactWith $pos: $warn")
                BlockBardLogBuffer.warn("PlayerController", warn)
                return false
            }
        }

        val hitResult = aimAt(pos, "interactWith") ?: return false
        val result = mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false)
        return true
    }

    /**
     * Plays a noteblock without changing its pitch -- the playback counterpart to
     * interactWith(). Uses NoteBlock.attack() (left-click) which calls playNote()
     * directly with no state.cycle(NOTE), unlike useWithoutItem() (right-click).
     *
     * startDestroyBlock() triggers the server-side attack() on its first call per
     * target. stopDestroyBlock() is called immediately after every time to abort the
     * mining-progress sequence before any progress can persist.
     */
    fun playNoteAt(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("playNoteAt $pos: no player")
            return false
        }

        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            val msg = "§e[BlockBard] §cCannot interact in ${gameMode.getName()} mode"
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg))
            logger.warn("playNoteAt $pos: blocked -- player is in $gameMode mode")
            return false
        }

        val hitResult = aimAt(pos, "playNoteAt") ?: return false
        val dispatched = mc.gameMode?.startDestroyBlock(pos, hitResult.direction) ?: false
        mc.gameMode?.stopDestroyBlock()
        if (dispatched) {
            val level = mc.level
            if (level != null) {
                val entry = NoteBlockRegistry.get(pos)
                val note = entry?.noteIndex ?: 0
                level.addParticle(
                    ParticleTypes.NOTE,
                    pos.x + 0.5, pos.y + 1.2, pos.z + 0.5,
                    note / 24.0, 0.0, 0.0
                )
            }
        }
        return dispatched
    }

    fun interactWith(entry: NoteBlockEntry): Boolean = interactWith(entry.pos)

    /**
     * NoteRequest-aware playback entry point, wired to ArpeggioScheduler.interactDelegate.
     * Registers the intended (midiNote, instrument) with SoundVerifier immediately
     * before firing the interaction.
     */
    fun playNoteAt(pos: BlockPos, request: NoteRequest): Boolean {
        val expectedInstrument = request.instrument ?: NoteBlockRegistry.get(pos)?.instrument
        SoundVerifier.expectNote(pos, request.midiNote, expectedInstrument)
        return playNoteAt(pos)
    }

    /**
     * Dynamic on-the-fly retuning: right-clicks the noteblock once, which advances
     * state.cycle(NOTE) by 1 and immediately plays the new note at note onset.
     * Updates NoteBlockRegistry and registers SoundVerifier expectation.
     */
    fun retuneAndPlayNoteAt(pos: BlockPos, request: NoteRequest): Boolean {
        val entry = NoteBlockRegistry.get(pos) ?: return playNoteAt(pos, request)
        val newNoteIndex = (entry.noteIndex + 1) % 25
        val expectedInstrument = request.instrument ?: entry.instrument
        SoundVerifier.expectNote(pos, request.midiNote, expectedInstrument)
        val success = interactWith(pos)
        if (success) {
            NoteBlockRegistry.updateTunedNote(pos, newNoteIndex)
            val level = Minecraft.getInstance().level
            if (level != null) {
                level.addParticle(
                    ParticleTypes.NOTE,
                    pos.x + 0.5, pos.y + 1.2, pos.z + 0.5,
                    newNoteIndex / 24.0, 0.0, 0.0
                )
            }
        }
        return success
    }

    fun clearOrganMap() {
        logger.info("clearOrganMap: organ map cleared")
        organMap = null
    }
}
