package kyrielie.blockbard.client.config

import kyrielie.blockbard.organ.ShiftMode
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

enum class LoopMode { NONE, LOOP_ONE, LOOP_ALL, SHUFFLE_ALL }

data class BlockBardConfig(
    var scanRadius: Int = 5,
    var sphericalScan: Boolean = true,
    var shiftMode: String = ShiftMode.BEST_EFFORT.name,
    var maxOctaveShift: Int = 1,
    var reportShiftsInHud: Boolean = true,
    var hudEnabled: Boolean = false,
    var arpeggioStaleTimeoutMs: Long = 200L,
    var defaultTempoMultiplier: Float = 1.0f,
    var autoRescanIntervalSeconds: Int = 3,
    var maxTuningClicksPerTick: Int = 1,
    var lastPlayedTrack: String? = null,
    var shuffleHistory: MutableList<String> = mutableListOf(),
    var defaultOctave: Int = 4,
    var midiDeviceName: String? = null,
    var keyMappings: List<Int> = listOf(54, 55, 56, 57, 58, 59, 60, 61, 62),
    // Anticheat-compatibility tunables
    var smoothCameraRotation: Boolean = false,
    var fastTuning: Boolean = true,
    var maxRotationDegreesPerTick: Float = 35f,
    var rotationConvergenceThresholdDegrees: Float = 2f,
    var rotationInProgressTimeoutMs: Long = 1500L,
    // Tier 1: debug logging toggle
    // When false (default), hot-path log calls route to logger.debug() and are
    // suppressed by the default Minecraft log config. When true they route to
    // logger.info() and appear in the log file and console. Does not suppress
    // important events (scan summaries, tuning start/done, file load, errors).
    var debugLogging: Boolean = false,
    // Tier 2: multi-note dispatch
    // Maximum notes dispatched per game tick. Default is 8. Can be set higher (up to 64)
    // for high-density chords and complex multi-track songs.
    var maxNotesPerTick: Int = 8,
    // Tier 4: loop / auto-advance mode
    // NONE        = stop after each song (current behavior)
    // LOOP_ONE    = repeat the same song indefinitely (no retune needed)
    // LOOP_ALL    = advance through all songs in sorted order, retuning between tracks
    // SHUFFLE_ALL = advance randomly, avoiding recent repeats (see shuffleHistory),
    //               retuning between tracks
    var loopMode: String = LoopMode.NONE.name,
    // Instrument remapping: source instrument name -> target instrument name (or null/"MUTE" to silence)
    var instrumentRemap: MutableMap<String, String?> = mutableMapOf(),
    // Favorites & filter state
    var favorites: MutableList<String> = mutableListOf(),
    var activeFilter: String = "ALL", // ALL, FAVORITES, MIDI, NBS
    // Dynamic on-the-fly retuning
    var dynamicRetuning: Boolean = false,
    var dynamicRetuningMaxClicks: Int = 2,
    // Optional player movement when centering
    var centerMovesPlayer: Boolean = false,
    // Send feedback to chat (false = only in debug buffer & logs)
    var enableChatFeedback: Boolean = false
) {
    fun isFavorite(fileName: String): Boolean = favorites.contains(fileName)

    fun toggleFavorite(fileName: String): Boolean {
        return if (favorites.contains(fileName)) {
            favorites.remove(fileName)
            false
        } else {
            favorites.add(fileName)
            true
        }
    }
    fun shiftModeEnum(): ShiftMode = try {
        ShiftMode.valueOf(shiftMode)
    } catch (_: IllegalArgumentException) {
        ShiftMode.BEST_EFFORT
    }

    fun loopModeEnum(): LoopMode = try {
        LoopMode.valueOf(loopMode)
    } catch (_: IllegalArgumentException) {
        LoopMode.NONE
    }
}

object ConfigManager {
    private val logger = LoggerFactory.getLogger("BlockBard/Config")
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(BlockBardConfig::class.java, InstanceCreator { BlockBardConfig() })
        .setPrettyPrinting()
        .create()
    private val configFile: File by lazy {
        FabricLoader.getInstance().configDir.resolve("blockbard/config.json").toFile()
    }

    var config: BlockBardConfig = BlockBardConfig()
        private set

    fun load() {
        configFile.parentFile?.mkdirs()
        if (!configFile.exists()) {
            save()
            return
        }
        try {
            config = gson.fromJson(configFile.readText(), BlockBardConfig::class.java)
                ?: BlockBardConfig()
            // Coalesce any zero/null that would break functionality back to a safe value
            if (config.scanRadius <= 0) config.scanRadius = 5
            if (config.arpeggioStaleTimeoutMs <= 0L) config.arpeggioStaleTimeoutMs = 200L
            if (config.maxRotationDegreesPerTick <= 0f) config.maxRotationDegreesPerTick = 35f
            if (config.rotationConvergenceThresholdDegrees <= 0f) config.rotationConvergenceThresholdDegrees = 2f
            if (config.rotationInProgressTimeoutMs <= 0L) config.rotationInProgressTimeoutMs = 1500L
            if (config.maxNotesPerTick <= 0) config.maxNotesPerTick = 8
            if (config.maxNotesPerTick > 64) config.maxNotesPerTick = 64
            @Suppress("SENSELESS_COMPARISON")
            if ((config.shuffleHistory as MutableList<String>?) == null) config.shuffleHistory = mutableListOf()
            @Suppress("SENSELESS_COMPARISON")
            if ((config.instrumentRemap as MutableMap<String, String?>?) == null) config.instrumentRemap = mutableMapOf()
        } catch (e: Exception) {
            logger.warn("Config parse failed, resetting to defaults: ${e.message}", e)
            config = BlockBardConfig()
            save()
        }
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        configFile.writeText(gson.toJson(config))
    }
}
