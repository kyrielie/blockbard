package kyrielie.blockbard.organ

/**
 * Kuhn-Munkres (Hungarian) algorithm for minimum cost bipartite matching.
 * Solves the assignment problem on an n x m matrix where n <= m.
 * Finds an injection from all n rows to distinct columns minimizing the total cost.
 *
 * Time complexity: O(n^2 * m)
 */
class HungarianAlgorithm(
    val n: Int,
    val m: Int,
    private val cost: Array<LongArray>
) {
    init {
        require(n <= m) { "Number of rows ($n) must be <= number of columns ($m)" }
    }

    companion object {
        const val INF: Long = 1_000_000_000_000L // 10^12
    }

    /**
     * Solves the assignment problem.
     * Returns an IntArray of length n, where result[i] is the matched column (0 until m)
     * for row i (0 until n).
     */
    fun solve(): IntArray {
        if (n == 0) return IntArray(0)

        val u = LongArray(n + 1)
        val v = LongArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = LongArray(m + 1) { Long.MAX_VALUE / 2 }
            val used = BooleanArray(m + 1)

            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Long.MAX_VALUE / 2
                var j1 = 0

                for (j in 1..m) {
                    if (!used[j]) {
                        val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (cur < minv[j]) {
                            minv[j] = cur
                            way[j] = j0
                        }
                        if (minv[j] < delta) {
                            delta = minv[j]
                            j1 = j
                        }
                    }
                }

                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }

                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val result = IntArray(n) { -1 }
        for (j in 1..m) {
            if (p[j] != 0) {
                result[p[j] - 1] = j - 1
            }
        }
        return result
    }
}
