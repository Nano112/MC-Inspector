package dev.harrison.mcinspector.mcp

import java.util.concurrent.atomic.AtomicLong

/** Lightweight counters shown in the panel-lib status panel. */
object McpStats {
    val startedAt: Long = System.currentTimeMillis()
    private val calls = AtomicLong()
    @Volatile var lastTool: String = "—"; private set
    @Volatile var lastCallAt: Long = 0; private set
    @Volatile var boundPort: Int = -1
    private val perTool = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    fun record(tool: String) {
        calls.incrementAndGet(); lastTool = tool; lastCallAt = System.currentTimeMillis()
        perTool.computeIfAbsent(tool) { AtomicLong() }.incrementAndGet()
    }
    val totalCalls: Long get() = calls.get()
    fun perTool(): Map<String, Long> = perTool.mapValues { it.value.get() }.toSortedMap()
}
