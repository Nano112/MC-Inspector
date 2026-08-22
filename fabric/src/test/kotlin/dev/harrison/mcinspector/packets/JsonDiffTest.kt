package dev.harrison.mcinspector.packets

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonDiffTest {
    private fun j(s: String) = JsonParser.parseString(s)

    @Test fun reportsChangedAddedRemovedPaths() {
        val c = JsonDiff.diff(j("""{"a":1,"b":{"x":[1,2]},"gone":true}"""), j("""{"a":2,"b":{"x":[1,3,4]},"new":"n"}"""))
        val paths = c.map { it.path }
        assertEquals(listOf("a", "b.x[1]", "b.x[2]", "gone", "new"), paths)
        assertEquals("1", c[0].before.toString()); assertEquals("2", c[0].after.toString())
        assertTrue(c.first { it.path == "gone" }.after == null)
    }

    @Test fun identicalIsEmpty() = assertTrue(JsonDiff.diff(j("""{"a":[1,{"b":2}]}"""), j("""{"a":[1,{"b":2}]}""")).isEmpty())

    @Test fun hexDump() {
        val lines = Hex.dump(byteArrayOf(0x7a, 0x0b, 0x41, 0x42, 0xff.toByte()))
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("00000000  7a 0b 41 42 ff"), lines[0])
        assertTrue(lines[0].endsWith("|z.AB.|"), lines[0])
    }
}
