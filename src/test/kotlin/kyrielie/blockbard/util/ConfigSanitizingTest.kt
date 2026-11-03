package kyrielie.blockbard.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigSanitizingTest {

    @Test
    fun positiveOr_int() {
        assertEquals(5, positiveOr(5, 9))
        assertEquals(1, positiveOr(1, 9))
        assertEquals(9, positiveOr(0, 9))
        assertEquals(9, positiveOr(-1, 9))
        assertEquals(9, positiveOr(Int.MIN_VALUE, 9))
        assertEquals(Int.MAX_VALUE, positiveOr(Int.MAX_VALUE, 9))
    }

    @Test
    fun positiveOr_long() {
        assertEquals(200L, positiveOr(200L, 7L))
        assertEquals(7L, positiveOr(0L, 7L))
        assertEquals(7L, positiveOr(-5L, 7L))
        assertEquals(7L, positiveOr(Long.MIN_VALUE, 7L))
    }

    @Test
    fun positiveFiniteOr_rejectsNanInfinityZeroAndNegative() {
        assertEquals(35f, positiveFiniteOr(35f, 1f))
        assertEquals(0.5f, positiveFiniteOr(0.5f, 1f))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -0f, -3f)) {
            assertEquals(2f, positiveFiniteOr(bad, 2f), "value=$bad")
        }
    }

    @Test
    fun sanitizeTuningClickIntervalTicks() {
        assertEquals(2, sanitizeTuningClickIntervalTicks(0))
        assertEquals(2, sanitizeTuningClickIntervalTicks(-4))
        assertEquals(1, sanitizeTuningClickIntervalTicks(1))
        assertEquals(7, sanitizeTuningClickIntervalTicks(7))
        assertEquals(20, sanitizeTuningClickIntervalTicks(500))
        assertEquals(20, sanitizeTuningClickIntervalTicks(Int.MAX_VALUE))
    }

    @Test
    fun sanitizeMaxNotesPerTick() {
        assertEquals(8, sanitizeMaxNotesPerTick(0))
        assertEquals(8, sanitizeMaxNotesPerTick(-4))
        assertEquals(1, sanitizeMaxNotesPerTick(1))
        assertEquals(8, sanitizeMaxNotesPerTick(8))
        assertEquals(64, sanitizeMaxNotesPerTick(64))
        assertEquals(64, sanitizeMaxNotesPerTick(65))
        assertEquals(64, sanitizeMaxNotesPerTick(Int.MAX_VALUE))
    }

    @Test
    fun sanitizeTempoMultiplier() {
        assertEquals(1.0f, sanitizeTempoMultiplier(Float.NaN))
        assertEquals(1.0f, sanitizeTempoMultiplier(Float.POSITIVE_INFINITY))
        assertEquals(1.0f, sanitizeTempoMultiplier(0f))
        assertEquals(1.0f, sanitizeTempoMultiplier(-2f))
        assertEquals(0.5f, sanitizeTempoMultiplier(0.1f))
        assertEquals(2.0f, sanitizeTempoMultiplier(3f))
        assertEquals(1.5f, sanitizeTempoMultiplier(1.5f))
        assertEquals(0.5f, sanitizeTempoMultiplier(0.5f))
        assertEquals(2.0f, sanitizeTempoMultiplier(2.0f))
    }
}
