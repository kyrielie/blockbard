package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.coversNatively
import kyrielie.blockbard.util.isPitched
import kyrielie.blockbard.util.midiBase
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory
import kotlin.math.max

data class TimelineNote(
    val timeMs: Long,
    val midiNote: Int,
    val instrument: NoteBlockInstrument,
    val durationMs: Long = 50L // Instantaneous one-shot trigger: noteblocks release after 1 tick (50ms)
)

data class ScheduledPlayNote(
    val timeMs: Long,
    val pos: BlockPos,
    val midiNote: Int,
    val instrument: NoteBlockInstrument
)

data class ScheduledRetuneTask(
    val startMs: Long,
    val deadlineMs: Long,
    val pos: BlockPos,
    val startNoteIndex: Int,
    val targetNoteIndex: Int,
    val instrument: NoteBlockInstrument,
    val clicks: Int
)

data class DynamicAllocationStats(
    val totalNotes: Int,
    val physicalBlocksUsed: Int,
    val distinctPitchesNeeded: Int,
    val lookaheadRetunesScheduled: Int,
    val maxConcurrentPolyphony: Map<NoteBlockInstrument, Int>
)

data class DynamicPerformancePlan(
    val playNotes: List<ScheduledPlayNote>,
    val retuneTasks: List<ScheduledRetuneTask>,
    val unplayable: List<TimelineNote>,
    val stats: DynamicAllocationStats
)

/**
 * Pre-computes lookahead voice allocation and rest-period retuning schedules for songs,
 * allowing organs with fewer physical blocks than distinct notes to play flawlessly.
 */
object TimelineVoiceAllocator {

    private val logger = LoggerFactory.getLogger("BlockBard/TimelineVoiceAllocator")

    const val MS_PER_CLICK = 75L       // Safe click rate limit during rest (approx 13 clicks/sec)
    const val REST_BUFFER_MS = 25L     // Tight quiet buffer after 50ms note release before retuning starts

    private class BlockSimState(
        val entry: NoteBlockEntry,
        var currentNoteIndex: Int = entry.noteIndex,
        var freeAtMs: Long = 0L
    )

    /**
     * Compiles a full dynamic performance plan from a chronological list of note events
     * and a list of reachable organ blocks.
     */
    fun compilePlan(
        events: List<TimelineNote>,
        reachableBlocks: List<NoteBlockEntry>
    ): DynamicPerformancePlan {
        if (events.isEmpty() || reachableBlocks.isEmpty()) {
            return DynamicPerformancePlan(
                emptyList(),
                emptyList(),
                events,
                DynamicAllocationStats(events.size, 0, 0, 0, emptyMap())
            )
        }

        val sortedEvents = events.sortedBy { it.timeMs }
        val blocksByInstrument = reachableBlocks
            .filter { it.status == NoteBlockStatus.PLAYABLE }
            .groupBy { it.instrument }

        val scheduledPlays = mutableListOf<ScheduledPlayNote>()
        val scheduledRetunes = mutableListOf<ScheduledRetuneTask>()
        val unplayable = mutableListOf<TimelineNote>()
        val usedBlockPositions = mutableSetOf<BlockPos>()
        val maxPolyphony = mutableMapOf<NoteBlockInstrument, Int>()

        // Group events by instrument to allocate voice intervals independently
        val eventsByInstrument = sortedEvents.groupBy { it.instrument }

        for ((instrument, instEvents) in eventsByInstrument) {
            val physicalBlocks = blocksByInstrument[instrument] ?: emptyList()

            // Unpitched percussion (SNARE, HAT, BASEDRUM) produce identical sound across all pitches;
            // bind to any available block of that instrument with 0 retuning.
            if (!instrument.isPitched) {
                val block = physicalBlocks.firstOrNull()
                if (block != null) {
                    usedBlockPositions.add(block.pos)
                    for (ev in instEvents) {
                        scheduledPlays.add(ScheduledPlayNote(ev.timeMs, block.pos, ev.midiNote, instrument))
                    }
                } else {
                    unplayable.addAll(instEvents)
                }
                continue
            }

            if (physicalBlocks.isEmpty()) {
                unplayable.addAll(instEvents)
                continue
            }

            // Calculate peak simultaneous polyphony for this instrument
            var currentPoly = 0
            var peakPoly = 0
            val activeEndTimes = mutableListOf<Long>()
            for (ev in instEvents) {
                activeEndTimes.removeAll { it <= ev.timeMs }
                activeEndTimes.add(ev.timeMs + ev.durationMs)
                currentPoly = activeEndTimes.size
                if (currentPoly > peakPoly) peakPoly = currentPoly
            }
            maxPolyphony[instrument] = peakPoly

            // Initialize virtual voice states for each physical block
            val blockStates = physicalBlocks.map { BlockSimState(it) }

            for (ev in instEvents) {
                val targetNoteIndex = ev.midiNote - instrument.midiBase
                if (targetNoteIndex !in 0..24) {
                    unplayable.add(ev)
                    continue
                }

                // 1. Prioritize a block that is ALREADY tuned to targetNoteIndex and is free
                val exactFree = blockStates
                    .filter { it.freeAtMs <= ev.timeMs && it.currentNoteIndex == targetNoteIndex }
                    .minByOrNull { it.entry.distanceFromPlayer }

                if (exactFree != null) {
                    scheduledPlays.add(ScheduledPlayNote(ev.timeMs, exactFree.entry.pos, ev.midiNote, instrument))
                    exactFree.freeAtMs = ev.timeMs + ev.durationMs
                    usedBlockPositions.add(exactFree.entry.pos)
                    continue
                }

                // 2. Find free blocks that have sufficient rest time to retune before ev.timeMs
                val candidateWithRetune = blockStates
                    .filter { it.freeAtMs <= ev.timeMs }
                    .mapNotNull { b ->
                        val clicks = clicksNeeded(b.currentNoteIndex, targetNoteIndex)
                        val timeNeededMs = clicks * MS_PER_CLICK
                        val availableRestMs = ev.timeMs - (b.freeAtMs + REST_BUFFER_MS)
                        if (availableRestMs >= timeNeededMs) {
                            Triple(b, clicks, timeNeededMs)
                        } else null
                    }
                    .minWithOrNull(
                        compareBy(
                            { it.second }, // Minimize tuning clicks
                            { it.first.entry.distanceFromPlayer }
                        )
                    )

                if (candidateWithRetune != null) {
                    val (b, clicks, timeNeededMs) = candidateWithRetune
                    val retuneStart = max(b.freeAtMs + REST_BUFFER_MS, ev.timeMs - timeNeededMs - 50L)
                    val retuneDeadline = ev.timeMs - 20L

                    if (clicks > 0) {
                        scheduledRetunes.add(
                            ScheduledRetuneTask(
                                startMs = retuneStart,
                                deadlineMs = retuneDeadline,
                                pos = b.entry.pos,
                                startNoteIndex = b.currentNoteIndex,
                                targetNoteIndex = targetNoteIndex,
                                instrument = instrument,
                                clicks = clicks
                            )
                        )
                    }

                    scheduledPlays.add(ScheduledPlayNote(ev.timeMs, b.entry.pos, ev.midiNote, instrument))
                    b.currentNoteIndex = targetNoteIndex
                    b.freeAtMs = ev.timeMs + ev.durationMs
                    usedBlockPositions.add(b.entry.pos)
                    continue
                }

                // 3. Fallback: Emergency 1-click JIT retune for fast runs
                val jitCandidate = blockStates
                    .filter { it.freeAtMs <= ev.timeMs }
                    .firstOrNull { clicksNeeded(it.currentNoteIndex, targetNoteIndex) == 1 }

                if (jitCandidate != null) {
                    scheduledPlays.add(ScheduledPlayNote(ev.timeMs, jitCandidate.entry.pos, ev.midiNote, instrument))
                    jitCandidate.currentNoteIndex = targetNoteIndex
                    jitCandidate.freeAtMs = ev.timeMs + ev.durationMs
                    usedBlockPositions.add(jitCandidate.entry.pos)
                    continue
                }

                // 4. Infeasible without dropping or shifting
                unplayable.add(ev)
            }
        }

        scheduledPlays.sortBy { it.timeMs }
        scheduledRetunes.sortBy { it.startMs }

        val distinctPitches = events.map { it.midiNote }.distinct().size
        val stats = DynamicAllocationStats(
            totalNotes = events.size,
            physicalBlocksUsed = usedBlockPositions.size,
            distinctPitchesNeeded = distinctPitches,
            lookaheadRetunesScheduled = scheduledRetunes.size,
            maxConcurrentPolyphony = maxPolyphony
        )

        logger.info(
            "compilePlan: ${scheduledPlays.size}/${events.size} notes scheduled across ${usedBlockPositions.size} blocks " +
            "with ${scheduledRetunes.size} lookahead retune tasks (${unplayable.size} unplayable)"
        )

        return DynamicPerformancePlan(scheduledPlays, scheduledRetunes, unplayable, stats)
    }
}
