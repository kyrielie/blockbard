package kyrielie.blockbard.util

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Converts a direction vector (delta from player eye to target) into Minecraft yaw and pitch angles.
 * Returns Pair(yaw, pitch) in degrees, clamped to valid ranges.
 */
fun vecToYawPitch(delta: Vec3): Pair<Float, Float> {
    val horizontalDist = sqrt(delta.x * delta.x + delta.z * delta.z)
    val yaw = Math.toDegrees(atan2(-delta.x, delta.z)).toFloat()
    val pitch = Math.toDegrees(-atan2(delta.y, horizontalDist)).toFloat()
    return Pair(Mth.wrapDegrees(yaw), pitch.coerceIn(-90f, 90f))
}