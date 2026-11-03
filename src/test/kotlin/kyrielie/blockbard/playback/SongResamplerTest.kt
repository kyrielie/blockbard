package kyrielie.blockbard.playback

import kyrielie.blockbard.client.playback.NbsFile
import kyrielie.blockbard.client.playback.NbsNote
import kyrielie.blockbard.client.playback.SongResampler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

class SongResamplerTest {

    @Test
    fun `resampling 10 tps to 20 tps doubles tick indices`() {
        val originalNotes = listOf(
            NbsNote(tick = 0, layer = 0, instrument = 0, key = 33, velocity = 100),
            NbsNote(tick = 5, layer = 0, instrument = 0, key = 35, velocity = 100),
            NbsNote(tick = 10, layer = 0, instrument = 0, key = 37, velocity = 100)
        )
        val original = NbsFile(
            file = File("test.nbs"),
            version = 4,
            tempo = 10.0f,
            notes = originalNotes,
            title = "Test Song"
        )

        val resampled = SongResampler.resample(original, 20.0f)

        assertEquals(20.0f, resampled.tempo)
        assertEquals(3, resampled.notes.size)
        assertEquals(0, resampled.notes[0].tick)
        assertEquals(10, resampled.notes[1].tick)
        assertEquals(20, resampled.notes[2].tick)

        // Musical duration should remain identical: 10 ticks @ 10 TPS = 1000ms; 20 ticks @ 20 TPS = 1000ms
        assertEquals(original.durationMs, resampled.durationMs)
    }

    @Test
    fun `resampling song already at 20 tps returns identical object`() {
        val notes = listOf(
            NbsNote(tick = 0, layer = 0, instrument = 0, key = 33, velocity = 100),
            NbsNote(tick = 20, layer = 0, instrument = 0, key = 45, velocity = 100)
        )
        val original = NbsFile(
            file = File("test20.nbs"),
            version = 4,
            tempo = 20.0f,
            notes = notes,
            title = "Already 20 TPS"
        )

        val resampled = SongResampler.resample(original, 20.0f)

        assertEquals(20.0f, resampled.tempo)
        assertEquals(notes, resampled.notes)
    }

    @Test
    fun `resampling fractional tempos correctly rounds ticks`() {
        // 15 TPS -> 20 TPS (ratio = 4/3 = 1.333...)
        val notes = listOf(
            NbsNote(tick = 1, layer = 0, instrument = 0, key = 33, velocity = 100), // 1 * 1.333 = 1.333 -> 1
            NbsNote(tick = 2, layer = 0, instrument = 0, key = 35, velocity = 100), // 2 * 1.333 = 2.666 -> 3
            NbsNote(tick = 3, layer = 0, instrument = 0, key = 37, velocity = 100)  // 3 * 1.333 = 4.0   -> 4
        )
        val original = NbsFile(
            file = File("test15.nbs"),
            version = 4,
            tempo = 15.0f,
            notes = notes,
            title = "15 TPS"
        )

        val resampled = SongResampler.resample(original, 20.0f)

        assertEquals(20.0f, resampled.tempo)
        assertEquals(1, resampled.notes[0].tick)
        assertEquals(3, resampled.notes[1].tick)
        assertEquals(4, resampled.notes[2].tick)
    }
}
