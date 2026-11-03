package kyrielie.blockbard.client.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Resamples NBS songs with non-standard tempos to standard Minecraft 20 TPS.
 *
 * Algorithm ported from NoteBlockLib / NoteBlockTool (SongResampler.java).
 *
 * Many NBS files are composed at custom tempos (e.g. 10 TPS, 15 TPS, or 30 TPS).
 * Playing them without resampling requires fractional tick clock interpolation and
 * sleep jitter. By resampling notes to a uniform 20.0 ticks per second (1 tick = 50ms),
 * every note falls cleanly on standard Minecraft client tick boundaries without
 * altering the total musical duration or playback pitch.
 */
object SongResampler {

    const val STANDARD_MINECRAFT_TPS: Float = 20.0f

    /**
     * Resamples an [NbsFile] to the [targetTempo] (default 20.0 TPS).
     * If the song is already within 0.01 TPS of the target, returns it unchanged.
     */
    fun resample(nbs: NbsFile, targetTempo: Float = STANDARD_MINECRAFT_TPS): NbsFile {
        if (abs(nbs.tempo - targetTempo) < 0.01f || nbs.tempo <= 0f) {
            return nbs
        }

        val ratio = targetTempo / nbs.tempo
        val resampledNotes = nbs.notes.map { note ->
            val newTick = (note.tick * ratio).roundToInt()
            note.copy(tick = newTick)
        }

        return nbs.copy(
            tempo = targetTempo,
            notes = resampledNotes
        )
    }
}
