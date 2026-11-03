package kyrielie.blockbard.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MidiDeviceMatcherTest {

    private val devices = listOf("Gervill", "Real Time Sequencer", "Yamaha SC3 Bluetooth", "Yamaha SC3 Bluetooth", "IAC Driver Bus 1")

    @Test
    fun exactMatchWins() {
        assertEquals(MidiDeviceMatch.Found("Gervill"), MidiDeviceMatcher.match(devices, "Gervill"))
    }

    @Test
    fun duplicateNamesCollapseToOneMatch() {
        assertEquals(MidiDeviceMatch.Found("Yamaha SC3 Bluetooth"), MidiDeviceMatcher.match(devices, "Yamaha SC3 Bluetooth"))
    }

    @Test
    fun caseInsensitiveEquality() {
        assertEquals(MidiDeviceMatch.Found("IAC Driver Bus 1"), MidiDeviceMatcher.match(devices, "iac driver bus 1"))
    }

    @Test
    fun uniqueSubstringMatches() {
        assertEquals(MidiDeviceMatch.Found("Yamaha SC3 Bluetooth"), MidiDeviceMatcher.match(devices, "sc3"))
    }

    @Test
    fun ambiguousSubstringReportsCandidates() {
        val result = MidiDeviceMatcher.match(listOf("Yamaha SC3", "Yamaha PSR"), "yamaha")
        assertEquals(MidiDeviceMatch.Ambiguous(listOf("Yamaha SC3", "Yamaha PSR")), result)
    }

    @Test
    fun unknownOrBlankIsNotFound() {
        assertEquals(MidiDeviceMatch.NotFound, MidiDeviceMatcher.match(devices, "Korg"))
        assertEquals(MidiDeviceMatch.NotFound, MidiDeviceMatcher.match(devices, "   "))
        assertEquals(MidiDeviceMatch.NotFound, MidiDeviceMatcher.match(emptyList(), "Gervill"))
    }
}

class MidiDeviceSelectSenderTest {
    private data class Port(val name: String, val canSend: Boolean)

    private fun pick(items: List<Port>, name: String) =
        MidiDeviceMatcher.selectSender(items, name, { it.name }, { it.canSend })

    @org.junit.jupiter.api.Test
    fun skipsOutputPortListedFirstUnderTheSameName() {
        val out = Port("Yamaha", false)
        val inn = Port("Yamaha", true)
        org.junit.jupiter.api.Assertions.assertSame(inn, pick(listOf(out, inn), "Yamaha"))
    }

    @org.junit.jupiter.api.Test
    fun nullWhenOnlyOutputExistsOrNameMissing() {
        org.junit.jupiter.api.Assertions.assertNull(pick(listOf(Port("Yamaha", false)), "Yamaha"))
        org.junit.jupiter.api.Assertions.assertNull(pick(listOf(Port("Other", true)), "Yamaha"))
    }
}
