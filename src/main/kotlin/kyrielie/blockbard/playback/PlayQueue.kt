package kyrielie.blockbard.playback

import kotlin.random.Random

/** Stable identifier of a song. Until the folder-playlist pass this is the file name. */
typealias SongId = String

/** Repeat behaviour, orthogonal to shuffle. */
enum class RepeatMode { OFF, ALL, ONE }

/** Where a queue entry comes from. */
enum class QueueSource { USER, CONTEXT }

/** One row of the "up next" list. */
data class QueueRow(val id: SongId, val source: QueueSource)

/**
 * Pure play-order model: a context (a list of song ids, optionally shuffled), a cursor into it,
 * and an explicit user queue that always plays before the context.
 *
 * No Minecraft, `File` or thread types. Not thread-safe by design: only the client thread may
 * touch an instance. The caller supplies the context already in the order it wants for the
 * non-shuffled case (for example sorted by name); duplicate ids are dropped.
 *
 * Rules (see `blockbard-roadmap-plan.md` section 3.2):
 *  - The order is rebuilt only by [setContext], [setShuffle], [reshuffle], [refreshContext] and
 *    [startFromContext] under shuffle. Nothing else reorders it.
 *  - Natural end ([onSongFinished]): user queue, then Repeat ONE replay, then the next context
 *    item (wrapping only under Repeat ALL), else null.
 *  - [skipNext] is the same but treats Repeat ONE as Repeat ALL.
 *  - A song played from the user queue never moves the cursor.
 *  - [startFromUpcoming] jumps the cursor forward to an upcoming context row without reordering.
 *
 * Implementation notes that go beyond the plan's API sketch:
 *  - [stop] clears the now-playing state. A null result from [onSongFinished] or [skipNext] does
 *    not change any state, so the caller decides what a null means and calls [stop] if needed.
 *  - [contextOrder] and [contextCursor] expose the materialised order for the UI and tests.
 *  - History remembers, for each song, the context song the cursor sat on while it played (its
 *    "anchor"; the song itself for context entries). [skipPrev] puts the cursor back on that
 *    anchor, so going back to an earlier queued song and then forward resumes the context where
 *    it was at that time, instead of skipping a song. Matching is by id, so it survives a
 *    reorder; an anchor that is no longer in the order leaves the cursor unchanged.
 */
class PlayQueue(private val random: Random = Random.Default) {

    var repeat: RepeatMode = RepeatMode.OFF

    var shuffle: Boolean = false
        private set

    private var base: List<SongId> = emptyList()
    private var order: MutableList<SongId> = mutableListOf()
    private var cursor: Int = -1
    private var current: SongId? = null
    private var currentSource: QueueSource? = null
    private var currentAnchor: SongId? = null
    private val user = ArrayList<SongId>()

    private data class Played(val id: SongId, val source: QueueSource, val anchor: SongId?)

    private val history = ArrayDeque<Played>()

    val nowPlaying: SongId? get() = current
    val nowPlayingSource: QueueSource? get() = currentSource
    val userQueue: List<SongId> get() = user.toList()

    /** Snapshot of the materialised context order (sorted, or the stored shuffle permutation). */
    val contextOrder: List<SongId> get() = order.toList()

    /** Index in [contextOrder] of the last context song played, or -1. */
    val contextCursor: Int get() = cursor

    /** True when the context loops back to its start at the end (Repeat ALL). */
    val wrapsAtEnd: Boolean get() = repeat == RepeatMode.ALL

    /** True when the current song will simply repeat (Repeat ONE with an empty user queue). */
    val repeatingCurrent: Boolean
        get() = repeat == RepeatMode.ONE && user.isEmpty() && current != null

    // ---- context --------------------------------------------------------------------------

    fun setContext(songs: List<SongId>) {
        base = songs.distinct()
        rebuildOrder()
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        rebuildOrder()
    }

    /** New permutation with the current song first. No effect while shuffle is off. */
    fun reshuffle() {
        if (!shuffle) return
        rebuildOrder()
    }

    /**
     * The library changed on disk. Missing ids leave the order, new ids join it, and the cursor
     * stays on the same song. With shuffle on, survivors keep their relative order and the new
     * ids are shuffled onto the tail; with shuffle off the order is the new sorted list.
     */
    fun refreshContext(songs: List<SongId>) {
        val newBase = songs.distinct()
        val keep = newBase.toHashSet()
        val oldOrder = order
        val oldCursor = cursor
        base = newBase

        if (!shuffle) {
            var anchor: SongId? = null
            var i = oldCursor
            while (i >= 0) {
                if (oldOrder[i] in keep) {
                    anchor = oldOrder[i]
                    break
                }
                i--
            }
            order = newBase.toMutableList()
            cursor = if (anchor != null) order.indexOf(anchor) else -1
            return
        }

        val survivors = oldOrder.filter { it in keep }
        val survivorSet = survivors.toHashSet()
        val survivingUpToCursor =
            if (oldCursor >= 0) oldOrder.subList(0, oldCursor + 1).count { it in keep } else 0
        val added = newBase.filter { it !in survivorSet }.shuffled(random)
        order = (survivors + added).toMutableList()
        cursor = survivingUpToCursor - 1
    }

    // ---- user queue -----------------------------------------------------------------------

    fun enqueueLast(id: SongId) {
        user.add(id)
    }

    fun enqueueNext(id: SongId) {
        user.add(0, id)
    }

    fun moveUser(from: Int, to: Int): Boolean {
        if (from !in user.indices || to !in user.indices) return false
        if (from == to) return true
        user.add(to, user.removeAt(from))
        return true
    }

    fun moveUserToFront(index: Int): Boolean = moveUser(index, 0)

    fun removeUser(index: Int): SongId? = if (index in user.indices) user.removeAt(index) else null

    fun clearUser() {
        user.clear()
    }

    /**
     * Copies an upcoming context row into the user queue (appended last). [contextOffset] 0 is
     * the first upcoming context row. The context itself is not changed.
     */
    fun promote(contextOffset: Int): Boolean {
        if (contextOffset < 0) return false
        val index = cursor + 1 + contextOffset
        if (index >= order.size) return false
        user.add(order[index])
        return true
    }

    // ---- starting and advancing -----------------------------------------------------------

    /**
     * Play [id] from the context (double-click or Play on a library row). Returns null, with no
     * state change, when [id] is not in the context.
     */
    fun startFromContext(id: SongId): SongId? {
        if (id !in base) return null
        if (shuffle) {
            order = permutation(id)
            cursor = 0
        } else {
            cursor = order.indexOf(id)
        }
        begin(id, QueueSource.CONTEXT)
        return id
    }

    /** "Play now" from a user queue row. Removes the entry; cursor and order are untouched. */
    fun startFromUser(index: Int): SongId? {
        if (index !in user.indices) return null
        val id = user.removeAt(index)
        begin(id, QueueSource.USER)
        return id
    }

    /**
     * "Play now" from an upcoming context row ([contextOffset] 0 is the first upcoming context
     * row, as in [promote]). Moves the cursor onto that row without reordering anything, so
     * the songs after it stay in place. Returns null, with no state change, when out of range.
     */
    fun startFromUpcoming(contextOffset: Int): SongId? {
        if (contextOffset < 0) return null
        val index = cursor + 1 + contextOffset
        if (index >= order.size) return null
        cursor = index
        val id = order[index]
        begin(id, QueueSource.CONTEXT)
        return id
    }

    /** The current song ended naturally. Returns the next song to play, or null to stop. */
    fun onSongFinished(): SongId? {
        if (user.isEmpty() && repeat == RepeatMode.ONE) {
            val c = current
            if (c != null) return c
        }
        return nextItem(wrap = repeat == RepeatMode.ALL)
    }

    /** Manual skip forward. Repeat ONE behaves like Repeat ALL here. Null at the end under OFF. */
    fun skipNext(): SongId? = nextItem(wrap = repeat != RepeatMode.OFF)

    /** Manual skip back through the bounded play history. Null when there is none. */
    fun skipPrev(): SongId? {
        val previous = history.removeLastOrNull() ?: return null
        current = previous.id
        currentSource = previous.source
        currentAnchor = previous.anchor
        val anchor = previous.anchor
        if (anchor != null) {
            val index = order.indexOf(anchor)
            if (index >= 0) cursor = index
        }
        return previous.id
    }

    /** Playback stopped. Clears now-playing; cursor, order, queue and history are kept. */
    fun stop() {
        current = null
        currentSource = null
        currentAnchor = null
    }

    // ---- display --------------------------------------------------------------------------

    /**
     * Up to [limit] rows: user queue first, then the context after the cursor. Never invents
     * the order that follows a wrap; see [wrapsAtEnd] and [repeatingCurrent].
     */
    fun upcoming(limit: Int): List<QueueRow> {
        if (limit <= 0) return emptyList()
        val rows = ArrayList<QueueRow>()
        for (id in user) {
            if (rows.size >= limit) return rows
            rows.add(QueueRow(id, QueueSource.USER))
        }
        var i = cursor + 1
        while (i < order.size && rows.size < limit) {
            rows.add(QueueRow(order[i], QueueSource.CONTEXT))
            i++
        }
        return rows
    }

    // ---- internals ------------------------------------------------------------------------

    private fun nextItem(wrap: Boolean): SongId? {
        if (user.isNotEmpty()) {
            val id = user.removeAt(0)
            begin(id, QueueSource.USER)
            return id
        }
        return nextFromContext(wrap)
    }

    private fun nextFromContext(wrap: Boolean): SongId? {
        if (order.isEmpty()) return null
        val next = cursor + 1
        if (next < order.size) {
            cursor = next
            val id = order[next]
            begin(id, QueueSource.CONTEXT)
            return id
        }
        if (!wrap) return null
        if (shuffle) order = wrapPermutation()
        cursor = 0
        val id = order[0]
        begin(id, QueueSource.CONTEXT)
        return id
    }

    /** Fresh permutation for a new cycle; the first song differs from the current one if possible. */
    private fun wrapPermutation(): MutableList<SongId> {
        val out = base.shuffled(random).toMutableList()
        if (out.size > 1 && out[0] == current) {
            val j = 1 + random.nextInt(out.size - 1)
            val tmp = out[0]
            out[0] = out[j]
            out[j] = tmp
        }
        return out
    }

    /** [first] (when it is in the context) followed by the rest shuffled. */
    private fun permutation(first: SongId?): MutableList<SongId> {
        if (first == null || first !in base) return base.shuffled(random).toMutableList()
        val out = ArrayList<SongId>(base.size)
        out.add(first)
        out.addAll(base.filter { it != first }.shuffled(random))
        return out
    }

    /** Rule 8, 9 and 10: rebuild the order around the current song. */
    private fun rebuildOrder() {
        val c = current
        val index = if (c != null) base.indexOf(c) else -1
        if (shuffle) {
            order = permutation(c)
            cursor = if (index >= 0) 0 else -1
        } else {
            order = base.toMutableList()
            cursor = index
        }
    }

    private fun begin(id: SongId, source: QueueSource) {
        val c = current
        val cs = currentSource
        if (c != null && cs != null) {
            history.addLast(Played(c, cs, currentAnchor))
            if (history.size > HISTORY_CAP) history.removeFirst()
        }
        current = id
        currentSource = source
        currentAnchor = order.getOrNull(cursor)
    }

    companion object {
        const val HISTORY_CAP = 50
    }
}
