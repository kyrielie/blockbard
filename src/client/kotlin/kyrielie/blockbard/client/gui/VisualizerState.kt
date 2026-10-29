package kyrielie.blockbard.client.gui

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument

data class VisualizerNote(
    val pitchIndex: Int, // 0..24
    val instrument: NoteBlockInstrument,
    val timestampMs: Long
)

object VisualizerState {
    private val lock = Any()
    private val activeNotes = mutableListOf<VisualizerNote>()
    private const val DECAY_MS = 350L

    fun trigger(midiNote: Int, instrument: NoteBlockInstrument?) {
        // Map MIDI note (F#3 = 54) into 0..24 range
        val pitchIndex = if (midiNote in 54..78) {
            midiNote - 54
        } else {
            ((midiNote - 54) % 24 + 24) % 24
        }
        val inst = instrument ?: NoteBlockInstrument.HARP
        val now = System.currentTimeMillis()
        synchronized(lock) {
            activeNotes.removeAll { now - it.timestampMs > DECAY_MS }
            activeNotes.add(VisualizerNote(pitchIndex, inst, now))
        }
    }

    /**
     * Returns the current illumination (intensity 0.0f..1.0f and RGB color) for a pitch index (0..24).
     */
    fun getPitchState(pitchIndex: Int): Pair<Float, Int>? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val matching = activeNotes.filter { it.pitchIndex == pitchIndex }
            if (matching.isEmpty()) return null
            val mostRecent = matching.maxByOrNull { it.timestampMs } ?: return null
            val age = now - mostRecent.timestampMs
            if (age > DECAY_MS) return null
            val intensity = (1.0f - (age.toFloat() / DECAY_MS)).coerceIn(0.0f, 1.0f)
            val color = getInstrumentColor(mostRecent.instrument)
            return Pair(intensity, color)
        }
    }

    fun getInstrumentColor(inst: NoteBlockInstrument): Int = when (inst) {
        NoteBlockInstrument.HARP            -> 0x55FF55 // Emerald green
        NoteBlockInstrument.BASEDRUM        -> 0xFF5555 // Red
        NoteBlockInstrument.SNARE           -> 0xFFFF55 // Yellow
        NoteBlockInstrument.HAT             -> 0xE0E0E0 // Light grey/white
        NoteBlockInstrument.BASS            -> 0xD2691E // Warm chocolate
        NoteBlockInstrument.FLUTE           -> 0x55FFFF // Cyan
        NoteBlockInstrument.BELL            -> 0xFFD700 // Gold
        NoteBlockInstrument.GUITAR          -> 0xFF9900 // Amber orange
        NoteBlockInstrument.CHIME           -> 0xFFFFAA // Pale yellow
        NoteBlockInstrument.XYLOPHONE       -> 0x88EEFF // Ice blue
        NoteBlockInstrument.IRON_XYLOPHONE  -> 0xCCFFFF // Bright white blue
        NoteBlockInstrument.COW_BELL        -> 0xA0522D // Sienna
        NoteBlockInstrument.DIDGERIDOO      -> 0x8B4513 // Brown
        NoteBlockInstrument.BIT             -> 0xFF55FF // Magenta
        NoteBlockInstrument.BANJO           -> 0xDEB887 // Burlywood
        NoteBlockInstrument.PLING           -> 0x00FF88 // Spring green
        else                                -> 0x55FF55
    }

    fun clear() = synchronized(lock) {
        activeNotes.clear()
    }
}
