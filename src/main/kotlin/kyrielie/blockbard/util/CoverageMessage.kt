package kyrielie.blockbard.util

/**
 * One-line summary of how much of a song the organ can play.
 *
 * @param covered notes assigned to a block
 * @param total covered plus unplayable
 * @param shiftedCount notes that needed an octave shift
 * @param addHints one entry per instrument that is short of blocks, already formatted
 *   (for example "2 BELL (gold block)"); empty when nothing is missing
 * @param anyInstrumentCount notes no instrument can play
 */
fun formatCoverageMessage(
    covered: Int,
    total: Int,
    shiftedCount: Int,
    addHints: List<String>,
    anyInstrumentCount: Int
): String = buildString {
    append("$covered/$total notes covered")
    if (shiftedCount > 0) {
        append(". ⚠ $shiftedCount note(s) octave-shifted")
    }
    if (addHints.isNotEmpty()) {
        append(". Add: ")
        append(addHints.joinToString())
    }
    if (anyInstrumentCount > 0) {
        append(". $anyInstrumentCount note(s) unplayable on any instrument")
    }
}
