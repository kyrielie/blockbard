package kyrielie.blockbard.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CoverageMessageTest {

    @Test
    fun fullyCoveredSongIsJustTheCount() {
        assertEquals("12/12 notes covered", formatCoverageMessage(12, 12, 0, emptyList(), 0))
    }

    @Test
    fun shiftedNotesAreMentioned() {
        assertEquals(
            "10/10 notes covered. ⚠ 3 note(s) octave-shifted",
            formatCoverageMessage(10, 10, 3, emptyList(), 0)
        )
    }

    @Test
    fun missingInstrumentsAreListedInOrder() {
        assertEquals(
            "8/11 notes covered. Add: 2 BELL (gold block), 1 FLUTE (clay)",
            formatCoverageMessage(8, 11, 0, listOf("2 BELL (gold block)", "1 FLUTE (clay)"), 0)
        )
    }

    @Test
    fun notesNoInstrumentCanPlayComeLast() {
        assertEquals(
            "7/9 notes covered. ⚠ 1 note(s) octave-shifted. Add: 1 HARP (none). 2 note(s) unplayable on any instrument",
            formatCoverageMessage(7, 9, 1, listOf("1 HARP (none)"), 2)
        )
    }
}
