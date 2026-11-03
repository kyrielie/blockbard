package kyrielie.blockbard.util

import kotlin.math.sqrt

/** Wraps [degrees] into [-180, 180). */
fun wrapDegrees(degrees: Float): Float = (((degrees % 360f) + 540f) % 360f) - 180f

/**
 * Orders [items] greedily by angular proximity: starting from ([startYaw], [startPitch]) it repeatedly
 * picks the remaining item whose aim direction is closest (yaw difference wrapped at 180 degrees).
 * Keeps the camera sweep short and smooth between consecutive blocks. Items for which [angles]
 * returns null keep their original relative order at the end. Ties go to the earlier item.
 */
fun <T> orderByAngularProximity(
    items: List<T>,
    startYaw: Float,
    startPitch: Float,
    angles: (T) -> Pair<Float, Float>?
): List<T> {
    val withAngles = ArrayList<Pair<T, Pair<Float, Float>>>()
    val without = ArrayList<T>()
    for (item in items) {
        val a = angles(item)
        if (a != null) withAngles += item to a else without += item
    }

    val result = ArrayList<T>(items.size)
    var yaw = startYaw
    var pitch = startPitch
    val remaining = withAngles.toMutableList()
    while (remaining.isNotEmpty()) {
        var bestIdx = 0
        var bestCost = Float.MAX_VALUE
        for (i in remaining.indices) {
            val (ay, ap) = remaining[i].second
            val dy = wrapDegrees(ay - yaw)
            val dp = ap - pitch
            val cost = sqrt(dy * dy + dp * dp)
            if (cost < bestCost) { bestCost = cost; bestIdx = i }
        }
        val picked = remaining.removeAt(bestIdx)
        result += picked.first
        yaw = picked.second.first
        pitch = picked.second.second
    }
    result += without
    return result
}
