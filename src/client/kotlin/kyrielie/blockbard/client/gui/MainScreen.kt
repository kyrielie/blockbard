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
import net.minecraft.client.gui.GuiGraphicsExtractor          // was: GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent             // new: event object for mouseClicked
import net.minecraft.network.chat.Component
import java.io.File

class MainScreen(private val parent: Screen? = null) : Screen(Component.literal("BlockBard")) {

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
    private var lastShiftMessage: String = ""
    private var scanTicker = 0

    // Tuning tick queue
    private var pendingTunerBlock: net.minecraft.core.BlockPos? = null

    override fun init() {
        super.init()
        refreshFiles()
        OrganScanner.scan()
        updateOrganInfo()

        // ── File list controls ──
        addRenderableWidget(
            Button.builder(Component.literal("↑")) { scrollOffset = (scrollOffset - 1).coerceAtLeast(0) }
                .pos(10, 60).size(20, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("↓")) { scrollOffset = (scrollOffset + 1).coerceAtMost((midiFiles.size - maxVisible).coerceAtLeast(0)) }
                .pos(10, 80).size(20, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Refresh")) { refreshFiles() }
                .pos(10, 100).size(60, 16).build()
        )

        // ── Organ controls ──
        addRenderableWidget(
            Button.builder(Component.literal("Scan")) {
                OrganScanner.scan()
                updateOrganInfo()
            }.pos(10, 200).size(50, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Center")) {
                val result = PlayerController.centerOnOrgan()
                organScanMessage = when (result) {
                    is CenterResult.Centered -> "Centered. ${result.reachableCount}/${result.totalFound} reachable."
                    CenterResult.NoBlocks -> "No playable noteblocks found."
                    CenterResult.NoPlayer -> "No player found."
                }
            }.pos(65, 200).size(60, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Tune")) {
                startTuning()
            }.pos(130, 200).size(50, 16).build()
        )

        // ── Playback controls ──
        addRenderableWidget(
            Button.builder(Component.literal("▶ Play")) {
                if (MidiFilePlayer.isPaused) MidiFilePlayer.resume()
                else startPlayback()
            }.pos(10, 222).size(55, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("⏸ Pause")) {
                if (MidiFilePlayer.isPaused) MidiFilePlayer.resume()
                else MidiFilePlayer.pause()
            }.pos(70, 222).size(60, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("⏹ Stop")) {
                MidiFilePlayer.stop()
                NbsPlayer.stop()
            }.pos(135, 222).size(50, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("⇀ Shuffle")) {
                shuffle()
            }.pos(190, 222).size(60, 16).build()
        )

        // Tempo buttons
        addRenderableWidget(
            Button.builder(Component.literal("Tempo -")) {
                MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier - 0.1f).coerceAtLeast(0.5f)
            }.pos(10, 242).size(55, 16).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Tempo +")) {
                MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier + 0.1f).coerceAtMost(2.0f)
            }.pos(70, 242).size(55, 16).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Close")) {
                onClose()
            }.pos(width - 70, height - 24).size(60, 16).build()
        )
    }

    private fun refreshFiles() {
        midiFiles = (midisDir.listFiles { f ->
            f.extension.lowercase() in listOf("mid", "midi", "nbs")
        } ?: emptyArray()).toList().sortedBy { it.name }
        scrollOffset = 0
    }

    private fun updateOrganInfo() {
        val playable = NoteBlockRegistry.allPlayable()
        val silenced = NoteBlockRegistry.all().count { it.status == NoteBlockStatus.SILENCED }
        val mobHeads = NoteBlockRegistry.allMobHeadEntries().size
        organScanMessage = "Playable: ${playable.size}  Silenced: $silenced  Mob heads: $mobHeads"
    }

    private fun startTuning() {
        val midi = MidiFilePlayer.loadedMidi ?: return
        val map = PlayerController.organMap ?: run {
            organScanMessage = "Run Center first!"
            return
        }
        val reachable = NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) }
        val result = MidiToOrganMapper.buildAssignment(midi.noteUsageCounts, reachable)
        assignment = result
        val targets = MidiToOrganMapper.computeTuneTargets(result, reachable)
        tuningTargets = targets
        tuner = NoteBlockTuner(targets) { done, total ->
            tuneProgress = Pair(done, total)
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
    }

    private fun startPlayback() {
        val file = selectedFile ?: return
        if (file.extension.lowercase() == "nbs") {
            val nbs = NbsFileLoader.load(file)
            NbsPlayer.play(nbs, MidiFilePlayer.tempoMultiplier)
        } else {
            if (MidiFilePlayer.loadedMidi?.file != file) {
                MidiFilePlayer.load(file)
            }
            MidiFilePlayer.play()
        }
    }

    private fun shuffle() {
        val available = midiFiles.filter { it != selectedFile }
        if (available.isEmpty()) return
        val next = available.random()
        selectFile(next)
    }

    private fun selectFile(file: File) {
        selectedFile = file
        selectedIndex = midiFiles.indexOf(file)
        MidiFilePlayer.stop()
        if (file.extension.lowercase() in listOf("mid", "midi")) {
            MidiFilePlayer.load(file)
        }
        ConfigManager.config.lastPlayedTrack = file.name
        organScanMessage = "Loaded: ${file.name}. Run Scan → Center → Tune → Play."
        coverageMessage = ""
    }

    override fun tick() {
        super.tick()
        scanTicker++
        if (scanTicker >= 20 * ConfigManager.config.autoRescanIntervalSeconds) {
            scanTicker = 0
            OrganScanner.scan()
            updateOrganInfo()
        }

        // Process one tuner click per tick
        val t = tuner
        if (t != null && !t.isDone) {
            val pos = t.nextClick()
            if (pos != null) PlayerController.interactWith(pos)
            if (t.isDone) {
                tuner = null
                organScanMessage = "Tuning complete! Press Play."
            }
        }
    }

    // render() → extractRenderState()  (GuiGraphics → GuiGraphicsExtractor)
    // renderBackground() → extractBackground()
    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Do NOT call extractBackground() here — the engine already calls it before invoking
        // extractRenderState() via extractRenderStateWithTooltipAndSubtitles().
        // Calling it again triggers "Can only blur once per frame".
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        val font = minecraft.font

        // text() replaces drawString()
        graphics.text(font, "♪ BlockBard", 10, 10, 0xFFFFAA)
        graphics.text(font, "MIDI / NBS Files:", 10, 26, 0xCCCCCC)

        // File list
        val visibleFiles = midiFiles.drop(scrollOffset).take(maxVisible)
        visibleFiles.forEachIndexed { i, file ->
            val y = 36 + i * 14
            val realIdx = scrollOffset + i
            val color = if (realIdx == selectedIndex) 0x55FF55 else 0xDDDDDD
            val prefix = if (realIdx == selectedIndex) "▶ " else "  "
            val label = prefix + file.name.take(30)
            graphics.text(font, label, 34, y, color)
        }

        if (midiFiles.isEmpty()) {
            graphics.text(font, "(no files — drop .mid/.nbs in config/blockbard/midis/)", 10, 36, 0xAAAAAA)
        }

        // Organ status
        val organY = 175
        graphics.text(font, "─── Organ ───", 10, organY, 0x88AAFF)
        graphics.text(font, organScanMessage, 10, organY + 12, 0xFFFFFF)

        // Instrument counts
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

    // mouseClicked now takes (MouseButtonEvent, Boolean) instead of (Double, Double, Int)
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()
        // Check file list clicks — only on primary (left) button
        if (event.button() == 0) {
            val visibleFiles = midiFiles.drop(scrollOffset).take(maxVisible)
            visibleFiles.forEachIndexed { i, file ->
                val y = 36 + i * 14
                if (mouseX >= 34 && mouseX <= width - 10 && mouseY >= y && mouseY < y + 14) {
                    selectFile(file)
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun onClose() {
        ConfigManager.save()
        // setScreen is now on mc.gui, not mc directly
        minecraft.gui.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false
}
