package kyrielie.blockbard.client.config

import kyrielie.blockbard.organ.ShiftMode
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File

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
    var keyMappings: List<Int> = listOf(54, 55, 56, 57, 58, 59, 60, 61, 62)
) {
    fun shiftModeEnum(): ShiftMode = try {
        ShiftMode.valueOf(shiftMode)
    } catch (_: IllegalArgumentException) {
        ShiftMode.BEST_EFFORT
    }
}

object ConfigManager {
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
        } catch (_: Exception) {
            config = BlockBardConfig()
            save()
        }
    }

    fun save() {
        configFile.parentFile?.mkdirs()
        configFile.writeText(gson.toJson(config))
    }
}
