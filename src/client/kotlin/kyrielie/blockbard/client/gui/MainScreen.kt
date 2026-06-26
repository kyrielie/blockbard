package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.client.playback.NbsFileLoader
import kyrielie.blockbard.client.playback.NbsPlayer
import kyrielie.blockbard.organ.*
import kyrielie.blockbard.client.organ.OrganScanner
import kyrielie.blockbard.client.player.CenterResult
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.util.midiNoteToName
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.level.GameType
import org.slf4j.LoggerFactory
import java.io.File

class MainScreen(private val parent: Screen? = null) : Screen(Component.literal("BlockBard")) {

    private val logger = LoggerFactory.getLogger("BlockBard/MainScreen")

    private val midisDir: File by lazy {
        FabricLoader.getInstance().configDir.resolve("blockbard/midis").toFile().also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                dir.resolve("README.txt").writeText(
                    "Drop .mid or .nbs files here and click 'Refresh' in BlockBard."
                )
            }
        }
    }

    private var midiFiles: List<File> = emptyList()
    private var selectedFile: File? = null
    private var selectedIndex: Int = -1
    private var scrollOffset: Int = 0
    private val maxVisible = 8

    // Tuning state
    private var tuner: NoteBlockTuner? = null
    private var assignment: OrganAssignment? = null
    private var tuneStatusMsg: String = ""

    // General status
    private var organScanMessage: String = "Press Scan to detect noteblocks"
    private var coverageMessage: String = ""
    private var scanTicker = 0

    // Scale playback state
    private var scaleNotes: List<NoteBlockEntry> = emptyList()
    private var scaleIndex: Int = -1
    private var scaleTicker: Int = 0
    private val scaleTickInterval = 4 // ticks between notes (~200ms at 20tps)

    override fun init() {
        super.init()
        logger.info("MainScreen: init()")
        refreshFiles()
        OrganScanner.scan()
        updateOrganInfo()

        // ── File list controls ──
        addRenderableWidget(Button.builder(Component.literal("↑")) {
            scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
            logger.debug("scroll up → offset=$scrollOffset")
        }.pos(10, 60).size(20, 16).build())

        addRenderableWidget(Button.builder(Component.literal("↓")) {
            scrollOffset = (scrollOffset + 1).coerceAtMost((midiFiles.size - maxVisible).coerceAtLeast(0))
            logger.debug("scroll down → offset=$scrollOffset")
        }.pos(10, 80).size(20, 16).build())

        addRenderableWidget(Button.builder(Component.literal("Refresh")) {
            logger.info("Refresh clicked")
            refreshFiles()
            chat("Refreshed — ${midiFiles.size} files found")
        }.pos(10, 100).size(60, 16).build())

        // ── Organ controls ──
        addRenderableWidget(Button.builder(Component.literal("Scan")) {
            logger.info("Scan clicked")
            OrganScanner.scan()
            updateOrganInfo()
        }.pos(10, 200).size(50, 16).build())

        addRenderableWidget(Button.builder(Component.literal("Center")) {
            logger.info("Center clicked")
            chat("Centering on organ...")
            val result = PlayerController.centerOnOrgan()
            organScanMessage = when (result) {
                is CenterResult.Centered -> "Centered. ${result.reachableCount}/${result.totalFound} reachable."
                CenterResult.NoBlocks   -> "No playable noteblocks found."
                CenterResult.NoPlayer   -> "No player found."
            }
            chat(organScanMessage)
            logger.info("Center result: $organScanMessage")
        }.pos(65, 200).size(60, 16).build())

        addRenderableWidget(Button.builder(Component.literal("Tune")) {
            logger.info("Tune clicked")
            startTuning()
        }.pos(130, 200).size(50, 16).build())

        addRenderableWidget(Button.builder(Component.literal("▶ Scale")) {
            logger.info("Play Scale clicked")
            playScale()
        }.pos(185, 200).size(60, 16).build())

        // ── Playback controls ──
        addRenderableWidget(Button.builder(Component.literal("▶ Play")) {
            logger.info("Play clicked — isPaused=${MidiFilePlayer.isPaused}, file=${selectedFile?.name}")
            if (MidiFilePlayer.isPaused) {
                MidiFilePlayer.resume()
                chat("Resumed")
            } else {
                startPlayback()
            }
        }.pos(10, 222).size(55, 16).build())

        addRenderableWidget(Button.builder(Component.literal("⏸ Pause")) {
            logger.info("Pause clicked")
            if (MidiFilePlayer.isPaused) {
                MidiFilePlayer.resume(); chat("Resumed")
            } else {
                MidiFilePlayer.pause(); chat("Paused")
            }
        }.pos(70, 222).size(60, 16).build())

        addRenderableWidget(Button.builder(Component.literal("⏹ Stop")) {
            logger.info("Stop clicked")
            MidiFilePlayer.stop()
            NbsPlayer.stop()
            stopScale()
            abortTuning()
            chat("Stopped")
        }.pos(135, 222).size(50, 16).build())

        addRenderableWidget(Button.builder(Component.literal("⇀ Shuffle")) {
            logger.info("Shuffle clicked")
            shuffle()
        }.pos(190, 222).size(60, 16).build())

        addRenderableWidget(Button.builder(Component.literal("Tempo -")) {
            MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier - 0.1f).coerceAtLeast(0.5f)
            logger.info("Tempo − → ${MidiFilePlayer.tempoMultiplier}")
            chat("Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x")
        }.pos(10, 242).size(55, 16).build())

        addRenderableWidget(Button.builder(Component.literal("Tempo +")) {
            MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier + 0.1f).coerceAtMost(2.0f)
            logger.info("Tempo + → ${MidiFilePlayer.tempoMultiplier}")
            chat("Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x")
        }.pos(70, 242).size(55, 16).build())

        addRenderableWidget(Button.builder(Component.literal("Close")) {
            logger.info("Close clicked")
            onClose()
        }.pos(width - 70, height - 24).size(60, 16).build())
    }

    // ── Helpers ──

    private fun chat(msg: String) {
        minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §f$msg"))
    }

    private fun chatWarn(msg: String) {
        minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §c$msg"))
    }

    private fun refreshFiles() {
        midiFiles = (midisDir.listFiles { f ->
            f.extension.lowercase() in listOf("mid", "midi", "nbs")
        } ?: emptyArray()).toList().sortedBy { it.name }
        scrollOffset = 0
        logger.info("refreshFiles: found ${midiFiles.size} files in ${midisDir.absolutePath}")
    }

    private fun updateOrganInfo() {
        val playable = NoteBlockRegistry.allPlayable()
        val silenced = NoteBlockRegistry.all().count { it.status == NoteBlockStatus.SILENCED }
        val mobHeads = NoteBlockRegistry.allMobHeadEntries().size
        organScanMessage = "Playable: ${playable.size}  Silenced: $silenced  Mob heads: $mobHeads"
        logger.info("updateOrganInfo: $organScanMessage")
    }

    // ── Tuning ──

    private fun startTuning() {
        val mc = minecraft
        val player = mc.player ?: run { chatWarn("No player"); return }

        // Gamemode guard
        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            chatWarn("Cannot tune in ${gameMode.getName()} mode")
            logger.warn("startTuning: blocked — $gameMode mode")
            return
        }

        // Empty hand check — critical: useWithoutItem only fires when hands are empty
        val mainHand = player.mainHandItem
        val offHand  = player.offhandItem
        if (!mainHand.isEmpty) {
            chatWarn("Empty your main hand before tuning — held items block noteblock interaction")
            logger.warn("startTuning: blocked — player holding ${mainHand.item} in main hand")
            return
        }
        if (!offHand.isEmpty) {
            chatWarn("Empty your offhand before tuning")
            logger.warn("startTuning: blocked — player holding ${offHand.item} in offhand")
            return
        }

        val map = PlayerController.organMap ?: run {
            chatWarn("Run Center first!")
            logger.warn("startTuning: no organ map")
            return
        }

        val reachable = NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) }
        if (reachable.isEmpty()) {
            chatWarn("No reachable noteblocks — run Scan then Center first")
            logger.warn("startTuning: no reachable blocks")
            return
        }

        val targets: List<TuneTarget>

        val midi = MidiFilePlayer.loadedMidi
        if (midi != null) {
            // MIDI loaded — map notes from the song to blocks
            logger.info("startTuning: MIDI mode — ${reachable.size} reachable blocks, ${midi.distinctNotes.size} distinct notes")
            val result = MidiToOrganMapper.buildAssignment(midi.noteUsageCounts, reachable)
            assignment = result
            if (result.unplayable.isNotEmpty()) {
                val names = result.unplayable.map { midiNoteToName(it) }
                chatWarn("Warning: ${names.size} notes unplayable: ${names.take(6).joinToString()}")
            }
            updateCoverageMessage(result)
            targets = MidiToOrganMapper.computeTuneTargets(result, reachable)
        } else {
            // No MIDI — tune blocks to a chromatic scale starting at F#3 (MIDI 54, noteIndex 0 on HARP)
            // This is the natural test target: one block per semitone, ascending, *per instrument*.
            // Note indices are scoped to each instrument independently (DiscJockey-style:
            // noteblocksForInstrument is grouped per NoteBlockInstrument) — otherwise blocks
            // past the 25th in a flat ordering would all collide on noteIndex 24.
            logger.info("startTuning: no-MIDI mode — tuning ${reachable.size} blocks to chromatic scale from F#3")
            chat("No MIDI loaded — tuning blocks to chromatic scale (F#3 upward)")
            val sorted = reachable.sortedBy { it.distanceFromPlayer }
            targets = sorted
                .groupBy { it.instrument }
                .flatMap { (_, blocksForInstrument) ->
                    blocksForInstrument.mapIndexed { i, entry ->
                        val targetNoteIndex = i.coerceIn(0, 24)
                        TuneTarget(entry.pos, entry.noteIndex, targetNoteIndex, entry.instrument)
                    }
                }
            assignment = null
            val perInstrumentCounts = sorted.groupBy { it.instrument }.mapValues { it.value.size }
            coverageMessage = "Test scale: ${sorted.size} blocks across ${perInstrumentCounts.size} instrument(s) → noteIndex 0–24 per instrument"
        }

        if (targets.isEmpty()) {
            chat("Nothing to tune — all blocks already at target notes!")
            logger.info("startTuning: no targets needed")
            return
        }

        val totalClicks = targets.sumOf { it.estimatedClicks }
        chat("Tuning ${targets.size} blocks (~$totalClicks clicks)...")
        logger.info("startTuning: ${targets.size} targets, $totalClicks total clicks")

        tuner = NoteBlockTuner(
            targets         = targets,
            worldNoteReader = { pos ->
                mc.level?.getBlockState(pos)?.let { state ->
                    if (state.block == net.minecraft.world.level.block.Blocks.NOTE_BLOCK)
                        state.getValue(net.minecraft.world.level.block.NoteBlock.NOTE)
                    else null
                }
            },
            interactBlock   = { pos -> PlayerController.interactWith(pos) },
            pingMs          = { PlayerController.currentPingMs() },
            onProgress      = { done, total, msg ->
                tuneStatusMsg = "$done/$total — $msg"
                logger.debug("tuner progress: $tuneStatusMsg")
            }
        )
        tuner!!.start()
    }

    private fun abortTuning() {
        if (tuner?.isActive == true) {
            logger.info("abortTuning: tuner cancelled")
            chat("Tuning cancelled")
        }
        tuner = null
        tuneStatusMsg = ""
    }

    private fun updateCoverageMessage(result: OrganAssignment) {
        val covered   = result.assignment.size
        val total     = covered + result.unplayable.size
        val unplayable = result.unplayable.map { midiNoteToName(it) }
        coverageMessage = "$covered/$total notes covered" +
                if (unplayable.isNotEmpty()) ". Missing: ${unplayable.take(4).joinToString()}" else ""
        logger.info("coverage: $coverageMessage")
    }

    // ── Scale playback ──

    private fun playScale() {
        val mc = minecraft
        val player = mc.player ?: run { logger.warn("playScale: no player"); return }

        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE) {
            chatWarn("Cannot play scale in Creative — noteblocks would break!")
            logger.warn("playScale: blocked — Creative mode")
            return
        }

        val playable = NoteBlockRegistry.allPlayable()
        if (playable.isEmpty()) {
            chatWarn("No playable noteblocks found — run Scan first")
            logger.warn("playScale: no playable blocks")
            return
        }

        // Sort by actual MIDI note — this naturally orders GUITAR (base 42) before HARP (base 54)
        // and BASS (base 30) before both. No instrument assumptions needed.
        scaleNotes = playable.sortedBy { it.midiNote }
        scaleIndex = 0
        scaleTicker = 0

        val noteNames = scaleNotes.map { "${it.instrument.name.take(4)}:${kyrielie.blockbard.util.midiNoteToName(it.midiNote)}" }
        logger.info("playScale: ${scaleNotes.size} notes in pitch order: $noteNames")
        chat("Playing scale (${scaleNotes.size} notes, ${scaleNotes.first().instrument.name} → ${scaleNotes.last().instrument.name})")
    }

    private fun stopScale() {
        if (scaleIndex >= 0) {
            logger.info("stopScale: cancelled at note $scaleIndex/${scaleNotes.size}")
        }
        scaleNotes = emptyList()
        scaleIndex = -1
        scaleTicker = 0
    }

    private fun tickScale() {
        if (scaleIndex < 0) return
        if (scaleIndex >= scaleNotes.size) {
            logger.info("tickScale: scale complete")
            chat("Scale complete!")
            stopScale()
            return
        }

        scaleTicker++
        if (scaleTicker < scaleTickInterval) return
        scaleTicker = 0

        val entry = scaleNotes[scaleIndex]
        val noteName = midiNoteToName(entry.midiNote)
        logger.info("tickScale: [$scaleIndex/${scaleNotes.size - 1}] ${entry.instrument.name} noteIndex=${entry.noteIndex} midi=${entry.midiNote} ($noteName) @ ${entry.pos}")
        minecraft.player?.sendSystemMessage(
            Component.literal("§b[BlockBard] §f[$scaleIndex] ${entry.instrument.name} §a$noteName §7@ ${entry.pos.toShortString()}")
        )
        ArpeggioScheduler.enqueue(NoteRequest(entry.midiNote, resolvedPos = entry.pos))
        scaleIndex++
    }

    // ── Playback ──

    private fun startPlayback() {
        val file = selectedFile ?: run { chatWarn("No file selected"); return }
        logger.info("startPlayback: ${file.name}")
        chat("Playing: ${file.name}")
        if (file.extension.lowercase() == "nbs") {
            val nbs = NbsFileLoader.load(file)
            logger.info("startPlayback: NBS '${nbs.title}' ${nbs.notes.size} notes at ${nbs.tempo} tps")
            NbsPlayer.play(nbs, MidiFilePlayer.tempoMultiplier)
        } else {
            if (MidiFilePlayer.loadedMidi?.file != file) {
                val midi = MidiFilePlayer.load(file)
                logger.info("startPlayback: MIDI ${midi.events.size} events, ${midi.distinctNotes.size} distinct notes")
            }
            MidiFilePlayer.play()
        }
    }

    private fun shuffle() {
        val available = midiFiles.filter { it != selectedFile }
        if (available.isEmpty()) { logger.info("shuffle: nothing to shuffle to"); return }
        selectFile(available.random())
    }

    private fun selectFile(file: File) {
        selectedFile = file
        selectedIndex = midiFiles.indexOf(file)
        MidiFilePlayer.stop()
        if (file.extension.lowercase() in listOf("mid", "midi")) {
            val midi = MidiFilePlayer.load(file)
            logger.info("selectFile: ${file.name} — ${midi.events.size} events, tempo=${midi.baseTempoUsPerBeat}µs/beat")
            chat("Loaded: ${file.name} (${midi.distinctNotes.size} distinct notes)")
        }
        ConfigManager.config.lastPlayedTrack = file.name
        organScanMessage = "Loaded: ${file.name}. Run Scan → Center → Tune → Play."
        coverageMessage = ""
    }

    // ── Tick ──

    override fun tick() {
        super.tick()

        // Auto-rescan
        scanTicker++
        if (scanTicker >= 20 * ConfigManager.config.autoRescanIntervalSeconds) {
            scanTicker = 0
            OrganScanner.scan()
            updateOrganInfo()
        }

        // Tuner tick
        val t = tuner
        if (t != null) {
            t.onTick()

            if (t.isDone) {
                tuner = null
                organScanMessage = "Tuning complete! Press ▶ Play."
                chat("Tuning complete! All blocks verified.")
                logger.info("tick: tuning complete")
                // Rescan so registry reflects real world state
                OrganScanner.scan()
                updateOrganInfo()
            } else if (t.isFailed) {
                tuner = null
                chatWarn("Tuning failed — check logs for details")
                logger.warn("tick: tuning failed")
            }
        }

        // Scale tick
        tickScale()
    }

    // ── Render ──

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val font = minecraft.font

        graphics.text(font, "♪ BlockBard", 10, 10, 0xFFFFAA)
        graphics.text(font, "MIDI / NBS Files:", 10, 26, 0xCCCCCC)

        // File list
        val visibleFiles = midiFiles.drop(scrollOffset).take(maxVisible)
        visibleFiles.forEachIndexed { i, file ->
            val y = 36 + i * 14
            val realIdx = scrollOffset + i
            val color = if (realIdx == selectedIndex) 0x55FF55 else 0xDDDDDD
            val prefix = if (realIdx == selectedIndex) "▶ " else "  "
            graphics.text(font, prefix + file.name.take(30), 34, y, color)
        }
        if (midiFiles.isEmpty()) {
            graphics.text(font, "(no files — drop .mid/.nbs into config/blockbard/midis/)", 10, 36, 0xAAAAAA)
        }

        // Organ status section
        val organY = 175
        graphics.text(font, "─── Organ ───", 10, organY, 0x88AAFF)
        graphics.text(font, organScanMessage, 10, organY + 12, 0xFFFFFF)

        val counts = NoteBlockRegistry.countPerInstrument()
        var cy = organY + 24
        counts.entries.take(5).forEach { (inst, count) ->
            graphics.text(font, "${inst.name}: $count", 10, cy, 0xDDDDDD)
            cy += 10
        }

        if (coverageMessage.isNotEmpty()) {
            graphics.text(font, coverageMessage, 10, cy + 2, 0xFFCC44)
            cy += 12
        }

        // Tuner status
        val t = tuner
        if (t != null) {
            val stateLabel = when (t.state) {
                TunerState.TUNING    -> "§eTuning..."
                TunerState.VERIFYING -> "§aVerifying..."
                else                 -> ""
            }
            graphics.text(font, "$stateLabel ${t.confirmedBlocks}/${t.total}", 10, cy + 2, 0x44FF88)
            cy += 12
            if (tuneStatusMsg.isNotEmpty()) {
                graphics.text(font, tuneStatusMsg.take(45), 10, cy, 0xCCFFCC)
                cy += 10
            }
        }

        // Scale progress
        if (scaleIndex >= 0 && scaleIndex < scaleNotes.size) {
            val entry = scaleNotes[scaleIndex]
            graphics.text(font, "♪ Scale: ${midiNoteToName(entry.midiNote)} ($scaleIndex/${scaleNotes.size})", 10, cy + 2, 0xAAFF44)
        }

        // Playback status bar
        val pbY = height - 42
        val status = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused ->
                "▶ PLAYING  ${selectedFile?.name ?: ""}"
            MidiFilePlayer.isPaused -> "⏸ PAUSED"
            else                    -> "⏹ IDLE"
        }
        graphics.text(font, status, 10, pbY, 0xAAFFAA)
        graphics.text(font, "Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x", 200, pbY, 0xCCCCCC)

        // Ping display (useful when tuning on a server)
        val ping = PlayerController.currentPingMs()
        if (ping > 0) graphics.text(font, "Ping: ${ping}ms", 200, pbY + 10, 0xAAAAAA)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        if (event.button() == 0) {
            val visibleFiles = midiFiles.drop(scrollOffset).take(maxVisible)
            visibleFiles.forEachIndexed { i, file ->
                val y = 36 + i * 14
                if (mouseX >= 34 && mouseX <= width - 10 && mouseY >= y && mouseY < y + 14) {
                    logger.info("file clicked: ${file.name}")
                    selectFile(file)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun onClose() {
        logger.info("MainScreen: closing")
        ConfigManager.save()
        minecraft.gui.setScreen(parent)
    }

    // Suppress the background blur — keeps the game visually unobstructed while the GUI is open
    override fun extractBlurredBackground(graphics: GuiGraphicsExtractor) {
        // Intentionally empty — do not call super, which would call graphics.blurBeforeThisStratum()
    }

    override fun isPauseScreen(): Boolean = false
}