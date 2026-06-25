package kyrielie.blockbard.organ

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

data class ReachInfo(
    val isReachable: Boolean,
    val yaw: Float,
    val pitch: Float,
    val distance: Double
)

/**
 * Built once by PlayerController.centerOnOrgan(). Maps each noteblock position to
 * its reachability and pre-computed facing angles from the chosen vantage point.
 */
class OrganMap(
    val standPos: BlockPos,
    private val reachMap: Map<BlockPos, ReachInfo>
) {
    fun getReachInfo(pos: BlockPos): ReachInfo? = reachMap[pos]
    fun isReachable(pos: BlockPos): Boolean = reachMap[pos]?.isReachable == true
    fun reachableCount(): Int = reachMap.values.count { it.isReachable }
    fun allReachable(): List<BlockPos> = reachMap.filterValues { it.isReachable }.keys.toList()
}
