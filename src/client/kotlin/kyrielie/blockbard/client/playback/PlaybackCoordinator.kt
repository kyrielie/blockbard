package kyrielie.blockbard.client.playback

import kyrielie.blockbard.client.config.ConfigManager
import kyrielie.blockbard.playback.SongId
import org.slf4j.LoggerFactory
import java.io.File

/**
 * What the coordinator needs from whatever can load, tune and start a song. Today that is the
 * most recent [kyrielie.blockbard.client.gui.MainScreen] instance, which stays registered after
 * the screen closes (the tuner lifecycle moves out of the screen in the next pass, after which
 * the coordinator will not need a host for this).
 */
interface PlaybackHost {
    /** True while a tuner run is in progress. */
    val isTuning: Boolean

    /** Library file for a song id; a placeholder path when the file is gone. */
    fun fileForId(id: SongId): File

    /** Select [id], load it, tune if needed and play it, as a queue or skip start does. */
    fun startQueuedSong(id: SongId): Boolean

    /** Auto-advance to a different song: select [file], retune, then play. */
    fun beginAdvance(file: File)

    /** Auto-advance to the same song (Repeat ONE): already loaded and tuned, just play again. */
    fun replayCurrent()

    /** Cancel any tuner run and the tune-then-play chain that depends on it. */
    fun abortTuning()

    /** The queue changed; refresh any cached display. */
    fun onQueueChanged()
}

/**
 * Decides what plays when a song ends, independent of whether the GUI is open. Ticked from
 * END_CLIENT_TICK in `BlockBardClient`.
 *
 * Thread rule: the playback thread's `onFinished` callback only sets [songEnded]. Everything
 * else here, including every touch of the [ClientPlayQueue] queue, runs on the client thread.
 */
object PlaybackCoordinator {
    private val logger = LoggerFactory.getLogger("BlockBard/Coordinator")

    /** Consecutive missing files skipped at song end before going idle. */
    private const val MAX_MISSING_SKIPS = 5

    /** Set by the last opened screen; kept after it closes. Null until the screen first opens. */
    @Volatile
    var host: PlaybackHost? = null

    @Volatile
    private var songEnded: Boolean = false

    // Client thread only. An armed advance waits for the tuner and the players to be idle.
    private var advanceArmed: Boolean = false
    private var advanceFile: File? = null // null while armed means "replay the current song"

    private val queue get() = ClientPlayQueue.queue

    /** Wires the playback-thread callbacks once at client init. */
    fun install() {
        val ended: () -> Unit = {
            songEnded = true
            logger.info("onFinished: song ended, next song is chosen on the client thread")
        }
        MidiFilePlayer.onFinished = ended
        NbsPlayer.onFinished = ended
    }

    fun isTuning(): Boolean = host?.isTuning == true

    /** Client thread, every tick. */
    fun tick() {
        if (songEnded) {
            songEnded = false
            handleSongEnded()
        }
        if (!advanceArmed) return

        val h = host
        if (h == null) {
            cancelAdvance()
            queue.stop()
            return
        }
        // Wait for a tune-then-play chain and for the previous song to be fully idle.
        if (h.isTuning || MidiFilePlayer.isActive() || NbsPlayer.isPlaying) return

        advanceArmed = false
        val next = advanceFile
        advanceFile = null
        if (next != null) {
            logger.info("auto-advance: selecting ${next.name} and retuning")
            h.beginAdvance(next)
        } else {
            logger.info("auto-advance: replaying the current file")
            h.replayCurrent()
        }
    }

    /**
     * Asks the queue what follows the song that just ended (queued songs first, then Repeat ONE,
     * then the playlist; see PlayQueue) and arms the advance that [tick] acts on. A song that no
     * longer exists is skipped, up to a small cap.
     */
    private fun handleSongEnded() {
        val h = host
        if (h == null) {
            // Nothing can tune or start a song without a host.
            logger.info("handleSongEnded: no host, going idle")
            queue.stop()
            return
        }
        val ended = queue.nowPlaying
        var next: File? = null
        var attempts = 0
        while (next == null && attempts < MAX_MISSING_SKIPS) {
            attempts++
            val id = queue.onSongFinished() ?: break
            val candidate = h.fileForId(id)
            if (candidate.isFile) {
                next = candidate
            } else {
                logger.warn("handleSongEnded: $id no longer exists, skipping")
            }
        }
        if (next == null) {
            queue.stop()
            logger.info("handleSongEnded: nothing to play next, going idle")
            h.onQueueChanged()
            return
        }
        // The same song again (Repeat ONE, or a one-song playlist) needs no retune.
        advanceFile = if (songIdOf(next) == ended) null else next
        advanceArmed = true
        logger.info("handleSongEnded: next=${next.name} replay=${advanceFile == null}")
        h.onQueueChanged()
    }

    private fun cancelAdvance() {
        songEnded = false
        advanceArmed = false
        advanceFile = null
    }

    /** Stop button and `/bb stop`: stop everything and forget what was about to start. */
    fun stop() {
        cancelAdvance()
        host?.abortTuning()
        MidiFilePlayer.stop()
        NbsPlayer.stop()
        queue.stop()
    }

    /**
     * `/bb play` calls this before it starts a song directly. Returns a refusal message while a
     * tuner is running (starting playback mid-tune would fight the tuner for rotation and
     * block interactions), or null when the caller may go ahead. On success the song is
     * registered with the queue so its end, Next and Prev follow the play order.
     */
    fun beginExternalPlay(file: File): String? {
        if (isTuning()) {
            return "Tuning is in progress. Wait for it to finish, or stop it first."
        }
        cancelAdvance()
        val id = songIdOf(file)
        if (queue.nowPlaying != id && queue.startFromContext(id) == null) {
            // Not in the playlist (the screen has not listed the library yet): do not leave a
            // stale now-playing song behind.
            queue.stop()
        }
        return null
    }

    /** Called once a song has actually started. Remembers it for the next session. */
    fun onPlaybackStarted(file: File) {
        val id = songIdOf(file)
        val cfg = ConfigManager.config
        if (cfg.lastPlayedTrack != id) {
            cfg.lastPlayedTrack = id
            ConfigManager.save()
        }
    }
}
