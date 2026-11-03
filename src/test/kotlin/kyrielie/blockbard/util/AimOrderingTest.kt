package kyrielie.blockbard.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AimOrderingTest {

    private val angles = mapOf(
        "a" to (0f to 0f),
        "b" to (10f to 0f),
        "c" to (90f to 0f),
        "d" to (-170f to 0f),
        "e" to (175f to 0f)
    )

    private fun order(items: List<String>, yaw: Float = 0f, pitch: Float = 0f) =
        orderByAngularProximity(items, yaw, pitch) { angles[it] }

    @Test
    fun wrapDegrees_wrapsIntoHalfOpenRange() {
        assertEquals(-170f, wrapDegrees(190f))
        assertEquals(170f, wrapDegrees(-190f))
        assertEquals(0f, wrapDegrees(360f))
        assertEquals(-180f, wrapDegrees(180f))
        assertEquals(10f, wrapDegrees(10f))
    }

    @Test
    fun sweepsNearestFirst() {
        assertEquals(listOf("a", "b", "c"), order(listOf("c", "a", "b")))
    }

    @Test
    fun yawWrapsAcrossTheSeam() {
        // From yaw 180, "e" (175) and "d" (-170) are 5 and 10 degrees away; "a" is 180 away.
        assertEquals(listOf("e", "d", "a"), order(listOf("a", "d", "e"), yaw = 180f))
    }

    @Test
    fun itemsWithoutAnglesGoLastInOriginalOrder() {
        val result = orderByAngularProximity(listOf("x", "b", "y", "a"), 0f, 0f) { angles[it] }
        assertEquals(listOf("a", "b", "x", "y"), result)
    }

    @Test
    fun resultIsAPermutation_andEmptyIsEmpty() {
        val items = listOf("e", "c", "a", "d", "b")
        assertEquals(items.sorted(), order(items).sorted())
        assertEquals(emptyList<String>(), order(emptyList()))
    }

    @Test
    fun tiesKeepOriginalOrder() {
        val same = mapOf("p" to (5f to 0f), "q" to (5f to 0f))
        val result = orderByAngularProximity(listOf("p", "q"), 0f, 0f) { same[it] }
        assertEquals(listOf("p", "q"), result)
    }
}
