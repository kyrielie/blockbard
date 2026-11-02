package kyrielie.blockbard.client.config

import kyrielie.blockbard.organ.ShiftMode
import kyrielie.blockbard.playback.LoopSettings
import kyrielie.blockbard.playback.RepeatMode
import kyrielie.blockbard.util.positiveFiniteOr
import kyrielie.blockbard.util.positiveOr
import kyrielie.blockbard.util.sanitizeMaxNotesPerTick
import kyrielie.blockbard.util.sanitizeTempoMultiplier
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File


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
    var reachThrough: Boolean = false,
    var smoothCameraRotation: Boolean = false,
    var rotateDuringTuning: Boolean = true,
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
    // Maximum notes dispatched per game tick (and the token bucket ceiling). Default is 8.
    // The refill rate is fixed at 8 tokens per 310 ms, so values above 8 only enlarge the
    // burst allowed after idle time; they do not raise the sustained rate and can exceed
    // strict servers' interact limits.
    var maxNotesPerTick: Int = 8,
    // DEPRECATED, kept for one release so a downgrade still reads a valid value: the old single
    // loop setting (NONE, LOOP_ONE, LOOP_ALL, SHUFFLE_ALL). It is read once, to migrate files
    // that have no repeatMode yet, and is not written afterwards (see migrateLoopSettings()).
    var loopMode: String = LoopSettings.LEGACY_NONE,
    // Repeat and shuffle, independent of each other. repeatMode is OFF, ALL or ONE; null means
    // "file written before this field existed" and triggers the migration from loopMode.
    var repeatMode: String? = null,
    var shuffle: Boolean = false,
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

    fun repeatModeEnum(): RepeatMode = LoopSettings.parseRepeat(repeatMode) ?: RepeatMode.OFF

    /**
     * Fills repeatMode and shuffle from loopMode when the file predates them, and normalises an
     * unknown or oddly cased repeatMode. Returns true when the file should be saved again.
     */
    fun migrateLoopSettings(): Boolean {
        val resolution = LoopSettings.resolve(repeatMode, shuffle, loopMode)
        repeatMode = resolution.settings.repeat.name
        shuffle = resolution.settings.shuffle
        return resolution.changed
    }

    /**
     * Replaces values that would break functionality (zero, negative, NaN, infinite, null) with
     * safe ones. The value rules are pure helpers in `util/ConfigSanitizing.kt`.
     */
    fun sanitize() {
        scanRadius = positiveOr(scanRadius, 5)
        arpeggioStaleTimeoutMs = positiveOr(arpeggioStaleTimeoutMs, 200L)
        maxRotationDegreesPerTick = positiveFiniteOr(maxRotationDegreesPerTick, 35f)
        rotationConvergenceThresholdDegrees = positiveFiniteOr(rotationConvergenceThresholdDegrees, 2f)
        rotationInProgressTimeoutMs = positiveOr(rotationInProgressTimeoutMs, 1500L)
        maxNotesPerTick = sanitizeMaxNotesPerTick(maxNotesPerTick)
        defaultTempoMultiplier = sanitizeTempoMultiplier(defaultTempoMultiplier)
        autoRescanIntervalSeconds = positiveOr(autoRescanIntervalSeconds, 3)
        maxTuningClicksPerTick = positiveOr(maxTuningClicksPerTick, 1)
        // Gson can leave these null when the file holds an explicit null.
        @Suppress("SENSELESS_COMPARISON")
        if ((shuffleHistory as MutableList<String>?) == null) shuffleHistory = mutableListOf()
        @Suppress("SENSELESS_COMPARISON")
        if ((instrumentRemap as MutableMap<String, String?>?) == null) instrumentRemap = mutableMapOf()
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
            config.migrateLoopSettings()
            save()
            return
        }
        try {
            config = gson.fromJson(configFile.readText(), BlockBardConfig::class.java)
                ?: BlockBardConfig()
            config.sanitize()
            // Files from before repeat/shuffle existed carry only loopMode; migrate once and persist.
            if (config.migrateLoopSettings()) save()
        } catch (e: Exception) {
            logger.warn("Config parse failed, resetting to defaults: ${e.message}", e)
            config = BlockBardConfig()
            config.migrateLoopSettings()
            save()
        }
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        configFile.writeText(gson.toJson(config))
    }
}
