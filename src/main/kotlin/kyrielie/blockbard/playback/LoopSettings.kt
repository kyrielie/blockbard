package kyrielie.blockbard.playback

/** The persisted repeat and shuffle pair. */
data class RepeatShuffle(val repeat: RepeatMode, val shuffle: Boolean)

/** Outcome of [LoopSettings.resolve]: the settings to use and whether the file should be rewritten. */
data class LoopResolution(val settings: RepeatShuffle, val changed: Boolean)

/**
 * Pure rules for the repeat and shuffle settings: migration from the old single `loopMode`
 * string, parsing and display.
 */
object LoopSettings {
    const val LEGACY_NONE = "NONE"
    const val LEGACY_LOOP_ONE = "LOOP_ONE"
    const val LEGACY_LOOP_ALL = "LOOP_ALL"
    const val LEGACY_SHUFFLE_ALL = "SHUFFLE_ALL"

    /** Old `loopMode` value to the new pair. Exact match, as the old `valueOf` was; null or unknown is OFF. */
    fun migrateLegacy(loopMode: String?): RepeatShuffle = when (loopMode) {
        LEGACY_LOOP_ONE -> RepeatShuffle(RepeatMode.ONE, false)
        LEGACY_LOOP_ALL -> RepeatShuffle(RepeatMode.ALL, false)
        LEGACY_SHUFFLE_ALL -> RepeatShuffle(RepeatMode.ALL, true)
        else -> RepeatShuffle(RepeatMode.OFF, false)
    }

    /** Case-insensitive, whitespace-tolerant; null for null or an unknown name. */
    fun parseRepeat(name: String?): RepeatMode? {
        val trimmed = name?.trim() ?: return null
        return RepeatMode.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    }

    /** "on"/"true" and "off"/"false", case-insensitive; null for anything else. */
    fun parseOnOff(text: String?): Boolean? = when (text?.trim()?.lowercase()) {
        "on", "true" -> true
        "off", "false" -> false
        else -> null
    }

    /**
     * Decide the settings from what a config file held. A null [repeatMode] means the file
     * predates the field, so it is migrated from [loopMode] (`changed` = true). A present value is
     * kept, normalised to its canonical name; an unknown one becomes OFF. [shuffle] is kept as is.
     */
    fun resolve(repeatMode: String?, shuffle: Boolean, loopMode: String?): LoopResolution {
        if (repeatMode == null) return LoopResolution(migrateLegacy(loopMode), true)
        val canonical = parseRepeat(repeatMode) ?: RepeatMode.OFF
        return LoopResolution(RepeatShuffle(canonical, shuffle), canonical.name != repeatMode)
    }

    /** Short label for the in-GUI button, for example "Loop: ALL+Shuf". */
    fun label(repeat: RepeatMode, shuffle: Boolean): String =
        "Loop: ${repeat.name}" + if (shuffle) "+Shuf" else ""
}
