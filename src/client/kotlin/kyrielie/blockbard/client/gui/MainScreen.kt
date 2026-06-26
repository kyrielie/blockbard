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

    // Playback state
    private var tuner: NoteBlockTuner? = null
    private var tuningTargets: List<TuneTarget> = emptyList()
    private var tuneProgress: Pair<Int, Int> = Pair(0, 0)
    private var assignment: OrganAssignment? = null
    private var organScanMessage: String = "Press Scan to detect noteblocks"
    private var coverageMessage: String = ""
    private var scanTicker = 0

    // Scale playback state
    private var scaleNotes: List<NoteBlockEntry> = emptyList()
    private var scaleIndex: Int = -1
    private var scaleTicker: Int = 0
    private val scaleTickInterval = 4  // ticks between each note (~200ms)

    override fun init() {
        super.init()
        logger.info("MainScreen: init()")
        refreshFiles()
        OrganScanner.scan()
        updateOrganInfo()

        // ── File list controls ──
        addRenderableWidget(
            Button.builder(Component.literal("↑")) {
                scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
                logger.debug("MainScreen: scroll up → offset=$scrollOffset")
            }.pos(10, 60).size(20, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("↓")) {
                scrollOffset = (scrollOffset + 1).coerceAtMost((midiFiles.size - maxVisible).coerceAtLeast(0))
                logger.debug("MainScreen: scroll down → offset=$scrollOffset")
            }.pos(10, 80).size(20, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Refresh")) {
                logger.info("MainScreen: Refresh clicked")
                refreshFiles()
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fRefreshed — ${midiFiles.size} files found"))
            }.pos(10, 100).size(60, 16).build()
        )

        // ── Organ controls ──
        addRenderableWidget(
            Button.builder(Component.literal("Scan")) {
                logger.info("MainScreen: Scan clicked")
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fScanning..."))
                OrganScanner.scan()
                updateOrganInfo()
                // OrganScanner.scan() already prints results to chat
            }.pos(10, 200).size(50, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Center")) {
                logger.info("MainScreen: Center clicked")
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fCentering on organ..."))
                val result = PlayerController.centerOnOrgan()
                organScanMessage = when (result) {
                    is CenterResult.Centered -> "Centered. ${result.reachableCount}/${result.totalFound} reachable."
                    CenterResult.NoBlocks -> "No playable noteblocks found."
                    CenterResult.NoPlayer -> "No player found."
                }
                val msg = "§b[BlockBard] §f$organScanMessage"
                minecraft.player?.sendSystemMessage(Component.literal(msg))
                logger.info("MainScreen: Center result → $organScanMessage")
            }.pos(65, 200).size(60, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Tune")) {
                logger.info("MainScreen: Tune clicked")
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fStarting tuning..."))
                startTuning()
            }.pos(130, 200).size(50, 16).build()
        )

        // ── Scale test ──
        addRenderableWidget(
            Button.builder(Component.literal("▶ Scale")) {
                logger.info("MainScreen: Play Scale clicked")
                playScale()
            }.pos(185, 200).size(60, 16).build()
        )

        // ── Playback controls ──
        addRenderableWidget(
            Button.builder(Component.literal("▶ Play")) {
                logger.info("MainScreen: Play clicked — isPaused=${MidiFilePlayer.isPaused}, selectedFile=${selectedFile?.name}")
                if (MidiFilePlayer.isPaused) {
                    MidiFilePlayer.resume()
                    minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §aResumed"))
                } else {
                    startPlayback()
                }
            }.pos(10, 222).size(55, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("⏸ Pause")) {
                logger.info("MainScreen: Pause clicked — isPaused=${MidiFilePlayer.isPaused}")
                if (MidiFilePlayer.isPaused) {
                    MidiFilePlayer.resume()
                    minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §aResumed"))
                } else {
                    MidiFilePlayer.pause()
                    minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §ePaused"))
                }
            }.pos(70, 222).size(60, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("⏹ Stop")) {
                logger.info("MainScreen: Stop clicked")
                MidiFilePlayer.stop()
                NbsPlayer.stop()
                stopScale()
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §cStopped"))
            }.pos(135, 222).size(50, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("⇀ Shuffle")) {
                logger.info("MainScreen: Shuffle clicked")
                shuffle()
            }.pos(190, 222).size(60, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Tempo -")) {
                MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier - 0.1f).coerceAtLeast(0.5f)
                logger.info("MainScreen: Tempo − → ${MidiFilePlayer.tempoMultiplier}")
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fTempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x"))
            }.pos(10, 242).size(55, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Tempo +")) {
                MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier + 0.1f).coerceAtMost(2.0f)
                logger.info("MainScreen: Tempo + → ${MidiFilePlayer.tempoMultiplier}")
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fTempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x"))
            }.pos(70, 242).size(55, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Close")) {
                logger.info("MainScreen: Close clicked")
                onClose()
            }.pos(width - 70, height - 24).size(60, 16).build()
        )
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

    private fun startTuning() {
        val midi = MidiFilePlayer.loadedMidi ?: run {
            organScanMessage = "No MIDI file loaded!"
            minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §cNo MIDI file loaded — select one first"))
            logger.warn("startTuning: no MIDI loaded")
            return
        }
        val map = PlayerController.organMap ?: run {
            organScanMessage = "Run Center first!"
            minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §cRun Center first"))
            logger.warn("startTuning: no organ map — Center must be run first")
            return
        }
        val reachable = NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) }
        logger.info("startTuning: ${reachable.size} reachable playable blocks, ${midi.distinctNotes.size} distinct MIDI notes in file")

        val result = MidiToOrganMapper.buildAssignment(midi.noteUsageCounts, reachable)
        assignment = result
        logger.info("startTuning: mapped ${result.assignment.size} notes, ${result.unplayable.size} unplayable: ${result.unplayable.map { midiNoteToName(it) }}")

        val targets = MidiToOrganMapper.computeTuneTargets(result, reachable)
        tuningTargets = targets
        val totalClicks = targets.sumOf { it.clicksRequired }
        logger.info("startTuning: ${targets.size} blocks to tune, $totalClicks total clicks")
        minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fTuning ${targets.size} blocks ($totalClicks clicks)..."))

        tuner = NoteBlockTuner(targets) { done, total ->
            tuneProgress = Pair(done, total)
            logger.debug("tuning progress: $done/$total blocks")
        }
        organScanMessage = "Tuning..."
        updateCoverageMessage(result)
    }

    private fun updateCoverageMessage(result: OrganAssignment) {
        val total = (result.assignment.size + result.unplayable.size)
        val covered = result.assignment.size
        val unplayable = result.unplayable.map { midiNoteToName(it) }
        coverageMessage = "$covered/$total notes covered" +
                if (unplayable.isNotEmpty()) ". Missing: ${unplayable.take(4).joinToString()}" else ""
        logger.info("coverage: $coverageMessage")
    }

    // ── Scale playback ──

    private fun playScale() {
        val mc = minecraft
        val player = mc.player ?: run {
            logger.warn("playScale: no player")
            return
        }

        // Creative mode guard
        val gameMode = mc.gameMode?.playerMode
        if (gameMode == net.minecraft.world.level.GameType.CREATIVE) {
            val msg = "§e[BlockBard] §cCannot play scale in Creative mode — noteblocks would break!"
            player.sendSystemMessage(Component.literal(msg))
            logger.warn("playScale: blocked — Creative mode")
            return
        }

        val playable = NoteBlockRegistry.allPlayable()
        if (playable.isEmpty()) {
            player.sendSystemMessage(Component.literal("§b[BlockBard] §cNo playable noteblocks found — run Scan first"))
            logger.warn("playScale: no playable blocks in registry")
            return
        }

        // Sort by MIDI note for a natural ascending scale
        scaleNotes = playable.sortedBy { it.midiNote }
        scaleIndex = 0
        scaleTicker = 0

        val noteNames = scaleNotes.map { midiNoteToName(it.midiNote) }
        logger.info("playScale: playing ${scaleNotes.size} notes: $noteNames")
        player.sendSystemMessage(Component.literal("§b[BlockBard] §aPlaying scale: ${noteNames.joinToString(" → ")}"))
    }

    private fun stopScale() {
        if (scaleIndex >= 0) {
            logger.info("stopScale: cancelled at note $scaleIndex/${scaleNotes.size}")
            minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §eScale stopped"))
        }
        scaleNotes = emptyList()
        scaleIndex = -1
        scaleTicker = 0
    }

    private fun tickScale() {
        if (scaleIndex < 0 || scaleIndex >= scaleNotes.size) {
            if (scaleIndex >= scaleNotes.size) {
                logger.info("tickScale: scale complete")
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §aScale complete!"))
                stopScale()
            }
            return
        }

        scaleTicker++
        if (scaleTicker < scaleTickInterval) return
        scaleTicker = 0

        val entry = scaleNotes[scaleIndex]
        val noteName = midiNoteToName(entry.midiNote)
        logger.info("tickScale: playing note $scaleIndex/${scaleNotes.size - 1} — ${entry.instrument.name} noteIndex=${entry.noteIndex} midi=${entry.midiNote} ($noteName) at ${entry.pos}")
        minecraft.player?.sendSystemMessage(
            Component.literal("§b[BlockBard] §f[$scaleIndex] ${entry.instrument.name} §a$noteName §7@ ${entry.pos.toShortString()}")
        )

        // Enqueue with pre-resolved pos so it bypasses the assignment map
        kyrielie.blockbard.organ.ArpeggioScheduler.enqueue(
            kyrielie.blockbard.organ.NoteRequest(entry.midiNote, resolvedPos = entry.pos)
        )
        scaleIndex++
    }

    private fun startPlayback() {
        val file = selectedFile ?: run {
            minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §cNo file selected"))
            logger.warn("startPlayback: no file selected")
            return
        }
        logger.info("startPlayback: starting ${file.name}")
        minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §aPlaying: ${file.name}"))
        if (file.extension.lowercase() == "nbs") {
            val nbs = NbsFileLoader.load(file)
            logger.info("startPlayback: loaded NBS '${nbs.title}' — ${nbs.notes.size} notes at ${nbs.tempo} tps")
            NbsPlayer.play(nbs, MidiFilePlayer.tempoMultiplier)
        } else {
            if (MidiFilePlayer.loadedMidi?.file != file) {
                val midi = MidiFilePlayer.load(file)
                logger.info("startPlayback: loaded MIDI — ${midi.events.size} events, ${midi.distinctNotes.size} distinct notes")
            }
            MidiFilePlayer.play()
        }
    }

    private fun shuffle() {
        val available = midiFiles.filter { it != selectedFile }
        if (available.isEmpty()) {
            logger.info("shuffle: only one file, nothing to shuffle to")
            return
        }
        val next = available.random()
        logger.info("shuffle: selected ${next.name}")
        selectFile(next)
    }

    private fun selectFile(file: File) {
        selectedFile = file
        selectedIndex = midiFiles.indexOf(file)
        MidiFilePlayer.stop()
        if (file.extension.lowercase() in listOf("mid", "midi")) {
            val midi = MidiFilePlayer.load(file)
            logger.info("selectFile: loaded ${file.name} — ${midi.events.size} events, tempo=${midi.baseTempoUsPerBeat}µs/beat")
            minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fLoaded: ${file.name} (${midi.distinctNotes.size} distinct notes)"))
        }
        ConfigManager.config.lastPlayedTrack = file.name
        organScanMessage = "Loaded: ${file.name}. Run Scan → Center → Tune → Play."
        coverageMessage = ""
    }

    override fun tick() {
        super.tick()

        // Auto-rescan
        scanTicker++
        if (scanTicker >= 20 * ConfigManager.config.autoRescanIntervalSeconds) {
            scanTicker = 0
            OrganScanner.scan()
            updateOrganInfo()
        }

        // Tuning tick — one click per tick
        val t = tuner
        if (t != null && !t.isDone) {
            val pos = t.nextClick()
            if (pos != null) {
                logger.debug("tick: tuning click at $pos (${t.completedBlocks}/${t.totalBlocks})")
                PlayerController.interactWith(pos)
            }
            if (t.isDone) {
                tuner = null
                organScanMessage = "Tuning complete! Press Play."
                minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §aTuning complete!"))
                logger.info("tick: tuning complete")
            }
        }

        // Scale tick
        tickScale()
    }

    // In 26.x, Screen uses extractRenderState() instead of render()
    // Do NOT call extractBackground() here — already called before this by the engine
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
            graphics.text(font, "(no files — drop .mid/.nbs in config/blockbard/midis/)", 10, 36, 0xAAAAAA)
        }

        // Organ status
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

        if (tuner != null) {
            val (done, total) = tuneProgress
            graphics.text(font, "Tuning $done/$total blocks...", 10, cy + 2, 0x44FF88)
        }

        if (scaleIndex >= 0 && scaleIndex < scaleNotes.size) {
            val entry = scaleNotes[scaleIndex]
            graphics.text(font, "♪ Scale: ${midiNoteToName(entry.midiNote)} ($scaleIndex/${scaleNotes.size})", 10, cy + 14, 0xAAFF44)
        }

        // Playback status
        val pbY = height - 42
        val status = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> "▶ PLAYING ${selectedFile?.name ?: ""}"
            MidiFilePlayer.isPaused -> "⏸ PAUSED"
            else -> "⏹ IDLE"
        }
        graphics.text(font, status, 10, pbY, 0xAAFFAA)
        graphics.text(font, "Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x", 200, pbY, 0xCCCCCC)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        if (event.button() == 0) {
            val visibleFiles = midiFiles.drop(scrollOffset).take(maxVisible)
            visibleFiles.forEachIndexed { i, file ->
                val y = 36 + i * 14
                if (mouseX >= 34 && mouseX <= width - 10 && mouseY >= y && mouseY < y + 14) {
                    logger.info("MainScreen: file clicked — ${file.name}")
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

    override fun isPauseScreen(): Boolean = false
}
