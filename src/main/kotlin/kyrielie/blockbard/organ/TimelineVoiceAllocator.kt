package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded
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

// Generic models for pure unit testing outside Minecraft classloader
data class GenericTimelineNote<I>(
    val timeMs: Long,
    val midiNote: Int,
    val instrument: I,
    val durationMs: Long = 50L
)

data class GenericScheduledPlayNote<P, I>(
    val timeMs: Long,
    val pos: P,
    val midiNote: Int,
    val instrument: I
)

data class GenericScheduledRetuneTask<P, I>(
    val startMs: Long,
    val deadlineMs: Long,
    val pos: P,
    val startNoteIndex: Int,
    val targetNoteIndex: Int,
    val instrument: I,
    val clicks: Int
)

data class GenericAllocationStats<I>(
    val totalNotes: Int,
    val physicalBlocksUsed: Int,
    val distinctPitchesNeeded: Int,
    val lookaheadRetunesScheduled: Int,
    val maxConcurrentPolyphony: Map<I, Int>
)

data class GenericPerformancePlan<P, I>(
    val playNotes: List<GenericScheduledPlayNote<P, I>>,
    val retuneTasks: List<GenericScheduledRetuneTask<P, I>>,
    val unplayable: List<GenericTimelineNote<I>>,
    val stats: GenericAllocationStats<I>
)

data class VoiceBlockSpec<P, I>(
    val pos: P,
    val instrument: I,
    val noteIndex: Int,
    val isPlayable: Boolean = true,
    val distanceFromPlayer: Double = 1.0
)

data class InstrumentSpec(
    val midiBase: Int,
    val isPitched: Boolean
)

/**
 * Pre-computes lookahead voice allocation and rest-period retuning schedules for songs,
 * allowing organs with fewer physical blocks than distinct notes to play flawlessly.
 */
object TimelineVoiceAllocator {

    private val logger = LoggerFactory.getLogger("BlockBard/TimelineVoiceAllocator")

    const val MS_PER_CLICK = 75L       // Safe click rate limit during rest (approx 13 clicks/sec)
    const val REST_BUFFER_MS = 25L     // Tight quiet buffer after 50ms note release before retuning starts

    private class BlockSimState<P, I>(
        val block: VoiceBlockSpec<P, I>,
        var currentNoteIndex: Int = block.noteIndex,
        var freeAtMs: Long = 0L
    )

    /**
     * Pure, generic solver for lookahead voice allocation and rest retuning.
     */
    fun <P, I> compileGenericPlan(
        events: List<GenericTimelineNote<I>>,
        reachableBlocks: List<VoiceBlockSpec<P, I>>,
        instrumentSpec: (I) -> InstrumentSpec,
        maxClicksPerRetune: Int = 2
    ): GenericPerformancePlan<P, I> {
        if (events.isEmpty() || reachableBlocks.isEmpty()) {
            return GenericPerformancePlan(
                emptyList(),
                emptyList(),
                events,
                GenericAllocationStats(events.size, 0, 0, 0, emptyMap())
            )
        }

        val sortedEvents = events.sortedBy { it.timeMs }
        val blocksByInstrument = reachableBlocks
            .filter { it.isPlayable }
            .groupBy { it.instrument }

        val scheduledPlays = mutableListOf<GenericScheduledPlayNote<P, I>>()
        val scheduledRetunes = mutableListOf<GenericScheduledRetuneTask<P, I>>()
        val unplayable = mutableListOf<GenericTimelineNote<I>>()
        val usedBlockPositions = mutableSetOf<P>()
        val maxPolyphony = mutableMapOf<I, Int>()

        // Group events by instrument to allocate voice intervals independently
        val eventsByInstrument = sortedEvents.groupBy { it.instrument }

        for ((instrument, instEvents) in eventsByInstrument) {
            val spec = instrumentSpec(instrument)
            val physicalBlocks = blocksByInstrument[instrument] ?: emptyList()

            // Unpitched percussion (SNARE, HAT, BASEDRUM) produce identical sound across all pitches;
            // bind to any available block of that instrument with 0 retuning.
            if (!spec.isPitched) {
                val block = physicalBlocks.firstOrNull()
                if (block != null) {
                    usedBlockPositions.add(block.pos)
                    for (ev in instEvents) {
                        scheduledPlays.add(GenericScheduledPlayNote(ev.timeMs, block.pos, ev.midiNote, instrument))
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
                val targetNoteIndex = ev.midiNote - spec.midiBase
                if (targetNoteIndex !in 0..24) {
                    unplayable.add(ev)
                    continue
                }

                // 1. Prioritize a block that is ALREADY tuned to targetNoteIndex and is free
                val exactFree = blockStates
                    .filter { it.freeAtMs <= ev.timeMs && it.currentNoteIndex == targetNoteIndex }
                    .minByOrNull { it.block.distanceFromPlayer }

                if (exactFree != null) {
                    scheduledPlays.add(GenericScheduledPlayNote(ev.timeMs, exactFree.block.pos, ev.midiNote, instrument))
                    exactFree.freeAtMs = ev.timeMs + ev.durationMs
                    usedBlockPositions.add(exactFree.block.pos)
                    continue
                }

                // 2. Find free blocks that have sufficient rest time to retune before ev.timeMs
                // and whose tuning clicks do not exceed the ghost-note limit (maxClicksPerRetune)
                val candidateWithRetune = if (maxClicksPerRetune > 0) {
                    blockStates
                        .filter { it.freeAtMs <= ev.timeMs }
                        .mapNotNull { b ->
                            val clicks = clicksNeeded(b.currentNoteIndex, targetNoteIndex)
                            if (clicks > maxClicksPerRetune) return@mapNotNull null
                            val timeNeededMs = clicks * MS_PER_CLICK
                            val availableRestMs = ev.timeMs - (b.freeAtMs + REST_BUFFER_MS)
                            if (availableRestMs >= timeNeededMs) {
                                Triple(b, clicks, timeNeededMs)
                            } else null
                        }
                        .minWithOrNull(
                            compareBy(
                                { it.second }, // Minimize tuning clicks
                                { it.first.block.distanceFromPlayer }
                            )
                        )
                } else null

                if (candidateWithRetune != null) {
                    val (b, clicks, timeNeededMs) = candidateWithRetune
                    val retuneStart = max(b.freeAtMs + REST_BUFFER_MS, ev.timeMs - timeNeededMs - 50L)
                    val retuneDeadline = ev.timeMs - 20L

                    if (clicks > 0) {
                        scheduledRetunes.add(
                            GenericScheduledRetuneTask(
                                startMs = retuneStart,
                                deadlineMs = retuneDeadline,
                                pos = b.block.pos,
                                startNoteIndex = b.currentNoteIndex,
                                targetNoteIndex = targetNoteIndex,
                                instrument = instrument,
                                clicks = clicks
                            )
                        )
                    }

                    scheduledPlays.add(GenericScheduledPlayNote(ev.timeMs, b.block.pos, ev.midiNote, instrument))
                    b.currentNoteIndex = targetNoteIndex
                    b.freeAtMs = ev.timeMs + ev.durationMs
                    usedBlockPositions.add(b.block.pos)
                    continue
                }

                // 3. Fallback: Emergency 1-click JIT retune for fast runs
                val jitCandidate = if (maxClicksPerRetune >= 1) {
                    blockStates
                        .filter { it.freeAtMs <= ev.timeMs }
                        .firstOrNull { clicksNeeded(it.currentNoteIndex, targetNoteIndex) == 1 }
                } else null

                if (jitCandidate != null) {
                    scheduledPlays.add(GenericScheduledPlayNote(ev.timeMs, jitCandidate.block.pos, ev.midiNote, instrument))
                    jitCandidate.currentNoteIndex = targetNoteIndex
                    jitCandidate.freeAtMs = ev.timeMs + ev.durationMs
                    usedBlockPositions.add(jitCandidate.block.pos)
                    continue
                }

                // 4. Infeasible without dropping or shifting
                unplayable.add(ev)
            }
        }

        scheduledPlays.sortBy { it.timeMs }
        scheduledRetunes.sortBy { it.startMs }

        val distinctPitches = events.map { it.midiNote }.distinct().size
        val stats = GenericAllocationStats(
            totalNotes = events.size,
            physicalBlocksUsed = usedBlockPositions.size,
            distinctPitchesNeeded = distinctPitches,
            lookaheadRetunesScheduled = scheduledRetunes.size,
            maxConcurrentPolyphony = maxPolyphony
        )

        return GenericPerformancePlan(scheduledPlays, scheduledRetunes, unplayable, stats)
    }

    /**
     * Compiles a full dynamic performance plan from a chronological list of note events
     * and a list of reachable organ blocks.
     */
    fun compilePlan(
        events: List<TimelineNote>,
        reachableBlocks: List<NoteBlockEntry>,
        maxClicksPerRetune: Int = 2
    ): DynamicPerformancePlan {
        val genericEvents = events.map { GenericTimelineNote(it.timeMs, it.midiNote, it.instrument, it.durationMs) }
        val genericBlocks = reachableBlocks.map {
            VoiceBlockSpec(
                pos = it.pos,
                instrument = it.instrument,
                noteIndex = it.noteIndex,
                isPlayable = it.status == NoteBlockStatus.PLAYABLE,
                distanceFromPlayer = it.distanceFromPlayer
            )
        }
        val plan = compileGenericPlan(
            events = genericEvents,
            reachableBlocks = genericBlocks,
            instrumentSpec = { InstrumentSpec(it.midiBase, it.isPitched) },
            maxClicksPerRetune = maxClicksPerRetune
        )

        val playNotes = plan.playNotes.map { ScheduledPlayNote(it.timeMs, it.pos, it.midiNote, it.instrument) }
        val retunes = plan.retuneTasks.map {
            ScheduledRetuneTask(
                startMs = it.startMs,
                deadlineMs = it.deadlineMs,
                pos = it.pos,
                startNoteIndex = it.startNoteIndex,
                targetNoteIndex = it.targetNoteIndex,
                instrument = it.instrument,
                clicks = it.clicks
            )
        }
        val unplayable = plan.unplayable.map { TimelineNote(it.timeMs, it.midiNote, it.instrument, it.durationMs) }
        val stats = DynamicAllocationStats(
            totalNotes = plan.stats.totalNotes,
            physicalBlocksUsed = plan.stats.physicalBlocksUsed,
            distinctPitchesNeeded = plan.stats.distinctPitchesNeeded,
            lookaheadRetunesScheduled = plan.stats.lookaheadRetunesScheduled,
            maxConcurrentPolyphony = plan.stats.maxConcurrentPolyphony
        )

        logger.info(
            "compilePlan: ${playNotes.size}/${events.size} notes scheduled across ${stats.physicalBlocksUsed} blocks " +
            "with ${retunes.size} lookahead retune tasks (${unplayable.size} unplayable)"
        )

        return DynamicPerformancePlan(playNotes, retunes, unplayable, stats)
    }
}
