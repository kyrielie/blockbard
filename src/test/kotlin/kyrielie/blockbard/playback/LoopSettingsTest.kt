package kyrielie.blockbard.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoopSettingsTest {

    private fun pair(repeat: RepeatMode, shuffle: Boolean) = RepeatShuffle(repeat, shuffle)

    // ---- migration of the old loopMode ----------------------------------------------------

    @Test
    fun migrateLegacy_everyOldValue() {
        assertEquals(pair(RepeatMode.OFF, false), LoopSettings.migrateLegacy("NONE"))
        assertEquals(pair(RepeatMode.ONE, false), LoopSettings.migrateLegacy("LOOP_ONE"))
        assertEquals(pair(RepeatMode.ALL, false), LoopSettings.migrateLegacy("LOOP_ALL"))
        assertEquals(pair(RepeatMode.ALL, true), LoopSettings.migrateLegacy("SHUFFLE_ALL"))
    }

    @Test
    fun migrateLegacy_unknownOrMissingIsOff() {
        for (value in listOf(null, "", "garbage", "loop_all", " LOOP_ALL", "SHUFFLE")) {
            assertEquals(pair(RepeatMode.OFF, false), LoopSettings.migrateLegacy(value), "value=$value")
        }
    }

    @Test
    fun resolve_fileWithoutRepeatModeMigratesAndAsksForSave() {
        val cases = mapOf(
            "NONE" to pair(RepeatMode.OFF, false),
            "LOOP_ONE" to pair(RepeatMode.ONE, false),
            "LOOP_ALL" to pair(RepeatMode.ALL, false),
            "SHUFFLE_ALL" to pair(RepeatMode.ALL, true),
        )
        for ((old, expected) in cases) {
            val result = LoopSettings.resolve(repeatMode = null, shuffle = false, loopMode = old)
            assertEquals(expected, result.settings, old)
            assertTrue(result.changed, old)
        }
    }

    @Test
    fun resolve_unknownLegacyValueWithoutRepeatModeIsOff() {
        val result = LoopSettings.resolve(null, false, "nonsense")
        assertEquals(pair(RepeatMode.OFF, false), result.settings)
        assertTrue(result.changed)
    }

    @Test
    fun resolve_alreadyMigratedIsLeftAloneEvenIfLoopModeIsStale() {
        // loopMode is not updated after migration, so it may disagree with the new fields.
        val result = LoopSettings.resolve("ONE", true, "LOOP_ALL")
        assertEquals(pair(RepeatMode.ONE, true), result.settings)
        assertFalse(result.changed)
    }

    @Test
    fun resolve_isIdempotent() {
        val first = LoopSettings.resolve(null, false, "SHUFFLE_ALL")
        val second = LoopSettings.resolve(first.settings.repeat.name, first.settings.shuffle, "SHUFFLE_ALL")
        assertEquals(first.settings, second.settings)
        assertFalse(second.changed)
    }

    @Test
    fun resolve_normalisesCaseAndUnknownRepeatModeAndKeepsShuffle() {
        val lower = LoopSettings.resolve("all", true, "NONE")
        assertEquals(pair(RepeatMode.ALL, true), lower.settings)
        assertTrue(lower.changed)

        val padded = LoopSettings.resolve(" One ", false, "NONE")
        assertEquals(pair(RepeatMode.ONE, false), padded.settings)
        assertTrue(padded.changed)

        val unknown = LoopSettings.resolve("sometimes", true, "LOOP_ALL")
        assertEquals(pair(RepeatMode.OFF, true), unknown.settings)
        assertTrue(unknown.changed)
    }

    // ---- parsing --------------------------------------------------------------------------

    @Test
    fun parseRepeat() {
        assertEquals(RepeatMode.OFF, LoopSettings.parseRepeat("off"))
        assertEquals(RepeatMode.ALL, LoopSettings.parseRepeat("ALL"))
        assertEquals(RepeatMode.ONE, LoopSettings.parseRepeat("  one "))
        assertNull(LoopSettings.parseRepeat(null))
        assertNull(LoopSettings.parseRepeat(""))
        assertNull(LoopSettings.parseRepeat("loop_all"))
    }

    @Test
    fun parseOnOff() {
        assertEquals(true, LoopSettings.parseOnOff("on"))
        assertEquals(true, LoopSettings.parseOnOff("TRUE"))
        assertEquals(false, LoopSettings.parseOnOff("Off"))
        assertEquals(false, LoopSettings.parseOnOff(" false "))
        assertNull(LoopSettings.parseOnOff(null))
        assertNull(LoopSettings.parseOnOff("yes"))
        assertNull(LoopSettings.parseOnOff(""))
    }

    @Test
    fun label() {
        assertEquals("Loop: OFF", LoopSettings.label(RepeatMode.OFF, false))
        assertEquals("Loop: ALL", LoopSettings.label(RepeatMode.ALL, false))
        assertEquals("Loop: ONE", LoopSettings.label(RepeatMode.ONE, false))
        assertEquals("Loop: ALL+Shuf", LoopSettings.label(RepeatMode.ALL, true))
        assertEquals("Loop: OFF+Shuf", LoopSettings.label(RepeatMode.OFF, true))
    }
}
