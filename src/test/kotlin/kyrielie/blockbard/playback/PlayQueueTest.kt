package kyrielie.blockbard.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PlayQueueTest {

    private val abcde = listOf("a", "b", "c", "d", "e")

    private fun queue(
        songs: List<String> = abcde,
        mode: RepeatMode = RepeatMode.OFF,
        shuffle: Boolean = false,
        seed: Int = 1,
    ): PlayQueue {
        val q = PlayQueue(Random(seed))
        q.setContext(songs)
        q.repeat = mode
        q.setShuffle(shuffle)
        return q
    }

    // ---- precedence -----------------------------------------------------------------------

    @Test
    fun userQueueBeatsContextInEveryMode() {
        for (mode in RepeatMode.values()) for (shuffle in listOf(false, true)) {
            val q = queue(mode = mode, shuffle = shuffle)
            assertEquals("a", q.startFromContext("a"))
            q.enqueueLast("x")
            assertEquals("x", q.onSongFinished(), "$mode shuffle=$shuffle")
            assertEquals(QueueSource.USER, q.nowPlayingSource)
        }
    }

    @Test
    fun contextResumesAfterUserQueueDrains() {
        for (mode in listOf(RepeatMode.OFF, RepeatMode.ALL)) for (shuffle in listOf(false, true)) {
            val q = queue(mode = mode, shuffle = shuffle)
            q.startFromContext("a")
            val expected = q.contextOrder[1]
            q.enqueueLast("x")
            assertEquals("x", q.onSongFinished())
            assertEquals(expected, q.onSongFinished(), "$mode shuffle=$shuffle")
            assertEquals(QueueSource.CONTEXT, q.nowPlayingSource)
        }
    }

    // ---- repeat ---------------------------------------------------------------------------

    @Test
    fun repeatOneRepeatsWithEmptyQueue() {
        val q = queue(listOf("a", "b"), RepeatMode.ONE)
        q.startFromContext("a")
        repeat(3) { assertEquals("a", q.onSongFinished()) }
    }

    @Test
    fun repeatOnePlaysQueuedFirstThenRepeatsLastQueued() {
        val q = queue(listOf("a", "b", "c"), RepeatMode.ONE)
        q.startFromContext("a")
        q.enqueueLast("x")
        assertEquals("x", q.onSongFinished())
        assertEquals("x", q.onSongFinished())
        assertEquals("x", q.onSongFinished())
        // The cursor never moved: leaving Repeat ONE resumes the context after "a".
        q.repeat = RepeatMode.OFF
        assertEquals("b", q.onSongFinished())
    }

    @Test
    fun skipNextUnderRepeatOneMovesForwardAndWraps() {
        val q = queue(listOf("a", "b", "c"), RepeatMode.ONE)
        q.startFromContext("a")
        assertEquals("b", q.skipNext())
        assertEquals("c", q.skipNext())
        assertEquals("a", q.skipNext())
    }

    @Test
    fun repeatOffStopsAtEndAndSkipNextLeavesStateAlone() {
        val q = queue(listOf("a", "b"))
        q.startFromContext("b")
        assertNull(q.onSongFinished())
        assertNull(q.skipNext())
        assertEquals("b", q.nowPlaying)
        assertEquals(1, q.contextCursor)
    }

    @Test
    fun repeatAllWrapsWithoutShuffle() {
        val q = queue(listOf("a", "b"), RepeatMode.ALL)
        q.startFromContext("b")
        assertEquals("a", q.onSongFinished())
        assertEquals("b", q.onSongFinished())
    }

    @Test
    fun singleSongContext() {
        for (mode in listOf(RepeatMode.ALL, RepeatMode.ONE)) for (shuffle in listOf(false, true)) {
            val q = queue(listOf("a"), mode, shuffle)
            q.startFromContext("a")
            repeat(5) { assertEquals("a", q.onSongFinished(), "$mode shuffle=$shuffle") }
        }
        val off = queue(listOf("a"))
        off.startFromContext("a")
        assertNull(off.onSongFinished())
    }

    // ---- shuffle --------------------------------------------------------------------------

    @Test
    fun noRepeatWithinShuffleCycleAndWrapStartsDifferently() {
        val songs = (0 until 10).map { "s$it" }
        for (seed in 0 until 50) {
            val q = queue(songs, RepeatMode.ALL, true, seed)
            val first = ArrayList<String>()
            first.add(q.startFromContext("s0")!!)
            repeat(9) { first.add(q.onSongFinished()!!) }
            assertEquals(10, first.toSet().size, "cycle 1 seed $seed")
            val second = ArrayList<String>()
            repeat(10) { second.add(q.onSongFinished()!!) }
            assertEquals(10, second.toSet().size, "cycle 2 seed $seed")
            assertTrue(second[0] != first.last(), "wrap repeated the last song, seed $seed")
        }
    }

    @Test
    fun wrapOfTwoSongsAlwaysAlternates() {
        for (seed in 0 until 100) {
            val q = queue(listOf("a", "b"), RepeatMode.ALL, true, seed)
            q.startFromContext("a")
            assertEquals("b", q.onSongFinished())
            assertEquals("a", q.onSongFinished(), "seed $seed")
        }
    }

    @Test
    fun startFromContextUnderShuffleMakesChosenSongFirst() {
        for (seed in 0 until 20) {
            val q = queue(shuffle = true, seed = seed)
            assertEquals("c", q.startFromContext("c"))
            assertEquals("c", q.contextOrder[0])
            assertEquals(0, q.contextCursor)
            assertEquals(abcde.toSet(), q.contextOrder.toSet())
            assertEquals(5, q.contextOrder.size)
        }
    }

    @Test
    fun startFromContextWithUnknownIdChangesNothing() {
        val q = queue()
        q.startFromContext("b")
        assertNull(q.startFromContext("zzz"))
        assertEquals("b", q.nowPlaying)
        assertEquals(1, q.contextCursor)
    }

    @Test
    fun shuffleOnThenOffRestoresSortedOrderWithCursorOnCurrent() {
        val q = queue()
        q.startFromContext("c")
        q.setShuffle(true)
        assertEquals("c", q.contextOrder[0])
        assertEquals(0, q.contextCursor)
        assertEquals(abcde.toSet(), q.contextOrder.toSet())
        q.setShuffle(false)
        assertEquals(abcde, q.contextOrder)
        assertEquals(2, q.contextCursor)
        assertEquals("d", q.onSongFinished())
    }

    @Test
    fun reshuffleKeepsCurrentFirstAndIsNoOpWithoutShuffle() {
        val plain = queue()
        plain.startFromContext("b")
        plain.reshuffle()
        assertEquals(abcde, plain.contextOrder)

        val q = queue(shuffle = true, seed = 7)
        q.startFromContext("b")
        q.reshuffle()
        assertEquals("b", q.contextOrder[0])
        assertEquals(0, q.contextCursor)
        assertEquals(abcde.toSet(), q.contextOrder.toSet())
    }

    @Test
    fun orderOnlyChangesOnExplicitEvents() {
        val q = queue(shuffle = true, seed = 3)
        q.startFromContext("a")
        val snapshot = q.contextOrder
        q.enqueueLast("x"); q.enqueueNext("y"); q.enqueueLast("z")
        q.moveUser(0, 2); q.moveUserToFront(1)
        q.promote(0)
        q.upcoming(50)
        q.removeUser(0)
        q.startFromUser(0)
        q.skipPrev()
        q.onSongFinished()
        assertEquals(snapshot, q.contextOrder)
    }

    @Test
    fun sameSeedGivesSameSequence() {
        fun run(): List<String?> {
            val q = queue(shuffle = true, mode = RepeatMode.ALL, seed = 99)
            q.startFromContext("a")
            return (0 until 12).map { q.onSongFinished() }
        }
        assertEquals(run(), run())
    }

    // ---- startFromUser and interjection ---------------------------------------------------

    @Test
    fun startFromUserDoesNotMoveCursor() {
        val q = queue(listOf("a", "b", "c", "d"))
        q.startFromContext("b")
        q.enqueueLast("x")
        q.enqueueLast("y")
        assertEquals("y", q.startFromUser(1))
        assertEquals(1, q.contextCursor)
        assertEquals(listOf("a", "b", "c", "d"), q.contextOrder)
        assertEquals("x", q.onSongFinished())
        assertEquals("c", q.onSongFinished())
    }

    @Test
    fun startFromUserOutOfRangeReturnsNull() {
        val q = queue()
        q.enqueueLast("x")
        assertNull(q.startFromUser(-1))
        assertNull(q.startFromUser(1))
        assertEquals(listOf("x"), q.userQueue)
    }

    // ---- setContext and refreshContext ----------------------------------------------------

    @Test
    fun setContextWithoutCurrentSongStartsAtIndexZero() {
        val q = queue(listOf("a", "b"))
        q.startFromContext("a")
        q.setContext(listOf("x", "y", "z"))
        assertEquals(-1, q.contextCursor)
        assertEquals("x", q.onSongFinished())
    }

    @Test
    fun setContextWithoutCurrentSongUnderShuffleStillUsesNewList() {
        val q = queue(listOf("a", "b"), shuffle = true)
        q.startFromContext("a")
        q.setContext(listOf("x", "y", "z"))
        assertEquals(-1, q.contextCursor)
        assertEquals(setOf("x", "y", "z"), q.contextOrder.toSet())
        assertTrue(q.onSongFinished() in setOf("x", "y", "z"))
    }

    @Test
    fun setContextContainingCurrentSongPointsCursorAtIt() {
        val q = queue(listOf("a", "b", "c"))
        q.startFromContext("b")
        q.setContext(listOf("b", "c", "d"))
        assertEquals(0, q.contextCursor)
        assertEquals("c", q.onSongFinished())

        val s = queue(listOf("a", "b", "c"), shuffle = true)
        s.startFromContext("b")
        s.setContext(listOf("b", "c", "d"))
        assertEquals("b", s.contextOrder[0])
        assertEquals(0, s.contextCursor)
    }

    @Test
    fun setContextDropsDuplicateIds() {
        val q = queue(listOf("a", "b", "a", "c", "b"))
        assertEquals(listOf("a", "b", "c"), q.contextOrder)
    }

    @Test
    fun refreshContextWithShuffleKeepsSurvivorsAndCursor() {
        for (seed in 0 until 20) {
            val q = queue(listOf("a", "b", "c", "d", "e", "f"), shuffle = true, seed = seed)
            q.startFromContext("c")
            q.onSongFinished()
            q.onSongFinished()
            val before = q.contextOrder
            val current = q.nowPlaying!!
            assertEquals(2, q.contextCursor)
            val removed = setOf(before[0], before[4])
            val survivors = before.filter { it !in removed }
            q.refreshContext(survivors + listOf("n1", "n2"))

            val after = q.contextOrder
            assertEquals(survivors, after.take(survivors.size), "seed $seed")
            assertEquals(setOf("n1", "n2"), after.drop(survivors.size).toSet())
            assertEquals(current, after[q.contextCursor])
            assertEquals(current, q.nowPlaying)
        }
    }

    @Test
    fun refreshContextWithoutShuffleUsesNewSortedOrder() {
        val q = queue(listOf("a", "c", "e"))
        q.startFromContext("c")
        q.refreshContext(listOf("a", "b", "c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), q.contextOrder)
        assertEquals(2, q.contextCursor)
        assertEquals("d", q.onSongFinished())
    }

    @Test
    fun refreshContextWhenCurrentSongRemovedResumesAfterIt() {
        val q = queue(listOf("a", "b", "c"))
        q.startFromContext("b")
        q.refreshContext(listOf("a", "c"))
        assertEquals(0, q.contextCursor)
        assertEquals("c", q.onSongFinished())

        val s = queue(listOf("a", "b", "c", "d"), shuffle = true, seed = 5)
        s.startFromContext("b")
        val nextBefore = s.contextOrder[1]
        s.refreshContext(listOf("a", "c", "d"))
        assertEquals(nextBefore, s.onSongFinished())
    }

    // ---- history --------------------------------------------------------------------------

    @Test
    fun skipPrevWalksHistoryBackwards() {
        val q = queue(listOf("a", "b", "c", "d"))
        q.startFromContext("a")
        assertEquals("b", q.onSongFinished())
        assertEquals("c", q.onSongFinished())
        assertEquals("b", q.skipPrev())
        assertEquals(1, q.contextCursor)
        assertEquals("a", q.skipPrev())
        assertNull(q.skipPrev())
        assertEquals("a", q.nowPlaying)
        assertEquals("b", q.onSongFinished())
    }

    @Test
    fun skipPrevHistoryIsCapped() {
        val songs = (0 until 100).map { "s$it" }
        val q = queue(songs)
        q.startFromContext("s0")
        repeat(99) { q.onSongFinished() }
        var count = 0
        while (q.skipPrev() != null) count++
        assertEquals(PlayQueue.HISTORY_CAP, count)
    }

    @Test
    fun skipPrevToQueuedSongRestoresWhereTheContextResumes() {
        val q = queue(listOf("a", "b", "c", "d"))
        q.startFromContext("b")
        q.enqueueLast("x")
        assertEquals("x", q.onSongFinished())
        assertEquals("c", q.onSongFinished())
        assertEquals("x", q.skipPrev())
        assertEquals(QueueSource.USER, q.nowPlayingSource)
        // The cursor is back where it was while "x" played, so forward replays "c", not "d".
        assertEquals(1, q.contextCursor)
        assertEquals("c", q.onSongFinished())
        assertEquals("d", q.onSongFinished())
    }

    @Test
    fun skipPrevWithAnAnchorThatLeftTheContextKeepsTheCursor() {
        val q = queue(listOf("a", "b", "c", "d"))
        q.startFromContext("b")
        q.enqueueLast("x")
        q.onSongFinished()
        q.onSongFinished()
        q.setContext(listOf("c", "d", "e"))
        assertEquals("x", q.skipPrev())
        assertEquals(0, q.contextCursor)
    }

    // ---- empty and stop -------------------------------------------------------------------

    @Test
    fun emptyEverythingReturnsNullWithoutThrowing() {
        for (mode in RepeatMode.values()) for (shuffle in listOf(false, true)) {
            val q = PlayQueue(Random(1))
            q.repeat = mode
            q.setShuffle(shuffle)
            assertNull(q.onSongFinished())
            assertNull(q.skipNext())
            assertNull(q.skipPrev())
            assertNull(q.startFromContext("a"))
            assertNull(q.startFromUser(0))
            assertNull(q.removeUser(0))
            assertFalse(q.moveUser(0, 0))
            assertFalse(q.moveUserToFront(0))
            assertFalse(q.promote(0))
            assertTrue(q.upcoming(10).isEmpty())
            q.reshuffle()
            q.refreshContext(emptyList())
            q.setContext(emptyList())
            q.clearUser()
            q.stop()
            assertNull(q.nowPlaying)
        }
    }

    @Test
    fun userQueuePlaysEvenWithEmptyContext() {
        val q = PlayQueue(Random(1))
        q.enqueueLast("x")
        assertEquals("x", q.onSongFinished())
        assertNull(q.onSongFinished())
    }

    @Test
    fun stopClearsNowPlayingButKeepsPosition() {
        val q = queue()
        q.startFromContext("b")
        q.stop()
        assertNull(q.nowPlaying)
        assertNull(q.nowPlayingSource)
        assertEquals(1, q.contextCursor)
        assertEquals("c", q.onSongFinished())
    }

    // ---- user queue editing ---------------------------------------------------------------

    @Test
    fun userQueueEditing() {
        val q = queue()
        q.enqueueLast("x")
        q.enqueueLast("x")
        assertEquals(listOf("x", "x"), q.userQueue)
        q.enqueueNext("y")
        assertEquals(listOf("y", "x", "x"), q.userQueue)

        assertFalse(q.moveUser(-1, 0))
        assertFalse(q.moveUser(0, 3))
        assertTrue(q.moveUser(1, 1))
        assertTrue(q.moveUser(0, 2))
        assertEquals(listOf("x", "x", "y"), q.userQueue)
        assertTrue(q.moveUserToFront(2))
        assertEquals(listOf("y", "x", "x"), q.userQueue)

        assertNull(q.removeUser(5))
        assertEquals("y", q.removeUser(0))
        q.clearUser()
        assertTrue(q.userQueue.isEmpty())
    }

    @Test
    fun userQueueSnapshotDoesNotChangeWhenTheQueueDoes() {
        val q = queue()
        q.enqueueLast("x")
        val snapshot = q.userQueue
        q.enqueueLast("y")
        q.removeUser(0)
        assertEquals(listOf("x"), snapshot)
        assertEquals(listOf("y"), q.userQueue)
    }

    @Test
    fun promoteCopiesAnUpcomingContextRow() {
        val q = queue(listOf("a", "b", "c", "d"))
        q.startFromContext("a")
        assertTrue(q.promote(0))
        assertTrue(q.promote(2))
        assertEquals(listOf("b", "d"), q.userQueue)
        assertFalse(q.promote(3))
        assertFalse(q.promote(-1))
        assertEquals("b", q.onSongFinished())
        assertEquals("d", q.onSongFinished())
        // The context row was copied, not moved: the context still resumes at "b".
        assertEquals("b", q.onSongFinished())
        assertEquals(QueueSource.CONTEXT, q.nowPlayingSource)
    }

    @Test
    fun promoteBeforeAnythingPlayedUsesTheStartOfTheContext() {
        val q = queue(listOf("a", "b"))
        assertTrue(q.promote(0))
        assertEquals(listOf("a"), q.userQueue)
    }

    // ---- upcoming and flags ---------------------------------------------------------------

    @Test
    fun upcomingListsUserRowsThenContextRows() {
        val q = queue(listOf("a", "b", "c"))
        q.startFromContext("a")
        q.enqueueLast("x")
        assertEquals(
            listOf(
                QueueRow("x", QueueSource.USER),
                QueueRow("b", QueueSource.CONTEXT),
                QueueRow("c", QueueSource.CONTEXT),
            ),
            q.upcoming(10),
        )
        assertEquals(listOf(QueueRow("x", QueueSource.USER), QueueRow("b", QueueSource.CONTEXT)), q.upcoming(2))
        assertEquals(listOf(QueueRow("x", QueueSource.USER)), q.upcoming(1))
        assertTrue(q.upcoming(0).isEmpty())
        assertTrue(q.upcoming(-3).isEmpty())
    }

    @Test
    fun upcomingBeforeAnythingPlayedStartsAtIndexZero() {
        val q = queue(listOf("a", "b"))
        assertEquals(listOf("a", "b"), q.upcoming(10).map { it.id })
    }

    @Test
    fun upcomingDoesNotInventThePostWrapOrder() {
        val q = queue(listOf("a", "b"), RepeatMode.ALL, shuffle = true)
        q.startFromContext("a")
        q.onSongFinished()
        assertTrue(q.upcoming(10).isEmpty())
        assertTrue(q.wrapsAtEnd)
    }

    @Test
    fun wrapAndRepeatFlags() {
        val q = queue(listOf("a", "b"))
        q.startFromContext("a")

        q.repeat = RepeatMode.OFF
        assertFalse(q.wrapsAtEnd); assertFalse(q.repeatingCurrent)

        q.repeat = RepeatMode.ALL
        assertTrue(q.wrapsAtEnd); assertFalse(q.repeatingCurrent)

        q.repeat = RepeatMode.ONE
        assertFalse(q.wrapsAtEnd); assertTrue(q.repeatingCurrent)

        q.enqueueLast("x")
        assertFalse(q.repeatingCurrent)
        q.clearUser()
        assertTrue(q.repeatingCurrent)

        q.stop()
        assertFalse(q.repeatingCurrent)
    }

    // ---- startFromUpcoming ----------------------------------------------------------------

    @Test
    fun startFromUpcomingJumpsWithoutReordering() {
        for (shuffle in listOf(false, true)) {
            val q = queue(shuffle = shuffle)
            q.startFromContext("a")
            val before = q.contextOrder
            val target = before[q.contextCursor + 1 + 2]

            assertEquals(target, q.startFromUpcoming(2), "shuffle=$shuffle")
            assertEquals(before, q.contextOrder, "shuffle=$shuffle")
            assertEquals(before.indexOf(target), q.contextCursor, "shuffle=$shuffle")
            assertEquals(QueueSource.CONTEXT, q.nowPlayingSource)
            assertEquals(target, q.nowPlaying)
            // The song after the jump target is still next.
            assertEquals(before[before.indexOf(target) + 1], q.onSongFinished(), "shuffle=$shuffle")
        }
    }

    @Test
    fun startFromUpcomingOutOfRangeChangesNothing() {
        val q = queue()
        q.startFromContext("a")
        val cursorBefore = q.contextCursor

        assertNull(q.startFromUpcoming(-1))
        assertNull(q.startFromUpcoming(abcde.size))
        assertEquals("a", q.nowPlaying)
        assertEquals(cursorBefore, q.contextCursor)
    }

    @Test
    fun startFromUpcomingLeavesUserQueueAlone() {
        val q = queue()
        q.startFromContext("a")
        q.enqueueLast("x")

        assertEquals("c", q.startFromUpcoming(1))
        assertEquals(listOf("x"), q.userQueue)
        assertEquals("x", q.onSongFinished())
        assertEquals("d", q.onSongFinished())
    }
}
