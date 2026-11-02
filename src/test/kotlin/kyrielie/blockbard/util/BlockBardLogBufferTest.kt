package kyrielie.blockbard.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BlockBardLogBufferTest {

    @BeforeEach
    fun setUp() {
        BlockBardLogBuffer.clear()
        DebugLog.enabled = false
    }

    @AfterEach
    fun tearDown() {
        BlockBardLogBuffer.clear()
        DebugLog.enabled = false
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun tailText_emptyBuffer_isEmpty() {
        assertEquals("", BlockBardLogBuffer.getTailText(1000))
    }

    @Test
    fun tailText_underBudget_returnsEverythingWithoutHeader() {
        BlockBardLogBuffer.info("T", "one")
        BlockBardLogBuffer.info("T", "two")
        val text = BlockBardLogBuffer.getTailText(10_000)
        assertEquals(BlockBardLogBuffer.getAllText(), text)
        assertFalse(text.contains("omitted"))
    }

    @Test
    fun tailText_overBudget_keepsNewestWholeEntriesAndReportsOmitted() {
        repeat(50) { BlockBardLogBuffer.info("T", "entry-$it " + "x".repeat(100)) }
        val budget = 1_000
        val text = BlockBardLogBuffer.getTailText(budget)
        assertTrue(bytes(text) <= budget, "size ${bytes(text)} exceeds $budget")
        assertTrue(text.startsWith("[BlockBard] "))
        assertTrue(text.lines().first().contains("older log entries omitted"))
        // newest entry present, oldest absent
        assertTrue(text.contains("entry-49 "))
        assertFalse(text.contains("entry-0 "))
        // every body line is a complete formatted entry (no partial lines)
        text.lines().drop(1).forEach { assertTrue(it.startsWith("[") && it.contains("[INFO] [T] entry-")) }
        // omitted count matches
        val omitted = text.lines().first().removePrefix("[BlockBard] ").substringBefore(' ').toInt()
        assertEquals(50, omitted + text.lines().drop(1).size)
    }

    @Test
    fun tailText_countsUtf8BytesNotChars() {
        // 3 bytes per char; a char-based budget would overshoot by 3x.
        repeat(20) { BlockBardLogBuffer.info("T", "\u20AC".repeat(100)) }
        val budget = 1_500
        val text = BlockBardLogBuffer.getTailText(budget)
        assertTrue(bytes(text) <= budget, "size ${bytes(text)} exceeds $budget")
    }

    @Test
    fun tailText_singleEntryLargerThanBudget_yieldsOnlyHeader() {
        BlockBardLogBuffer.info("T", "y".repeat(5_000))
        val text = BlockBardLogBuffer.getTailText(500)
        assertTrue(bytes(text) <= 500)
        assertTrue(text.contains("1 older log entries omitted"))
    }

    @Test
    fun tailText_defaultClipboardBudgetBoundsFullBuffer() {
        repeat(1000) { BlockBardLogBuffer.warn("PlayerController", "z".repeat(1_300)) }
        assertTrue(bytes(BlockBardLogBuffer.getAllText()) > 64 * 1024)
        assertTrue(bytes(BlockBardLogBuffer.getTailText(32_000)) <= 32_000)
    }

    @Test
    fun debug_isStoredOnlyWhenDebugLoggingEnabled() {
        BlockBardLogBuffer.debug("T", "hidden")
        assertEquals(0, BlockBardLogBuffer.size)
        DebugLog.enabled = true
        BlockBardLogBuffer.debug("T", "shown")
        assertEquals(1, BlockBardLogBuffer.size)
    }
}
