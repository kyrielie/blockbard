package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

data class TuneTarget(
    val pos: BlockPos,
    val currentNote: Int,   // 0–24, read from BlockState at scan time
    val targetNote: Int,    // required by MidiToOrganMapper assignment
    val instrument: NoteBlockInstrument
) {
    val clicksRequired: Int get() = clicksNeeded(currentNote, targetNote)
    val alreadyTuned: Boolean get() = currentNote == targetNote
}

/**
 * Manages the auto-tuning state machine.
 * Consumes TuneTargets one at a time; the client event loop calls nextClick() each tick
 * to get the next (pos, clickCount) action and reports progress via [onProgress].
 */
class NoteBlockTuner(
    private val targets: List<TuneTarget>,
    val onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
) {
    private var targetIndex = 0
    private var clicksRemaining = 0
    private var completed = 0
    val total: Int = targets.sumOf { it.clicksRequired }
    var isDone: Boolean = false
        private set

    init {
        advance()
    }

    private fun advance() {
        while (targetIndex < targets.size) {
            val t = targets[targetIndex]
            if (t.alreadyTuned) {
                targetIndex++
                completed++
                onProgress(completed, targets.size)
                continue
            }
            clicksRemaining = t.clicksRequired
            break
        }
        if (targetIndex >= targets.size) isDone = true
    }

    /** Returns the BlockPos to click this tick, or null if done. */
    fun nextClick(): BlockPos? {
        if (isDone) return null
        val target = targets.getOrNull(targetIndex) ?: run { isDone = true; return null }
        clicksRemaining--
        if (clicksRemaining <= 0) {
            // Update registry so subsequent scans reflect the new note
            NoteBlockRegistry.updateTunedNote(target.pos, target.targetNote)
            targetIndex++
            completed++
            onProgress(completed, targets.size)
            advance()
        }
        return target.pos
    }

    val completedBlocks: Int get() = completed
    val totalBlocks: Int get() = targets.size
}
