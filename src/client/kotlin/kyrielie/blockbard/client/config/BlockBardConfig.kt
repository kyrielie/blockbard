package kyrielie.blockbard.client.config

import kyrielie.blockbard.organ.ShiftMode
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

enum class LoopMode { NONE, LOOP_ONE, LOOP_ALL, SHUFFLE_ALL }

data class BlockBardConfig(
    var scanRadius: Int = 5,
    var shiftMode: String = ShiftMode.BEST_EFFORT.name,
    var maxOctaveShift: Int = 1,
    var reportShiftsInHud: Boolean = true,
    var hudEnabled: Boolean = true,
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
    // Maximum notes dispatched per game tick. 1 = default musical behavior with full
    // rotation convergence gating. 2-8 = speed test; notes 2+ skip the convergence
    // gate and fire immediately after note 1 (useful for measuring max throughput or
    // diagnosing chord timing). Range 1-8.
    var maxNotesPerTick: Int = 1,
    // Tier 4: loop / auto-advance mode
    // NONE        = stop after each song (current behavior)
    // LOOP_ONE    = repeat the same song indefinitely (no retune needed)
    // LOOP_ALL    = advance through all songs in sorted order, retuning between tracks
    // SHUFFLE_ALL = advance randomly, avoiding recent repeats (see shuffleHistory),
    //               retuning between tracks
    var loopMode: String = LoopMode.NONE.name
) {
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
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
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
            // Gson + Kotlin data class caveat: fields absent from an older config.json
            // (e.g. saved before these fields were added) are set to JVM zero-defaults
            // (0, 0L, 0.0f, null) rather than the Kotlin-declared default expressions.
            // Coalesce any zero/null that would break functionality back to a safe value.
            if (config.scanRadius <= 0) config.scanRadius = 5
            if (config.arpeggioStaleTimeoutMs <= 0L) config.arpeggioStaleTimeoutMs = 200L
            if (config.maxRotationDegreesPerTick <= 0f) config.maxRotationDegreesPerTick = 35f
            if (config.rotationConvergenceThresholdDegrees <= 0f) config.rotationConvergenceThresholdDegrees = 2f
            if (config.rotationInProgressTimeoutMs <= 0L) config.rotationInProgressTimeoutMs = 1500L
            if (config.maxNotesPerTick <= 0) config.maxNotesPerTick = 1
            if (config.maxNotesPerTick > 8) config.maxNotesPerTick = 8
            if (config.shuffleHistory == null) config.shuffleHistory = mutableListOf()
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
