package kyrielie.blockbard.client.playback

import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.DynamicPerformancePlan
import org.slf4j.LoggerFactory

/**
 * Manages the active dynamic performance plan and synchronized rest-period retune schedule.
 *
 * When playback seeks or restarts, [onSeek] re-arms remaining retune tasks from the new
 * position without dropping lookahead work or desynchronizing with [PlaybackClock].
 */
object DynamicRetuneManager {

    private val logger = LoggerFactory.getLogger("BlockBard/DynamicRetuneManager")
    private var activePlan: DynamicPerformancePlan? = null

    fun loadPlan(plan: DynamicPerformancePlan) {
        activePlan = plan
        ArpeggioScheduler.loadRetuneSchedule(plan.retuneTasks)
        logger.info("DynamicRetuneManager: loaded plan with ${plan.retuneTasks.size} retune tasks (${plan.stats.physicalBlocksUsed} blocks)")
    }

    /**
     * Re-arms the retune schedule from [targetMs] onward when seeking or restarting.
     */
    fun onSeek(targetMs: Long) {
        val plan = activePlan ?: return
        val remaining = plan.retuneTasks.filter { it.deadlineMs >= targetMs }
        ArpeggioScheduler.loadRetuneSchedule(remaining)
        logger.debug("DynamicRetuneManager: seek to ${targetMs}ms, reloaded ${remaining.size}/${plan.retuneTasks.size} retune tasks")
    }

    fun clear() {
        activePlan = null
        ArpeggioScheduler.loadRetuneSchedule(emptyList())
        logger.debug("DynamicRetuneManager: cleared active performance plan")
    }

    val hasActivePlan: Boolean get() = activePlan != null
}
