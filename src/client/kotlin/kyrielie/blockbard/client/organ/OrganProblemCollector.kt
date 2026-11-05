package kyrielie.blockbard.client.organ

import kyrielie.blockbard.client.playback.TuningController
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.render.ManualTuningHighlighter
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteBlockStatus
import kyrielie.blockbard.organ.displayName
import kyrielie.blockbard.util.blockBelowHint
import kyrielie.blockbard.util.midiNoteToName
import net.minecraft.core.BlockPos

enum class ProblemSeverity {
    ERROR,
    WARNING,
    INFO
}

data class OrganProblem(
    val category: String,
    val severity: ProblemSeverity,
    val title: String,
    val details: String,
    val suggestion: String,
    val pos: BlockPos? = null
)

object OrganProblemCollector {

    /**
     * Collects all current organ issues including failed tuning blocks, untunable blocks,
     * silenced blocks, unreachable blocks, and unplayable notes for the active song.
     */
    fun collect(): List<OrganProblem> {
        val problems = mutableListOf<OrganProblem>()
        val seenPos = mutableSetOf<BlockPos>()

        // 1. Manual tuning failures (blocks that failed automatic tuning)
        val manualHighlights = ManualTuningHighlighter.all()
        for ((pos, info) in manualHighlights) {
            seenPos.add(pos)
            problems.add(
                OrganProblem(
                    category = "TUNING_FAILED",
                    severity = ProblemSeverity.ERROR,
                    title = "${info.instrument.name} at ${pos.x}, ${pos.y}, ${pos.z}",
                    details = "Failed reaching note ${info.targetNote} [${info.failState.tag}]: ${info.failState.description}",
                    suggestion = "Tune block manually or clear obstruction above block.",
                    pos = pos
                )
            )
        }

        // 2. Untunable / occluded blocks recorded in NoteBlockRegistry
        val untunable = NoteBlockRegistry.allUntunable()
        for ((pos, failReason) in untunable) {
            if (seenPos.add(pos)) {
                val entry = NoteBlockRegistry.get(pos)
                val instName = entry?.instrument?.name ?: "NoteBlock"
                problems.add(
                    OrganProblem(
                        category = "OCCLUDED",
                        severity = ProblemSeverity.WARNING,
                        title = "$instName at ${pos.x}, ${pos.y}, ${pos.z}",
                        details = "Flagged as untunable [${failReason.tag}]: ${failReason.description}",
                        suggestion = "Ensure a clear line of sight and empty space above the block.",
                        pos = pos
                    )
                )
            }
        }

        // 3. Silenced blocks (solid block overhead)
        val silenced = NoteBlockRegistry.all().filter { it.status == NoteBlockStatus.SILENCED }
        for (entry in silenced) {
            if (seenPos.add(entry.pos)) {
                problems.add(
                    OrganProblem(
                        category = "SILENCED",
                        severity = ProblemSeverity.ERROR,
                        title = "${entry.instrument.name} at ${entry.pos.x}, ${entry.pos.y}, ${entry.pos.z}",
                        details = "A solid block overhead is preventing this noteblock from producing sound.",
                        suggestion = "Remove the solid block directly above ${entry.pos.x}, ${entry.pos.y + 1}, ${entry.pos.z}.",
                        pos = entry.pos
                    )
                )
            }
        }

        // 4. Out-of-reach blocks
        val organMap = PlayerController.organMap
        val reachDistance = PlayerController.currentReachDistance()
        if (organMap != null) {
            val allPlayable = NoteBlockRegistry.allPlayable()
            val unreachable = allPlayable.filter { !organMap.isReachable(it.pos) }
            for (entry in unreachable) {
                val dist = (entry.distanceFromPlayer * 10).toInt() / 10.0
                problems.add(
                    OrganProblem(
                        category = "OUT_OF_REACH",
                        severity = ProblemSeverity.WARNING,
                        title = "${entry.instrument.name} at ${entry.pos.x}, ${entry.pos.y}, ${entry.pos.z}",
                        details = "Distance ${dist}m exceeds interaction range limit (${reachDistance}m).",
                        suggestion = "Move closer to organ center or run /bb center.",
                        pos = entry.pos
                    )
                )
            }
        }

        // 5. Unplayable song notes (from active tuning assignment)
        val activeAssignment = TuningController.assignment
        if (activeAssignment != null && activeAssignment.unplayable.isNotEmpty()) {
            for (pitch in activeAssignment.unplayable) {
                val inst = pitch.instrument
                val hint = if (inst != null) "Need: ${inst.blockBelowHint} for ${inst.name}" else "Need: NoteBlock for MIDI ${pitch.midiNote}"
                problems.add(
                    OrganProblem(
                        category = "UNPLAYABLE_NOTE",
                        severity = ProblemSeverity.WARNING,
                        title = "Unplayable ${pitch.displayName()}",
                        details = "Song requires MIDI note ${pitch.midiNote} (${midiNoteToName(pitch.midiNote)}).",
                        suggestion = hint
                    )
                )
            }
        }

        return problems
    }

    /** Total count of active problems detected. */
    fun count(): Int = collect().size
}
