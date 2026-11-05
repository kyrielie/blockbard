package kyrielie.blockbard.organ

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HungarianAlgorithmTest {

    @Test
    fun emptyMatrix_returnsEmptyMatching() {
        val solver = HungarianAlgorithm(0, 0, emptyArray())
        assertArrayEquals(IntArray(0), solver.solve())
    }

    @Test
    fun singleElement() {
        val cost = arrayOf(longArrayOf(42L))
        val solver = HungarianAlgorithm(1, 1, cost)
        val match = solver.solve()
        assertEquals(0, match[0])
    }

    @Test
    fun square2x2_findsMinimumCost() {
        // [ [10, 1],
        //   [ 5, 2] ]
        // Row 0 -> Col 1 (cost 1), Row 1 -> Col 0 (cost 5): Total = 6
        // vs Row 0 -> Col 0 (10) + Row 1 -> Col 1 (2): Total = 12
        val cost = arrayOf(
            longArrayOf(10L, 1L),
            longArrayOf(5L, 2L)
        )
        val solver = HungarianAlgorithm(2, 2, cost)
        val match = solver.solve()
        assertEquals(1, match[0])
        assertEquals(0, match[1])
    }

    @Test
    fun square3x3_classicAssignment() {
        val cost = arrayOf(
            longArrayOf(15L, 40L, 45L),
            longArrayOf(20L, 60L, 35L),
            longArrayOf(20L, 40L, 25L)
        )
        // Optimal:
        // Row 0 -> Col 0 (15)
        // Row 1 -> Col 2 (35) or Row 2 -> Col 2 (25)
        // Row 0 -> Col 0 (15)
        // Row 1 -> Col 2 (35)
        // Row 2 -> Col 1 (40)
        // Sum = 15 + 35 + 40 = 90
        // Wait, what about:
        // Row 0 -> Col 1 (40), Row 1 -> Col 0 (20), Row 2 -> Col 2 (25): Sum = 85!
        val solver = HungarianAlgorithm(3, 3, cost)
        val match = solver.solve()
        val totalCost = match.indices.sumOf { r -> cost[r][match[r]] }
        assertEquals(85L, totalCost)
        assertEquals(1, match[0])
        assertEquals(0, match[1])
        assertEquals(2, match[2])
    }

    @Test
    fun rectangular_moreColumnsThanRows() {
        // 2 rows, 4 columns
        val cost = arrayOf(
            longArrayOf(10L, 20L, 5L, 30L),
            longArrayOf(15L, 25L, 4L, 2L)
        )
        // Row 0 -> Col 2 (cost 5)
        // Row 1 -> Col 3 (cost 2)
        // Total = 7
        val solver = HungarianAlgorithm(2, 4, cost)
        val match = solver.solve()
        assertEquals(2, match[0])
        assertEquals(3, match[1])
        val total = match.indices.sumOf { r -> cost[r][match[r]] }
        assertEquals(7L, total)
    }

    @Test
    fun greedyFailure_optimalityProof() {
        // Greedy note tuning failure case:
        // Note 0 needs 0 clicks on Block 0, but 1 click on Block 1.
        // Note 1 needs 0 clicks on Block 0, but 24 clicks on Block 1.
        // Greedy by order (if Note 0 chooses first): Note 0 takes Block 0 (0 clicks).
        // Then Note 1 must take Block 1 (24 clicks!). Total = 24 clicks.
        //
        // Optimal: Note 0 takes Block 1 (1 click).
        // Note 1 takes Block 0 (0 clicks).
        // Total clicks = 1 click!
        val cost = arrayOf(
            longArrayOf(0L, 1L),
            longArrayOf(0L, 24L)
        )
        val solver = HungarianAlgorithm(2, 2, cost)
        val match = solver.solve()
        assertEquals(1, match[0])
        assertEquals(0, match[1])
        val total = match.indices.sumOf { r -> cost[r][match[r]] }
        assertEquals(1L, total)
    }

    @Test
    fun dummyColumnAssignment_whenRealColumnsAreExhaustedOrInferior() {
        // 3 notes, 1 real block (Col 0), 3 dummy blocks (Col 1, 2, 3)
        // Note 0 (melody, weight 100): missing penalty = 1,000,000
        // Note 1 (background, weight 1): missing penalty = 10,000
        // Real block col 0 takes 5 clicks (cost 100) for both notes.
        val inf = HungarianAlgorithm.INF
        val cost = arrayOf(
            // Col 0 (real), Col 1 (dummy 0), Col 2 (dummy 1)
            longArrayOf(100L, 1_000_000L, inf),
            longArrayOf(100L, inf, 10_000L)
        )
        val solver = HungarianAlgorithm(2, 3, cost)
        val match = solver.solve()
        // Note 0 MUST be assigned the real block (col 0) because missing penalty 1,000,000 is huge!
        // Note 1 gets its dummy block (col 2).
        assertEquals(0, match[0])
        assertEquals(2, match[1])
    }

    @Test
    fun performanceStressTest_dense100x200() {
        val n = 100
        val m = 200
        val rand = Random(42)
        val cost = Array(n) {
            LongArray(m) { rand.nextLong(0, 10_000) }
        }

        val start = System.currentTimeMillis()
        val solver = HungarianAlgorithm(n, m, cost)
        val match = solver.solve()
        val elapsed = System.currentTimeMillis() - start

        assertEquals(n, match.size)
        val matchedCols = match.toSet()
        assertEquals(n, matchedCols.size) // All assigned to distinct columns
        assertTrue(elapsed < 200, "100x200 matching took ${elapsed}ms, expected < 200ms")
    }
}
