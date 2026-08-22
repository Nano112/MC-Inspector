package dev.harrison.mcinspector.packets

import java.lang.reflect.Modifier
import java.util.Optional
import java.util.UUID

/**
 * Build a modified packet. Most packets are records (immutable): a copy is made through the canonical
 * constructor with the named component replaced. Plain classes are mutated in place. Values are coerced
 * to the target type (numbers, booleans, strings, enums by name, UUID, Optional).
 */
object PacketRewrite {
    fun set(packet: Any, field: String, value: Any?): Any = setAll(packet, mapOf(field to value))

    fun setAll(packet: Any, changes: Map<String, Any?>): Any {
        val cls = packet.javaClass
        if (cls.isRecord) {
            val comps = cls.recordComponents
            val unknown = changes.keys - comps.map { it.name }.toSet()
            require(unknown.isEmpty()) { "unknown component(s) $unknown on ${cls.simpleName}; has ${comps.map { it.name }}" }
            val args = comps.map { c ->
                val acc = c.accessor.apply { isAccessible = true }
                if (c.name in changes) coerce(changes[c.name], c.type, c.name) else acc.invoke(packet)
            }.toTypedArray()
            val ctor = cls.getDeclaredConstructor(*comps.map { it.type }.toTypedArray()).apply { isAccessible = true }
            return ctor.newInstance(*args)
        }
        for ((name, v) in changes) {
            val f = findField(cls, name) ?: throw IllegalArgumentException("no field '$name' on ${cls.simpleName}")
            f.isAccessible = true
            if (Modifier.isFinal(f.modifiers)) throw IllegalArgumentException("field '$name' is final on non-record ${cls.simpleName}; cannot rewrite")
            f.set(packet, coerce(v, f.type, name))
        }
        return packet
    }

    /** Read a component/field by name (records or classes). */
    fun get(packet: Any, field: String): Any? {
        val cls = packet.javaClass
        if (cls.isRecord) {
            val c = cls.recordComponents.firstOrNull { it.name == field } ?: throw IllegalArgumentException("no component '$field' on ${cls.simpleName}")
            return c.accessor.apply { isAccessible = true }.invoke(packet)
        }
        val f = findField(cls, field) ?: throw IllegalArgumentException("no field '$field' on ${cls.simpleName}")
        f.isAccessible = true
        return f.get(packet)
    }

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) { c.declaredFields.firstOrNull { it.name == name }?.let { return it }; c = c.superclass }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun coerce(v: Any?, target: Class<*>, name: String): Any? {
        val raw = if (v is org.mozilla.javascript.Wrapper) v.unwrap() else v
        if (raw == null) return if (target.isPrimitive) throw IllegalArgumentException("'$name' is primitive ${target.simpleName}, cannot be null") else null
        return when {
            target.isInstance(raw) && !target.isPrimitive -> raw
            target == Int::class.javaPrimitiveType || target == Integer::class.java -> (raw as Number).toInt()
            target == Long::class.javaPrimitiveType || target == java.lang.Long::class.java -> (raw as Number).toLong()
            target == Double::class.javaPrimitiveType || target == java.lang.Double::class.java -> (raw as Number).toDouble()
            target == Float::class.javaPrimitiveType || target == java.lang.Float::class.java -> (raw as Number).toFloat()
            target == Short::class.javaPrimitiveType || target == java.lang.Short::class.java -> (raw as Number).toShort()
            target == Byte::class.javaPrimitiveType || target == java.lang.Byte::class.java -> (raw as Number).toByte()
            target == Boolean::class.javaPrimitiveType || target == java.lang.Boolean::class.java -> raw as Boolean
            target == String::class.java -> raw.toString()
            target == UUID::class.java -> UUID.fromString(raw.toString())
            target.isEnum -> (target.enumConstants as Array<Enum<*>>).first { it.name.equals(raw.toString(), true) }
            target == Optional::class.java -> Optional.ofNullable(raw)
            else -> throw IllegalArgumentException("cannot convert ${raw.javaClass.simpleName} to ${target.simpleName} for '$name'")
        }
    }
}
