package dev.harrison.mcinspector.log

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keeps the last [capacity] log lines in memory for the `read_logs` tool. */
class LogRingBuffer(private val capacity: Int = 2000) {
    data class Line(val epochMs: Long, val level: String, val logger: String, val thread: String, val message: String) {
        fun format(): String = "${TS.format(Instant.ofEpochMilli(epochMs))} [$thread/$level] ($logger) $message"
        companion object { private val TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()) }
    }

    private val lines = ArrayDeque<Line>(capacity)

    fun push(line: Line) = synchronized(lines) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
    }

    fun push(level: String, logger: String, message: String, thread: String = "t", epochMs: Long = System.currentTimeMillis()) =
        push(Line(epochMs, level, logger, thread, message))

    /** Last [count] lines (oldest first) matching [level] (exact, case-insensitive) and [filter] (regex on formatted line). */
    fun lines(count: Int = 100, filter: Regex? = null, level: String? = null): List<Line> {
        val snapshot = synchronized(lines) { lines.toList() }
        return snapshot.asSequence()
            .filter { level == null || it.level.equals(level, ignoreCase = true) }
            .filter { filter == null || filter.containsMatchIn(it.format()) }
            .toList()
            .takeLast(count)
    }

    fun size(): Int = synchronized(lines) { lines.size }

    /** Attach to the log4j2 root logger so every mod/MC log line is captured. */
    fun install() {
        val appender = object : AbstractAppender("mcinspector-ring", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            override fun append(event: LogEvent) {
                val t = event.thrown
                val msg = if (t != null) "${event.message.formattedMessage} — ${t::class.java.simpleName}: ${t.message}" else event.message.formattedMessage
                push(Line(event.timeMillis, event.level.name(), event.loggerName.substringAfterLast('.'), event.threadName, msg))
            }
        }
        appender.start()
        val root = LogManager.getRootLogger() as Logger
        root.addAppender(appender)
    }
}
