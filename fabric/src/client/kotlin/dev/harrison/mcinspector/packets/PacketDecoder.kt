package dev.harrison.mcinspector.packets

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.UUID

/**
 * Reflective packet → JSON decoder. Pure Kotlin (no MC classes referenced directly) so it is unit-testable;
 * Minecraft value types are recognised by class name. Never throws: failures become `"<error: …>"` leaves.
 */
object PacketDecoder {
    const val MAX_DEPTH = 4
    const val MAX_ELEMENTS = 32
    const val MAX_STRING = 200

    /** Class names rendered via `toString()` as leaves (they have compact, meaningful toString()s). */
    private val leafByName = setOf(
        "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceKey",
        "net.minecraft.core.BlockPos", "net.minecraft.core.Vec3i", "net.minecraft.core.SectionPos", "net.minecraft.core.ChunkPos",
        "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.Vec2", "net.minecraft.world.level.ChunkPos",
        "net.minecraft.world.item.ItemStack", "net.minecraft.world.level.block.state.BlockState",
        "net.minecraft.network.chat.Component", "net.minecraft.world.entity.EntityType", "net.minecraft.core.Holder",
    )

    fun decode(value: Any?): JsonElement = try { encode(value, 0, HashSet()) } catch (t: Throwable) { JsonPrimitive("<error: ${t::class.simpleName}: ${t.message}>") }

    /** `k=v, k=v` from the first few top-level fields, for table rows. */
    fun summarize(fields: JsonElement, max: Int = 4, width: Int = 120): String {
        if (!fields.isJsonObject) return fields.toString().take(width)
        val parts = fields.asJsonObject.entrySet().take(max).map { (k, v) ->
            val s = when {
                v.isJsonPrimitive -> v.asJsonPrimitive.let { if (it.isString) it.asString else it.toString() }
                v.isJsonNull -> "null"
                v.isJsonArray -> "[${v.asJsonArray.size()}]"
                else -> "{…}"
            }
            "$k=${s.take(40)}"
        }
        return parts.joinToString(", ").take(width)
    }

    private fun encode(v: Any?, depth: Int, seen: MutableSet<Any>): JsonElement {
        if (v == null) return JsonNull.INSTANCE
        when (v) {
            is Boolean -> return JsonPrimitive(v)
            is Number -> return if (v is Double && (v.isNaN() || v.isInfinite()) || v is Float && (v.isNaN() || v.isInfinite())) JsonPrimitive(v.toString()) else JsonPrimitive(v)
            is Char -> return JsonPrimitive(v.toString())
            is String -> return JsonPrimitive(truncate(v))
            is Enum<*> -> return JsonPrimitive(v.name)
            is UUID -> return JsonPrimitive(v.toString())
            is Optional<*> -> return if (v.isPresent) encode(v.get(), depth, seen) else JsonNull.INSTANCE
        }
        val cls = v.javaClass
        if (isLeafType(cls)) return JsonPrimitive(truncate(safeToString(v)))
        if (depth >= MAX_DEPTH) return JsonPrimitive("<${cls.simpleName}>")
        if (cls.isArray) return encodeArray(v, depth, seen)
        if (v is Collection<*>) return encodeIterable(v, v.size, depth, seen)
        if (v is Map<*, *>) return JsonObject().also { o -> v.entries.take(MAX_ELEMENTS).forEach { (k, e) -> o.add(k.toString().take(64), encode(e, depth + 1, seen)) }; if (v.size > MAX_ELEMENTS) o.addProperty("…", "${v.size - MAX_ELEMENTS} more") }
        if (v is Iterable<*>) return encodeIterable(v, -1, depth, seen)
        if (v is ByteArray) return JsonPrimitive("<${v.size} bytes>")
        if (isNettyBuf(cls)) return JsonPrimitive("<ByteBuf ${runCatching { cls.getMethod("readableBytes").invoke(v) }.getOrDefault("?")} bytes>")
        // Identity-based cycle guard for structured objects.
        val key = IdentityKey(v)
        if (!seen.add(key)) return JsonPrimitive("<cycle ${cls.simpleName}>")
        try {
            val o = JsonObject()
            if (cls.isRecord) {
                for (rc in cls.recordComponents) {
                    val acc = rc.accessor.apply { runCatching { isAccessible = true } }
                    o.add(rc.name, runCatching { encode(acc.invoke(v), depth + 1, seen) }.getOrElse { JsonPrimitive("<error: ${it.message}>") })
                }
            } else {
                var c: Class<*>? = cls
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                        runCatching { f.isAccessible = true }.getOrElse { continue }
                        o.add(f.name, runCatching { encode(f.get(v), depth + 1, seen) }.getOrElse { JsonPrimitive("<error: ${it.message}>") })
                    }
                    c = c.superclass
                }
            }
            if (o.size() == 0) return JsonPrimitive(truncate(safeToString(v)))
            return o
        } finally {
            seen.remove(key)
        }
    }

    private fun encodeArray(v: Any, depth: Int, seen: MutableSet<Any>): JsonElement {
        val n = java.lang.reflect.Array.getLength(v)
        val a = JsonArray()
        for (i in 0 until minOf(n, MAX_ELEMENTS)) a.add(encode(java.lang.reflect.Array.get(v, i), depth + 1, seen))
        if (n > MAX_ELEMENTS) a.add(JsonPrimitive("… ${n - MAX_ELEMENTS} more"))
        return a
    }

    private fun encodeIterable(v: Iterable<*>, size: Int, depth: Int, seen: MutableSet<Any>): JsonElement {
        val a = JsonArray()
        var i = 0
        for (e in v) { if (i++ >= MAX_ELEMENTS) { a.add(JsonPrimitive("… ${if (size >= 0) "${size - MAX_ELEMENTS} more" else "more"}")); break }; a.add(encode(e, depth + 1, seen)) }
        return a
    }

    private fun isLeafType(cls: Class<*>): Boolean {
        var c: Class<*>? = cls
        while (c != null) {
            if (c.name in leafByName) return true
            if (c.interfaces.any { it.name in leafByName }) return true
            c = c.superclass
        }
        return false
    }

    private fun isNettyBuf(cls: Class<*>): Boolean {
        var c: Class<*>? = cls
        while (c != null) { if (c.name == "io.netty.buffer.ByteBuf") return true; c = c.superclass }
        return false
    }

    private fun safeToString(v: Any): String = runCatching { v.toString() }.getOrElse { "<${v.javaClass.simpleName}>" }
    private fun truncate(s: String): String = if (s.length > MAX_STRING) s.take(MAX_STRING) + "…(${s.length})" else s

    private class IdentityKey(val o: Any) {
        override fun equals(other: Any?) = other is IdentityKey && other.o === o
        override fun hashCode() = System.identityHashCode(o)
    }
}
