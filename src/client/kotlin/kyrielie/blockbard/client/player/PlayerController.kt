package kyrielie.blockbard.client.player

import kyrielie.blockbard.organ.NoteBlockEntry
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteRequest
import kyrielie.blockbard.organ.OrganMap
import kyrielie.blockbard.organ.ReachInfo
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
 * target. Many anticheat plugins flag any single-tick rotation delta beyond a
 * threshold well below human-mouse-speed as a "rotation hack" signature and will
 * silently drop the following use-item packet even though the rotation packet itself
 * is accepted and rendered locally on the client. Snapping yaw/pitch instantly to the
 * target every tick (the old behavior) produced exactly that signature whenever notes
 * were on opposite sides of the player. Easing toward the target at a capped rate
 * removes the signature without slowing down playback overall, since interactWith()
 * is only invoked once the eased rotation has converged (see rotationConverged()).
 *
 * var, not const val — set from BlockBardConfig.maxRotationDegreesPerTick at init
 * (see BlockBardClient.onInitializeClient()), since this is exactly the kind of
 * anticheat-compatibility tunable that needs per-server adjustment, like
 * ArpeggioScheduler.staleTimeoutMs already is.
 */
var MAX_ROTATION_DEGREES_PER_TICK = 35f

/**
 * How close yaw/pitch must be to the target (degrees) before a note is considered
 * aimed. var, not const val — see MAX_ROTATION_DEGREES_PER_TICK kdoc; set from
 * BlockBardConfig.rotationConvergenceThresholdDegrees at init.
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
     * Triple is (targetPos, yaw, pitch). Set/updated by primeRotation() every tick the
     * note is pending, eased toward the target — see MAX_ROTATION_DEGREES_PER_TICK.
     * Consumed and cleared by interactWith(); null if no note is pending.
     */
    private var primedRotation: Triple<BlockPos, Float, Float>? = null

    /**
     * Eases yaw/pitch toward [pos] by at most MAX_ROTATION_DEGREES_PER_TICK per call,
     * and records the in-progress rotation for this tick's movement packet. Call this
     * every tick a note is pending (BEFORE ArpeggioScheduler.onTick() in END_CLIENT_TICK),
     * not just once — convergence may take several ticks for large angle changes.
     *
     * Use rotationConverged(pos) to check whether it's safe to fire interactWith(pos) yet.
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
            logger.debug(
                "primeRotation $pos: yaw ${"%.2f".format(yRotBefore)}->${"%.2f".format(yRotAfter)}" +
                    " (target ${"%.2f".format(targetYaw)}) pitch ${"%.2f".format(xRotBefore)}->${"%.2f".format(xRotAfter)}" +
                    " (target ${"%.2f".format(targetPitch)})"
            )
        }
    }

    /** Signed shortest-path step from [from] toward [to], capped at MAX_ROTATION_DEGREES_PER_TICK. */
    private fun clampedStep(from: Float, to: Float): Float {
        val diff = Mth.wrapDegrees(to - from)
        val capped = diff.coerceIn(-MAX_ROTATION_DEGREES_PER_TICK, MAX_ROTATION_DEGREES_PER_TICK)
        return capped
    }

    /**
     * Returns true once the player's current rotation is within
     * ROTATION_CONVERGENCE_THRESHOLD_DEGREES of [pos]'s target rotation. Callers
     * (ArpeggioScheduler) should wait for this before invoking interactWith(pos) —
     * firing the use packet while still mid-turn reproduces the same "instant rotation
     * + click" signature this easing was added to avoid.
     */
    fun rotationConverged(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val primed = primedRotation?.takeIf { it.first == pos } ?: run {
            // No primed rotation recorded for this pos yet (e.g. first tick it became
            // the head of the queue, before primeRotation has run for it this tick).
            return false
        }
        val (_, targetYaw, targetPitch) = primed
        val yawDiff = abs(Mth.wrapDegrees(targetYaw - player.yRot))
        val pitchDiff = abs(targetPitch - player.xRot)
        return yawDiff <= ROTATION_CONVERGENCE_THRESHOLD_DEGREES &&
            pitchDiff <= ROTATION_CONVERGENCE_THRESHOLD_DEGREES
    }

    /**
     * Aims at [pos] (rotation + hit-face/hitResult computation) — shared by both
     * interactWith(pos) (tuning, right-click/useItemOn) and playNoteAt(pos) (playback,
     * left-click/attack). Returns the BlockHitResult to interact with, or null if the
     * block is out of reach. Does not perform the interaction itself — callers choose
     * useItemOn vs startDestroyBlock/stopDestroyBlock depending on intent.
     */
    private fun aimAt(pos: BlockPos, caller: String): BlockHitResult? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("$caller $pos: no player")
            return null
        }

        if (organMap == null) {
            logger.warn("$caller $pos: organMap is null — Center not pressed yet")
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

        // Determine target rotation — prefer primed value (set one tick earlier in START_CLIENT_TICK),
        // fall back to map precompute, fall back to live computation.
        val (yaw, pitch) = primedRotation?.takeIf { it.first == pos }
            ?.let { Pair(it.second, it.third) }
            ?: (reach?.let { Pair(it.yaw, it.pitch) } ?: vecToYawPitch(delta))
        primedRotation = null

        // Re-apply rotation immediately before firing the interaction — same approach as
        // the old working version. The primeRotation in START_CLIENT_TICK gets the
        // rotation into the movement packet; re-applying here ensures the rotation is
        // correct for the interaction packet regardless of whether anything overwrote it
        // between START and END tick.
        val yRotBefore = player.yRot
        val xRotBefore = player.xRot
        player.setYRot(yaw)
        player.setXRot(pitch)
        val yRotAfter = player.yRot
        val xRotAfter = player.xRot
        if (yRotAfter != yaw || xRotAfter != pitch) {
            logger.warn(
                "$caller $pos: rotation override detected — wanted yaw=${"%.2f".format(yaw)} pitch=${"%.2f".format(pitch)}" +
                    " got yaw=${"%.2f".format(yRotAfter)} pitch=${"%.2f".format(xRotAfter)}"
            )
        }
        logger.info(
            "$caller $pos: rot ${"%.2f".format(yRotBefore)},${"%.2f".format(xRotBefore)}" +
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
        return if (
            liveHit is BlockHitResult &&
            liveHit.blockPos == pos &&
            liveHit.type == HitResult.Type.BLOCK
        ) {
            logger.debug("$caller $pos: using live hitResult face=${liveHit.direction}")
            liveHit
        } else {
            val liveDesc = when (liveHit) {
                is BlockHitResult -> "BlockHit(${liveHit.blockPos},${liveHit.direction})"
                null -> "null"
                else -> liveHit.type.name
            }
            logger.debug("$caller $pos: hitResult not on target ($liveDesc) — using synthetic face=$hitFace")
            BlockHitResult(target, hitFace, pos, false)
        }
    }

    /**
     * Sends a right-click interact to a noteblock. Used by tuning only — see
     * NoteBlock.useWithoutItem in vanilla source: a right-click cycles the block's NOTE
     * property by one (state.cycle(NOTE)), which is exactly the tuning gesture and
     * exactly why this must never be called during playback (see playNoteAt below).
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

        val hitResult = aimAt(pos, "interactWith") ?: return false
        mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
        return true
    }

    /**
     * Plays a noteblock without changing its pitch — the playback counterpart to
     * interactWith() above. Uses the left-click/attack interaction instead of
     * right-click/useItemOn: NoteBlock.attack() in vanilla source calls playNote()
     * directly with no state.cycle(NOTE) call at all, unlike useWithoutItem(). This is
     * the fix for the bug where every played note also advanced that block's tuning by
     * one semitone, since the original implementation used useItemOn (right-click) for
     * both tuning and playback indiscriminately.
     *
     * The client has no way to invoke NoteBlock.attack()'s effect directly — it only
     * runs server-side (guarded by `if (!level.isClientSide())` in vanilla source), so
     * the client's role is limited to notifying the server via the same packet path
     * normal left-click mining uses: MultiPlayerGameMode.startDestroyBlock(pos, dir)
     * triggers the server-side attack() call on its first invocation per target
     * (destroyProgress == 0f), but it also unconditionally begins a mining-progress
     * sequence (isDestroying = true) — left unchecked, repeatedly hitting the same
     * block would accumulate destroy progress across calls and eventually break it.
     * stopDestroyBlock() is called immediately after, every time, with no exceptions,
     * to abort that sequence before any progress can persist between notes — a single
     * call's progress fraction is always well under 1.0 for a noteblock's normal
     * hardness, so this is safe even at the queue's max dispatch rate of one note/tick.
     */
    fun playNoteAt(pos: BlockPos): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run {
            logger.warn("playNoteAt $pos: no player")
            return false
        }

        // ── Creative / Spectator guard — same rationale as interactWith ──
        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            val msg = "§e[BlockBard] §cCannot interact in ${gameMode.getName()} mode"
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg))
            logger.warn("playNoteAt $pos: blocked — player is in $gameMode mode")
            return false
        }

        val hitResult = aimAt(pos, "playNoteAt") ?: return false
        val dispatched = mc.gameMode?.startDestroyBlock(pos, hitResult.direction) ?: false
        mc.gameMode?.stopDestroyBlock()
        return dispatched
    }

    fun interactWith(entry: NoteBlockEntry): Boolean = interactWith(entry.pos)

    /**
     * NoteRequest-aware playback entry point, wired to ArpeggioScheduler.interactDelegate
     * by the client entrypoint. Registers the intended (midiNote, instrument) with
     * SoundVerifier immediately before firing the interaction, so the next note_block
     * SoundInstance the client plays can be compared against what we meant to play —
     * see SoundVerifier kdoc for why this has to happen here rather than after the call
     * returns: the server (same process in singleplayer) can dispatch the resulting
     * sound packet before this function returns, so registering after the call risks
     * missing it.
     *
     * Calls playNoteAt(pos), not interactWith(pos) — see playNoteAt's kdoc. This was
     * previously interactWith(pos), which meant every played note also advanced that
     * block's tuning by one semitone (right-click cycles NOTE; this needs the
     * left-click/attack interaction that does not).
     *
     * The tuner's interactBlock lambda (MainScreen.startTuning()) intentionally keeps
     * using the request-less interactWith(pos) overload above — a tuning click isn't
     * "playing" any particular note, it's incrementing pitch by one semitone, so there
     * is no intended (midiNote, instrument) to register, and it needs the right-click
     * behavior, not playNoteAt's left-click behavior.
     */
    fun playNoteAt(pos: BlockPos, request: NoteRequest): Boolean {
        // request.instrument is null for callers with no instrument source (scale test,
        // direct keyboard 1-9 presses — see NoteRequest kdoc). Falling back to "any
        // instrument is fine" there would make SoundVerifier a no-op for exactly the
        // chromatic-scale test BlockBard's debugging plan calls for running first, since
        // every sound would trivially "match". Resolve the real instrument from the
        // registry at pos when we have one, so the scale test still gets a concrete
        // expected (instrument, noteIndex) to check against.
        val expectedInstrument = request.instrument ?: NoteBlockRegistry.get(pos)?.instrument
        SoundVerifier.expectNote(pos, request.midiNote, expectedInstrument)
        return playNoteAt(pos)
    }

    fun clearOrganMap() {
        logger.info("clearOrganMap: organ map cleared")
        organMap = null
    }
}