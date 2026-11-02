package kyrielie.blockbard.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogLevel {
    INFO, WARN, ERROR, DEBUG
}

data class LogEntry(
    val time: String,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatted(): String = "[$time] [${level.name}] [$tag] $message"
}

object BlockBardLogBuffer {
    private const val MAX_ENTRIES = 1000
    private const val HEADER_RESERVE_BYTES = 96
    private val entries = ConcurrentLinkedDeque<LogEntry>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun log(level: LogLevel, tag: String, message: String) {
        val timeStr = LocalTime.now().format(timeFormatter)
        entries.add(LogEntry(timeStr, level, tag, message))
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    /** DEBUG entries are stored only while [DebugLog.enabled] is true (the debugLogging setting). */
    fun debug(tag: String, message: String) {
        if (DebugLog.enabled) log(LogLevel.DEBUG, tag, message)
    }

    fun getAll(): List<LogEntry> = entries.toList()

    fun getAllText(): String = entries.joinToString("\n") { it.formatted() }

    /**
     * Returns the newest whole entries whose UTF-8 size fits within [maxBytes] (header included),
     * oldest first. When older entries were dropped, the text starts with a one-line header saying
     * how many. Exists because the clipboard path (SDL_SetClipboardText) copies the string onto
     * LWJGL's fixed-size thread-local stack; an unbounded string crashes with "Out of stack space".
     */
    fun getTailText(maxBytes: Int): String {
        val snapshot = entries.toList()
        val budget = maxBytes - HEADER_RESERVE_BYTES
        val selected = ArrayDeque<String>()
        var used = 0
        for (i in snapshot.indices.reversed()) {
            val line = snapshot[i].formatted()
            val cost = line.toByteArray(Charsets.UTF_8).size + 1 // newline
            if (used + cost > budget) break
            used += cost
            selected.addFirst(line)
        }
        val omitted = snapshot.size - selected.size
        val body = selected.joinToString("\n")
        return if (omitted > 0) "[BlockBard] $omitted older log entries omitted\n$body" else body
    }

    fun clear() {
        entries.clear()
    }

    val size: Int get() = entries.size
}
