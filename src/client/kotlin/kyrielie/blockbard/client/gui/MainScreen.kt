package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.BlockBardClient
import kyrielie.blockbard.client.config.BlockBardConfig
import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.config.LoopMode
import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.client.playback.NbsFile
import kyrielie.blockbard.client.playback.NbsFileLoader
import kyrielie.blockbard.client.playback.NbsPlayer
import kyrielie.blockbard.client.playback.SongAudioPreviewer
import kyrielie.blockbard.client.playback.nbsInstrumentToBlock
import kyrielie.blockbard.client.input.KeyboardInputHandler
import kyrielie.blockbard.client.midi.MidiChannelResolver
import kyrielie.blockbard.client.midi.OrganReadinessChecker
import kyrielie.blockbard.organ.*
import kyrielie.blockbard.client.organ.OrganScanner
import kyrielie.blockbard.client.player.CenterResult
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.player.SoundVerifier
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.util.DebugLog
import kyrielie.blockbard.util.blockBelowHint
import kyrielie.blockbard.util.midiNoteToName
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.level.GameType
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kyrielie.blockbard.client.playback.SongMetadataCache

/**
 * Returns [rgb] (a plain 0xRRGGBB literal) with a fully-opaque alpha byte set.
 *
 * GuiGraphicsExtractor.text() silently drops the entire text draw call when
 * ARGB.alpha(color) == 0. A bare 0xRRGGBB Kotlin Int literal has alpha == 0x00,
 * so every color value must pass through this function.
 */
fun opaque(rgb: Int): Int = rgb or (0xFF shl 24)

class MainScreen(private val parent: Screen? = null, initialTab: Int = 0) : Screen(Component.literal("BlockBard")) {

    private val logger = LoggerFactory.getLogger("BlockBard/MainScreen")

    // ── Tab state ──────────────────────────────────────────────────────────────
    // 0 = Files, 1 = Organ, 2 = Settings
    private var activeTab: Int = initialTab

    // Tab button references kept so updateTabVisibility() can toggle them
    private var tabButtons: List<Button> = emptyList()
    // All per-tab content widgets, grouped by tab index
    private val tabContent: MutableMap<Int, MutableList<AbstractWidget>> = mutableMapOf(
        0 to mutableListOf(),
        1 to mutableListOf(),
        2 to mutableListOf()
    )

    private lateinit var songListWidget: SongListWidget

    // ── File list ──────────────────────────────────────────────────────────────
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
    private var searchQuery: String = ""
    private var activeFilter: String = ConfigManager.config.activeFilter
    private var isScrubbing: Boolean = false
    private var filterButtons: MutableMap<String, Button> = mutableMapOf()

    private val displayedFiles: List<File>
        get() {
            var list = midiFiles
            list = when (activeFilter) {
                "FAV" -> list.filter { ConfigManager.config.isFavorite(it.name) }
                "MID" -> list.filter { it.extension.lowercase() in listOf("mid", "midi") }
                "NBS" -> list.filter { it.extension.lowercase() == "nbs" }
                else  -> list
            }
            if (searchQuery.isNotBlank()) {
                list = list.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
            return list.sortedWith(
                compareByDescending<File> { ConfigManager.config.isFavorite(it.name) }
                    .thenBy { it.name.lowercase() }
            )
        }
    private var selectedFile: File? = null
    private var selectedIndex: Int = -1

    // ── Tuning ─────────────────────────────────────────────────────────────────
    private var tuner: NoteBlockTuner? = null
    private var assignment: OrganAssignment? = null
    private var tuneStatusMsg: String = ""
    private var loadedNbs: NbsFile? = null

    // ── Loop / auto-advance (Tier 4) ───────────────────────────────────────────
    // Both flags are only ever written from the game tick thread (tick()), so no
    // synchronization is needed beyond @Volatile for the visibility of onSongFinished()
    // writes from the playback thread.
    @Volatile private var pendingAutoPlay: Boolean = false
    @Volatile private var pendingNextFile: File? = null

    // ── Status messages ────────────────────────────────────────────────────────
    private var organScanMessage: String = "Press Scan to detect noteblocks"
    private var coverageMessage: String = ""
    private var readinessMessage: String = ""
    private var scanTicker = 0

    // ── Scale playback ─────────────────────────────────────────────────────────
    private var scaleNotes: List<NoteBlockEntry> = emptyList()
    private var scaleIndex: Int = -1
    private var scaleTicker: Int = 0
    private val scaleTickInterval = 4

    override fun init() {
        super.init()
        logger.info("MainScreen: init()")
        tabContent.values.forEach { it.clear() }
        refreshFiles()
        OrganScanner.scan()
        updateOrganInfo()

        // ── Tab buttons (always visible) ──────────────────────────────────────
        val tabW = 60
        val tabBtnFiles = addRenderableWidget(Button.builder(Component.literal("Files")) {
            switchTab(0)
        }.pos(10, 6).size(tabW, 16).build())
        val tabBtnOrgan = addRenderableWidget(Button.builder(Component.literal("Organ")) {
            switchTab(1)
        }.pos(74, 6).size(tabW, 16).build())
        val tabBtnSettings = addRenderableWidget(Button.builder(Component.literal("Settings")) {
            switchTab(2)
        }.pos(138, 6).size(tabW, 16).build())
        tabButtons = listOf(tabBtnFiles, tabBtnOrgan, tabBtnSettings)

        // Close button -- always visible top right
        addRenderableWidget(Button.builder(Component.literal("Close")) {
            logger.info("Close clicked")
            onClose()
        }.pos(width - 64, 6).size(54, 16).build())

        // ── Tab 0: Files ──────────────────────────────────────────────────────
        fun <T : AbstractWidget> tab0(w: T): T { tabContent[0]!!.add(w); return w }

        val searchBox = EditBox(font, 10, 26, width - 150, 16, Component.literal("Search"))
        searchBox.setHint(Component.literal("Search tracks..."))
        searchBox.value = searchQuery
        searchBox.setResponder { text ->
            searchQuery = text
            if (::songListWidget.isInitialized) {
                songListWidget.setFiles(displayedFiles, selectedFile)
            }
        }
        tab0(addRenderableWidget(searchBox))

        val pillY = 26
        val allBtn = tab0(addRenderableWidget(Button.builder(Component.literal(if (activeFilter == "ALL") "[All]" else "All")) {
            setFilter("ALL")
        }.pos(width - 136, pillY).size(28, 16).build()))

        val favBtn = tab0(addRenderableWidget(Button.builder(Component.literal(if (activeFilter == "FAV") "[★]" else "★")) {
            setFilter("FAV")
        }.pos(width - 106, pillY).size(22, 16).build()))

        val midBtn = tab0(addRenderableWidget(Button.builder(Component.literal(if (activeFilter == "MID") "[MID]" else "MID")) {
            setFilter("MID")
        }.pos(width - 82, pillY).size(34, 16).build()))

        val nbsBtn = tab0(addRenderableWidget(Button.builder(Component.literal(if (activeFilter == "NBS") "[NBS]" else "NBS")) {
            setFilter("NBS")
        }.pos(width - 46, pillY).size(36, 16).build()))

        filterButtons.clear()
        filterButtons["ALL"] = allBtn
        filterButtons["FAV"] = favBtn
        filterButtons["MID"] = midBtn
        filterButtons["NBS"] = nbsBtn

        val listTop = 46
        val listBottom = height - 96
        val listHeight = (listBottom - listTop).coerceAtLeast(30)
        songListWidget = SongListWidget(
            minecraft = minecraft,
            width = width,
            height = listHeight,
            y = listTop,
            itemHeight = 26,
            onSelect = { file ->
                selectFile(file)
            },
            onDoubleClick = { file ->
                selectFile(file)
                startPlayback()
            },
            onToggleFavorite = { file ->
                ConfigManager.config.toggleFavorite(file.name)
                ConfigManager.save()
                songListWidget.setFiles(displayedFiles, selectedFile)
            }
        )
        tab0(addRenderableWidget(songListWidget))
        songListWidget.setFiles(displayedFiles, selectedFile)

        // Playback row 1 (height - 44)
        val row1Y = height - 44
        tab0(addRenderableWidget(Button.builder(Component.literal("Play")) {
            logger.info("Play clicked")
            when {
                MidiFilePlayer.isPaused -> { MidiFilePlayer.resume(); chat("Resumed") }
                NbsPlayer.isPaused      -> { NbsPlayer.resume();      chat("Resumed") }
                else                    -> startPlayback()
            }
        }.pos(10, row1Y).size(40, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal("Tune & Play")) {
            logger.info("Tune & Play clicked")
            val file = selectedFile ?: run { chatWarn("Select a song first"); return@builder }
            val map = PlayerController.organMap ?: run {
                chatWarn("Run Center on Organ first!")
                return@builder
            }
            val reachable = NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) }
            if (reachable.isEmpty()) {
                chatWarn("No reachable noteblocks -- run Center first")
                return@builder
            }
            chat("Preparing organ & playing ${file.name}...")
            startTuning(thenPlay = true)
        }.pos(52, row1Y).size(74, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal("Pause")) {
            logger.info("Pause clicked")
            when {
                MidiFilePlayer.isPaused   -> { MidiFilePlayer.resume(); chat("Resumed") }
                NbsPlayer.isPaused        -> { NbsPlayer.resume();      chat("Resumed") }
                MidiFilePlayer.isActive() -> { MidiFilePlayer.pause();  chat("Paused") }
                NbsPlayer.isPlaying       -> { NbsPlayer.pause();       chat("Paused") }
                else                      -> chat("Nothing playing")
            }
        }.pos(128, row1Y).size(42, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal("Stop")) {
            logger.info("Stop clicked")
            MidiFilePlayer.stop()
            NbsPlayer.stop()
            stopScale()
            abortTuning()
            pendingAutoPlay = false
            pendingNextFile = null
            chat("Stopped")
        }.pos(172, row1Y).size(40, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal("Shuffle")) {
            logger.info("Shuffle clicked")
            shuffle()
        }.pos(214, row1Y).size(50, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal(if (SongAudioPreviewer.isPlaying) "Stop Prev" else "Preview")) { btn ->
            if (SongAudioPreviewer.isPlaying) {
                SongAudioPreviewer.stopPreview()
                btn.message = Component.literal("Preview")
                chat("Audio preview stopped")
            } else {
                selectedFile?.let { file ->
                    MidiFilePlayer.stop()
                    NbsPlayer.stop()
                    SongAudioPreviewer.startPreview(file, MidiFilePlayer.tempoMultiplier)
                    btn.message = Component.literal("Stop Prev")
                    chat("Auditioning: ${file.name}")
                } ?: chatWarn("Select a song first")
            }
        }.pos(266, row1Y).size(56, 16).build()))

        // Playback row 2 (height - 24)
        val row2Y = height - 24
        tab0(addRenderableWidget(Button.builder(Component.literal("Tempo -")) {
            MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier - 0.1f).coerceAtLeast(0.5f)
            chat("Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x")
        }.pos(10, row2Y).size(50, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal("Tempo +")) {
            MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier + 0.1f).coerceAtLeast(0.5f).coerceAtMost(2.0f)
            chat("Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x")
        }.pos(64, row2Y).size(50, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal(loopModeLabel())) { btn ->
            val next = nextLoopMode()
            ConfigManager.config.loopMode = next.name
            ConfigManager.save()
            btn.message = Component.literal(loopModeLabel())
            chat("Loop: ${loopModeLabel()}")
            logger.info("loopMode -> $next")
        }.pos(118, row2Y).size(94, 16).build()))

        tab0(addRenderableWidget(Button.builder(Component.literal("Refresh")) {
            logger.info("Refresh clicked")
            refreshFiles()
            chat("Refreshed -- ${midiFiles.size} files found")
        }.pos(216, row2Y).size(52, 16).build()))

        // ── Tab 1: Organ ──────────────────────────────────────────────────────
        fun <T : AbstractWidget> tab1(w: T): T { tabContent[1]!!.add(w); return w }
        val organBtnY = 40

        tab1(addRenderableWidget(Button.builder(Component.literal("Scan")) {
            logger.info("Scan clicked")
            OrganScanner.scan()
            updateOrganInfo()
        }.pos(10, organBtnY).size(48, 16).build()))

        tab1(addRenderableWidget(Button.builder(Component.literal("Center")) {
            logger.info("Center clicked")
            chat("Centering on organ...")
            val result = PlayerController.centerOnOrgan()
            organScanMessage = when (result) {
                is CenterResult.Centered -> "Centered. ${result.reachableCount}/${result.totalFound} reachable."
                CenterResult.NoBlocks   -> "No playable noteblocks found."
                CenterResult.NoPlayer   -> "No player found."
            }
            chat(organScanMessage)
        }.pos(62, organBtnY).size(54, 16).build()))

        tab1(addRenderableWidget(Button.builder(Component.literal("Tune")) {
            logger.info("Tune clicked")
            startTuning()
        }.pos(120, organBtnY).size(48, 16).build()))

        tab1(addRenderableWidget(Button.builder(Component.literal("Scale")) {
            logger.info("Play Scale clicked")
            playScale()
        }.pos(172, organBtnY).size(48, 16).build()))

        tab1(addRenderableWidget(Button.builder(Component.literal(if (MaterialsShoppingOverlay.isVisible) "Hide Mats" else "Show Mats")) { btn ->
            MaterialsShoppingOverlay.isVisible = !MaterialsShoppingOverlay.isVisible
            btn.message = Component.literal(if (MaterialsShoppingOverlay.isVisible) "Hide Mats" else "Show Mats")
            chat("Materials overlay ${if (MaterialsShoppingOverlay.isVisible) "shown" else "hidden"}")
        }.pos(224, organBtnY).size(74, 16).build()))

        // ── Tab 2: Settings ───────────────────────────────────────────────────
        fun <T : AbstractWidget> tab2(w: T): T { tabContent[2]!!.add(w); return w }

        val cfg = ConfigManager.config
        val col2X = (width - 150).coerceIn(180, 240)
        var sy = 42

        // Fast Tuning toggle
        tab2(addRenderableWidget(Button.builder(Component.literal(if (cfg.fastTuning) "ON" else "OFF")) { btn ->
            cfg.fastTuning = !cfg.fastTuning
            ConfigManager.save()
            btn.message = Component.literal(if (cfg.fastTuning) "ON" else "OFF")
            chat("Fast tuning: ${if (cfg.fastTuning) "ON" else "OFF"}")
            logger.info("fastTuning -> ${cfg.fastTuning}")
        }.pos(col2X, sy).size(54, 16).build()))
        sy += 20

        // Smooth camera rotation toggle
        tab2(addRenderableWidget(Button.builder(Component.literal(if (cfg.smoothCameraRotation) "ON" else "OFF")) { btn ->
            cfg.smoothCameraRotation = !cfg.smoothCameraRotation
            ConfigManager.save()
            btn.message = Component.literal(if (cfg.smoothCameraRotation) "ON" else "OFF")
            chat("Camera rotation: ${if (cfg.smoothCameraRotation) "ON" else "OFF"}")
            logger.info("smoothCameraRotation -> ${cfg.smoothCameraRotation}")
        }.pos(col2X, sy).size(54, 16).build()))
        sy += 20

        // Notes per tick
        tab2(addRenderableWidget(Button.builder(Component.literal("-")) {
            cfg.maxNotesPerTick = (cfg.maxNotesPerTick - 1).coerceAtLeast(1)
            ArpeggioScheduler.maxNotesPerTick = cfg.maxNotesPerTick
            ConfigManager.save()
            chat("Notes/tick: ${cfg.maxNotesPerTick}")
        }.pos(col2X, sy).size(20, 16).build()))
        tab2(addRenderableWidget(Button.builder(Component.literal("+")) {
            cfg.maxNotesPerTick = (cfg.maxNotesPerTick + 1).coerceAtMost(64)
            ArpeggioScheduler.maxNotesPerTick = cfg.maxNotesPerTick
            ConfigManager.save()
            chat("Notes/tick: ${cfg.maxNotesPerTick}")
        }.pos(col2X + 24, sy).size(20, 16).build()))
        sy += 20

        // Scan radius
        tab2(addRenderableWidget(Button.builder(Component.literal("-")) {
            cfg.scanRadius = (cfg.scanRadius - 1).coerceAtLeast(1)
            OrganScanner.scanRadius = cfg.scanRadius
            ConfigManager.save()
            chat("Scan radius: ${cfg.scanRadius}")
        }.pos(col2X, sy).size(20, 16).build()))
        tab2(addRenderableWidget(Button.builder(Component.literal("+")) {
            cfg.scanRadius = (cfg.scanRadius + 1).coerceAtMost(10)
            OrganScanner.scanRadius = cfg.scanRadius
            ConfigManager.save()
            chat("Scan radius: ${cfg.scanRadius}")
        }.pos(col2X + 24, sy).size(20, 16).build()))
        sy += 20

        // Shift mode
        tab2(addRenderableWidget(Button.builder(Component.literal(shiftModeLabel())) { btn ->
            val modes = kyrielie.blockbard.organ.ShiftMode.entries
            val cur = cfg.shiftModeEnum()
            val next = modes[(modes.indexOf(cur) + 1) % modes.size]
            cfg.shiftMode = next.name
            InstrumentShifter.mode = next
            ConfigManager.save()
            btn.message = Component.literal(shiftModeLabel())
            chat("Shift mode: $next")
        }.pos(col2X, sy).size(110, 16).build()))
        sy += 20

        // Debug log toggle
        tab2(addRenderableWidget(Button.builder(Component.literal(debugLogLabel())) { btn ->
            cfg.debugLogging = !cfg.debugLogging
            DebugLog.enabled = cfg.debugLogging
            ConfigManager.save()
            btn.message = Component.literal(debugLogLabel())
            chat("Debug log: ${if (cfg.debugLogging) "ON" else "OFF"}")
            logger.info("debugLogging -> ${cfg.debugLogging}")
        }.pos(col2X, sy).size(54, 16).build()))
        sy += 20

        // Keybinds button
        tab2(addRenderableWidget(Button.builder(Component.literal("Key Binds...")) {
            minecraft.gui.setScreen(KeyBindsScreen(this, minecraft.options))
        }.pos(col2X, sy).size(110, 16).build()))

        // Apply initial tab visibility
        updateTabVisibility()
    }

    // ── Tab management ─────────────────────────────────────────────────────────

    private fun switchTab(index: Int) {
        activeTab = index
        updateTabVisibility()
    }

    private fun updateTabVisibility() {
        tabButtons.forEachIndexed { i, btn ->
            btn.active = (i != activeTab)
        }
        for ((tab, widgets) in tabContent) {
            val visible = tab == activeTab
            widgets.forEach { it.visible = visible; it.active = visible }
        }
    }

    // ── Label helpers ──────────────────────────────────────────────────────────

    private fun loopModeLabel(): String = when (ConfigManager.config.loopModeEnum()) {
        LoopMode.NONE        -> "Loop: OFF"
        LoopMode.LOOP_ONE    -> "Loop: ONE"
        LoopMode.LOOP_ALL    -> "Loop: ALL"
        LoopMode.SHUFFLE_ALL -> "Loop: SHUFFLE"
    }

    private fun nextLoopMode(): LoopMode {
        val modes = LoopMode.entries
        val cur = ConfigManager.config.loopModeEnum()
        return modes[(modes.indexOf(cur) + 1) % modes.size]
    }

    private fun debugLogLabel(): String {
        val on = ConfigManager.config.debugLogging
        return "Debug Log: ${if (on) "ON" else "OFF"}"
    }

    private fun shiftModeLabel(): String = "Shift: ${ConfigManager.config.shiftModeEnum().name}"

    // ── Helpers ────────────────────────────────────────────────────────────────

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

        // Automatically select the last played track or the first available file
        if (selectedFile == null && midiFiles.isNotEmpty()) {
            val last = ConfigManager.config.lastPlayedTrack
            val match = midiFiles.firstOrNull { it.name.equals(last, ignoreCase = true) } ?: midiFiles.first()
            selectFile(match)
        }

        if (::songListWidget.isInitialized) {
            songListWidget.setFiles(displayedFiles, selectedFile)
        }
        logger.info("refreshFiles: found ${midiFiles.size} files in ${midisDir.absolutePath}")
    }

    private fun updateOrganInfo() {
        val playable = NoteBlockRegistry.allPlayable()
        val silenced = NoteBlockRegistry.all().count { it.status == NoteBlockStatus.SILENCED }
        val mobHeads = NoteBlockRegistry.allMobHeadEntries().size
        organScanMessage = "Playable: ${playable.size}  Silenced: $silenced  Mob heads: $mobHeads"
        logger.info("updateOrganInfo: $organScanMessage")
    }

    // ── Loop / auto-advance (Tier 4) ───────────────────────────────────────────

    /**
     * Called by MidiFilePlayer.onFinished and NbsPlayer.onFinished -- may run on the
     * playback thread. Only sets flags; all MC API calls happen in tick() on the game
     * thread.
     */
    private fun onSongFinished() {
        when (ConfigManager.config.loopModeEnum()) {
            LoopMode.NONE        -> { /* stay idle */ }
            LoopMode.LOOP_ONE    -> { pendingAutoPlay = true }
            LoopMode.LOOP_ALL    -> { pendingNextFile = nextInOrder(); pendingAutoPlay = true }
            LoopMode.SHUFFLE_ALL -> { pendingNextFile = nextShuffle(); pendingAutoPlay = true }
        }
        logger.info("onSongFinished: loopMode=${ConfigManager.config.loopMode} pendingNext=${pendingNextFile?.name}")
    }

    private fun nextInOrder(): File? {
        if (midiFiles.isEmpty()) return null
        val cur = selectedIndex
        return midiFiles[(cur + 1) % midiFiles.size]
    }

    private fun nextShuffle(): File? {
        if (midiFiles.isEmpty()) return null
        val cfg = ConfigManager.config
        val history = cfg.shuffleHistory
        val maxHistory = (midiFiles.size - 1).coerceAtLeast(1).coerceAtMost(5)
        val candidates = midiFiles.filter { it.name !in history && it != selectedFile }
        val pick = if (candidates.isNotEmpty()) {
            candidates.random()
        } else {
            // All files have been played recently -- clear history and pick anything
            history.clear()
            midiFiles.filter { it != selectedFile }.randomOrNull() ?: midiFiles.random()
        }
        history.add(pick.name)
        while (history.size > maxHistory) history.removeAt(0)
        ConfigManager.save()
        return pick
    }

    // ── Tuning ─────────────────────────────────────────────────────────────────

    private fun startTuning(thenPlay: Boolean = false) {
        SongAudioPreviewer.stopPreview()
        val mc = minecraft
        val player = mc.player ?: run { chatWarn("No player"); return }

        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            chatWarn("Cannot tune in ${gameMode.getName()} mode")
            return
        }

        val mainHand = player.mainHandItem
        val offHand  = player.offhandItem
        if (!mainHand.isEmpty) {
            chatWarn("Empty your main hand before tuning")
            return
        }
        if (!offHand.isEmpty) {
            chatWarn("Empty your offhand before tuning")
            return
        }

        val map = PlayerController.organMap ?: run {
            chatWarn("Run Center first!")
            return
        }

        val reachable = NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) }
        if (reachable.isEmpty()) {
            chatWarn("No reachable noteblocks -- run Scan then Center first")
            return
        }

        fun assignAndReport(noteUsageCounts: Map<NotePitch, Int>): List<TuneTarget> {
            val result = MidiToOrganMapper.buildAssignment(noteUsageCounts, reachable)
            assignment = result
            ArpeggioScheduler.assignment = result.assignment
            ArpeggioScheduler.voicePools = result.voicePools
            if (result.unplayable.isNotEmpty()) {
                val names = result.unplayable.map { it.displayName() }
                chatWarn("${names.size} notes unplayable: ${names.take(6).joinToString()}")
            }
            updateCoverageMessage(result)
            return MidiToOrganMapper.computeTuneTargets(result, reachable)
        }

        val midi = MidiFilePlayer.loadedMidi
        val nbs = loadedNbs?.takeIf { it.file == selectedFile }
        val targets: List<TuneTarget>

        if (midi != null) {
            logger.info("startTuning: MIDI mode -- ${reachable.size} reachable, ${midi.distinctNotes.size} distinct notes")
            targets = assignAndReport(midi.noteUsageCounts)
        } else if (nbs != null) {
            val noteUsageCounts = nbs.notes
                .map { NotePitch(it.key + 21, nbsInstrumentToBlock(it.instrument)) }
                .groupingBy { it }
                .eachCount()
            logger.info("startTuning: NBS mode -- ${reachable.size} reachable, ${noteUsageCounts.size} distinct pairs")
            targets = assignAndReport(noteUsageCounts)
        } else {
            // No song -- chromatic scale test
            logger.info("startTuning: no-song mode -- ${reachable.size} blocks to chromatic scale")
            chat("No song loaded -- tuning to chromatic scale (F#3 upward)")
            val sorted = reachable.sortedBy { it.distanceFromPlayer }
            targets = sorted.groupBy { it.instrument }.flatMap { (_, blocksForInstrument) ->
                blocksForInstrument.mapIndexed { i, entry ->
                    TuneTarget(entry.pos, entry.noteIndex, i.coerceIn(0, 24), entry.instrument)
                }
            }
            assignment = null
            ArpeggioScheduler.assignment = emptyMap()
            ArpeggioScheduler.voicePools = emptyMap()
            coverageMessage = "Test scale: ${sorted.size} blocks"
        }

        if (targets.isEmpty()) {
            chat("Nothing to tune -- all blocks already at target notes!")
            if (thenPlay) startPlayback()
            return
        }

        val totalClicks = targets.sumOf { it.estimatedClicks }
        chat("Tuning ${targets.size} blocks (~$totalClicks clicks)...")
        logger.info("startTuning: ${targets.size} targets, $totalClicks clicks, thenPlay=$thenPlay")

        tuner = NoteBlockTuner(
            targets           = targets,
            worldNoteReader   = { pos ->
                mc.level?.getBlockState(pos)?.let { state ->
                    if (state.block == net.minecraft.world.level.block.Blocks.NOTE_BLOCK)
                        state.getValue(net.minecraft.world.level.block.NoteBlock.NOTE)
                    else null
                }
            },
            interactBlock     = { pos -> PlayerController.interactWith(pos) },
            pingMs            = { PlayerController.currentPingMs() },
            fastTuning        = ConfigManager.config.fastTuning,
            onProgress        = { done, total, msg ->
                tuneStatusMsg = "$done/$total -- $msg"
                logger.debug("tuner progress: $tuneStatusMsg")
            }
        )

        pendingAutoPlay = thenPlay
        if (thenPlay) pendingNextFile = null

        tuner!!.start()
    }

    private fun abortTuning() {
        if (tuner?.isActive == true) {
            chat("Tuning cancelled")
            logger.info("abortTuning: tuner cancelled")
        }
        tuner = null
        tuneStatusMsg = ""
    }

    private fun updateCoverageMessage(result: OrganAssignment) {
        val covered = result.assignment.size
        val total   = covered + result.unplayable.size

        // Group unplayable notes by instrument and include block-below material hints.
        // No .take(4) limit -- Tier 6a: full instrument list displayed across multiple
        // lines via word-wrap in extractRenderState.
        val byInstrument = result.unplayable
            .filter { it.instrument != null }
            .groupBy { it.instrument!! }
            .mapValues { (_, notes) -> notes.map { it.midiNote }.distinct().size }
        val anyInstrumentCount = result.unplayable.count { it.instrument == null }

        coverageMessage = buildString {
            append("$covered/$total notes covered")
            if (byInstrument.isNotEmpty()) {
                append(". Add: ")
                append(byInstrument.entries.joinToString { (inst, count) ->
                    "$count ${inst.name} (${inst.blockBelowHint})"
                })
            }
            if (anyInstrumentCount > 0) {
                append(". $anyInstrumentCount note(s) unplayable on any instrument")
            }
        }
        logger.info("coverage: $coverageMessage")
    }

    // ── Scale playback ─────────────────────────────────────────────────────────

    private fun playScale() {
        SongAudioPreviewer.stopPreview()
        val mc = minecraft
        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE) {
            chatWarn("Cannot play scale in Creative -- noteblocks would break!")
            return
        }

        val playable = NoteBlockRegistry.allPlayable()
        if (playable.isEmpty()) {
            chatWarn("No playable noteblocks found -- run Scan first")
            return
        }

        scaleNotes = playable.sortedBy { it.midiNote }
        scaleIndex = 0
        scaleTicker = 0
        SoundVerifier.reset()
        chat("Playing scale (${scaleNotes.size} notes)")
    }

    private fun stopScale() {
        scaleNotes = emptyList()
        scaleIndex = -1
        scaleTicker = 0
    }

    private fun tickScale() {
        if (scaleIndex < 0) return
        if (scaleIndex >= scaleNotes.size) {
            chat("Scale complete!")
            stopScale()
            return
        }
        scaleTicker++
        if (scaleTicker < scaleTickInterval) return
        scaleTicker = 0
        val entry = scaleNotes[scaleIndex]
        logger.info("tickScale: [$scaleIndex/${scaleNotes.size - 1}] ${entry.instrument.name} midi=${entry.midiNote} @ ${entry.pos}")
        ArpeggioScheduler.enqueue(NoteRequest(entry.midiNote, resolvedPos = entry.pos))
        scaleIndex++
    }

    // ── Playback ───────────────────────────────────────────────────────────────

    private fun startPlayback() {
        SongAudioPreviewer.stopPreview()
        val file = selectedFile ?: run { chatWarn("No file selected"); return }
        logger.info("startPlayback: ${file.name}")

        // Tier 6d: warn about missing coverage so the player knows to expect silent notes,
        // but do not block -- they chose to play, let them.
        val unplayableCount = assignment?.unplayable?.size ?: 0
        if (unplayableCount > 0) {
            chatWarn("$unplayableCount notes will be silent -- organ incomplete (see Files tab)")
        }

        SoundVerifier.reset()

        if (file.extension.lowercase() == "nbs") {
            val cached = loadedNbs?.takeIf { it.file == file }
            val nbs = cached ?: try {
                NbsFileLoader.load(file).also { loadedNbs = it }
            } catch (e: Exception) {
                logger.warn("startPlayback: NBS load failed: ${e.message}", e)
                chatWarn("Failed to load ${file.name}: ${e.message}")
                return
            }
            // Wire onFinished for loop/auto-advance (Tier 4)
            NbsPlayer.onFinished = { onSongFinished() }
            chat("Playing: ${file.name}")
            NbsPlayer.play(nbs)
        } else {
            if (MidiFilePlayer.loadedMidi?.file != file) {
                MidiFilePlayer.load(file)
            }
            // Wire onFinished for loop/auto-advance (Tier 4)
            MidiFilePlayer.onFinished = { onSongFinished() }
            chat("Playing: ${file.name}")
            MidiFilePlayer.play()
        }
    }

    private fun shuffle() {
        val next = nextShuffle() ?: return
        selectFile(next)
    }

    private fun selectFile(file: File) {
        SongAudioPreviewer.stopPreview()
        selectedFile = file
        selectedIndex = midiFiles.indexOf(file)
        if (::songListWidget.isInitialized && songListWidget.getSelectedFile() != file) {
            songListWidget.setFiles(displayedFiles, selectedFile)
        }
        MidiFilePlayer.stop()
        NbsPlayer.stop()
        readinessMessage = ""
        coverageMessage = ""
        var loadFailed = false

        if (file.extension.lowercase() in listOf("mid", "midi")) {
            loadedNbs = null
            try {
                val midi = MidiFilePlayer.load(file)
                logger.info("selectFile: ${file.name} -- ${midi.events.size} events")
                chat("Loaded: ${file.name} (${midi.distinctNotes.size} distinct notes)")

                // Tier 6b (MIDI): pre-flight readiness check with full instrument list and
                // block-below material hints -- no .take(4) truncation (Tier 6a).
                val requirements = MidiChannelResolver.resolveRequirements(file)
                MaterialsShoppingOverlay.updateRequirements(requirements)
                val report = OrganReadinessChecker.check(requirements)
                readinessMessage = if (report.isFullyCovered) {
                    "All notes covered (${report.totalRequired} notes)"
                } else {
                    val shortfall = report.shortfallByInstrument()
                    if (shortfall.isNotEmpty())
                        "Missing: " + shortfall.entries.joinToString { (inst, count) ->
                            "$count ${inst.name} (${inst.blockBelowHint})"
                        }
                    else
                        "${report.totalRequired - report.coveredNotes.size} notes unplayable"
                }
                if (!report.isFullyCovered) {
                    val shortfall = report.shortfallByInstrument()
                    val needMsg = if (shortfall.isNotEmpty())
                        shortfall.entries.joinToString { (inst, count) ->
                            "$count ${inst.name} (${inst.blockBelowHint})"
                        }
                    else "${report.totalRequired - report.coveredNotes.size} notes unplayable on any instrument"
                    chatWarn("Missing noteblocks: $needMsg")
                }
            } catch (e: Exception) {
                logger.warn("selectFile: MIDI load failed: ${e.message}", e)
                chatWarn("Failed to load ${file.name}: ${e.message}")
                loadFailed = true
                MaterialsShoppingOverlay.clear()
            }
        } else if (file.extension.lowercase() == "nbs") {
            try {
                val nbs = NbsFileLoader.load(file)
                loadedNbs = nbs
                logger.info("selectFile: ${file.name} -- ${nbs.notes.size} notes")
                chat("Loaded: ${file.name} (${nbs.notes.size} notes)")

                // Tier 6b (NBS): same pre-flight check as MIDI.
                // Custom instruments (index >= 16) have no NoteBlockInstrument equivalent
                // and resolve to null; they are excluded from the requirements set since
                // OrganReadinessChecker cannot check for them by instrument type.
                val nbsRequirements: Set<NotePitch> = nbs.notes
                    .mapNotNull { note ->
                        val inst = nbsInstrumentToBlock(note.instrument) ?: return@mapNotNull null
                        NotePitch(note.key + 21, inst)
                    }.toSet()

                MaterialsShoppingOverlay.updateRequirements(nbsRequirements)
                val report = OrganReadinessChecker.check(nbsRequirements)
                readinessMessage = if (report.isFullyCovered) {
                    "All notes covered (${report.totalRequired} notes)"
                } else {
                    val shortfall = report.shortfallByInstrument()
                    if (shortfall.isNotEmpty())
                        "Missing: " + shortfall.entries.joinToString { (inst, count) ->
                            "$count ${inst.name} (${inst.blockBelowHint})"
                        }
                    else
                        "${report.totalRequired - report.coveredNotes.size} notes unplayable"
                }
                if (!report.isFullyCovered) {
                    val shortfall = report.shortfallByInstrument()
                    val needMsg = if (shortfall.isNotEmpty())
                        shortfall.entries.joinToString { (inst, count) ->
                            "$count ${inst.name} (${inst.blockBelowHint})"
                        }
                    else "${report.totalRequired - report.coveredNotes.size} notes unplayable on any instrument"
                    chatWarn("Missing noteblocks: $needMsg")
                }
            } catch (e: Exception) {
                logger.warn("selectFile: NBS load failed: ${e.message}", e)
                chatWarn("Failed to load ${file.name}: ${e.message}")
                loadedNbs = null
                loadFailed = true
                MaterialsShoppingOverlay.clear()
            }
        } else {
            loadedNbs = null
        }

        ConfigManager.config.lastPlayedTrack = file.name
        organScanMessage = if (loadFailed) {
            "Failed to load ${file.name}"
        } else {
            "Loaded: ${file.name}. Run Scan -> Center -> Tune -> Play."
        }
    }

    // ── Tick ───────────────────────────────────────────────────────────────────

    override fun tick() {
        super.tick()

        // Auto-rescan -- skipped during active playback or tuning
        val playbackOrTuningActive = MidiFilePlayer.isActive() || NbsPlayer.isPlaying || tuner?.isActive == true
        scanTicker++
        if (scanTicker >= 20 * ConfigManager.config.autoRescanIntervalSeconds) {
            scanTicker = 0
            if (!playbackOrTuningActive) {
                OrganScanner.scan()
                updateOrganInfo()
            }
        }

        // Tuner tick
        val t = tuner
        if (t != null) {
            t.onTick()

            if (t.isDone) {
                tuner = null
                tuneStatusMsg = ""
                organScanMessage = "Tuning complete! Press Play."
                chat("Tuning complete!")
                logger.info("tick: tuning complete, pendingAutoPlay=$pendingAutoPlay")
                OrganScanner.scan()
                updateOrganInfo()

                // Tier 4: if startTuning() was called via selectiveRetuneAndPlay chain,
                // start playback now that tuning is verified.
                if (pendingAutoPlay && pendingNextFile == null) {
                    pendingAutoPlay = false
                    startPlayback()
                }
            } else if (t.isFailed) {
                tuner = null
                tuneStatusMsg = ""
                pendingAutoPlay = false
                pendingNextFile = null
                chatWarn("Tuning failed -- check logs for details")
                logger.warn("tick: tuning failed")
            }
        }

        // Tier 4: auto-advance handler -- runs after tuner so a thenPlay=true tuning
        // completion above is handled first and doesn't race with this branch.
        if (pendingAutoPlay && tuner == null && !MidiFilePlayer.isActive() && !NbsPlayer.isPlaying) {
            pendingAutoPlay = false
            val next = pendingNextFile
            if (next != null) {
                pendingNextFile = null
                logger.info("auto-advance: selecting ${next.name} and retuning")
                selectFile(next)
                // startTuning(thenPlay=true): retune, then startPlayback() when done.
                // NoteBlockTuner skips blocks already at their target note, so for
                // songs sharing most pitches this is fast (mostly verification passes).
                startTuning(thenPlay = true)
            } else {
                // LOOP_ONE: same file, already tuned -- just replay
                logger.info("auto-advance: looping current file")
                startPlayback()
            }
        }

        tickScale()
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val font = minecraft.font

        when (activeTab) {
            0 -> renderFilesTab(graphics, font)
            1 -> renderOrganTab(graphics, font)
            2 -> renderSettingsTab(graphics, font)
        }
    }

    private fun renderFilesTab(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font) {
        if (displayedFiles.isEmpty()) {
            val emptyMsg = if (midiFiles.isEmpty()) "(no files found in config/blockbard/midis/)"
                           else "(no matches for filter/search)"
            graphics.text(font, emptyMsg, 14, 54, opaque(0xAAAAAA))
        }

        // Item 9: Live Piano Roll / Note Visualizer strip (y = height - 92, h = 14)
        val visY = height - 92
        val visW = (width - 20).coerceAtMost(320)
        val visX = 10
        val keyCount = 25
        val keyW = visW.toFloat() / keyCount
        graphics.fill(visX - 1, visY - 1, visX + visW + 1, visY + 15, opaque(0x181818))
        for (i in 0 until keyCount) {
            val kx1 = (visX + i * keyW).toInt()
            val kx2 = (visX + (i + 1) * keyW - 1).toInt()
            val isAccidental = (i % 12) in listOf(0, 2, 4, 7, 9)
            val baseColor = if (isAccidental) opaque(0x2A2A2A) else opaque(0x555555)

            val state = VisualizerState.getPitchState(i)
            val fillColor = if (state != null) {
                blendColor(baseColor, state.second, state.first)
            } else baseColor

            graphics.fill(kx1, visY, kx2, visY + 14, fillColor)
        }

        // Item 1: Track title, status, and mm:ss timestamps (y = height - 74)
        val trackName = selectedFile?.nameWithoutExtension?.take(32) ?: "No Track Selected"
        val statusLabel = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> "▶ PLAYING: $trackName"
            NbsPlayer.isPlaying && !NbsPlayer.isPaused            -> "▶ PLAYING: $trackName"
            MidiFilePlayer.isPaused || NbsPlayer.isPaused         -> "❚❚ PAUSED: $trackName"
            SongAudioPreviewer.isPlaying                          -> "♪ PREVIEW: $trackName"
            else                                                  -> "■ READY: $trackName"
        }
        val statusColor = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> opaque(0x55FF55)
            NbsPlayer.isPlaying && !NbsPlayer.isPaused            -> opaque(0x55FF55)
            MidiFilePlayer.isPaused || NbsPlayer.isPaused         -> opaque(0xFFFF55)
            SongAudioPreviewer.isPlaying                          -> opaque(0x55FFFF)
            else                                                  -> opaque(0xAAAAAA)
        }
        graphics.text(font, statusLabel, 10, height - 74, statusColor)

        val (elapsedSecs, totalSecs) = getPlaybackTimes()
        val timeText = "${formatTime(elapsedSecs)} / ${formatTime(totalSecs)}"
        val timeW = font.width(timeText)
        graphics.text(font, timeText, width - 10 - timeW, height - 74, opaque(0xFFFFFF))

        // Item 1: Interactive Seek Bar (y = height - 62, h = 4)
        val barY = height - 62
        val barX = 10
        val barW = width - 20
        val fraction = if (totalSecs > 0.0) (elapsedSecs / totalSecs).coerceIn(0.0, 1.0) else 0.0
        val progressW = (barW * fraction).toInt()

        graphics.fill(barX, barY, barX + barW, barY + 4, opaque(0x333333))
        if (progressW > 0) {
            graphics.fill(barX, barY, barX + progressW, barY + 4, opaque(0x55FF55))
        }
        val thumbX = barX + progressW
        graphics.fill(thumbX - 2, barY - 2, thumbX + 2, barY + 6, opaque(0xFFFFFF))

        // Row 2 extra diagnostics (ping and coverage warning)
        val ping = PlayerController.currentPingMs()
        val pingStr = if (ping > 0) "Ping: ${ping}ms" else ""
        val statusLine = coverageMessage.takeIf { it.isNotEmpty() } ?: readinessMessage
        val row2Status = if (statusLine.isNotEmpty()) statusLine else pingStr
        if (row2Status.isNotEmpty()) {
            val color = if (coverageMessage.isNotEmpty()) opaque(0xFFCC44) else opaque(0x888888)
            val maxW = (width - 275).coerceAtLeast(50)
            val disp = if (font.width(row2Status) > maxW) {
                var cut = row2Status
                while (cut.isNotEmpty() && font.width("$cut...") > maxW) {
                    cut = cut.dropLast(1)
                }
                "$cut..."
            } else row2Status
            graphics.text(font, disp, 274, height - 20, color)
        }
    }

    private fun renderOrganTab(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font) {
        graphics.text(font, "--- Organ Overview & Diagnostics ---", 10, 28, opaque(0x88AAFF))

        var cy = 60
        graphics.text(font, organScanMessage, 10, cy, opaque(0xFFFFFF))
        cy += 12

        val counts = NoteBlockRegistry.countPerInstrument()
        counts.entries.forEach { (inst, count) ->
            if (cy >= height - 42) {
                graphics.text(font, "... and ${counts.size} instruments total", 10, cy, opaque(0x999999))
                cy += 9
                return@forEach
            }
            graphics.text(font, "${inst.name}: $count", 10, cy, opaque(0xDDDDDD))
            cy += 9
        }

        if (cy < height - 36) {
            val t = tuner
            if (t != null) {
                val stateLabel = when (t.state) {
                    TunerState.TUNING    -> "Tuning..."
                    TunerState.VERIFYING -> "Verifying..."
                    else                 -> ""
                }
                graphics.text(font, "$stateLabel ${t.confirmedBlocks}/${t.total}", 10, cy, opaque(0x44FF88))
                cy += 10
                if (tuneStatusMsg.isNotEmpty() && cy < height - 36) {
                    graphics.text(font, tuneStatusMsg.take(50), 10, cy, opaque(0xCCFFCC))
                    cy += 9
                }
            }
        }

        if (scaleIndex >= 0 && scaleIndex < scaleNotes.size && cy < height - 36) {
            val entry = scaleNotes[scaleIndex]
            graphics.text(font, "Scale: ${midiNoteToName(entry.midiNote)} ($scaleIndex/${scaleNotes.size})", 10, cy, opaque(0xAAFF44))
        }

        // Bottom status
        val status = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> "PLAYING ${selectedFile?.name ?: ""}"
            NbsPlayer.isPlaying && !NbsPlayer.isPaused            -> "PLAYING ${selectedFile?.name ?: ""}"
            MidiFilePlayer.isPaused || NbsPlayer.isPaused         -> "PAUSED"
            tuner?.isActive == true                               -> "TUNING"
            else                                                  -> "IDLE"
        }
        graphics.text(font, status, 10, height - 24, opaque(0xAAFFAA))
        val ping = PlayerController.currentPingMs()
        if (ping > 0) {
            graphics.text(font, "Ping: ${ping}ms", width - 140, height - 24, opaque(0xAAAAAA))
        }
    }

    private fun renderSettingsTab(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font) {
        val cfg = ConfigManager.config
        graphics.text(font, "--- BlockBard Configuration ---", 14, 28, opaque(0x88AAFF))

        val col1X = 14
        var sy = 42

        graphics.text(font, "Fast Tuning (batch clicks per tick):", col1X, sy + 4, opaque(0xCCCCCC))
        sy += 20
        graphics.text(font, "Smooth Camera Rotation (face blocks):", col1X, sy + 4, opaque(0xCCCCCC))
        sy += 20
        graphics.text(font, "Max Notes / Tick: ${cfg.maxNotesPerTick}", col1X, sy + 4, opaque(0xCCCCCC))
        sy += 20
        graphics.text(font, "Scan Radius: ${cfg.scanRadius} blocks", col1X, sy + 4, opaque(0xCCCCCC))
        sy += 20
        graphics.text(font, "Instrument Shift Mode:", col1X, sy + 4, opaque(0xCCCCCC))
        sy += 20
        graphics.text(font, "Verbose Debug Logging:", col1X, sy + 4, opaque(0xCCCCCC))
        sy += 20
        graphics.text(font, "Minecraft Controls & Key Binds:", col1X, sy + 4, opaque(0xCCCCCC))
        sy += 24

        // Rotation tunables note
        graphics.text(font, "Advanced Tunables (edit config.json to change):", col1X, sy, opaque(0x888888))
        sy += 10
        graphics.text(font, "  maxRotationDegreesPerTick: ${cfg.maxRotationDegreesPerTick}", col1X, sy, opaque(0x666666))
        sy += 9
        graphics.text(font, "  convergenceThresholdDegrees: ${cfg.rotationConvergenceThresholdDegrees}", col1X, sy, opaque(0x666666))
        sy += 9
        graphics.text(font, "  rotationInProgressTimeoutMs: ${cfg.rotationInProgressTimeoutMs}", col1X, sy, opaque(0x666666))
    }

    /**
     * Word-wraps [text] into at most [maxLines] lines of width [maxWidth] pixels,
     * rendering each line [lineHeight] pixels below the previous. Used for coverage
     * and readiness messages which can be arbitrarily long after removing the old
     * .take(50) truncation (Tier 5 / Tier 6a).
     *
     * Splits on ". " boundaries first (semantic breaks), then falls back to space
     * boundaries within each segment. Returns the y coordinate after the last
     * rendered line.
     */
    private fun renderWrapped(
        graphics: GuiGraphicsExtractor,
        font: net.minecraft.client.gui.Font,
        text: String,
        x: Int,
        startY: Int,
        maxWidth: Int,
        lineHeight: Int,
        color: Int,
        maxLines: Int = 4
    ): Int {
        val words = text.split(" ")
        var line = ""
        var y = startY
        var linesUsed = 0

        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.width(candidate) > maxWidth && line.isNotEmpty()) {
                if (linesUsed >= maxLines - 1 && word != words.last()) {
                    val truncated = "$line ..."
                    graphics.text(font, truncated, x, y, color)
                    return y + lineHeight
                }
                graphics.text(font, line, x, y, color)
                y += lineHeight
                linesUsed++
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty() && linesUsed < maxLines) {
            graphics.text(font, line, x, y, color)
            y += lineHeight
        }
        return y
    }

    // ── Input & Drag/Drop ──────────────────────────────────────────────────────

    override fun onFilesDrop(files: List<Path>) {
        var imported = 0
        var lastFile: File? = null
        for (path in files) {
            val file = path.toFile()
            val ext = file.extension.lowercase()
            if (ext in listOf("mid", "midi", "nbs")) {
                try {
                    val dest = midisDir.resolve(file.name)
                    file.copyTo(dest, overwrite = true)
                    imported++
                    lastFile = dest
                } catch (e: Exception) {
                    logger.warn("Failed to copy dropped file ${file.name}: ${e.message}")
                }
            }
        }
        if (imported > 0) {
            refreshFiles()
            lastFile?.let { selectFile(it) }
            chat("Imported $imported track(s) from drag-and-drop")
            logger.info("onFilesDrop: imported $imported files, selected ${lastFile?.name}")
        } else {
            chatWarn("Dropped files must be .mid or .nbs")
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (activeTab == 0 && event.button() == 0) {
            val barY = height - 62
            if (event.y() >= barY - 4 && event.y() <= barY + 8 && event.x() >= 10 && event.x() <= width - 10) {
                isScrubbing = true
                handleScrub(event.x())
                return true
            }
        }
        if (activeTab == 0 && ::songListWidget.isInitialized && songListWidget.visible) {
            if (songListWidget.mouseClicked(event, doubleClick)) {
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (activeTab == 0 && isScrubbing) {
            handleScrub(event.x())
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (isScrubbing) {
            isScrubbing = false
            return true
        }
        return super.mouseReleased(event)
    }

    private fun handleScrub(mouseX: Double) {
        val barX = 10
        val barW = (width - 20).coerceAtLeast(1)
        val fraction = ((mouseX - barX) / barW.toDouble()).coerceIn(0.0, 1.0)

        if (MidiFilePlayer.isActive() || MidiFilePlayer.isPaused || (MidiFilePlayer.loadedMidi?.file == selectedFile && MidiFilePlayer.loadedMidi != null)) {
            val totalTicks = MidiFilePlayer.getTotalTicks()
            if (totalTicks > 0) {
                val targetTick = (fraction * totalTicks).toLong()
                MidiFilePlayer.seekToTick(targetTick)
            }
        } else if (NbsPlayer.isPlaying || NbsPlayer.isPaused || NbsPlayer.currentNbs != null) {
            val totalTicks = NbsPlayer.getTotalTicks()
            if (totalTicks > 0) {
                val targetTick = (fraction * totalTicks).toLong()
                NbsPlayer.seekToTick(targetTick)
            }
        }
    }

    private fun getPlaybackTimes(): Pair<Double, Double> {
        return when {
            MidiFilePlayer.isActive() || MidiFilePlayer.isPaused -> {
                Pair(MidiFilePlayer.getElapsedSeconds(), MidiFilePlayer.getDurationSeconds())
            }
            NbsPlayer.isPlaying || NbsPlayer.isPaused -> {
                Pair(NbsPlayer.getElapsedSeconds(), NbsPlayer.getDurationSeconds())
            }
            selectedFile != null -> {
                val meta = SongMetadataCache.get(selectedFile!!)
                val dur = meta?.durationSeconds?.toDouble() ?: 0.0
                Pair(0.0, dur)
            }
            else -> Pair(0.0, 0.0)
        }
    }

    private fun formatTime(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        val mins = s / 60
        val secs = s % 60
        return "%02d:%02d".format(mins, secs)
    }

    private fun blendColor(base: Int, overlay: Int, factor: Float): Int {
        val f = factor.coerceIn(0.0f, 1.0f)
        val r1 = (base shr 16) and 0xFF
        val g1 = (base shr 8) and 0xFF
        val b1 = base and 0xFF
        val r2 = (overlay shr 16) and 0xFF
        val g2 = (overlay shr 8) and 0xFF
        val b2 = overlay and 0xFF
        val r = (r1 + (r2 - r1) * f).toInt()
        val g = (g1 + (g2 - g1) * f).toInt()
        val b = (b1 + (b2 - b1) * f).toInt()
        return opaque((r shl 16) or (g shl 8) or b)
    }

    private fun updateFilterButtons() {
        filterButtons["ALL"]?.message = Component.literal(if (activeFilter == "ALL") "[All]" else "All")
        filterButtons["FAV"]?.message = Component.literal(if (activeFilter == "FAV") "[★]" else "★")
        filterButtons["MID"]?.message = Component.literal(if (activeFilter == "MID") "[MID]" else "MID")
        filterButtons["NBS"]?.message = Component.literal(if (activeFilter == "NBS") "[NBS]" else "NBS")
    }

    private fun setFilter(filter: String) {
        activeFilter = filter
        ConfigManager.config.activeFilter = filter
        ConfigManager.save()
        updateFilterButtons()
        if (::songListWidget.isInitialized) {
            songListWidget.setFiles(displayedFiles, selectedFile)
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.key()
        if (keyCode in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_9 && (focused == null || focused !is EditBox)) {
            if (KeyboardInputHandler.tryDispatch(keyCode)) return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        logger.info("MainScreen: closing")
        ConfigManager.save()
        minecraft.gui.setScreen(parent)
    }

    override fun extractBlurredBackground(graphics: GuiGraphicsExtractor) {
        // Intentionally empty -- do not blur the background while the GUI is open
    }

    override fun isPauseScreen(): Boolean = false
}
