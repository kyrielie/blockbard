package kyrielie.blockbard.client.midi

import kyrielie.blockbard.client.config.ConfigManager
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.slf4j.LoggerFactory

/**
 * Creative instrument remapping and muting engine.
 * Allows substituting hard-to-source instruments with available ones,
 * or silencing noisy percussion tracks before note assignment and playback.
 */
object InstrumentRemapper {

    private val logger = LoggerFactory.getLogger("BlockBard/InstrumentRemapper")

    /**
     * Remaps [instrument] according to the config.
     * Returns:
     * - The remapped [NoteBlockInstrument], or
     * - `null` if the instrument is muted (mapped to null or "MUTE"/"NONE"), or
     * - [instrument] unmodified if not in the remap table.
     */
    fun remap(instrument: NoteBlockInstrument): NoteBlockInstrument? {
        val remapConfig = ConfigManager.config.instrumentRemap
        if (!remapConfig.containsKey(instrument.name)) {
            return instrument
        }
        val target = remapConfig[instrument.name] ?: return null
        if (target.equals("MUTE", ignoreCase = true) || target.equals("NONE", ignoreCase = true)) {
            return null
        }
        return try {
            NoteBlockInstrument.valueOf(target.uppercase())
        } catch (_: IllegalArgumentException) {
            instrument
        }
    }

    /** Remaps [source] to [target]. Pass `null` to mute. */
    fun setRemap(source: NoteBlockInstrument, target: NoteBlockInstrument?) {
        if (target == null) {
            ConfigManager.config.instrumentRemap[source.name] = null
        } else {
            ConfigManager.config.instrumentRemap[source.name] = target.name
        }
        ConfigManager.save()
        logger.info("Remapped ${source.name} -> ${target?.name ?: "MUTED"}")
    }

    /** Clears any remap rule for [source]. */
    fun clearRemap(source: NoteBlockInstrument) {
        ConfigManager.config.instrumentRemap.remove(source.name)
        ConfigManager.save()
        logger.info("Cleared remap for ${source.name}")
    }

    /** Clears all remap rules. */
    fun clearAll() {
        ConfigManager.config.instrumentRemap.clear()
        ConfigManager.save()
        logger.info("Cleared all instrument remaps")
    }

    /** Returns all currently active remaps as human-readable strings. */
    fun activeRemaps(): Map<String, String> =
        ConfigManager.config.instrumentRemap.mapValues { (_, v) -> v ?: "MUTED" }
}
