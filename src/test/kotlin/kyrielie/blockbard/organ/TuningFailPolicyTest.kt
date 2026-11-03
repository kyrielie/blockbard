package kyrielie.blockbard.organ

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TuningFailPolicyTest {

    @Test
    fun deterministicFailures_alwaysMarkUntunable() {
        listOf(
            TuningFailState.BLOCK_MISSING, TuningFailState.OUT_OF_REACH,
            TuningFailState.OCCLUDED, TuningFailState.INTERACT_REJECTED
        ).forEach {
            assertTrue(TuningFailPolicy.marksUntunable(it, anyBlockConfirmed = false), it.tag)
            assertTrue(TuningFailPolicy.marksUntunable(it, anyBlockConfirmed = true), it.tag)
        }
    }

    @Test
    fun unresponsive_marksOnlyWhenOtherBlocksRespondedInTheSameRun() {
        assertTrue(TuningFailPolicy.marksUntunable(TuningFailState.UNRESPONSIVE, anyBlockConfirmed = true))
        assertFalse(TuningFailPolicy.marksUntunable(TuningFailState.UNRESPONSIVE, anyBlockConfirmed = false))
    }

    @Test
    fun desync_neverMarksUntunable() {
        assertFalse(TuningFailPolicy.marksUntunable(TuningFailState.DESYNC, anyBlockConfirmed = true))
        assertFalse(TuningFailPolicy.marksUntunable(TuningFailState.DESYNC, anyBlockConfirmed = false))
    }
}
