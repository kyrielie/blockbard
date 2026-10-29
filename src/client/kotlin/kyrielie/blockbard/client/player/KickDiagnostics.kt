package kyrielie.blockbard.client.player

import kyrielie.blockbard.organ.ArpeggioScheduler
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory

/**
 * Captures a self-contained postmortem the moment the client disconnects from a
 * server, so a packet-rate kick (or any other disconnect) can be diagnosed directly
 * from the client log without fetching an external log archive and grepping by hand.
 *
 * Two things are correlated:
 *   1. The disconnect reason text, read off whatever screen Minecraft has switched to
 *      by the time ClientPlayConnectionEvents.DISCONNECT fires (see reasonFromScreen).
 *   2. ArpeggioScheduler.recentDispatches() -- the last RECENT_DISPATCH_CAPACITY
 *      interacts sent, with the token bucket's value immediately before/after each.
 *
 * Why this matters: SoundVerifier confirms a sound was *heard*, which is a client-side
 * echo of a successful round trip. It says nothing about whether the server's own rate
 * limiter was content with the send rate -- a server can silently drop interacts before
 * ever reaching a kick threshold, or kick on a threshold stricter than the 8-per-310ms
 * assumption baked into the bucket's refill rate. The only ground truth for "where is
 * the real limit" is what the server itself says when it cuts the connection, correlated
 * against exactly what was sent leading up to that moment. Without this, finding that
 * number means manually fetching an external log (as in the mclo.gs report that led to
 * this) after every test run; with it, the answer prints inline as soon as it happens.
 */
object KickDiagnostics {

    private val logger = LoggerFactory.getLogger("BlockBard/KickDiagnostics")

    /** How far back to summarise dispatch history in the postmortem. */
    private const val SHORT_WINDOW_MS = 310L   // matches the Paper rate-limit window
    private const val LONG_WINDOW_MS = 1000L

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            onDisconnect(client)
        }
        logger.info("KickDiagnostics registered on ClientPlayConnectionEvents.DISCONNECT")
    }

    private fun onDisconnect(client: Minecraft) {
        val reason = reasonFromScreen(client)
        val history = ArpeggioScheduler.recentDispatches()
        val now = System.currentTimeMillis()

        logger.warn("DISCONNECTED: reason=\"$reason\"")

        if (history.isEmpty()) {
            logger.warn("  no recent ArpeggioScheduler dispatches recorded -- disconnect is likely unrelated to note playback")
            return
        }

        val last = history.last()
        val shortWindow = history.filter { now - it.atMs <= SHORT_WINDOW_MS }
        val longWindow = history.filter { now - it.atMs <= LONG_WINDOW_MS }

        logger.warn("  bucket budget at last dispatch: before=${"%.2f".format(last.budgetBefore)} after=${"%.2f".format(last.budgetAfter)}")
        logger.warn("  last ${SHORT_WINDOW_MS}ms: ${shortWindow.size} interacts sent")
        logger.warn("  last ${LONG_WINDOW_MS}ms: ${longWindow.size} interacts sent")

        if (shortWindow.size >= 2) {
            val spanMs = shortWindow.last().atMs - shortWindow.first().atMs
            logger.warn("  short-window detail: ${shortWindow.size} interacts spanning ${spanMs}ms")
        }

        // Dump the raw tail for manual inspection -- cheap, and the exact sequence
        // (not just the count) matters when correlating against the server's own log.
        logger.warn("  dispatch tail (oldest first):")
        history.takeLast(20).forEach { record ->
            val ageMs = now - record.atMs
            logger.warn(
                "    -${ageMs}ms MIDI ${record.midiNote} -> ${record.pos} " +
                    "budget ${"%.2f".format(record.budgetBefore)}->${"%.2f".format(record.budgetAfter)}"
            )
        }

        if (last.budgetAfter < 0f) {
            logger.warn(
                "  budget went negative (${"%.2f".format(last.budgetAfter)}) -- the configured ceiling " +
                    "(ArpeggioScheduler.maxNotesPerTick) allowed more sends than the bucket had tokens for. " +
                    "If this disconnect was a packet-rate kick, lower maxNotesPerTick below its current value " +
                    "and/or lower the bucket ceiling."
            )
        } else {
            logger.warn(
                "  budget stayed non-negative -- if this disconnect was a packet-rate kick, the real server " +
                    "limit is likely stricter than the 8-per-310ms assumption the bucket refill rate is based on."
            )
        }
    }

    /**
     * Best-effort extraction of the disconnect reason text from whatever Screen
     * Minecraft has switched to by the time DISCONNECT fires (typically a
     * DisconnectedScreen, but mod-injected disconnect screens exist too -- e.g.
     * ModMismatchDisconnectedScreen). Uses reflection rather than a hard import
     * against a specific accessor name/signature, since that detail was not
     * verified against this project's exact Minecraft/mapping version and a wrong
     * guess would either fail to compile or silently match the wrong method.
     * Degrades to a fixed placeholder string rather than throwing -- a disconnect
     * handler must never itself crash.
     */
    private fun reasonFromScreen(client: Minecraft): String {
        val screen = client.gui.screen() ?: return "(no screen set -- reason unavailable)"
        return runCatching {
            val screenClass: Class<*> = screen::class.java
            val candidateMethodNames = listOf("getReason", "reason", "getMessage")
            for (methodName in candidateMethodNames) {
                val method = runCatching { screenClass.getMethod(methodName) }.getOrNull() ?: continue
                val result = runCatching { method.invoke(screen) }.getOrNull() ?: continue
                return result.toString()
            }
            "(${screenClass.simpleName}, reason text not found via reflection)"
        }.getOrElse { "(reason extraction failed: ${it.message})" }
    }
}
