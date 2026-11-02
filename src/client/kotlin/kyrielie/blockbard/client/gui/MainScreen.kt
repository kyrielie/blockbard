package kyrielie.blockbard.client.gui

import kyrielie.blockbard.client.BlockBardClient
import kyrielie.blockbard.client.config.BlockBardConfig
import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.client.playback.NbsFile
import kyrielie.blockbard.client.playback.NbsFileLoader
import kyrielie.blockbard.client.playback.NbsPlayer
import kyrielie.blockbard.client.playback.SongAudioPreviewer
import kyrielie.blockbard.client.playback.ClientPlayQueue
import kyrielie.blockbard.client.playback.PlaybackCoordinator
import kyrielie.blockbard.client.playback.PlaybackHost
import kyrielie.blockbard.client.playback.songIdOf
import kyrielie.blockbard.client.playback.nbsInstrumentToBlock
import kyrielie.blockbard.client.input.KeyboardInputHandler
import kyrielie.blockbard.client.midi.OrganReadinessChecker
import kyrielie.blockbard.organ.*
import kyrielie.blockbard.client.organ.OrganScanner
import kyrielie.blockbard.client.player.CenterResult
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.player.SoundVerifier
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.playback.LoopSettings
import kyrielie.blockbard.playback.QueueRow
import kyrielie.blockbard.playback.QueueSource
import kyrielie.blockbard.playback.RepeatMode
import kyrielie.blockbard.playback.SongId
import kyrielie.blockbard.util.BlockBardLogBuffer
import kyrielie.blockbard.util.DebugLog
import kyrielie.blockbard.util.LogLevel
import kyrielie.blockbard.util.blockBelowHint
import kyrielie.blockbard.util.clicksNeeded
import kyrielie.blockbard.util.isPitched
import kyrielie.blockbard.util.midiBase
import kyrielie.blockbard.util.midiNoteToName
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import kyrielie.blockbard.client.config.BlockBardYaclConfig
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

class MainScreen(private val parent: Screen? = null, initialTab: Int = 0) : Screen(Component.literal("BlockBard")), PlaybackHost {

    private val logger = LoggerFactory.getLogger("BlockBard/MainScreen")

    // ── Tab state ──────────────────────────────────────────────────────────────
    // 0 = Files, 1 = Queue, 2 = Organ, 3 = Logs
    private var activeTab: Int = initialTab.coerceIn(0, 3)

    // Tab button references kept so updateTabVisibility() can toggle them
    private var tabButtons: List<Button> = emptyList()
    // All per-tab content widgets, grouped by tab index
    private val tabContent: MutableMap<Int, MutableList<AbstractWidget>> = mutableMapOf(
        0 to mutableListOf(),
        1 to mutableListOf(),
        2 to mutableListOf(),
        3 to mutableListOf()
    )
    private val jukeboxWidgets: MutableList<AbstractWidget> = mutableListOf()
    private lateinit var playPauseBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var shuffleBtn: Button

    // Dynamic list of all tunable instruments (includes all 20 instruments including Trumpet variants)
    private val playableInstruments = NoteBlockInstrument.entries.filter { it.isTunable }
    private var selectedInstrumentIndex: Int = 0
    val selectedInstrument: NoteBlockInstrument get() = playableInstruments[selectedInstrumentIndex]

    private fun formatInstrumentName(inst: NoteBlockInstrument): String = when (inst) {
        NoteBlockInstrument.IRON_XYLOPHONE    -> "Iron Xylo"
        NoteBlockInstrument.TRUMPET           -> "Trumpet"
        NoteBlockInstrument.TRUMPET_EXPOSED   -> "Trp (Exposed)"
        NoteBlockInstrument.TRUMPET_WEATHERED -> "Trp (Weath)"
        NoteBlockInstrument.TRUMPET_OXIDIZED  -> "Trp (Oxid)"
        NoteBlockInstrument.DIDGERIDOO        -> "Didgeridoo"
        NoteBlockInstrument.COW_BELL          -> "Cowbell"
        NoteBlockInstrument.BASEDRUM          -> "Basedrum"
        else -> inst.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    private var virtualKeyboard: VirtualKeyboardWidget? = null
    private var instBtn: Button? = null

    private lateinit var songListWidget: SongListWidget
    private lateinit var queueListWidget: QueueListWidget
    private lateinit var logListWidget: LogListWidget
    // The live play queue (user queue, context order, history). It outlives this screen.
    private val queue get() = ClientPlayQueue.queue

    // Cached by updateQueueDisplay() so the per-frame renderers do not copy the queue.
    private var upcomingRows: List<QueueRow> = emptyList()
    private var queueUserCount: Int = 0
    private var queueContextRemaining: Int = 0
    private var nextUpTitle: String? = null

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
    private var tuningError: String? = null
    private var loadedNbs: NbsFile? = null

    // ── Loop / auto-advance (Tier 4) ───────────────────────────────────────────
    // What comes after a song ends is decided by PlaybackCoordinator (client thread). Here,
    // pendingAutoPlay only means "when the current tuner run finishes, start playback"
    // (the tune-then-play chain). Only written from the client thread.
    @Volatile private var pendingAutoPlay: Boolean = false

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
        // Stays registered after this screen closes, so auto-advance keeps working with the GUI closed.
        PlaybackCoordinator.host = this
        super.init()
        logger.info("MainScreen: init()")
        tabContent.values.forEach { it.clear() }
        refreshFiles()
        if (!PlayerController.isOrganMapValid()) {
            PlayerController.reconfirmOrgan()
        } else {
            OrganScanner.scan()
        }
        updateOrganInfo()

        // ── Tab buttons (always visible) ──────────────────────────────────────
        val tabW = 50
        val tabBtnFiles = addRenderableWidget(Button.builder(Component.literal("Files")) {
            switchTab(0)
        }.pos(10, 6).size(tabW, 16).build())
        val tabBtnQueue = addRenderableWidget(Button.builder(Component.literal("Queue")) {
            switchTab(1)
        }.pos(64, 6).size(tabW, 16).build())
        val tabBtnOrgan = addRenderableWidget(Button.builder(Component.literal("Organ")) {
            switchTab(2)
        }.pos(118, 6).size(tabW, 16).build())
        val tabBtnLogs = addRenderableWidget(Button.builder(Component.literal("Logs")) {
            switchTab(3)
        }.pos(172, 6).size(tabW, 16).build())
        tabButtons = listOf(tabBtnFiles, tabBtnQueue, tabBtnOrgan, tabBtnLogs)

        // Settings button opens YACL screen
        addRenderableWidget(Button.builder(Component.literal("⚙ Settings")) {
            logger.info("Opening YACL settings screen")
            minecraft.gui.setScreen(BlockBardYaclConfig.createScreen(this))
        }.pos(width - 148, 6).size(78, 16).build())

        // Close button -- always visible top right
        addRenderableWidget(Button.builder(Component.literal("Close")) {
            logger.info("Close clicked")
            onClose()
        }.pos(width - 64, 6).size(54, 16).build())

        val listTop = 46
        val listBottom = height - 112
        val listHeight = (listBottom - listTop).coerceAtLeast(30)

        // ── Tab 0: Files ──────────────────────────────────────────────────────
        fun <T : AbstractWidget> tab0(w: T): T { tabContent[0]!!.add(w); return w }

        val searchBox = EditBox(font, 10, 26, width - 236, 16, Component.literal("Search"))
        searchBox.setHint(Component.literal("Search tracks..."))
        searchBox.value = searchQuery
        searchBox.setResponder { text ->
            searchQuery = text
            if (::songListWidget.isInitialized) {
                songListWidget.setFiles(displayedFiles, selectedFile)
            }
        }
        tab0(addRenderableWidget(searchBox))

        tab0(addRenderableWidget(Button.builder(Component.literal("Queue view")) {
            queueView()
        }.tooltip(Tooltip.create(Component.literal("Add every song shown in the list (after filter and search) to the end of your queue")))
            .pos(width - 222, 26).size(76, 16).build()))

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
                playSelectedSong()
            },
            onToggleFavorite = { file ->
                ConfigManager.config.toggleFavorite(file.name)
                ConfigManager.save()
                songListWidget.setFiles(displayedFiles, selectedFile)
            },
            onQueueNext = { file ->
                queue.enqueueNext(songIdOf(file))
                updateQueueDisplay()
                chat("Playing next: ${file.nameWithoutExtension}")
            },
            onQueueLast = { file ->
                queue.enqueueLast(songIdOf(file))
                updateQueueDisplay()
                chat("Added to queue: ${file.nameWithoutExtension}")
            }
        )
        tab0(addRenderableWidget(songListWidget))
        songListWidget.setFiles(displayedFiles, selectedFile)

        // ── Tab 1: Queue ──────────────────────────────────────────────────────
        fun <T : AbstractWidget> tab1(w: T): T { tabContent[1]!!.add(w); return w }
        val qBtnY = 26

        tab1(addRenderableWidget(Button.builder(Component.literal("🔀 Reshuffle")) {
            reshuffleQueue()
        }.tooltip(Tooltip.create(Component.literal("New random order for the playlist (needs Shuffle on)"))).pos(10, qBtnY).size(74, 16).build()))

        tab1(addRenderableWidget(Button.builder(Component.literal("Clear Queue")) {
            queue.clearUser()
            updateQueueDisplay()
            chat("Play queue cleared")
        }.tooltip(Tooltip.create(Component.literal("Remove the songs you queued; the playlist order is kept"))).pos(88, qBtnY).size(74, 16).build()))

        queueListWidget = QueueListWidget(
            minecraft = minecraft,
            width = width,
            height = listHeight,
            y = listTop,
            itemHeight = 26,
            onPlayNow = { item -> playQueueRow(item) },
            onRemove = { item -> removeFromQueue(item) },
            onMoveUp = { item -> moveQueueItem(item, -1) },
            onMoveDown = { item -> moveQueueItem(item, 1) },
            onMoveToTop = { item -> moveQueueItemToTop(item) },
            onPromote = { item -> promoteQueueRow(item) }
        )
        tab1(addRenderableWidget(queueListWidget))

        // ── Tab 2: Organ Console ─────────────────────────────────────────────
        fun <T : AbstractWidget> tab2(w: T): T { tabContent[2]!!.add(w); return w }
        val organBtnY = 26

        // Instrument Switcher
        tab2(addRenderableWidget(Button.builder(Component.literal("<")) {
            selectedInstrumentIndex = (selectedInstrumentIndex - 1 + playableInstruments.size) % playableInstruments.size
            virtualKeyboard?.instrument = selectedInstrument
            instBtn?.message = Component.literal(formatInstrumentName(selectedInstrument))
        }.pos(10, organBtnY).size(20, 16).build()))

        val buttonInst = tab2(addRenderableWidget(Button.builder(Component.literal(formatInstrumentName(selectedInstrument))) {
            selectedInstrumentIndex = (selectedInstrumentIndex + 1) % playableInstruments.size
            virtualKeyboard?.instrument = selectedInstrument
            instBtn?.message = Component.literal(formatInstrumentName(selectedInstrument))
        }.pos(32, organBtnY).size(92, 16).build()))
        instBtn = buttonInst

        tab2(addRenderableWidget(Button.builder(Component.literal(">")) {
            selectedInstrumentIndex = (selectedInstrumentIndex + 1) % playableInstruments.size
            virtualKeyboard?.instrument = selectedInstrument
            instBtn?.message = Component.literal(formatInstrumentName(selectedInstrument))
        }.pos(126, organBtnY).size(20, 16).build()))

        // Organ Utility Buttons
        tab2(addRenderableWidget(Button.builder(Component.literal("Scan")) {
            logger.info("Scan clicked")
            OrganScanner.scan()
            updateOrganInfo()
        }.pos(152, organBtnY).size(40, 16).build()))

        tab2(addRenderableWidget(Button.builder(Component.literal("Center")) {
            logger.info("Center clicked")
            val result = PlayerController.centerOnOrgan()
            organScanMessage = when (result) {
                is CenterResult.Centered -> "${result.reachableCount}/${result.totalFound} reachable."
                CenterResult.NoBlocks   -> "No playable noteblocks found."
                CenterResult.NoPlayer   -> "No player found."
            }
            chat(organScanMessage)
        }.pos(194, organBtnY).size(48, 16).build()))

        tab2(addRenderableWidget(Button.builder(Component.literal("Tune")) {
            logger.info("Tune clicked (chromatic mode)")
            startTuning(forceChromatic = true)
        }.pos(244, organBtnY).size(42, 16).build()))

        tab2(addRenderableWidget(Button.builder(Component.literal("Scale")) {
            logger.info("Play Scale clicked")
            playScale()
        }.pos(288, organBtnY).size(42, 16).build()))

        tab2(addRenderableWidget(Button.builder(Component.literal(if (MaterialsShoppingOverlay.isVisible) "Hide Mats" else "Mats")) { btn ->
            MaterialsShoppingOverlay.isVisible = !MaterialsShoppingOverlay.isVisible
            btn.message = Component.literal(if (MaterialsShoppingOverlay.isVisible) "Hide Mats" else "Mats")
            chat("Materials overlay ${if (MaterialsShoppingOverlay.isVisible) "shown" else "hidden"}")
        }.pos(332, organBtnY).size(46, 16).build()))

        tab2(addRenderableWidget(Button.builder(Component.literal(if (kyrielie.blockbard.client.render.OrganOverlayRenderer.isEnabled) "Holo: ON" else "Holo: OFF")) { btn ->
            val on = kyrielie.blockbard.client.render.OrganOverlayRenderer.toggle()
            btn.message = Component.literal(if (on) "Holo: ON" else "Holo: OFF")
            chat("Organ hologram ${if (on) "enabled" else "disabled"}")
        }.pos(380, organBtnY).size(56, 16).build()))

        tab2(addRenderableWidget(Button.builder(Component.literal(if (kyrielie.blockbard.client.render.OrganBlueprintBuilder.isEnabled) "Stage: ON" else "Stage: OFF")) { btn ->
            val on = kyrielie.blockbard.client.render.OrganBlueprintBuilder.toggle()
            btn.message = Component.literal(if (on) "Stage: ON" else "Stage: OFF")
            chat("Stage blueprint guide ${if (on) "enabled" else "disabled"}")
        }.pos(438, organBtnY).size(58, 16).build()))

        // Virtual Piano Keyboard Widget
        val kbW = (width - 20).coerceAtMost(480)
        val kbH = (height - 130).coerceIn(60, 95)
        val kbX = (width - kbW) / 2
        val kbY = 66
        val vk = VirtualKeyboardWidget(kbX, kbY, kbW, kbH, selectedInstrument) { noteIdx, midi, inst ->
            chat("Played ${midiNoteToName(midi)} (${inst.name})")
        }
        virtualKeyboard = vk
        tab2(addRenderableWidget(vk))

        // ── Tab 3: Logs ───────────────────────────────────────────────────────
        fun <T : AbstractWidget> tab3(w: T): T { tabContent[3]!!.add(w); return w }

        val logBtnY = 26
        tab3(addRenderableWidget(Button.builder(Component.literal("Copy All")) {
            copyLogsToClipboard()
        }.pos(10, logBtnY).size(65, 16).build()))

        var errorFilterOnly = false
        tab3(addRenderableWidget(Button.builder(Component.literal("Show: All")) { btn ->
            errorFilterOnly = !errorFilterOnly
            btn.message = Component.literal(if (errorFilterOnly) "Show: Errors" else "Show: All")
            logListWidget.refreshLogs(if (errorFilterOnly) LogLevel.WARN else null)
        }.pos(79, logBtnY).size(84, 16).build()))

        tab3(addRenderableWidget(Button.builder(Component.literal("Clear")) {
            BlockBardLogBuffer.clear()
            logListWidget.refreshLogs()
            chat("Logs cleared")
        }.pos(167, logBtnY).size(48, 16).build()))

        val logListH = height - 52
        val logWidget = LogListWidget(minecraft, width - 20, logListH, 46, 14) { entry ->
            minecraft.keyboardHandler.clipboard = entry.formatted()
            chat("Copied entry to clipboard: ${entry.message.take(35)}...")
        }
        logListWidget = logWidget
        tab3(addRenderableWidget(logWidget))

        // ── Jukebox Controls (Shared across Tab 0 & Tab 1) ────────────────────
        jukeboxWidgets.clear()
        fun <T : AbstractWidget> jb(w: T): T { jukeboxWidgets.add(w); return w }

        val jbX = 6
        val jbY = height - 108
        val row1Y = jbY + 68
        val row2Y = jbY + 86

        // Row 1: Primary transport
        jb(addRenderableWidget(Button.builder(Component.literal("|◀")) {
            playPrevSong()
        }.tooltip(Tooltip.create(Component.literal("Previous Track / Restart (if >3s)"))).pos(jbX + 8, row1Y).size(30, 16).build()))

        playPauseBtn = jb(addRenderableWidget(Button.builder(Component.literal("▶ Play")) {
            togglePlayPause()
        }.tooltip(Tooltip.create(Component.literal("Play or Pause playback without resetting progress"))).pos(jbX + 42, row1Y).size(56, 16).build()))

        jb(addRenderableWidget(Button.builder(Component.literal("⏹")) {
            PlaybackCoordinator.stop()
            stopScale()
            pendingAutoPlay = false
            tuningError = null
            updateQueueDisplay()
            chat("Stopped")
        }.tooltip(Tooltip.create(Component.literal("Stop playback, clear queue schedule, and reset to 0:00"))).pos(jbX + 102, row1Y).size(28, 16).build()))

        jb(addRenderableWidget(Button.builder(Component.literal("▶|")) {
            playNextSong()
        }.tooltip(Tooltip.create(Component.literal("Next Track in Queue"))).pos(jbX + 134, row1Y).size(30, 16).build()))

        jb(addRenderableWidget(Button.builder(Component.literal("Tune & Play")) {
            playSelectedSong()
        }.tooltip(Tooltip.create(Component.literal("Tune organ to match song and start playback"))).pos(jbX + 168, row1Y).size(72, 16).build()))

        jb(addRenderableWidget(Button.builder(Component.literal(if (SongAudioPreviewer.isPlaying) "Stop Prev" else "♪ Preview")) { btn ->
            if (SongAudioPreviewer.isPlaying) {
                SongAudioPreviewer.stopPreview()
                btn.message = Component.literal("♪ Preview")
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
        }.pos(jbX + 244, row1Y).size(62, 16).build()))

        jb(addRenderableWidget(Button.builder(Component.literal("Queue ☰")) {
            switchTab(if (activeTab == 1) 0 else 1)
        }.pos(jbX + 310, row1Y).size(56, 16).build()))

        // Row 2: Secondary controls
        jb(addRenderableWidget(Button.builder(Component.literal("Tempo -")) {
            MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier - 0.1f).coerceAtLeast(0.5f)
            chat("Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x")
        }.pos(jbX + 8, row2Y).size(48, 16).build()))

        jb(addRenderableWidget(Button.builder(Component.literal("Tempo +")) {
            MidiFilePlayer.tempoMultiplier = (MidiFilePlayer.tempoMultiplier + 0.1f).coerceAtLeast(0.5f).coerceAtMost(2.0f)
            chat("Tempo: ${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x")
        }.pos(jbX + 60, row2Y).size(48, 16).build()))

        loopBtn = jb(addRenderableWidget(Button.builder(Component.literal(loopModeLabel())) { btn ->
            val modes = RepeatMode.entries
            val next = modes[(modes.indexOf(ConfigManager.config.repeatModeEnum()) + 1) % modes.size]
            ConfigManager.config.repeatMode = next.name
            ConfigManager.save()
            ClientPlayQueue.applyConfig(ConfigManager.config)
            btn.message = Component.literal(loopModeLabel())
            chat("Loop: ${loopModeLabel()}")
            updateQueueDisplay()
        }.tooltip(Tooltip.create(Component.literal("Repeat OFF, ALL or ONE. Songs you queued always play first."))).pos(jbX + 112, row2Y).size(92, 16).build()))

        shuffleBtn = jb(addRenderableWidget(Button.builder(Component.literal(shuffleLabel())) { btn ->
            ConfigManager.config.shuffle = !ConfigManager.config.shuffle
            ConfigManager.save()
            ClientPlayQueue.applyConfig(ConfigManager.config)
            btn.message = Component.literal(shuffleLabel())
            loopBtn.message = Component.literal(loopModeLabel())
            chat("Shuffle ${if (ConfigManager.config.shuffle) "on" else "off"}")
            updateQueueDisplay()
        }.tooltip(Tooltip.create(Component.literal("Play the playlist in random order"))).pos(jbX + 208, row2Y).size(66, 16).build()))

        jb(addRenderableWidget(Button.builder(Component.literal("Refresh")) {
            refreshFiles()
            chat("Refreshed -- ${midiFiles.size} files found")
        }.pos(jbX + 278, row2Y).size(52, 16).build()))

        updateQueueDisplay()

        // Apply initial tab visibility
        updateTabVisibility()
    }

    // ── Tab management ─────────────────────────────────────────────────────────

    private fun switchTab(index: Int) {
        activeTab = index
        focused = null
        if (activeTab == 1 && ::queueListWidget.isInitialized) {
            updateQueueDisplay()
        }
        if (activeTab == 3 && ::logListWidget.isInitialized) {
            logListWidget.refreshLogs()
        }
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
        if (::songListWidget.isInitialized) songListWidget.visible = (activeTab == 0)
        if (::queueListWidget.isInitialized) queueListWidget.visible = (activeTab == 1)
        virtualKeyboard?.visible = (activeTab == 2)
        if (::logListWidget.isInitialized) logListWidget.visible = (activeTab == 3)

        val showJukebox = (activeTab == 0 || activeTab == 1)
        jukeboxWidgets.forEach { it.visible = showJukebox; it.active = showJukebox }
    }

    // ── Label helpers ──────────────────────────────────────────────────────────

    private fun loopModeLabel(): String =
        LoopSettings.label(ConfigManager.config.repeatModeEnum(), ConfigManager.config.shuffle)

    private fun shuffleLabel(): String = if (ConfigManager.config.shuffle) "Shuf: ON" else "Shuf: OFF"

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun chat(msg: String) {
        BlockBardLogBuffer.info("GUI", msg)
        if (ConfigManager.config.enableChatFeedback) {
            minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §f$msg"))
        }
    }

    private fun chatWarn(msg: String) {
        BlockBardLogBuffer.warn("GUI", msg)
        if (ConfigManager.config.enableChatFeedback) {
            minecraft.player?.sendSystemMessage(Component.literal("§b[BlockBard] §c$msg"))
        }
    }

    private fun refreshFiles() {
        midiFiles = (midisDir.listFiles { f ->
            f.extension.lowercase() in listOf("mid", "midi", "nbs")
        } ?: emptyArray()).toList().sortedBy { it.name }
        // The context is the whole library, not the filtered view. refreshContext keeps the
        // current song and the shuffle order when only some files changed.
        queue.refreshContext(midiFiles.map(::songIdOf))

        // If a track is already playing or paused, preserve it as the active selection without stopping playback
        val activePlayingFile = MidiFilePlayer.loadedMidi?.file ?: loadedNbs?.file ?: NbsPlayer.currentNbs?.file
        val isPlaybackActive = (MidiFilePlayer.isActive() || NbsPlayer.isPlaying || MidiFilePlayer.isPaused || NbsPlayer.isPaused)

        if (activePlayingFile != null && isPlaybackActive) {
            selectedFile = activePlayingFile
            selectedIndex = midiFiles.indexOfFirst { it.name.equals(activePlayingFile.name, ignoreCase = true) }
        } else if (selectedFile == null && midiFiles.isNotEmpty()) {
            val last = ConfigManager.config.lastPlayedTrack
            val match = midiFiles.firstOrNull { it.name.equals(last, ignoreCase = true) } ?: midiFiles.first()
            selectFile(match, stopPlayback = false)
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

    // ── Loop / auto-advance (Tier 4) & Queue ───────────────────────────────────

    /** Library file for a song id; a placeholder path when the file is gone so the row still shows. */
    override fun fileForId(id: SongId): File =
        midiFiles.firstOrNull { songIdOf(it) == id } ?: midisDir.resolve(id)

    /** Starts [id] as the next track. Returns false and warns when the file no longer exists. */
    override fun startQueuedSong(id: SongId): Boolean {
        val file = fileForId(id)
        if (!file.isFile) {
            chatWarn("File not found: $id")
            updateQueueDisplay()
            return false
        }
        selectFile(file)
        if (::songListWidget.isInitialized) {
            songListWidget.setFiles(displayedFiles, selectedFile)
        }
        updateQueueDisplay()
        playSelectedSong(fromQueue = true)
        return true
    }

    private fun playNextSong() {
        val id = queue.skipNext() ?: run {
            chatWarn("End of queue")
            return
        }
        startQueuedSong(id)
    }

    private fun playPrevSong() {
        val (elapsedSecs, _) = getPlaybackTimes()
        if (elapsedSecs > 3.0) {
            seekToFraction(0.0)
            chat("Restarted track")
            return
        }
        val id = queue.skipPrev()
        if (id == null) {
            // No earlier song in the history: restart the current one if there is one.
            if (queue.nowPlaying != null) {
                seekToFraction(0.0)
                chat("Restarted track")
            } else {
                chatWarn("No previous track")
            }
            return
        }
        startQueuedSong(id)
    }

    private fun reshuffleQueue() {
        if (!ConfigManager.config.shuffle) {
            chatWarn("Shuffle is off. Turn it on with the Shuf button first.")
            return
        }
        queue.reshuffle()
        updateQueueDisplay()
        chat("Queue reshuffled")
    }

    /** Play now from a queue row (double-click). A user row is removed; a context row moves the cursor. */
    private fun playQueueRow(item: QueueItem) {
        // The list may be stale if something changed the queue since it was drawn.
        if (queue.upcoming(item.index + 1).getOrNull(item.index) != item.row) {
            updateQueueDisplay()
            return
        }
        val id = when (item.row.source) {
            QueueSource.USER -> queue.startFromUser(item.index)
            QueueSource.CONTEXT -> queue.startFromUpcoming(item.index - queue.userQueue.size)
        }
        if (id == null) {
            updateQueueDisplay()
            return
        }
        startQueuedSong(id)
    }

    /** Only songs the player queued can be removed; the rest of the list is the playlist order. */
    private fun removeFromQueue(item: QueueItem) {
        if (item.row.source != QueueSource.USER || queue.userQueue.getOrNull(item.index) != item.row.id) {
            updateQueueDisplay()
            return
        }
        val removed = queue.removeUser(item.index)
        if (removed != null) {
            chat("Removed from queue: ${fileForId(removed).nameWithoutExtension}")
        }
        updateQueueDisplay()
    }

    private fun moveQueueItem(item: QueueItem, delta: Int) {
        if (item.row.source != QueueSource.USER || queue.userQueue.getOrNull(item.index) != item.row.id) {
            updateQueueDisplay()
            return
        }
        if (queue.moveUser(item.index, item.index + delta)) {
            updateQueueDisplay()
        }
    }

    private fun moveQueueItemToTop(item: QueueItem) {
        if (item.row.source != QueueSource.USER || queue.userQueue.getOrNull(item.index) != item.row.id) {
            updateQueueDisplay()
            return
        }
        queue.moveUserToFront(item.index)
        updateQueueDisplay()
    }

    /** "+Q" on a playlist row: copies it to the end of the user queue. The playlist is unchanged. */
    private fun promoteQueueRow(item: QueueItem) {
        if (item.row.source != QueueSource.CONTEXT || queue.upcoming(item.index + 1).getOrNull(item.index) != item.row) {
            updateQueueDisplay()
            return
        }
        if (queue.promote(item.index - queue.userQueue.size)) {
            chat("Added to queue: ${fileForId(item.row.id).nameWithoutExtension}")
        }
        updateQueueDisplay()
    }

    /** Queues every song currently shown in the Files tab, in view order, after anything already queued. */
    private fun queueView() {
        val files = displayedFiles
        if (files.isEmpty()) {
            chatWarn("Nothing in the list to queue")
            return
        }
        files.forEach { queue.enqueueLast(songIdOf(it)) }
        updateQueueDisplay()
        chat("Queued ${files.size} track${if (files.size == 1) "" else "s"} from the current list")
    }

    /** Re-reads the queue into the cached display state and the list widget. */
    private fun updateQueueDisplay() {
        val rows = queue.upcoming(QUEUE_DISPLAY_LIMIT)
        upcomingRows = rows
        queueUserCount = queue.userQueue.size
        queueContextRemaining = (queue.contextOrder.size - (queue.contextCursor + 1)).coerceAtLeast(0)
        val byId = midiFiles.associateBy { songIdOf(it) }
        nextUpTitle = rows.firstOrNull()?.let { (byId[it.id] ?: midisDir.resolve(it.id)).nameWithoutExtension }
        if (::queueListWidget.isInitialized) {
            queueListWidget.setQueue(
                rows.mapIndexed { index, row -> QueueItem(index, row, byId[row.id] ?: midisDir.resolve(row.id)) }
            )
        }
    }

    private fun togglePlayPause() {
        when {
            MidiFilePlayer.isPaused   -> { MidiFilePlayer.resume(); chat("Resumed") }
            NbsPlayer.isPaused        -> { NbsPlayer.resume();      chat("Resumed") }
            MidiFilePlayer.isActive() -> { MidiFilePlayer.pause();  chat("Paused") }
            NbsPlayer.isPlaying       -> { NbsPlayer.pause();       chat("Paused") }
            else                      -> { playSelectedSong() }
        }
    }

    // ── PlaybackHost ───────────────────────────────────────────────────────────

    override val isTuning: Boolean
        get() = tuner?.isActive == true || activeTuner?.isActive == true

    override fun beginAdvance(file: File) {
        selectFile(file)
        // NoteBlockTuner skips blocks already at their target note, so for songs sharing most
        // pitches this is fast (mostly verification passes).
        startTuning(thenPlay = true)
    }

    override fun replayCurrent() {
        // The user may have browsed to another row since this song started; replay the song
        // that is actually playing (its organ assignment is still the one in use).
        val playing = queue.nowPlaying?.let { fileForId(it) }
        if (playing != null && playing.isFile && playing != selectedFile) selectFile(playing)
        startPlayback()
    }

    override fun onQueueChanged() {
        updateQueueDisplay()
    }

    // ── Tuning ─────────────────────────────────────────────────────────────────

    private fun startTuning(forceChromatic: Boolean = false, thenPlay: Boolean = false) {
        SongAudioPreviewer.stopPreview()
        val mc = minecraft
        val player = mc.player ?: run { chatWarn("No player"); return }

        val gameMode = mc.gameMode?.playerMode
        if (gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR) {
            chatWarn("Cannot tune in ${gameMode.getName()} mode")
            return
        }

        // Auto-select empty hotbar slot if main hand is holding an item
        if (!player.mainHandItem.isEmpty) {
            val emptySlot = (0..8).firstOrNull { player.inventory.getItem(it).isEmpty }
            if (emptySlot != null) {
                player.inventory.selectedSlot = emptySlot
                player.connection.send(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(emptySlot))
                logger.info("startTuning: switched to empty hotbar slot $emptySlot")
            } else {
                chatWarn("Hotbar has no empty slots -- clear a slot before tuning")
                return
            }
        }
        if (!player.offhandItem.isEmpty) {
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
            val targets = MidiToOrganMapper.computeTuneTargets(result, reachable)
            kyrielie.blockbard.client.render.OrganOverlayRenderer.activeTargets = targets.associate { it.pos to it.targetNote }
            return targets
        }

        val midi = if (forceChromatic) null else MidiFilePlayer.loadedMidi
        val nbs = if (forceChromatic) null else loadedNbs?.takeIf { it.file == selectedFile }
        val targets: List<TuneTarget>

        if (midi != null) {
            logger.info("startTuning: MIDI mode -- ${reachable.size} reachable, ${midi.distinctNotes.size} distinct notes")
            targets = assignAndReport(midi.noteUsageCounts)
        } else if (nbs != null) {
            val noteUsageCounts = nbs.notes
                .mapNotNull { note ->
                    val inst = nbsInstrumentToBlock(note.instrument) ?: return@mapNotNull null
                    val midi = if (!inst.isPitched) inst.midiBase else inst.midiBase + (note.key - 33)
                    NotePitch(midi, inst)
                }
                .groupingBy { it }
                .eachCount()
            logger.info("startTuning: NBS mode -- ${reachable.size} reachable, ${noteUsageCounts.size} distinct pairs")
            targets = assignAndReport(noteUsageCounts)
        } else {
            // Full Chromatic Scale Tuning (0..24 for each instrument in reach)
            logger.info("startTuning: chromatic mode -- ${reachable.size} blocks across instruments")
            chat("Tuning organ to chromatic scale (0..24 for each instrument)...")
            val sorted = reachable.sortedBy { it.distanceFromPlayer }
            targets = sorted.groupBy { it.instrument }.flatMap { (inst, blocksForInstrument) ->
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
        }

        val totalClicks = targets.sumOf { it.estimatedClicks }
        if (targets.isEmpty() || totalClicks == 0) {
            chat("Organ is in tune!")
            logger.info("startTuning: 0 clicks needed -- skipping tuner")
            if (thenPlay) startPlayback()
            return
        }

        chat("Tuning ${targets.size} blocks (~$totalClicks clicks)...")
        logger.info("startTuning: ${targets.size} targets, $totalClicks clicks, thenPlay=$thenPlay")

        val newTuner = NoteBlockTuner(
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
            primeRotation     = { pos -> PlayerController.primeRotation(pos, isTuning = true) },
            rotationConverged = { pos -> PlayerController.rotationConverged(pos, isTuning = true) },
            diagnoseFailState = { pos, _, _ ->
                PlayerController.diagnoseBlockInteractability(pos)
            },
            onBlockFailed     = { target, failState, _, _ ->
                NoteBlockRegistry.markUntunable(target.pos, failState)
                kyrielie.blockbard.client.render.ManualTuningHighlighter.add(
                    target.pos,
                    target.instrument,
                    target.targetNote,
                    failState
                )
            },
            onProgress        = { done, total, msg ->
                tuneStatusMsg = "$done/$total -- $msg"
                logger.debug("tuner progress: $tuneStatusMsg")
            }
        )
        tuner = newTuner
        activeTuner = newTuner
        currentScreen = this

        pendingAutoPlay = thenPlay

        newTuner.start()
    }

    override fun abortTuning() {
        if (tuner?.isActive == true || activeTuner?.isActive == true) {
            chat("Tuning cancelled")
            logger.info("abortTuning: tuner cancelled")
        }
        tuner = null
        activeTuner = null
        pendingAutoPlay = false
        tuneStatusMsg = ""
        kyrielie.blockbard.client.render.OrganOverlayRenderer.activeTargets = emptyMap()
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

        val shiftedCount = result.shifts.count { it.value != 0 }

        coverageMessage = buildString {
            append("$covered/$total notes covered")
            if (shiftedCount > 0) {
                append(". ⚠ $shiftedCount note(s) octave-shifted")
            }
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

    /**
     * Starts or resumes the selected song. [fromQueue] is true when the play queue already
     * decided what plays (skip, play-now from a queue row); otherwise a manual start registers
     * the song with the queue as playing from the playlist.
     */
    private fun playSelectedSong(fromQueue: Boolean = false) {
        val file = (if (::songListWidget.isInitialized) songListWidget.getSelectedFile() else null)
            ?: selectedFile
            ?: run {
                chatWarn("Select a song first")
                return
            }
        tuningError = null
        if (selectedFile != file) {
            selectFile(file, stopPlayback = true)
        }

        val isCurrentMidi = MidiFilePlayer.loadedMidi?.file == file
        val isCurrentNbs = loadedNbs?.file == file

        when {
            MidiFilePlayer.isPaused && isCurrentMidi -> {
                MidiFilePlayer.resume()
                chat("Resumed: ${file.name}")
            }
            NbsPlayer.isPaused && isCurrentNbs -> {
                NbsPlayer.resume()
                chat("Resumed: ${file.name}")
            }
            else -> {
                if (!isCurrentMidi && !isCurrentNbs) {
                    MidiFilePlayer.stop()
                    NbsPlayer.stop()
                }
                val hasOrgan = PlayerController.reconfirmOrgan()
                if (!hasOrgan) {
                    chatWarn("No playable noteblocks found in reach around player.")
                    return
                }
                if (!fromQueue) {
                    val id = songIdOf(file)
                    if (queue.nowPlaying != id) queue.startFromContext(id)
                    updateQueueDisplay()
                }
                updateOrganInfo()
                startTuning(thenPlay = true)
            }
        }
    }

    private fun startPlayback() {
        SongAudioPreviewer.stopPreview()
        val file = selectedFile ?: run { chatWarn("No file selected"); return }
        logger.info("startPlayback: ${file.name}")

        // Ensure organ assignment is computed so ArpeggioScheduler maps notes to blocks
        if (assignment == null) {
            val map = PlayerController.organMap
            val reachable = if (map != null) NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) } else emptyList()
            if (reachable.isNotEmpty()) {
                val noteUsageCounts: Map<NotePitch, Int> = if (file.extension.lowercase() == "nbs") {
                    val nbs = loadedNbs ?: try { NbsFileLoader.load(file).also { loadedNbs = it } } catch (e: Exception) { null }
                    nbs?.notes?.mapNotNull { note ->
                        val inst = nbsInstrumentToBlock(note.instrument) ?: return@mapNotNull null
                        val midi = if (!inst.isPitched) inst.midiBase else inst.midiBase + (note.key - 33)
                        NotePitch(midi, inst)
                    }?.groupingBy { it }?.eachCount() ?: emptyMap()
                } else {
                    val midi = MidiFilePlayer.loadedMidi ?: try { MidiFilePlayer.load(file) } catch (e: Exception) { null }
                    midi?.noteUsageCounts ?: emptyMap()
                }
                if (noteUsageCounts.isNotEmpty()) {
                    val result = MidiToOrganMapper.buildAssignment(noteUsageCounts, reachable)
                    assignment = result
                    ArpeggioScheduler.assignment = result.assignment
                    ArpeggioScheduler.voicePools = result.voicePools
                    updateCoverageMessage(result)
                }
            }
        }

        // Tier 6d: warn about missing coverage so the player knows to expect silent notes,
        // but do not block -- they chose to play, let them.
        val unplayableCount = assignment?.unplayable?.size ?: 0
        if (unplayableCount > 0) {
            chatWarn("$unplayableCount notes will be silent -- organ incomplete (see Files tab)")
        }

        val map = PlayerController.organMap
        val reachable = if (map != null) NoteBlockRegistry.allPlayable().filter { map.isReachable(it.pos) } else emptyList()

        // Compile lookahead dynamic performance plan if enabled
        if (ConfigManager.config.dynamicRetuning && reachable.isNotEmpty()) {
            val timelineNotes: List<TimelineNote> = if (file.extension.lowercase() == "nbs") {
                val nbs = loadedNbs ?: try { NbsFileLoader.load(file).also { loadedNbs = it } } catch (e: Exception) { null }
                nbs?.notes?.mapNotNull { note ->
                    val inst = nbsInstrumentToBlock(note.instrument) ?: return@mapNotNull null
                    val midi = if (!inst.isPitched) inst.midiBase else inst.midiBase + (note.key - 33)
                    val timeMs = (note.tick / nbs.tempo * 1000.0).toLong()
                    TimelineNote(timeMs, midi, inst)
                } ?: emptyList()
            } else {
                val midi = MidiFilePlayer.loadedMidi ?: try { MidiFilePlayer.load(file) } catch (e: Exception) { null }
                midi?.events?.map { ev ->
                    val timeMs = midi.tempoMap.tickToMs(ev.tick)
                    TimelineNote(timeMs, ev.midiNote, ev.instrument)
                } ?: emptyList()
            }

            if (timelineNotes.isNotEmpty()) {
                val plan = TimelineVoiceAllocator.compilePlan(timelineNotes, reachable)
                ArpeggioScheduler.loadRetuneSchedule(plan.retuneTasks)
                val stats = plan.stats
                logger.info("Dynamic Retuning Plan loaded: ${stats.lookaheadRetunesScheduled} rest retunes for ${stats.totalNotes} notes across ${stats.physicalBlocksUsed} blocks")
                if (stats.lookaheadRetunesScheduled > 0) {
                    chat("Dynamic Retuning: ${stats.lookaheadRetunesScheduled} rest retunes scheduled across ${stats.physicalBlocksUsed} blocks.")
                }
            }
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
            chat("Playing: ${file.name}")
            NbsPlayer.play(nbs)
            PlaybackCoordinator.onPlaybackStarted(file)
        } else {
            if (MidiFilePlayer.loadedMidi?.file != file) {
                MidiFilePlayer.load(file)
            }
            chat("Playing: ${file.name}")
            MidiFilePlayer.play()
            PlaybackCoordinator.onPlaybackStarted(file)
        }
    }

    private fun selectFile(file: File, stopPlayback: Boolean = false) {
        SongAudioPreviewer.stopPreview()
        selectedFile = file
        selectedIndex = midiFiles.indexOfFirst { it.name.equals(file.name, ignoreCase = true) }
        if (::songListWidget.isInitialized && songListWidget.getSelectedFile() != file) {
            songListWidget.setFiles(displayedFiles, selectedFile)
        }
        if (stopPlayback) {
            MidiFilePlayer.stop()
            NbsPlayer.stop()
        }
        readinessMessage = ""
        coverageMessage = ""
        var loadFailed = false

        if (file.extension.lowercase() in listOf("mid", "midi")) {
            loadedNbs = null
            try {
                // Use parse() so selecting/inspecting a file does NOT overwrite active playback state
                val midi = if (MidiFilePlayer.loadedMidi?.file == file) {
                    MidiFilePlayer.loadedMidi!!
                } else {
                    MidiFilePlayer.parse(file)
                }
                logger.info("selectFile: ${file.name} -- ${midi.events.size} events")
                chat("Loaded: ${file.name} (${midi.distinctNotes.size} distinct notes)")

                // Tier 6b (MIDI): pre-flight readiness check with full instrument list and
                // block-below material hints -- no .take(4) truncation (Tier 6a).
                // Already normalised by parse(): unpitched instruments collapsed to midiBase.
                val requirements = midi.noteUsageCounts.keys
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
                        val midi = if (!inst.isPitched) inst.midiBase else inst.midiBase + (note.key - 33)
                        NotePitch(midi, inst)
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

        organScanMessage = if (loadFailed) {
            "Failed to load ${file.name}"
        } else {
            "Loaded: ${file.name}. Run Scan -> Center -> Tune -> Play."
        }
        updateQueueDisplay()
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
                activeTuner = null
                tuneStatusMsg = ""
                tuningError = null
                organScanMessage = "Tuning complete! Press Play."
                chat("Tuning complete!")
                logger.info("tick: tuning complete, pendingAutoPlay=$pendingAutoPlay")
                OrganScanner.scan()
                updateOrganInfo()

                // Tier 4: if startTuning() was called via selectiveRetuneAndPlay chain,
                // start playback now that tuning is verified.
                if (pendingAutoPlay) {
                    pendingAutoPlay = false
                    startPlayback()
                }
            } else if (t.isFailed) {
                val reason = t.failureReason ?: "Verification failed after retries"
                tuningError = reason
                tuner = null
                activeTuner = null
                tuneStatusMsg = ""
                pendingAutoPlay = false
                chatWarn("Tuning: $reason. Highlighted in world for manual tuning.")
                logger.warn("tick: tuning finished with manual tuning required: $reason")
            }
        }

        if (::playPauseBtn.isInitialized) {
            val isPlaying = (MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused) || (NbsPlayer.isPlaying && !NbsPlayer.isPaused)
            val isPaused = MidiFilePlayer.isPaused || NbsPlayer.isPaused
            val label = when {
                isPlaying -> "❚❚ Pause"
                isPaused  -> "▶ Resume"
                else      -> "▶ Play"
            }
            if (playPauseBtn.message.string != label) {
                playPauseBtn.message = Component.literal(label)
            }
        }

        tickScale()
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = minecraft.font

        // 1. Draw Jukebox console chassis, LCD, seek bar, and visualizer FIRST
        if (activeTab == 0 || activeTab == 1) {
            renderJukeboxConsole(graphics, font, mouseX, mouseY)
        }

        // 2. Render all active child widgets (buttons, transport controls, lists) ON TOP of backdrops
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)

        // 3. Render per-tab text overlays
        when (activeTab) {
            0 -> renderFilesTab(graphics, font)
            1 -> renderQueueTab(graphics, font)
            2 -> renderOrganTab(graphics, font)
            3 -> renderLogsTab(graphics, font)
        }
    }

    private fun renderLogsTab(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font) {
        val count = BlockBardLogBuffer.size
        graphics.text(font, "$count entries logged (click entry to copy single line, or use 'Copy All')", 220, 30, opaque(0x888888))
    }

    private fun renderFilesTab(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font) {
        if (displayedFiles.isEmpty()) {
            val emptyMsg = if (midiFiles.isEmpty()) "(no files found in config/blockbard/midis/)"
                           else "(no matches for filter/search)"
            graphics.text(font, emptyMsg, 14, 54, opaque(0xAAAAAA))
        }
    }

    private fun renderQueueTab(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font) {
        val total = queueUserCount + queueContextRemaining
        val emptyMsg = when {
            queue.repeatingCurrent -> "(Repeating the current track. Queue a song to play something else next.)"
            queue.wrapsAtEnd && queue.nowPlaying != null -> "(End of the playlist. Repeat ALL loops back to the start.)"
            else -> "(Queue is empty -- use Next or Last on tracks in the Library to queue them)"
        }
        if (upcomingRows.isEmpty()) {
            graphics.text(font, emptyMsg, 14, 54, opaque(0xAAAAAA))
        } else {
            val countStr = buildString {
                append(if (queueUserCount > 0) "$queueUserCount queued, $total up next" else "$total up next")
                if (queue.repeatingCurrent) append(" | repeating current")
                else if (queue.wrapsAtEnd) append(" | loops to start")
            }
            graphics.text(font, countStr, width - font.width(countStr) - 16, 30, opaque(0xAAAAAA))
        }
    }

    private fun renderJukeboxConsole(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font, mouseX: Int, mouseY: Int) {
        val jbX = 6
        val jbY = height - 108
        val jbW = width - 12
        val jbH = 104

        // 1. Jukebox Enclosure Chassis (warm dark oak / polished mahogany wood bevel)
        graphics.fill(jbX, jbY, jbX + jbW, jbY + jbH, opaque(0x181310))
        // Top & Left brass bevel highlight
        graphics.fill(jbX + 1, jbY + 1, jbX + jbW - 1, jbY + 2, opaque(0x8A6E46))
        graphics.fill(jbX + 1, jbY + 1, jbX + 2, jbY + jbH - 1, opaque(0x6E5637))
        // Bottom & Right shadow
        graphics.fill(jbX + 1, jbY + jbH - 2, jbX + jbW - 1, jbY + jbH - 1, opaque(0x0A0806))
        graphics.fill(jbX + jbW - 2, jbY + 1, jbX + jbW - 1, jbY + jbH - 1, opaque(0x0A0806))
        // Inner chassis
        graphics.fill(jbX + 2, jbY + 2, jbX + jbW - 2, jbY + jbH - 2, opaque(0x130F0D))

        // Corner decorative brass studs/rivets
        val studColor = opaque(0xD4AF37)
        graphics.fill(jbX + 4, jbY + 4, jbX + 6, jbY + 6, studColor)
        graphics.fill(jbX + jbW - 6, jbY + 4, jbX + jbW - 4, jbY + 6, studColor)
        graphics.fill(jbX + 4, jbY + jbH - 6, jbX + 6, jbY + jbH - 4, studColor)
        graphics.fill(jbX + jbW - 6, jbY + jbH - 6, jbX + jbW - 4, jbY + jbH - 4, studColor)

        // 2. LCD / Glass Marquee Display Window (jbY + 5, h = 21)
        val scrX = jbX + 8
        val scrY = jbY + 5
        val scrW = jbW - 16
        val scrH = 21
        graphics.fill(scrX, scrY, scrX + scrW, scrY + scrH, opaque(0x0C1510))
        // Screen border
        graphics.fill(scrX, scrY, scrX + scrW, scrY + 1, opaque(0x050A07))
        graphics.fill(scrX, scrY, scrX + 1, scrY + scrH, opaque(0x050A07))
        graphics.fill(scrX, scrY + scrH - 1, scrX + scrW, scrY + scrH, opaque(0x1B3024))
        graphics.fill(scrX + scrW - 1, scrY, scrX + scrW, scrY + scrH, opaque(0x1B3024))

        val isPlaying = (MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused) || (NbsPlayer.isPlaying && !NbsPlayer.isPaused)
        val isPaused = MidiFilePlayer.isPaused || NbsPlayer.isPaused
        val isPreview = SongAudioPreviewer.isPlaying
        val isTuning = tuner?.isActive == true

        // Spinning vinyl record animation
        val discFrames = listOf("◐", "◓", "◑", "◒")
        val discIdx = if (isPlaying) ((System.currentTimeMillis() / 200) % 4).toInt() else 0
        val discIcon = if (isPlaying) discFrames[discIdx] else "♫"
        val discColor = if (isPlaying) opaque(0xFFD700) else if (isPaused) opaque(0xFFAA44) else opaque(0x778877)
        graphics.text(font, discIcon, scrX + 5, scrY + 6, discColor)

        // Status badge
        val (statusText, statusBg) = when {
            isTuning            -> Pair("TUNING", 0xFFAA00)
            tuningError != null -> Pair("FAILED", 0xFF5555)
            isPlaying           -> Pair("PLAYING", 0x55FF77)
            isPaused            -> Pair("PAUSED", 0xFFFF55)
            isPreview           -> Pair("PREVIEW", 0x55FFFF)
            else                -> Pair("READY", 0x889988)
        }
        graphics.text(font, "[$statusText]", scrX + 16, scrY + 6, opaque(statusBg))

        // Track Name & Format
        val trackFile = selectedFile
        val trackTitle = trackFile?.nameWithoutExtension?.take(34) ?: "No Track Selected"
        graphics.text(font, trackTitle, scrX + 80, scrY + 6, opaque(0xFFFFFF))

        val isNbs = trackFile?.extension.equals("nbs", true)
        val fmtTag = if (trackFile == null) "" else if (isNbs) "[NBS]" else "[MID]"
        val fmtColor = if (isNbs) opaque(0xFFCC44) else opaque(0x55FFFF)
        graphics.text(font, fmtTag, scrX + 80 + font.width(trackTitle) + 6, scrY + 6, fmtColor)

        // Right side: Time & Tempo
        val (elapsedSecs, totalSecs) = getPlaybackTimes()
        val timeStr = "${formatTime(elapsedSecs)} / ${formatTime(totalSecs)}"
        val tempoStr = "${"%.1f".format(MidiFilePlayer.tempoMultiplier)}x"
        val rightInfo = "$tempoStr | $timeStr"
        val rightW = font.width(rightInfo)
        graphics.text(font, rightInfo, scrX + scrW - rightW - 6, scrY + 6, opaque(0x77FF99))

        // 3. Sub-Marquee (Up Next / Queue Preview) (jbY + 28, h = 11)
        val subY = jbY + 28
        val nextTitle = when {
            queue.repeatingCurrent && trackFile != null -> "Repeat Current Track"
            nextUpTitle != null -> nextUpTitle!!.take(28)
            queue.wrapsAtEnd && queue.nowPlaying != null -> "Start of playlist"
            else -> "End of queue"
        }
        val modeStr = loopModeLabel()
        val nextLine = "UP NEXT: $nextTitle  [$modeStr] (${queueUserCount + queueContextRemaining} queued)"
        graphics.text(font, nextLine, scrX + 4, subY, opaque(0xD4AF37))

        // 4. Jukebox Seek Bar (jbY + 41, h = 5)
        val barX = scrX
        val barY = jbY + 41
        val barW = scrW
        val fraction = if (totalSecs > 0.0) (elapsedSecs / totalSecs).coerceIn(0.0, 1.0) else 0.0
        val progressW = (barW * fraction).toInt()

        // Recessed groove
        graphics.fill(barX, barY, barX + barW, barY + 5, opaque(0x0A0D0F))
        graphics.fill(barX, barY, barX + barW, barY + 1, opaque(0x050708))
        // Progress fill
        if (progressW > 0) {
            graphics.fill(barX, barY + 1, barX + progressW, barY + 4, opaque(0x44DD66))
            graphics.fill(barX, barY + 1, barX + progressW, barY + 2, opaque(0x77FFAA))
        }
        // Scrubber thumb
        val thumbX = barX + progressW
        val isHoveredSeek = mouseX in barX..(barX + barW) && mouseY in (barY - 2)..(barY + 7)
        val thumbW = if (isHoveredSeek || isScrubbing) 6 else 4
        val thumbH = if (isHoveredSeek || isScrubbing) 9 else 7
        val thumbColor = if (isHoveredSeek || isScrubbing) opaque(0xFFFFAA) else opaque(0xE0C068)
        graphics.fill(thumbX - thumbW / 2, barY + 2 - thumbH / 2, thumbX + thumbW / 2, barY + 2 + thumbH / 2, thumbColor)

        // 5. Backlit Equalizer / Noteblock Visualizer Strip (jbY + 49, h = 16)
        val visX = scrX
        val visY = jbY + 49
        val visW = scrW
        val visH = 16
        val keyCount = 25
        val colW = visW.toFloat() / keyCount

        // Equalizer casing
        graphics.fill(visX, visY, visX + visW, visY + visH, opaque(0x0B0E12))
        graphics.fill(visX, visY, visX + visW, visY + 1, opaque(0x040608))

        for (i in 0 until keyCount) {
            val kx1 = (visX + i * colW).toInt()
            val kx2 = (visX + (i + 1) * colW - 1).toInt()
            val isAccidental = (i % 12) in listOf(0, 2, 4, 7, 9)
            val restingColor = if (isAccidental) opaque(0x1A1C22) else opaque(0x282B33)

            val pitchState = VisualizerState.getPitchState(i)
            if (pitchState != null) {
                val (intensity, color) = pitchState
                val activeH = (visH * intensity).toInt().coerceIn(2, visH)
                val fillY = visY + visH - activeH

                // Rising meter gradient
                graphics.fill(kx1, fillY, kx2, visY + visH, color or 0xFF000000.toInt())
                // Bright peak highlight
                graphics.fill(kx1, fillY, kx2, fillY + 1, opaque(0xFFFFFF))
            } else {
                graphics.fill(kx1, visY + 1, kx2, visY + visH, restingColor)
                // Resting baseline dot
                if (isPlaying) {
                    val restingPulse = ((Math.sin(System.currentTimeMillis() / 150.0 + i * 0.4) + 1.0) * 1.5).toInt()
                    graphics.fill(kx1, visY + visH - restingPulse - 1, kx2, visY + visH, opaque(0x354045))
                }
            }
        }
    }

    private fun renderOrganTab(graphics: GuiGraphicsExtractor, font: net.minecraft.client.gui.Font) {
        val playable = NoteBlockRegistry.allPlayable()
        val reachable = PlayerController.organMap?.reachableCount() ?: playable.size
        val instBlocks = playable.filter { it.instrument == selectedInstrument }
        val coveredNotes = instBlocks.map { it.noteIndex }.distinct().size

        // Diagnostic summary line (above keyboard)
        val header = "Organ: ${playable.size} playable ($reachable reachable) | ${selectedInstrument.name}: ${instBlocks.size} blocks ($coveredNotes/25 pitches)"
        graphics.text(font, header, 10, 48, opaque(0x88AAFF))

        // Info below keyboard
        val vk = virtualKeyboard
        val bottomY = if (vk != null) vk.y + vk.height + 8 else height - 42
        graphics.text(font, "Play via mouse click, MIDI device, or PC keyboard (QWERTY layout). Greyed out notes are missing.", 10, bottomY, opaque(0xAAAAAA))

        val t = tuner
        if (t != null && bottomY + 12 < height - 24) {
            val stateLabel = when (t.state) {
                TunerState.TUNING    -> "Tuning..."
                TunerState.VERIFYING -> "Verifying..."
                else                 -> ""
            }
            graphics.text(font, "$stateLabel ${t.confirmedBlocks}/${t.total} $tuneStatusMsg", 10, bottomY + 12, opaque(0x44FF88))
        }

        // Bottom status
        val status = when {
            MidiFilePlayer.isActive() && !MidiFilePlayer.isPaused -> "PLAYING ${selectedFile?.name ?: ""}"
            NbsPlayer.isPlaying && !NbsPlayer.isPaused            -> "PLAYING ${selectedFile?.name ?: ""}"
            MidiFilePlayer.isPaused || NbsPlayer.isPaused         -> "PAUSED"
            tuner?.isActive == true                               -> "TUNING"
            else                                                  -> "IDLE"
        }
        graphics.text(font, status, 10, height - 20, opaque(0xAAFFAA))
        val ping = PlayerController.currentPingMs()
        if (ping > 0) {
            graphics.text(font, "Ping: ${ping}ms", width - 140, height - 20, opaque(0xAAAAAA))
        }
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
        val jbY = height - 108
        val barY = jbY + 41
        val scrX = 6 + 8
        val scrW = width - 12 - 16
        if ((activeTab == 0 || activeTab == 1) && event.button() == 0) {
            if (event.y() >= barY - 4 && event.y() <= barY + 9 && event.x() >= scrX && event.x() <= scrX + scrW) {
                isScrubbing = true
                handleScrub(event.x())
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if ((activeTab == 0 || activeTab == 1) && isScrubbing) {
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
        val scrX = 6 + 8
        val scrW = (width - 12 - 16).coerceAtLeast(1)
        val fraction = ((mouseX - scrX) / scrW.toDouble()).coerceIn(0.0, 1.0)
        seekToFraction(fraction)
    }

    private fun seekToFraction(fraction: Double) {
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
        updateQueueDisplay()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.key()
        val isEditing = (activeTab == 0 && focused is EditBox && (focused as EditBox).isFocused && (focused as EditBox).visible)
        if (!isEditing) {
            val noteIdx = KeyboardInputHandler.getNoteIndex(keyCode)
            if (noteIdx != null) {
                virtualKeyboard?.flashKey(noteIdx)
                KeyboardInputHandler.tryDispatch(keyCode, selectedInstrument)
                return true
            }
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        logger.info("MainScreen: closing")
        ConfigManager.save()
        minecraft.gui.setScreen(parent)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // High-contrast dark backdrop so chat and world do not conflict with the menu HUD
        graphics.fill(0, 0, width, height, 0xD0101014.toInt())
    }

    override fun extractBlurredBackground(graphics: GuiGraphicsExtractor) {
        super.extractBlurredBackground(graphics)
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * Copies the newest log entries to the clipboard and writes the full buffer to a file.
     * The clipboard text is size-capped: SDL_SetClipboardText allocates the whole UTF-8 string on
     * LWJGL's fixed-size thread-local stack, so an unbounded string throws "Out of stack space".
     */
    private fun copyLogsToClipboard() {
        val fullPath: Path? = try {
            val p = FabricLoader.getInstance().gameDir.resolve("logs").resolve("blockbard-copy.txt")
            java.nio.file.Files.createDirectories(p.parent)
            java.nio.file.Files.writeString(p, BlockBardLogBuffer.getAllText())
            p
        } catch (t: Throwable) {
            logger.warn("Could not write full log file", t)
            null
        }
        try {
            val text = BlockBardLogBuffer.getTailText(CLIPBOARD_MAX_BYTES)
            minecraft.keyboardHandler.clipboard = text
            val bytes = text.toByteArray(Charsets.UTF_8).size
            chat(
                "Copied newest log entries to clipboard ($bytes bytes of ${BlockBardLogBuffer.size} entries)" +
                    (fullPath?.let { "; full log saved to $it" } ?: "")
            )
        } catch (t: Throwable) {
            logger.warn("Copy to clipboard failed", t)
            chat("Copy to clipboard failed" + (fullPath?.let { "; full log saved to $it" } ?: ""))
        }
    }

    companion object {
        /** Clipboard budget in UTF-8 bytes; well under LWJGL's default 64 KB thread stack. */
        private const val CLIPBOARD_MAX_BYTES = 32_000

        /** Most rows read from the queue for display; counts are taken from the full queue. */
        private const val QUEUE_DISPLAY_LIMIT = 500

        var activeTuner: NoteBlockTuner? = null
        var currentScreen: MainScreen? = null

        fun onClientTick(mc: net.minecraft.client.Minecraft) {
            val t = activeTuner ?: return
            // If MainScreen is not currently open, continue ticking the tuner
            if (mc.gui.screen() !is MainScreen) {
                t.onTick()
                if (t.isDone) {
                    activeTuner = null
                    mc.player?.sendSystemMessage(Component.literal("§b[BlockBard] §fTuning complete!"))
                    OrganScanner.scan()
                    currentScreen?.let { screen ->
                        if (screen.pendingAutoPlay) {
                            screen.pendingAutoPlay = false
                            screen.startPlayback()
                        }
                    }
                } else if (t.isFailed) {
                    activeTuner = null
                    currentScreen?.let {
                        it.pendingAutoPlay = false
                    }
                    mc.player?.sendSystemMessage(Component.literal("§b[BlockBard] §cTuning failed -- check logs for details"))
                }
            }
        }
    }
}
