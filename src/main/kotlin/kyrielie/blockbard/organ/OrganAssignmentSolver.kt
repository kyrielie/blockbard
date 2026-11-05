package kyrielie.blockbard.organ

import kyrielie.blockbard.util.clicksNeeded

/**
 * Pure minimum-cost organ assignment solver using the Kuhn-Munkres (Hungarian) algorithm.
 *
 * Models noteblock assignment as a bipartite matching problem:
 * - Left partition: requested notes (N), weighted by note frequency.
 * - Right partition: reachable physical blocks (M) + N dummy unassigned slots.
 *
 * Edge costs are determined by forward tuning clicks (0..24), timbre preservation
 * (native > instrument shift > octave shift), avoidance of untunable blocks, and distance.
 *
 * Prominent melody lines (high note frequency) receive a proportionally larger missing-note
 * penalty, ensuring they are guaranteed physical noteblocks over infrequent background notes
 * when blocks are scarce.
 */
object OrganAssignmentSolver {

    const val BASE_MISSING_PENALTY = 1_000_000L
    const val UNTUNABLE_PENALTY = 100_000L

    data class BlockSpec<T>(
        val id: T,
        val instrumentName: String,
        val midiBase: Int,
        val currentNoteIndex: Int,
        val distanceFromPlayer: Double = 0.0,
        val isUntunable: Boolean = false
    )

    data class NoteSpec<K>(
        val key: K,
        val midiNote: Int,
        val instrumentName: String? = null,
        val weight: Int = 1
    )

    data class Candidate(
        val shift: Int,
        val quality: Int, // 0 = native authored, 1 = instrument shift, 2 = 1-octave, 3 = 2-octave
        val clicks: Int
    )

    data class SolvedAssignment<K, T>(
        val matched: Map<K, T>,
        val unplayable: List<K>,
        val shifts: Map<K, Int>
    )

    /**
     * Evaluates whether a block can play a note under the given shift mode and returns the
     * candidate shift, quality tier, and tuning clicks needed.
     */
    fun evaluateCandidate(
        midiNote: Int,
        requestedInstrument: String?,
        blockInstrument: String,
        blockMidiBase: Int,
        blockCurrentNoteIndex: Int,
        shiftMode: ShiftMode,
        maxOctaveShift: Int
    ): Candidate? {
        val sameInstrument = requestedInstrument == null || requestedInstrument.equals(blockInstrument, ignoreCase = true)

        // 1. Native match on authored instrument (Quality 0)
        if (sameInstrument && midiNote in blockMidiBase..(blockMidiBase + 24)) {
            val target = midiNote - blockMidiBase
            val clicks = clicksNeeded(blockCurrentNoteIndex, target)
            return Candidate(shift = 0, quality = 0, clicks = clicks)
        }

        // 2. Instrument shift (Quality 1): different instrument, exact pitch (0 shift)
        if (shiftMode == ShiftMode.INSTRUMENT_SHIFT || shiftMode == ShiftMode.BEST_EFFORT || requestedInstrument == null) {
            if (!sameInstrument && midiNote in blockMidiBase..(blockMidiBase + 24)) {
                val target = midiNote - blockMidiBase
                val clicks = clicksNeeded(blockCurrentNoteIndex, target)
                return Candidate(shift = 0, quality = 1, clicks = clicks)
            }
        }

        // 3. Octave shift: transpose by ±12 or ±24 semitones
        if (shiftMode == ShiftMode.OCTAVE_SHIFT || shiftMode == ShiftMode.BEST_EFFORT) {
            val shifts1 = if (maxOctaveShift >= 1) listOf(-12, 12) else emptyList()
            var best1: Candidate? = null
            for (shift in shifts1) {
                val shifted = midiNote + shift
                if (shifted in blockMidiBase..(blockMidiBase + 24)) {
                    val target = shifted - blockMidiBase
                    val clicks = clicksNeeded(blockCurrentNoteIndex, target)
                    val cand = Candidate(shift = shift, quality = 2, clicks = clicks)
                    if (best1 == null || cand.clicks < best1.clicks) {
                        best1 = cand
                    }
                }
            }
            if (best1 != null) return best1

            val shifts2 = if (maxOctaveShift >= 2) listOf(-24, 24) else emptyList()
            var best2: Candidate? = null
            for (shift in shifts2) {
                val shifted = midiNote + shift
                if (shifted in blockMidiBase..(blockMidiBase + 24)) {
                    val target = shifted - blockMidiBase
                    val clicks = clicksNeeded(blockCurrentNoteIndex, target)
                    val cand = Candidate(shift = shift, quality = 3, clicks = clicks)
                    if (best2 == null || cand.clicks < best2.clicks) {
                        best2 = cand
                    }
                }
            }
            if (best2 != null) return best2
        }

        return null
    }

    fun computeEdgeCost(
        candidate: Candidate,
        isUntunable: Boolean,
        distanceFromPlayer: Double
    ): Long {
        val untunableCost = if (isUntunable) UNTUNABLE_PENALTY else 0L
        val qualityCost = candidate.quality * 1_000L
        val clickCost = candidate.clicks * 20L
        val distanceCost = (distanceFromPlayer * 2.0).toLong().coerceIn(0L, 50L)
        return untunableCost + qualityCost + clickCost + distanceCost
    }

    fun computeMissingPenalty(weight: Int): Long {
        val clampedWeight = weight.coerceAtLeast(1)
        return BASE_MISSING_PENALTY * clampedWeight
    }

    /**
     * Solves optimal note-to-block assignment minimizing total retuning clicks while
     * penalizing unassigned notes proportionally to their usage frequency.
     */
    fun <K, T> solve(
        notes: List<NoteSpec<K>>,
        blocks: List<BlockSpec<T>>,
        shiftMode: ShiftMode = ShiftMode.BEST_EFFORT,
        maxOctaveShift: Int = 1
    ): SolvedAssignment<K, T> {
        val n = notes.size
        val m = blocks.size

        if (n == 0) {
            return SolvedAssignment(emptyMap(), emptyList(), emptyMap())
        }
        if (m == 0) {
            return SolvedAssignment(emptyMap(), notes.map { it.key }, emptyMap())
        }

        val totalCols = m + n
        val costMatrix = Array(n) { LongArray(totalCols) { HungarianAlgorithm.INF } }
        val candidateMatrix = Array(n) { arrayOfNulls<Candidate>(m) }

        for (i in 0 until n) {
            val note = notes[i]
            // Dummy unassigned column for this note
            costMatrix[i][m + i] = computeMissingPenalty(note.weight)

            for (j in 0 until m) {
                val block = blocks[j]
                val candidate = evaluateCandidate(
                    midiNote = note.midiNote,
                    requestedInstrument = note.instrumentName,
                    blockInstrument = block.instrumentName,
                    blockMidiBase = block.midiBase,
                    blockCurrentNoteIndex = block.currentNoteIndex,
                    shiftMode = shiftMode,
                    maxOctaveShift = maxOctaveShift
                )
                if (candidate != null) {
                    candidateMatrix[i][j] = candidate
                    costMatrix[i][j] = computeEdgeCost(candidate, block.isUntunable, block.distanceFromPlayer)
                }
            }
        }

        val solver = HungarianAlgorithm(n, totalCols, costMatrix)
        val matching = solver.solve()

        val matched = mutableMapOf<K, T>()
        val unplayable = mutableListOf<K>()
        val shifts = mutableMapOf<K, Int>()

        for (i in 0 until n) {
            val note = notes[i]
            val matchedCol = matching[i]
            if (matchedCol in 0 until m) {
                val block = blocks[matchedCol]
                val candidate = candidateMatrix[i][matchedCol]
                    ?: error("Matched column $matchedCol had no valid candidate for note $i")
                matched[note.key] = block.id
                shifts[note.key] = candidate.shift
            } else {
                unplayable.add(note.key)
            }
        }

        return SolvedAssignment(matched, unplayable, shifts)
    }
}
