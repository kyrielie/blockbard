package kyrielie.blockbard.client.util

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-click tuning trace, one file per tuning run (overwritten by the next run):
 * `<gameDir>/logs/blockbard-tuning-trace.txt`. Kept out of [kyrielie.blockbard.util.BlockBardLogBuffer]
 * so it cannot crowd out other log entries or grow the clipboard text. Lines are flushed as written
 * and capped, so a crash still leaves a usable file. I/O failures are logged once and ignored.
 */
object TuningTraceFile {
    private val logger = LoggerFactory.getLogger("BlockBard/TuningTrace")
    private const val MAX_LINES = 5000

    private var writer: BufferedWriter? = null
    private var lines = 0
    private var failed = false

    val path: Path get() = FabricLoader.getInstance().gameDir.resolve("logs").resolve("blockbard-tuning-trace.txt")

    @Synchronized
    fun begin(header: String) {
        close()
        lines = 0
        failed = false
        try {
            val p = path
            Files.createDirectories(p.parent)
            writer = Files.newBufferedWriter(p)
            write("# $header")
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @Synchronized
    fun write(line: String) {
        val w = writer ?: return
        if (lines >= MAX_LINES) return
        try {
            w.write(line)
            w.newLine()
            w.flush()
            lines++
            if (lines == MAX_LINES) {
                w.write("# trace truncated at $MAX_LINES lines")
                w.newLine()
                w.flush()
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @Synchronized
    fun close() {
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
    }

    private fun fail(t: Throwable) {
        if (!failed) logger.warn("Tuning trace file disabled: ${t.message}")
        failed = true
        close()
    }
}
