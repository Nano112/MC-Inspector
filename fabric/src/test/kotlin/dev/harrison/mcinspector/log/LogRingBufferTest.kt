package dev.harrison.mcinspector.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LogRingBufferTest {
    @Test fun keepsLastN() {
        val b = LogRingBuffer(3)
        listOf("a", "b", "c", "d").forEach { b.push("INFO", "L", it) }
        assertEquals(listOf("b", "c", "d"), b.lines().map { it.message })
    }

    @Test fun filtersByRegexAndLevel() {
        val b = LogRingBuffer(10)
        b.push("INFO", "L", "alpha"); b.push("WARN", "L", "beta"); b.push("WARN", "L", "gamma")
        assertEquals(listOf("beta"), b.lines(filter = Regex("b")).map { it.message })
        assertEquals(listOf("beta", "gamma"), b.lines(level = "warn").map { it.message })
        assertEquals(listOf("gamma"), b.lines(count = 1, level = "WARN").map { it.message })
    }
}
