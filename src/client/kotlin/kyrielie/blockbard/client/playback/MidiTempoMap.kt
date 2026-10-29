package kyrielie.blockbard.client.playback

/**
 * A discrete tempo change event in a MIDI file.
 * @param tick Sequence tick at which the tempo becomes active.
 * @param usPerBeat Microseconds per quarter note (e.g. 500,000 = 120 BPM).
 * @param elapsedMsBefore Cumulative milliseconds elapsed up to [tick].
 */
data class TempoEvent(
    val tick: Long,
    val usPerBeat: Int,
    val elapsedMsBefore: Double
)

/**
 * Maps MIDI tick timestamps to real-world milliseconds across arbitrary tempo changes.
 */
class MidiTempoMap(
    val events: List<TempoEvent>,
    val ticksPerBeat: Int
) {
    /** Converts a sequence tick to milliseconds at 1x tempo. */
    fun tickToMs(tick: Long): Long {
        if (events.isEmpty()) {
            return ((tick.toDouble() / ticksPerBeat) * 500.0).toLong()
        }
        val active = events.lastOrNull { it.tick <= tick } ?: events.first()
        val ticksSince = tick - active.tick
        val msSince = (ticksSince.toDouble() / ticksPerBeat) * (active.usPerBeat / 1000.0)
        return (active.elapsedMsBefore + msSince).toLong()
    }

    /** Converts a sequence tick to scaled milliseconds under [tempoMultiplier]. */
    fun tickToScaledMs(tick: Long, tempoMultiplier: Float): Long {
        val baseMs = tickToMs(tick)
        return (baseMs / tempoMultiplier.coerceAtLeast(0.1f)).toLong()
    }

    companion object {
        /**
         * Builds a [MidiTempoMap] from a collection of raw (tick, tempoUsPerBeat) pairs.
         */
        fun build(tempoChanges: List<Pair<Long, Int>>, ticksPerBeat: Int): MidiTempoMap {
            val sorted = tempoChanges.sortedBy { it.first }
            val list = mutableListOf<TempoEvent>()
            var currentMs = 0.0
            var prevTick = 0L
            var currentUs = 500000 // default 120 BPM

            if (sorted.isEmpty() || sorted.first().first > 0L) {
                list.add(TempoEvent(0L, currentUs, 0.0))
            }

            for ((tick, us) in sorted) {
                if (tick > prevTick) {
                    val deltaTicks = tick - prevTick
                    currentMs += (deltaTicks.toDouble() / ticksPerBeat) * (currentUs / 1000.0)
                }
                currentUs = us
                prevTick = tick
                list.add(TempoEvent(tick, us, currentMs))
            }
            return MidiTempoMap(list, ticksPerBeat)
        }
    }
}
