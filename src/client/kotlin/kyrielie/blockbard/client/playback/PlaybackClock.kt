package kyrielie.blockbard.client.playback

/**
 * Wall-clock mapping for a playback thread that stays continuous when the tempo
 * multiplier changes mid-song.
 *
 * Time is tracked as an anchor: at wall time [anchorWallMs] the song position was
 * [anchorBaseMs] (milliseconds at 1x tempo). The wall time of a later position is
 * anchorWallMs + (baseMs - anchorBaseMs) / multiplier. When the multiplier differs
 * from the one the anchor was set under, the anchor is first moved to "now" so only
 * the remaining song time is rescaled, instead of the whole elapsed span.
 *
 * Not thread-safe: use from the playback thread only. Pause time is applied with
 * [shift] by that same thread.
 */
internal class PlaybackClock(startBaseMs: Double, multiplier: Float, nowMs: Long = System.currentTimeMillis()) {
    private var anchorWallMs: Double = nowMs.toDouble()
    private var anchorBaseMs: Double = startBaseMs
    private var anchorMultiplier: Float = sanitize(multiplier)

    /** Wall-clock millis at which song position [baseMs] should sound under [multiplier]. */
    fun wallTimeFor(baseMs: Double, multiplier: Float, nowMs: Long = System.currentTimeMillis()): Long {
        val m = sanitize(multiplier)
        if (m != anchorMultiplier) {
            anchorBaseMs += (nowMs - anchorWallMs) * anchorMultiplier
            anchorWallMs = nowMs.toDouble()
            anchorMultiplier = m
        }
        return (anchorWallMs + (baseMs - anchorBaseMs) / m).toLong()
    }

    /** Delays all future targets by [pausedMs], used after a pause. */
    fun shift(pausedMs: Long) {
        anchorWallMs += pausedMs
    }

    private fun sanitize(m: Float): Float = if (m.isFinite() && m >= MIN_MULTIPLIER) m else MIN_MULTIPLIER

    private companion object {
        const val MIN_MULTIPLIER = 0.1f
    }
}
