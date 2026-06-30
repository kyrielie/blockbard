package kyrielie.blockbard.client.player

import kyrielie.blockbard.organ.NoteBlockEntry
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.organ.OrganMap
import kyrielie.blockbard.organ.ReachInfo
import kyrielie.blockbard.util.DebugLog
import kyrielie.blockbard.util.vecToYawPitch
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
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
     * Builds the OrganMap from the player's current actual position.
     * Does NOT move the player.
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

        logger.info("centerOnOrgan: map built -- $reachableCount/${playableBlocks.size} reachable from player position")
        return CenterResult.Centered(playerPos, reachableCount, playableBlocks.size)
    }

    private fun buildOrganMap(playableBlocks: List<kyrielie.blockbard.organ.NoteBlockEntry>, standPos: BlockPos, eyeHeight: Float) {
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
        val target = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
        val delta = target.subtract(eyePos)
        val distance = delta.length()
        val reach = organMap?.getReachInfo(pos)
        val mapDistStr = reach?.distance?.let { "%.2f".format(it) } ?: "n/a"

        if (distance > REACH_DISTANCE) {
            logger.warn(
                "$caller $pos: too far liveDist=${"%.2f".format(distance)} mapDist=$mapDistStr " +
                    "clientPos=${player.position()} ping=${currentPingMs()}"
            )
            return null
        }

        val (yaw, pitch) = primedRotation?.takeIf { it.first == pos }
            ?.let { Pair(it.second, it.third) }
            ?: (reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta))
        primedRotation = null

        val yRotBefore = player.yRot
        val xRotBefore = player.xRot
        player.setYRot(yaw)
        player.setXRot(pitch)
        val yRotAfter = player.yRot
        val xRotAfter = player.xRot
        if (yRotAfter != yaw || xRotAfter != pitch) {
            logger.warn(
                "$caller $pos: rotation override detected -- wanted yaw=${"%.2f".format(yaw)} pitch=${"%.2f".format(pitch)}" +
                    " got yaw=${"%.2f".format(yRotAfter)} pitch=${"%.2f".format(xRotAfter)}"
            )
        }
        DebugLog.info(logger) {
            "$caller $pos: rot ${"%.2f".format(yRotBefore)},${"%.2f".format(xRotBefore)}" +
                " -> ${"%.2f".format(yRotAfter)},${"%.2f".format(xRotAfter)}" +
                " liveDist=${"%.2f".format(distance)} mapDist=$mapDistStr ping=${currentPingMs()}"
        }

        val hitFace = when {
            Math.abs(delta.x) > Math.abs(delta.y) && Math.abs(delta.x) > Math.abs(delta.z) ->
                if (delta.x > 0) Direction.WEST else Direction.EAST
            Math.abs(delta.y) > Math.abs(delta.z) ->
                if (delta.y > 0) Direction.DOWN else Direction.UP
            else ->
                if (delta.z > 0) Direction.NORTH else Direction.SOUTH
        }

        val liveHit = mc.hitResult
        return if (
            liveHit is BlockHitResult &&
            liveHit.blockPos == pos &&
            liveHit.type == HitResult.Type.BLOCK
        ) {
            logger.debug("$caller $pos: using live hitResult face=${liveHit.direction}")
            liveHit
        } else {
            logger.debug("$caller $pos: using synthetic face=$hitFace")
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

        val hitResult = aimAt(pos, "interactWith") ?: return false
        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
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

    fun clearOrganMap() {
        logger.info("clearOrganMap: organ map cleared")
        organMap = null
    }
}
