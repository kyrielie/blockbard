package kyrielie.blockbard.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoteUtilsTest {

    @Test
    fun midiNoteToName_knownNotes() {
        assertEquals("C-1", midiNoteToName(0))
        assertEquals("B-1", midiNoteToName(11))
        assertEquals("C0", midiNoteToName(12))
        assertEquals("C4", midiNoteToName(60))
        assertEquals("G9", midiNoteToName(127))
    }

    @Test
    fun midiNoteToName_negativeInputDoesNotThrow() {
        assertEquals("B-2", midiNoteToName(-1))
        assertEquals("C-2", midiNoteToName(-12))
    }

    @Test
    fun clicksNeeded_wrapsForwardOnly() {
        assertEquals(0, clicksNeeded(5, 5))
        assertEquals(1, clicksNeeded(24, 0))
        assertEquals(24, clicksNeeded(1, 0))
        assertEquals(10, clicksNeeded(0, 10))
    }
}
