package kyrielie.blockbard.client.command

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.gui.MaterialsShoppingOverlay
import kyrielie.blockbard.client.midi.InstrumentRemapper
import kyrielie.blockbard.client.organ.OrganScanner
import kyrielie.blockbard.client.playback.ClientPlayQueue
import kyrielie.blockbard.client.playback.PlaybackCoordinator
import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.client.playback.NbsFileLoader
import kyrielie.blockbard.client.playback.NbsPlayer
import kyrielie.blockbard.client.playback.SongAudioPreviewer
import kyrielie.blockbard.client.playback.nbsInstrumentToBlock
import kyrielie.blockbard.client.player.CenterResult
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.player.SoundVerifier
import kyrielie.blockbard.organ.ArpeggioScheduler
import kyrielie.blockbard.playback.LoopSettings
import kyrielie.blockbard.playback.RepeatMode
import kyrielie.blockbard.organ.NotePitch
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory
import java.io.File

object BlockBardCommands {

    private val logger = LoggerFactory.getLogger("BlockBard/Commands")

    private val midisDir: File by lazy {
        FabricLoader.getInstance().configDir.resolve("blockbard/midis").toFile().also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private fun getAvailableSongFiles(): List<File> =
        midisDir.listFiles()
            ?.filter { it.isFile && (it.extension.equals("mid", true) || it.extension.equals("midi", true) || it.extension.equals("nbs", true)) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun findSongFile(name: String): File? {
        val files = getAvailableSongFiles()
        // Exact name match
        files.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        // Match without extension
        files.firstOrNull { it.nameWithoutExtension.equals(name, ignoreCase = true) }?.let { return it }
        // Contains match
        return files.firstOrNull { it.name.contains(name, ignoreCase = true) }
    }

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = ClientCommands.literal("blockbard")

            // /blockbard stop
            root.then(ClientCommands.literal("stop").executes { ctx ->
                // Also cancels any tuner run and a pending auto-advance, and clears now-playing.
                PlaybackCoordinator.stop()
                SongAudioPreviewer.stopPreview()
                ArpeggioScheduler.clear()
                ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fStopped all playback, tuning, and previews."))
                1
            })

            // /blockbard scan
            root.then(ClientCommands.literal("scan").executes { ctx ->
                OrganScanner.scan()
                val count = kyrielie.blockbard.organ.NoteBlockRegistry.allPlayable().size
                ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fScan complete: §a$count §fplayable noteblocks found."))
                1
            })

            // /blockbard center
            root.then(ClientCommands.literal("center").executes { ctx ->
                val msg = if (kyrielie.blockbard.client.playback.PlaybackCoordinator.isTuning()) {
                    "§cCannot re-center while tuning."
                } else {
                    when (val res = PlayerController.centerOnOrgan()) {
                        is CenterResult.Centered -> "Centered on organ. §a${res.reachableCount}§f/${res.totalFound} noteblocks reachable."
                        CenterResult.NoBlocks   -> "§cNo playable noteblocks found. Run /bb scan first."
                        CenterResult.NoPlayer   -> "§cPlayer unavailable."
                    }
                }
                ctx.source.sendFeedback(Component.literal("§b[BlockBard] §f$msg"))
                1
            })

            // /blockbard materials
            root.then(ClientCommands.literal("materials").executes { ctx ->
                MaterialsShoppingOverlay.isVisible = !MaterialsShoppingOverlay.isVisible
                val state = if (MaterialsShoppingOverlay.isVisible) "§aenabled" else "§cdisabled"
                ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fMaterials shopping overlay $state§f."))
                1
            })

            // /blockbard tempo <multiplier>
            root.then(ClientCommands.literal("tempo")
                .then(ClientCommands.argument("multiplier", FloatArgumentType.floatArg(0.1f, 5.0f))
                    .executes { ctx ->
                        val multiplier = FloatArgumentType.getFloat(ctx, "multiplier")
                        MidiFilePlayer.tempoMultiplier = multiplier
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fPlayback tempo set to §a${"%.1f".format(multiplier)}x§f."))
                        1
                    }
                )
            )

            // /blockbard repeat <off|all|one>
            val repeatNames = RepeatMode.entries.map { it.name.lowercase() }
            root.then(ClientCommands.literal("repeat")
                .then(ClientCommands.argument("mode", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(repeatNames, builder) }
                    .executes { ctx ->
                        val mode = LoopSettings.parseRepeat(StringArgumentType.getString(ctx, "mode"))
                        if (mode == null) {
                            ctx.source.sendError(Component.literal("§b[BlockBard] §cUnknown repeat mode. Valid: ${repeatNames.joinToString()}"))
                        } else {
                            ConfigManager.config.repeatMode = mode.name
                            ConfigManager.save()
                            ClientPlayQueue.applyConfig(ConfigManager.config)
                            ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fRepeat set to §a${mode.name}§f."))
                        }
                        1
                    }
                )
            )

            // /blockbard shuffle <on|off>
            root.then(ClientCommands.literal("shuffle")
                .then(ClientCommands.argument("state", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("on", "off"), builder) }
                    .executes { ctx ->
                        val on = LoopSettings.parseOnOff(StringArgumentType.getString(ctx, "state"))
                        if (on == null) {
                            ctx.source.sendError(Component.literal("§b[BlockBard] §cUse on or off."))
                        } else {
                            ConfigManager.config.shuffle = on
                            ConfigManager.save()
                            ClientPlayQueue.applyConfig(ConfigManager.config)
                            ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fShuffle turned §a${if (on) "ON" else "OFF"}§f."))
                        }
                        1
                    }
                )
            )

            // /blockbard preview <song>
            root.then(ClientCommands.literal("preview")
                .then(ClientCommands.argument("song", StringArgumentType.greedyString())
                    .suggests { _, builder ->
                        SharedSuggestionProvider.suggest(getAvailableSongFiles().map { it.name }, builder)
                    }
                    .executes { ctx ->
                        val songName = StringArgumentType.getString(ctx, "song")
                        val file = findSongFile(songName)
                        if (file == null) {
                            ctx.source.sendError(Component.literal("§b[BlockBard] §cSong file not found: $songName"))
                            return@executes 0
                        }

                        MidiFilePlayer.stop()
                        NbsPlayer.stop()
                        SongAudioPreviewer.startPreview(file, MidiFilePlayer.tempoMultiplier)
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fAuditioning §a${file.name} §flocally..."))
                        1
                    }
                )
            )

            // /blockbard play <song>
            root.then(ClientCommands.literal("play")
                .then(ClientCommands.argument("song", StringArgumentType.greedyString())
                    .suggests { _, builder ->
                        SharedSuggestionProvider.suggest(getAvailableSongFiles().map { it.name }, builder)
                    }
                    .executes { ctx ->
                        val songName = StringArgumentType.getString(ctx, "song")
                        val file = findSongFile(songName)
                        if (file == null) {
                            ctx.source.sendError(Component.literal("§b[BlockBard] §cSong file not found: $songName"))
                            return@executes 0
                        }

                        // Refuse while a tuner is running, then register the song with the play queue.
                        PlaybackCoordinator.beginExternalPlay(file)?.let { refusal ->
                            ctx.source.sendError(Component.literal("§b[BlockBard] §c$refusal"))
                            return@executes 0
                        }

                        SongAudioPreviewer.stopPreview()
                        SoundVerifier.reset()

                        val ext = file.extension.lowercase()
                        if (ext == "nbs") {
                            try {
                                val nbs = NbsFileLoader.load(file)
                                val reqs = nbs.notes.mapNotNull { n ->
                                    val inst = nbsInstrumentToBlock(n.instrument)?.let { InstrumentRemapper.remap(it) } ?: return@mapNotNull null
                                    NotePitch(n.key + 21, inst)
                                }.toSet()
                                MaterialsShoppingOverlay.updateRequirements(reqs)
                                ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fPlaying NBS: §a${file.name}"))
                                NbsPlayer.play(nbs)
                                PlaybackCoordinator.onPlaybackStarted(file)
                            } catch (e: Exception) {
                                ctx.source.sendError(Component.literal("§b[BlockBard] §cFailed to load NBS: ${e.message}"))
                                return@executes 0
                            }
                        } else {
                            try {
                                val midi = MidiFilePlayer.load(file)
                                val reqs = midi.noteUsageCounts.keys
                                MaterialsShoppingOverlay.updateRequirements(reqs)
                                ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fPlaying MIDI: §a${file.name}"))
                                MidiFilePlayer.play()
                                PlaybackCoordinator.onPlaybackStarted(file)
                            } catch (e: Exception) {
                                ctx.source.sendError(Component.literal("§b[BlockBard] §cFailed to load MIDI: ${e.message}"))
                                return@executes 0
                            }
                        }
                        1
                    }
                )
            )

            // /blockbard remap ...
            val instruments = NoteBlockInstrument.entries.map { it.name.lowercase() }
            val targets = instruments + listOf("mute", "reset")

            val remapCmd = ClientCommands.literal("remap")
                .then(ClientCommands.literal("list").executes { ctx ->
                    val active = InstrumentRemapper.activeRemaps()
                    if (active.isEmpty()) {
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fNo active instrument remaps."))
                    } else {
                        val formatted = active.entries.joinToString(", ") { "${it.key} -> ${it.value}" }
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fActive remaps: §a$formatted"))
                    }
                    1
                })
                .then(ClientCommands.literal("clear").executes { ctx ->
                    InstrumentRemapper.clearAll()
                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fCleared all instrument remaps."))
                    1
                })
                .then(ClientCommands.argument("source", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(instruments, builder) }
                    .then(ClientCommands.argument("target", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(targets, builder) }
                        .executes { ctx ->
                            val srcStr = StringArgumentType.getString(ctx, "source").uppercase()
                            val tgtStr = StringArgumentType.getString(ctx, "target").lowercase()

                            val srcInst = try {
                                NoteBlockInstrument.valueOf(srcStr)
                            } catch (_: IllegalArgumentException) {
                                ctx.source.sendError(Component.literal("§b[BlockBard] §cUnknown source instrument: $srcStr"))
                                return@executes 0
                            }

                            when (tgtStr) {
                                "reset" -> {
                                    InstrumentRemapper.clearRemap(srcInst)
                                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fReset remap for §a${srcInst.name}§f."))
                                }
                                "mute" -> {
                                    InstrumentRemapper.setRemap(srcInst, null)
                                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fMuted instrument §a${srcInst.name}§f."))
                                }
                                else -> {
                                    val tgtInst = try {
                                        NoteBlockInstrument.valueOf(tgtStr.uppercase())
                                    } catch (_: IllegalArgumentException) {
                                        ctx.source.sendError(Component.literal("§b[BlockBard] §cUnknown target instrument: $tgtStr"))
                                        return@executes 0
                                    }
                                    InstrumentRemapper.setRemap(srcInst, tgtInst)
                                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fRemapped §a${srcInst.name} §f-> §6${tgtInst.name}§f."))
                                }
                            }
                            1
                        }
                    )
                )
            root.then(remapCmd)

            // /blockbard highlights
            root.then(ClientCommands.literal("highlights")
                .executes { ctx ->
                    val active = kyrielie.blockbard.client.render.ManualTuningHighlighter.all()
                    if (active.isEmpty()) {
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fNo noteblocks currently require manual tuning."))
                    } else {
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §e${active.size} block(s) highlighted for manual tuning:"))
                        active.forEach { (pos, info) ->
                            val targetMidi = kyrielie.blockbard.util.noteIndexToMidi(info.instrument, info.targetNote)
                            val name = kyrielie.blockbard.util.midiNoteToName(targetMidi)
                            ctx.source.sendFeedback(Component.literal("  §7- §f${pos.x}, ${pos.y}, ${pos.z} §6(${info.instrument.name}) §f-> §e$name §c[${info.failState.tag}]"))
                        }
                    }
                    1
                }
            )

            // /blockbard clearhighlights
            root.then(ClientCommands.literal("clearhighlights")
                .executes { ctx ->
                    val count = kyrielie.blockbard.client.render.ManualTuningHighlighter.count()
                    kyrielie.blockbard.client.render.ManualTuningHighlighter.clear()
                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fCleared $count manual tuning highlight(s)."))
                    1
                }
            )

            // /blockbard overlay
            root.then(ClientCommands.literal("overlay")
                .executes { ctx ->
                    val on = kyrielie.blockbard.client.render.OrganOverlayRenderer.toggle()
                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fOrgan hologram ${if (on) "§aenabled" else "§cdisabled"}"))
                    1
                }
            )

            // /blockbard blueprint
            root.then(ClientCommands.literal("blueprint")
                .executes { ctx ->
                    val on = kyrielie.blockbard.client.render.OrganBlueprintBuilder.toggle()
                    val count = kyrielie.blockbard.client.render.OrganBlueprintBuilder.totalPlanned()
                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fStage blueprint guide ${if (on) "§aenabled ($count blocks)" else "§cdisabled"}"))
                    1
                }
                .then(ClientCommands.literal("clear").executes { ctx ->
                    kyrielie.blockbard.client.render.OrganBlueprintBuilder.clear()
                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fCleared stage blueprint guide."))
                    1
                })
            )

            // /blockbard midi list | status | connect <name> | disconnect
            val midiNames = { kyrielie.blockbard.client.input.MidiInputHandler.listInputDevices() }
            root.then(ClientCommands.literal("midi")
                .then(ClientCommands.literal("list").executes { ctx ->
                    val ports = kyrielie.blockbard.client.input.MidiInputHandler.listPorts()
                    if (ports.isEmpty()) {
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §eJava sees no MIDI devices at all."))
                    } else {
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fMIDI devices visible to Java (§aIN§f = can send notes):"))
                        ports.forEach { p ->
                            val tag = if (p.canSend) "§aIN" else "§7--"
                            ctx.source.sendFeedback(Component.literal("  $tag §f${p.name} §7(${p.description})"))
                        }
                    }
                    1
                })
                .then(ClientCommands.literal("status").executes { ctx ->
                    val h = kyrielie.blockbard.client.input.MidiInputHandler
                    val saved = ConfigManager.config.midiDeviceName ?: "(none)"
                    if (h.isConnected) {
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fConnected to §a${h.connectedDeviceName}§f. Messages: ${h.messageCount}, note-ons: ${h.noteOnCount}, last note: ${if (h.lastNote < 0) "-" else h.lastNote}"))
                    } else {
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §cNot connected. §fSaved device: $saved. Use /bb midi list."))
                    }
                    1
                })
                .then(ClientCommands.literal("connect")
                    .then(ClientCommands.argument("device", StringArgumentType.greedyString())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(midiNames(), builder) }
                        .executes { ctx ->
                            val query = StringArgumentType.getString(ctx, "device")
                            when (val r = kyrielie.blockbard.client.input.MidiInputHandler.connect(query)) {
                                is kyrielie.blockbard.client.input.MidiConnectResult.Connected -> {
                                    ConfigManager.config.midiDeviceName = r.name
                                    ConfigManager.save()
                                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fConnected to §a${r.name}§f and saved it. Play a key, then /bb midi status."))
                                    1
                                }
                                is kyrielie.blockbard.client.input.MidiConnectResult.NotFound -> {
                                    val list = if (r.available.isEmpty()) "none" else r.available.joinToString()
                                    ctx.source.sendError(Component.literal("§b[BlockBard] §cNo input device matches '$query'. Inputs: $list"))
                                    0
                                }
                                is kyrielie.blockbard.client.input.MidiConnectResult.Ambiguous -> {
                                    ctx.source.sendError(Component.literal("§b[BlockBard] §c'$query' matches several: ${r.candidates.joinToString()}"))
                                    0
                                }
                                is kyrielie.blockbard.client.input.MidiConnectResult.Failed -> {
                                    ctx.source.sendError(Component.literal("§b[BlockBard] §cCould not open ${r.name}: ${r.reason}"))
                                    0
                                }
                            }
                        }
                    )
                )
                .then(ClientCommands.literal("disconnect").executes { ctx ->
                    kyrielie.blockbard.client.input.MidiInputHandler.disconnect()
                    ConfigManager.config.midiDeviceName = null
                    ConfigManager.save()
                    ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fMIDI input disconnected and cleared."))
                    1
                })
            )

            val registeredRoot = dispatcher.register(root)
            dispatcher.register(ClientCommands.literal("bb").redirect(registeredRoot))
            logger.info("Registered /blockbard and /bb client commands")
        }
    }
}
