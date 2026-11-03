package kyrielie.blockbard.util

/** Outcome of matching a user-typed or saved device name against the names the OS reports. */
sealed class MidiDeviceMatch {
    data class Found(val name: String) : MidiDeviceMatch()
    data class Ambiguous(val candidates: List<String>) : MidiDeviceMatch()
    object NotFound : MidiDeviceMatch()
}

/**
 * Pure name matching for MIDI input devices. Bluetooth and macOS CoreMIDI ports often report a
 * name that differs in case or extra words from what the user remembers, so exact match is tried
 * first, then case-insensitive equality, then a unique case-insensitive substring.
 */
object MidiDeviceMatcher {

    fun match(available: List<String>, query: String): MidiDeviceMatch {
        val q = query.trim()
        if (q.isEmpty()) return MidiDeviceMatch.NotFound
        val names = available.distinct()

        if (q in names) return MidiDeviceMatch.Found(q)

        val equalIgnoreCase = names.filter { it.trim().equals(q, ignoreCase = true) }
        if (equalIgnoreCase.size == 1) return MidiDeviceMatch.Found(equalIgnoreCase[0])
        if (equalIgnoreCase.size > 1) return MidiDeviceMatch.Ambiguous(equalIgnoreCase)

        val contains = names.filter { it.contains(q, ignoreCase = true) }
        return when (contains.size) {
            0 -> MidiDeviceMatch.NotFound
            1 -> MidiDeviceMatch.Found(contains[0])
            else -> MidiDeviceMatch.Ambiguous(contains)
        }
    }

    /**
     * Among [items] with the given [name], returns the first that [canSend]. A device listed
     * under the same name as both input and output port must resolve to the input one.
     */
    fun <T> selectSender(items: List<T>, name: String, nameOf: (T) -> String, canSend: (T) -> Boolean): T? =
        items.firstOrNull { nameOf(it) == name && canSend(it) }
}
