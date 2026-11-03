package kyrielie.blockbard.organ

/**
 * Decides which tuning failures permanently mark a block untunable in [NoteBlockRegistry].
 *
 * Marks deprioritise a block in later song assignments, so only failures that are deterministic
 * for this block and standing position qualify. Transient ones (the block advanced at some point,
 * or the whole server stopped responding) must not poison later runs.
 */
object TuningFailPolicy {

    /**
     * @param anyBlockConfirmed true when at least one other block advanced during the same run,
     * which shows the server was accepting clicks and a never-responding block is specific to it.
     */
    fun marksUntunable(state: TuningFailState, anyBlockConfirmed: Boolean): Boolean = when (state) {
        TuningFailState.BLOCK_MISSING,
        TuningFailState.OUT_OF_REACH,
        TuningFailState.OCCLUDED,
        TuningFailState.INTERACT_REJECTED -> true
        TuningFailState.UNRESPONSIVE -> anyBlockConfirmed
        TuningFailState.DESYNC -> false
    }
}
