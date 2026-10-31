package kyrielie.blockbard.client.playback

import java.io.File

/**
 * In-memory active user play queue.
 *
 * Tracks manually enqueued by the player (e.g. via the "+Q" button in the library)
 * take strict precedence over the automatic loop/shuffle engine. When playback finishes
 * or advances, [popNext] is queried before falling back to directory order or shuffle.
 */
object UserPlayQueue {
    private val queue = mutableListOf<File>()

    fun add(file: File) {
        queue.add(file)
    }

    fun addAll(files: Collection<File>) {
        queue.addAll(files)
    }

    fun removeAt(index: Int): File? {
        if (index in 0 until queue.size) {
            return queue.removeAt(index)
        }
        return null
    }

    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex in 0 until queue.size && toIndex in 0 until queue.size && fromIndex != toIndex) {
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex, item)
            return true
        }
        return false
    }

    fun clear() {
        queue.clear()
    }

    fun popNext(): File? {
        if (queue.isNotEmpty()) {
            return queue.removeAt(0)
        }
        return null
    }

    fun peek(): File? = queue.firstOrNull()

    fun getAll(): List<File> = queue.toList()

    fun isNotEmpty(): Boolean = queue.isNotEmpty()
    fun isEmpty(): Boolean = queue.isEmpty()
    val size: Int get() = queue.size
}
