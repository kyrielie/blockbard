package kyrielie.blockbard.organ

import java.util.Locale

/**
 * Diagnostic summary of a completed or failed noteblock tuning run.
 */
data class TunerSummary(
    val targets: Int,
    val clicks: Int,
    val retries: Int,
    val timeSeconds: Double,
    val pingMs: Int,
    val reachDistance: Double,
    val isCreative: Boolean,
    val failCounts: Map<TuningFailState, Int> = emptyMap()
) {
    /**
     * Formats the summary into a single structured diagnostic log line.
     *
     * Example:
     * `[BlockBard/Tuner] Run summary: targets=119 clicks=409 retries=1 time=2.4s ping=52ms reach=4.5 (creative=false) fails=[OCCLUDED:0, OUT_OF_REACH:0, UNRESPONSIVE:0, DESYNC:0]`
     */
    fun format(): String {
        val timeStr = String.format(Locale.ROOT, "%.1fs", timeSeconds)
        val reachStr = String.format(Locale.ROOT, "%.1f", reachDistance)

        val primaryStates = listOf(
            TuningFailState.OCCLUDED,
            TuningFailState.OUT_OF_REACH,
            TuningFailState.UNRESPONSIVE,
            TuningFailState.DESYNC
        )
        val primaryFails = primaryStates.joinToString(", ") { "${it.name}:${failCounts[it] ?: 0}" }

        val otherFails = failCounts
            .filter { (k, v) -> v > 0 && k !in primaryStates }
            .map { ", ${it.key.name}:${it.value}" }
            .joinToString("")

        return "[BlockBard/Tuner] Run summary: targets=$targets clicks=$clicks retries=$retries " +
            "time=$timeStr ping=${pingMs}ms reach=$reachStr (creative=$isCreative) " +
            "fails=[$primaryFails$otherFails]"
    }
}
