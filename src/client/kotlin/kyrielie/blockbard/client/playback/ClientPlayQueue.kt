package kyrielie.blockbard.client.playback

import kyrielie.blockbard.client.config.BlockBardConfig
import kyrielie.blockbard.playback.PlayQueue
import kyrielie.blockbard.playback.SongId
import java.io.File

/**
 * Maps a library file to its [SongId]. The single mapping point: until the folder-playlist pass
 * the id is the file name.
 */
fun songIdOf(file: File): SongId = file.name

/**
 * The one live [PlayQueue] for the client session. It outlives [kyrielie.blockbard.client.gui.MainScreen],
 * which is recreated each time the GUI opens, so queue contents, order and history survive
 * closing the screen. The playback coordinator takes ownership of this in a later pass.
 *
 * Client thread only: [PlayQueue] is not thread-safe. Playback-thread callbacks must set a flag
 * and let a client-thread tick touch the queue.
 */
object ClientPlayQueue {
    val queue: PlayQueue = PlayQueue()

    /**
     * Pushes the persisted repeat and shuffle settings into the live queue. Safe to call after
     * any settings change: [PlayQueue.setShuffle] does nothing when the value is unchanged, so a
     * save that did not touch shuffle does not reorder the queue.
     */
    fun applyConfig(config: BlockBardConfig) {
        queue.repeat = config.repeatModeEnum()
        queue.setShuffle(config.shuffle)
    }
}
