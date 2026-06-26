package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.midiNoteToName
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

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
 * Consumes TuneTargets one at a time; the client event loop calls nextClick() each tick.
 */
class NoteBlockTuner(
    private val targets: List<TuneTarget>,
    val onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
) {
    private val logger = LoggerFactory.getLogger("BlockBard/NoteBlockTuner")

    private var targetIndex = 0
    private var clicksRemaining = 0
    private var completed = 0
    val total: Int = targets.sumOf { it.clicksRequired }
    var isDone: Boolean = false
        private set

    init {
        logger.info("NoteBlockTuner: ${targets.size} blocks, $total total clicks")
        targets.forEachIndexed { i, t ->
            val targetName = midiNoteToName(t.instrument.let {
                kyrielie.blockbard.util.noteIndexToMidi(it, t.targetNote)
            })
            logger.debug("  [$i] ${t.pos} ${t.instrument.name} note ${t.currentNote}→${t.targetNote} ($targetName) = ${t.clicksRequired} clicks")
        }
        advance()
    }

    private fun advance() {
        while (targetIndex < targets.size) {
            val t = targets[targetIndex]
            if (t.alreadyTuned) {
                logger.debug("NoteBlockTuner: ${t.pos} already tuned at note ${t.currentNote} — skipping")
                targetIndex++
                completed++
                onProgress(completed, targets.size)
                continue
            }
            clicksRemaining = t.clicksRequired
            logger.info("NoteBlockTuner: advancing to block $targetIndex — ${t.pos} ${t.instrument.name} note ${t.currentNote}→${t.targetNote} ($clicksRemaining clicks)")
            break
        }
        if (targetIndex >= targets.size) {
            isDone = true
            logger.info("NoteBlockTuner: all blocks tuned")
        }
    }

    /** Returns the BlockPos to click this tick, or null if done. */
    fun nextClick(): BlockPos? {
        if (isDone) return null
        val target = targets.getOrNull(targetIndex) ?: run { isDone = true; return null }
        clicksRemaining--
        logger.debug("NoteBlockTuner: clicking ${target.pos} — $clicksRemaining clicks remaining")
        if (clicksRemaining <= 0) {
            NoteBlockRegistry.updateTunedNote(target.pos, target.targetNote)
            logger.info("NoteBlockTuner: finished ${target.pos} — tuned to note ${target.targetNote}")
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
