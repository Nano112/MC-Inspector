package dev.harrison.mcinspector.packets

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull

/** Structural diff of two decoded packets (pure, unit-tested). */
object JsonDiff {
    data class Change(val path: String, val before: JsonElement?, val after: JsonElement?)

    fun diff(a: JsonElement?, b: JsonElement?, path: String = "", out: MutableList<Change> = ArrayList()): List<Change> {
        val x = a ?: JsonNull.INSTANCE
        val y = b ?: JsonNull.INSTANCE
        when {
            x.isJsonObject && y.isJsonObject -> {
                val keys = LinkedHashSet<String>().apply { addAll(x.asJsonObject.keySet()); addAll(y.asJsonObject.keySet()) }
                for (k in keys) diff(x.asJsonObject.get(k), y.asJsonObject.get(k), if (path.isEmpty()) k else "$path.$k", out)
            }
            x.isJsonArray && y.isJsonArray -> {
                val n = maxOf(x.asJsonArray.size(), y.asJsonArray.size())
                for (i in 0 until n) diff(x.asJsonArray.getOrNull(i), y.asJsonArray.getOrNull(i), "$path[$i]", out)
            }
            x != y -> out += Change(path.ifEmpty { "$" }, if (a == null) null else x, if (b == null) null else y)
        }
        return out
    }

    private fun JsonArray.getOrNull(i: Int): JsonElement? = if (i < size()) get(i) else null

    fun toJson(changes: List<Change>): JsonArray = JsonArray().apply {
        for (c in changes) add(com.google.gson.JsonObject().apply {
            addProperty("path", c.path)
            add("before", c.before ?: JsonNull.INSTANCE)
            add("after", c.after ?: JsonNull.INSTANCE)
        })
    }
}

/** `00000000  7a 0b 03 ...  |z..|` style dump. */
object Hex {
    fun dump(bytes: ByteArray, width: Int = 16): List<String> {
        val out = ArrayList<String>()
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + width, bytes.size)
            val hex = (off until end).joinToString(" ") { "%02x".format(bytes[it].toInt() and 0xFF) }
            val ascii = (off until end).joinToString("") { val c = bytes[it].toInt() and 0xFF; if (c in 32..126) c.toChar().toString() else "." }
            out += "%08x  %-${width * 3 - 1}s  |%s|".format(off, hex, ascii)
            off = end
        }
        return out
    }
}
