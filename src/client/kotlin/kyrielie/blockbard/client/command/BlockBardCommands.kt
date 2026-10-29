package kyrielie.blockbard.client.command

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.client.config.LoopMode
import kyrielie.blockbard.client.gui.MaterialsShoppingOverlay
import kyrielie.blockbard.client.midi.InstrumentRemapper
import kyrielie.blockbard.client.midi.MidiChannelResolver
import kyrielie.blockbard.client.organ.OrganScanner
import kyrielie.blockbard.client.playback.MidiFilePlayer
import kyrielie.blockbard.client.playback.NbsFileLoader
import kyrielie.blockbard.client.playback.NbsPlayer
import kyrielie.blockbard.client.playback.SongAudioPreviewer
import kyrielie.blockbard.client.playback.nbsInstrumentToBlock
import kyrielie.blockbard.client.player.CenterResult
import kyrielie.blockbard.client.player.PlayerController
import kyrielie.blockbard.client.player.SoundVerifier
import kyrielie.blockbard.client.util.SongDownloader
import kyrielie.blockbard.organ.ArpeggioScheduler
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
                MidiFilePlayer.stop()
                NbsPlayer.stop()
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
                val res = PlayerController.centerOnOrgan()
                val msg = when (res) {
                    is CenterResult.Centered -> "Centered on organ. §a${res.reachableCount}§f/${res.totalFound} noteblocks reachable."
                    CenterResult.NoBlocks   -> "§cNo playable noteblocks found. Run /bb scan first."
                    CenterResult.NoPlayer   -> "§cPlayer unavailable."
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

            // /blockbard loop <mode>
            val loopModes = LoopMode.entries.map { it.name.lowercase() }
            root.then(ClientCommands.literal("loop")
                .then(ClientCommands.argument("mode", StringArgumentType.word())
                    .suggests { _, builder -> SharedSuggestionProvider.suggest(loopModes, builder) }
                    .executes { ctx ->
                        val modeStr = StringArgumentType.getString(ctx, "mode").uppercase()
                        try {
                            val mode = LoopMode.valueOf(modeStr)
                            ConfigManager.config.loopMode = mode.name
                            ConfigManager.save()
                            ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fLoop mode set to §a${mode.name}§f."))
                        } catch (_: IllegalArgumentException) {
                            ctx.source.sendError(Component.literal("§b[BlockBard] §cUnknown loop mode. Valid: ${loopModes.joinToString()}"))
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
                            } catch (e: Exception) {
                                ctx.source.sendError(Component.literal("§b[BlockBard] §cFailed to load NBS: ${e.message}"))
                                return@executes 0
                            }
                        } else {
                            try {
                                val midi = MidiFilePlayer.load(file)
                                val reqs = MidiChannelResolver.resolveRequirements(file)
                                MaterialsShoppingOverlay.updateRequirements(reqs)
                                ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fPlaying MIDI: §a${file.name}"))
                                MidiFilePlayer.play()
                            } catch (e: Exception) {
                                ctx.source.sendError(Component.literal("§b[BlockBard] §cFailed to load MIDI: ${e.message}"))
                                return@executes 0
                            }
                        }
                        1
                    }
                )
            )

            // /blockbard download <url> [filename]
            root.then(ClientCommands.literal("download")
                .then(ClientCommands.argument("url", StringArgumentType.string())
                    .executes { ctx ->
                        val url = StringArgumentType.getString(ctx, "url")
                        ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fDownloading §7$url§f..."))
                        SongDownloader.download(url)
                        1
                    }
                    .then(ClientCommands.argument("filename", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val url = StringArgumentType.getString(ctx, "url")
                            val filename = StringArgumentType.getString(ctx, "filename")
                            ctx.source.sendFeedback(Component.literal("§b[BlockBard] §fDownloading §7$url §fas §a$filename§f..."))
                            SongDownloader.download(url, filename)
                            1
                        }
                    )
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

            val registeredRoot = dispatcher.register(root)
            dispatcher.register(ClientCommands.literal("bb").redirect(registeredRoot))
            logger.info("Registered /blockbard and /bb client commands")
        }
    }
}
