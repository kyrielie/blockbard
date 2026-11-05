package kyrielie.blockbard.client.playback

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.gui.PlaybackHud
import kyrielie.blockbard.client.organ.OrganScanner
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.render.ManualTuningHighlighter
import kyrielie.blockbard.client.render.OrganOverlayRenderer
import kyrielie.blockbard.client.util.Notifier
import kyrielie.blockbard.client.util.TuningTraceFile
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.organ.MidiToOrganMapper
import kyrielie.blockbard.organ.NoteBlockEntry
import kyrielie.blockbard.organ.NoteBlockRegistry
import kyrielie.blockbard.organ.NoteBlockTuner
import kyrielie.blockbard.organ.NotePitch
import kyrielie.blockbard.organ.OrganAssignment
import kyrielie.blockbard.organ.TuneTarget
import kyrielie.blockbard.organ.TunerState
import kyrielie.blockbard.organ.displayName
import kyrielie.blockbard.util.BlockBardLogBuffer
import kyrielie.blockbard.util.blockBelowHint
import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.formatCoverageMessage
import kyrielie.blockbard.util.orderByAngularProximity
import net.minecraft.client.Minecraft
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.NoteBlock
import org.slf4j.LoggerFactory

/**
 * Owns the tuner run, the organ assignment it produces, and the tune-then-play chain. Ticked by
 * [PlaybackCoordinator.tick] from END_CLIENT_TICK, so tuning runs and finishes the same way with
 * the GUI open or closed. The screen only describes what to tune for ([Source]) and shows state.
 *
 * Client thread only.
 */
object TuningController {
    private val logger = LoggerFactory.getLogger("BlockBard/TuningController")

    /** What to tune the organ for. */
    sealed interface Source {
        /** A loaded song. [label] ("MIDI" or "NBS") is only for the log. */
        class Song(val label: String, val noteUsageCounts: Map<NotePitch, Int>) : Source

        /** Every pitch 0..24 of every instrument in reach. */
        object Chromatic : Source
    }

    var tuner: NoteBlockTuner? = null
        private set

    val isActive: Boolean get() = tuner?.isActive == true

    /** The organ assignment for the song last tuned for or played; null for a chromatic tune. */
    var assignment: OrganAssignment? = null
        private set

    /** Short progress text from the tuner, shown in the GUI. Empty when idle. */
    var statusMsg: String = ""
        private set

    /** Reason the last run ended in manual tuning being required; cleared by the next success. */
    var error: String? = null
        private set

    /** One-line coverage summary for the current assignment. */
    var coverageMessage: String = ""

    // When the current run finishes successfully, start playback.
    private var playAfterTune: Boolean = false

    fun clearError() {
        error = null
    }

    /** "Tuning... 12/40 msg" while a run is active, else null. */
    fun progressLine(): String? {
        val t = tuner ?: return null
        val stateLabel = when (t.state) {
            TunerState.TUNING -> "Tuning..."
            TunerState.VERIFYING -> "Verifying..."
            else -> ""
        }
        return "$stateLabel ${t.confirmedBlocks}/${t.total} $statusMsg"
    }

    /**
     * Assigns the song's notes to reachable blocks and publishes the result to the scheduler.
     * Replaces the previous assignment.
     */
    fun assign(noteUsageCounts: Map<NotePitch, Int>, reachable: List<NoteBlockEntry>): OrganAssignment {
        val result = MidiToOrganMapper.buildAssignment(noteUsageCounts, reachable)
        assignment = result
        ArpeggioScheduler.assignment = result.assignment
        ArpeggioScheduler.voicePools = result.voicePools
        updateCoverage(result)
        return result
    }

    /**
     * Starts a tuner run for [source]. When [thenPlay] is set, playback starts through the
     * coordinator's host as soon as the run finishes (or at once if nothing needs tuning).
     * Refusals (creative mode, no empty hand, no organ in reach) are reported in chat and
     * leave state unchanged.
     */
    fun start(source: Source, thenPlay: Boolean) {
        SongAudioPreviewer.stopPreview()
        val mc = Minecraft.getInstance()
        val player = mc.player ?: run { Notifier.warn("No player"); return }

        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            Notifier.warn("Cannot tune in ${gameMode.getName()} mode")
            return
        }

        // Auto-select empty hotbar slot if main hand is holding an item
        if (!player.mainHandItem.isEmpty) {
            val emptySlot = (0..8).firstOrNull { player.inventory.getItem(it).isEmpty }
            if (emptySlot != null) {
                player.inventory.selectedSlot = emptySlot
                player.connection.send(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(emptySlot))
                logger.info("start: switched to empty hotbar slot $emptySlot")
            } else {
                Notifier.warn("Hotbar has no empty slots -- clear a slot before tuning")
                return
            }
        }
        if (!player.offhandItem.isEmpty) {
            Notifier.warn("Empty your offhand before tuning")
            return
        }

        val map = PlayerController.organMap ?: run {
            Notifier.warn("Run Center first!")
            return
        }

        val reachable = NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) }
        if (reachable.isEmpty()) {
            Notifier.warn("No reachable noteblocks -- run Scan then Center first")
            return
        }

        val targets: List<TuneTarget> = when (source) {
            is Source.Song -> {
                logger.info("start: ${source.label} mode -- ${reachable.size} reachable, ${source.noteUsageCounts.size} distinct pairs")
                assignAndReport(source.noteUsageCounts, reachable)
            }
            Source.Chromatic -> chromaticTargets(reachable)
        }

        val totalClicks = targets.sumOf { it.estimatedClicks }
        if (targets.isEmpty() || totalClicks == 0) {
            Notifier.info("Organ is in tune!")
            logger.info("start: 0 clicks needed -- skipping tuner")
            if (thenPlay) PlaybackCoordinator.host?.startPlayback()
            return
        }

        Notifier.info("Tuning ${targets.size} blocks (~$totalClicks clicks)...")
        logger.info("start: ${targets.size} targets, $totalClicks clicks, thenPlay=$thenPlay")

        // Visit blocks in angular order so the camera sweeps smoothly instead of jumping around.
        val look = PlayerController.currentLookAngles()
        val orderedTargets = if (look == null) targets else orderByAngularProximity(targets, look.first, look.second) {
            PlayerController.aimAnglesFor(it.pos)
        }

        // Marks from earlier failed runs must not hide blocks that may simply have hit a transient
        // problem; deterministic failures are marked again by this run.
        NoteBlockRegistry.clearUntunable(orderedTargets.map { it.pos })

        val clickIntervalTicks = ConfigManager.config.tuningClickIntervalTicks
        TuningTraceFile.begin(
            "tuning run: ${orderedTargets.size} targets, $totalClicks est. clicks, intervalTicks=$clickIntervalTicks, " +
                "fastTuning=${ConfigManager.config.fastTuning}, rotateDuringTuning=${ConfigManager.config.rotateDuringTuning}, " +
                "ping=${PlayerController.currentPingMs()}ms"
        )
        BlockBardLogBuffer.info("Tuner", "Per-click trace: ${TuningTraceFile.path}")

        val newTuner = NoteBlockTuner(
            targets           = orderedTargets,
            worldNoteReader   = { pos ->
                mc.level?.getBlockState(pos)?.let { state ->
                    if (state.block == Blocks.NOTE_BLOCK) state.getValue(NoteBlock.NOTE) else null
                }
            },
            interactBlock     = { pos -> PlayerController.interactWith(pos) },
            pingMs            = { PlayerController.currentPingMs() },
            fastTuning        = ConfigManager.config.fastTuning,
            primeRotation     = { pos -> PlayerController.primeRotation(pos, isTuning = true) },
            rotationConverged = { pos -> PlayerController.rotationConverged(pos, isTuning = true) },
            diagnoseFailState = { pos, _, _ ->
                PlayerController.diagnoseBlockInteractability(pos)
            },
            clickGapMs        = clickIntervalTicks * 50L,
            fastClickGapMs    = maxOf(1, clickIntervalTicks / 2) * 50L,
            onTrace           = { line -> TuningTraceFile.write(line) },
            onBlockUntunable  = { target, failState ->
                NoteBlockRegistry.markUntunable(target.pos, failState)
            },
            onBlockFailed     = { target, failState, _, _ ->
                ManualTuningHighlighter.add(
                    target.pos,
                    target.instrument,
                    target.targetNote,
                    failState
                )
            },
            onProgress        = { done, total, msg ->
                statusMsg = "$done/$total -- $msg"
                logger.debug("tuner progress: $statusMsg")
            }
        )
        tuner = newTuner
        playAfterTune = thenPlay
        newTuner.start()
    }

    /** Client thread, every tick (from [PlaybackCoordinator.tick]). */
    fun tick() {
        val t = tuner ?: return
        t.onTick()

        if (t.isDone) {
            tuner = null
            statusMsg = ""
            error = null
            Notifier.info("Tuning complete!")
            logger.info("tuning complete, playAfterTune=$playAfterTune")
            OrganScanner.scan()
            val host = PlaybackCoordinator.host
            host?.onTuningComplete()
            // If start() was called through the tune-then-play chain, play now that tuning is verified.
            if (playAfterTune) {
                playAfterTune = false
                host?.startPlayback()
            }
        } else if (t.isFailed) {
            val reason = t.failureReason ?: "Verification failed after retries"
            error = reason
            tuner = null
            statusMsg = ""
            playAfterTune = false
            Notifier.warn("Tuning: $reason. Highlighted in world for manual tuning.")
            logger.warn("tuning finished with manual tuning required: $reason")
        }
    }

    /** Cancels a run in progress and the play-after-tune it carried. Does not clear [error]. */
    fun abort() {
        if (tuner?.isActive == true) {
            Notifier.info("Tuning cancelled")
            logger.info("abort: tuner cancelled")
        }
        tuner = null
        playAfterTune = false
        statusMsg = ""
        OrganOverlayRenderer.activeTargets = emptyMap()
    }

    private fun assignAndReport(noteUsageCounts: Map<NotePitch, Int>, reachable: List<NoteBlockEntry>): List<TuneTarget> {
        val result = assign(noteUsageCounts, reachable)
        if (result.unplayable.isNotEmpty()) {
            val names = result.unplayable.map { it.displayName() }
            Notifier.warn("${names.size} notes unplayable: ${names.take(6).joinToString()}")
        }
        val targets = MidiToOrganMapper.computeTuneTargets(result, reachable)
        OrganOverlayRenderer.activeTargets = targets.associate { it.pos to it.targetNote }
        return targets
    }

    /** Full chromatic scale tuning (0..24 for each instrument in reach). */
    private fun chromaticTargets(reachable: List<NoteBlockEntry>): List<TuneTarget> {
        logger.info("start: chromatic mode -- ${reachable.size} blocks across instruments")
        Notifier.info("Tuning organ to chromatic scale (0..24 for each instrument)...")
        val sorted = reachable.sortedBy { it.distanceFromPlayer }
        val targets = sorted.groupBy { it.instrument }.flatMap { (inst, blocksForInstrument) ->
            val instTargets = mutableListOf<TuneTarget>()
            val maxPitches = minOf(25, blocksForInstrument.size)
            val pitchesToCover = (0 until maxPitches).toMutableSet()
            val available = blocksForInstrument.toMutableList()

            // Pass 1: Keep blocks that already match a pitch in 0..24 (0 clicks)
            for (pitch in 0 until maxPitches) {
                val exact = available.filter { it.noteIndex == pitch }.minByOrNull { it.distanceFromPlayer }
                if (exact != null) {
                    instTargets.add(TuneTarget(exact.pos, exact.noteIndex, pitch, inst))
                    pitchesToCover.remove(pitch)
                    available.remove(exact)
                }
            }

            // Pass 2: Assign missing pitches to available blocks needing the fewest clicks
            for (pitch in pitchesToCover.sorted()) {
                val best = available.minWithOrNull(
                    compareBy(
                        { clicksNeeded(it.noteIndex, pitch) },
                        { it.distanceFromPlayer }
                    )
                )
                if (best != null) {
                    instTargets.add(TuneTarget(best.pos, best.noteIndex, pitch, inst))
                    available.remove(best)
                }
            }

            // Pass 3: Excess blocks beyond 25 keep their existing note (0 clicks, don't retune!)
            for (excess in available) {
                instTargets.add(TuneTarget(excess.pos, excess.noteIndex, excess.noteIndex, inst))
            }

            instTargets
        }
        assignment = null
        ArpeggioScheduler.assignment = emptyMap()
        ArpeggioScheduler.voicePools = emptyMap()
        coverageMessage = "Chromatic scale: ${targets.size} blocks"
        return targets
    }

    private fun updateCoverage(result: OrganAssignment) {
        val covered = result.assignment.size
        val total = covered + result.unplayable.size

        // Group unplayable notes by instrument and include block-below material hints.
        val byInstrument = result.unplayable
            .filter { it.instrument != null }
            .groupBy { it.instrument!! }
            .mapValues { (_, notes) -> notes.map { it.midiNote }.distinct().size }
        val anyInstrumentCount = result.unplayable.count { it.instrument == null }
        val shiftedCount = result.shifts.count { it.value != 0 }

        coverageMessage = formatCoverageMessage(
            covered = covered,
            total = total,
            shiftedCount = shiftedCount,
            addHints = byInstrument.entries.map { (inst, count) -> "$count ${inst.name} (${inst.blockBelowHint})" },
            anyInstrumentCount = anyInstrumentCount
        )

        if (shiftedCount > 0) {
            val shiftDetails = result.shifts.filter { it.value != 0 }
                .map { (note, shift) -> "${note.displayName()} (${if (shift > 0) "+$shift" else "$shift"} st)" }
                .take(6).joinToString()
            logger.warn("coverage: $shiftedCount notes octave-shifted due to organ pitch limits: $shiftDetails")
            PlaybackHud.lastShiftMessage = "⚠ $shiftedCount notes octave-shifted ($shiftDetails)"
        } else {
            PlaybackHud.lastShiftMessage = ""
        }
        logger.info("coverage: $coverageMessage")
    }
}
