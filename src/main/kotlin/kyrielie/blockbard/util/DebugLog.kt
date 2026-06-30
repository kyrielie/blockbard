package kyrielie.blockbard.util

import org.slf4j.Logger

/**
 * Conditional logger for hot-path call sites that produce too much output during
 * normal playback (per-tick rotation, per-note dispatch, per-enqueue lines).
 *
 * When [enabled] is false (the default), messages are routed to logger.debug() and
 * suppressed by the default Minecraft/Log4j2 config. When true, they are routed to
 * logger.info() and appear in the log file and console.
 *
 * [enabled] is set from BlockBardConfig.debugLogging at init in BlockBardClient and
 * re-applied whenever the in-game debug toggle is changed.
 *
 * Use a lambda to avoid string construction cost when disabled:
 *   DebugLog.info(logger) { "primeRotation $pos: yaw $yaw" }
 */
object DebugLog {
    @Volatile
    var enabled: Boolean = false

    fun info(logger: Logger, msg: () -> String) {
        if (enabled) logger.info(msg()) else logger.debug(msg())
    }
}
