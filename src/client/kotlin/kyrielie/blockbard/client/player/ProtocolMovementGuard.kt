package kyrielie.blockbard.client.player

import net.minecraft.core.BlockPos
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import org.slf4j.LoggerFactory

/**
 * Protocol safety guard for Minecraft 26.2/26.3 movement packets.
 *
 * Pattern inspired by SongPlayer's StageMovementPackets, adapted specifically for
 * BlockBard's mobile-friendly survival architecture:
 *
 * 1. Minecraft 26.2+ protocol contract: At most ONE position-bearing packet
 *    (ServerboundMovePlayerPacket.Pos or PosRot) is accepted per client tick.
 *    Sending multiple position packets in the same tick triggers server-side
 *    movement heuristics or duplicate-movement disconnects ("disconnect.flying").
 *
 * 2. Unlike SongPlayer (which locks the player to a stationary stage coordinate),
 *    BlockBard allows the player to freely walk around their survival organ.
 *    This guard ensures that when the walking player's client tick sends a position update,
 *    any additional aiming or positioning packets dispatched by BlockBard in that same tick
 *    are safely downgraded to look-only (ServerboundMovePlayerPacket.Rot).
 */
object ProtocolMovementGuard {

    private val logger = LoggerFactory.getLogger("BlockBard/ProtocolMovementGuard")

    @Volatile
    private var positionSentThisTick: Boolean = false

    @Volatile
    private var lastSentYaw: Float = 0f

    @Volatile
    private var lastSentPitch: Float = 0f

    /**
     * Called at the end of each client tick (ClientTickEvents.END_CLIENT_TICK)
     * to reset the per-tick position counter.
     */
    fun onClientTickEnd() {
        positionSentThisTick = false
    }

    /**
     * Records an outgoing packet observed on the connection.
     */
    fun observeOutgoing(packet: Packet<*>) {
        if (packet is ServerboundClientTickEndPacket) {
            positionSentThisTick = false
        } else if (packet is ServerboundMovePlayerPacket) {
            if (packet.hasPosition()) {
                positionSentThisTick = true
            }
            if (packet.hasRotation()) {
                val yaw = packet.getYRot(lastSentYaw)
                val pitch = packet.getXRot(lastSentPitch)
                if (yaw.isFinite()) lastSentYaw = yaw
                if (pitch.isFinite()) lastSentPitch = pitch
            }
        }
    }

    /**
     * Returns true if a position packet has already been sent during the current client tick.
     */
    fun isPositionSentThisTick(): Boolean = positionSentThisTick

    /**
     * Safely constructs an aiming packet. If a position update was already sent this tick,
     * or if only rotation is desired, emits a look-only Rot packet.
     */
    fun createSafeRotationPacket(
        yaw: Float,
        pitch: Float,
        onGround: Boolean,
        horizontalCollision: Boolean
    ): ServerboundMovePlayerPacket.Rot {
        val safeYaw = if (yaw.isFinite()) yaw else lastSentYaw
        val safePitch = if (pitch.isFinite()) pitch else lastSentPitch
        lastSentYaw = safeYaw
        lastSentPitch = safePitch
        return ServerboundMovePlayerPacket.Rot(safeYaw, safePitch, onGround, horizontalCollision)
    }

    /**
     * Safely dispatches a stage or centering movement packet. If a position has already
     * been dispatched this tick by player movement, downgrades to a Rot packet to avoid
     * the 26.2+ duplicate position disconnect.
     */
    fun sendStageMovement(
        connection: Connection,
        targetPos: BlockPos,
        yaw: Float,
        pitch: Float,
        onGround: Boolean = true,
        horizontalCollision: Boolean = false
    ) {
        val safeYaw = if (yaw.isFinite()) yaw else lastSentYaw
        val safePitch = if (pitch.isFinite()) pitch else lastSentPitch
        lastSentYaw = safeYaw
        lastSentPitch = safePitch

        if (positionSentThisTick) {
            logger.debug("Position already sent this tick; downgrading stage movement to Rot(yaw=$safeYaw, pitch=$safePitch)")
            connection.send(ServerboundMovePlayerPacket.Rot(safeYaw, safePitch, onGround, horizontalCollision))
        } else {
            positionSentThisTick = true
            val packet = ServerboundMovePlayerPacket.PosRot(
                targetPos.x + 0.5,
                targetPos.y.toDouble(),
                targetPos.z + 0.5,
                safeYaw,
                safePitch,
                onGround,
                horizontalCollision
            )
            connection.send(packet)
            logger.debug("Dispatched PosRot stage movement to $targetPos")
        }
    }
}
