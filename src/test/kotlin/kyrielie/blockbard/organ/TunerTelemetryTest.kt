package kyrielie.blockbard.organ

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunerTelemetryTest {

    @Test
    fun formatsStandardSummaryLineCorrectly() {
        val summary = TunerSummary(
            targets = 119,
            clicks = 409,
            retries = 1,
            timeSeconds = 2.4,
            pingMs = 52,
            reachDistance = 4.5,
            isCreative = false,
            failCounts = emptyMap()
        )

        assertEquals(
            "[BlockBard/Tuner] Run summary: targets=119 clicks=409 retries=1 time=2.4s ping=52ms reach=4.5 (creative=false) fails=[OCCLUDED:0, OUT_OF_REACH:0, UNRESPONSIVE:0, DESYNC:0]",
            summary.format()
        )
    }

    @Test
    fun formatsCreativeModeAndFailuresCorrectly() {
        val summary = TunerSummary(
            targets = 50,
            clicks = 120,
            retries = 0,
            timeSeconds = 1.8,
            pingMs = 25,
            reachDistance = 5.0,
            isCreative = true,
            failCounts = mapOf(
                TuningFailState.OCCLUDED to 2,
                TuningFailState.OUT_OF_REACH to 1
            )
        )

        assertEquals(
            "[BlockBard/Tuner] Run summary: targets=50 clicks=120 retries=0 time=1.8s ping=25ms reach=5.0 (creative=true) fails=[OCCLUDED:2, OUT_OF_REACH:1, UNRESPONSIVE:0, DESYNC:0]",
            summary.format()
        )
    }

    @Test
    fun appendsNonStandardFailuresWhenPresent() {
        val summary = TunerSummary(
            targets = 10,
            clicks = 5,
            retries = 0,
            timeSeconds = 0.5,
            pingMs = 10,
            reachDistance = 4.5,
            isCreative = false,
            failCounts = mapOf(
                TuningFailState.BLOCK_MISSING to 1,
                TuningFailState.INTERACT_REJECTED to 2
            )
        )

        val formatted = summary.format()
        assertTrue(formatted.contains("fails=[OCCLUDED:0, OUT_OF_REACH:0, UNRESPONSIVE:0, DESYNC:0"))
        assertTrue(formatted.contains("BLOCK_MISSING:1"))
        assertTrue(formatted.contains("INTERACT_REJECTED:2"))
    }

    @Test
    fun noteBlockTunerEmitsSummaryOnEmptyTargets() {
        val tuner = NoteBlockTuner(
            targets = emptyList(),
            worldNoteReader = { null },
            interactBlock = { true },
            pingMs = { 42 },
            reachDistance = { 4.5 },
            isCreative = { false }
        )

        tuner.start()
        val summary = tuner.lastSummary
        assertNotNull(summary)
        assertEquals(0, summary!!.targets)
        assertEquals(0, summary.clicks)
        assertEquals(0, summary.retries)
        assertEquals(42, summary.pingMs)
        assertEquals(4.5, summary.reachDistance)
        assertEquals(false, summary.isCreative)
        assertEquals(
            "[BlockBard/Tuner] Run summary: targets=0 clicks=0 retries=0 time=0.0s ping=42ms reach=4.5 (creative=false) fails=[OCCLUDED:0, OUT_OF_REACH:0, UNRESPONSIVE:0, DESYNC:0]",
            summary.format()
        )
    }

    @Test
    fun summaryWithMultipleFailuresAndRetries() {
        val summary = TunerSummary(
            targets = 24,
            clicks = 68,
            retries = 3,
            timeSeconds = 4.2,
            pingMs = 80,
            reachDistance = 5.0,
            isCreative = true,
            failCounts = mapOf(
                TuningFailState.OCCLUDED to 1,
                TuningFailState.OUT_OF_REACH to 2,
                TuningFailState.UNRESPONSIVE to 1,
                TuningFailState.DESYNC to 3
            )
        )

        assertEquals(
            "[BlockBard/Tuner] Run summary: targets=24 clicks=68 retries=3 time=4.2s ping=80ms reach=5.0 (creative=true) fails=[OCCLUDED:1, OUT_OF_REACH:2, UNRESPONSIVE:1, DESYNC:3]",
            summary.format()
        )
    }
}
