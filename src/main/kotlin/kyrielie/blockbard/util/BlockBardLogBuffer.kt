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
    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)

    fun getAll(): List<LogEntry> = entries.toList()

    fun getAllText(): String = entries.joinToString("\n") { it.formatted() }

    fun clear() {
        entries.clear()
    }

    val size: Int get() = entries.size
}
