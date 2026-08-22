package dev.harrison.mcinspector.packets

import dev.harrison.mcinspector.McInspectorClient
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.util.concurrent.ConcurrentHashMap

/**
 * JS for packet rules (Rhino, interpreted, hard deadline). A rule script sees:
 *  `packet` (the live packet object), `type`, `dir` ("IN"/"OUT"), `id` (record id),
 *  `get(packet, "field")`, `set(packet, "field", value)` → rewritten packet (records are copied),
 *  `drop()`, `hold()`, `log(...)`, `Packages.*` / `importClass` for Java.
 * Return value: a packet object → replacement (if different from the original); anything else → pass.
 * Condition scripts (LOG/DROP/PAUSE with a script) are expressions evaluated for truthiness.
 */
object RuleScripts {
    const val DEADLINE_MS = 50L

    private class Signal(val decision: Decision) : RuntimeException(null, null, false, false)

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 5_000
            applicationClassLoader = McInspectorClient::class.java.classLoader
        }
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = cx.getThreadLocal("deadline") as? Long ?: return
            if (System.nanoTime() > deadline) throw RuntimeException("rule script exceeded ${DEADLINE_MS} ms")
        }
    }

    private val compiled = ConcurrentHashMap<Int, Pair<String, Script>>()

    fun compile(rule: PacketRule): Script {
        val src = rule.script ?: throw IllegalStateException("rule has no script")
        compiled[rule.id]?.let { (s, sc) -> if (s == src) return sc }
        val cx = factory.enterContext()
        try {
            val sc = cx.compileString(src, "rule:${rule.name}", 1, null)
            compiled[rule.id] = src to sc
            return sc
        } finally { Context.exit() }
    }

    fun run(rule: PacketRule, packet: Any, dir: Direction, type: String, rec: PacketRecord): Decision {
        val result = exec(rule, packet, dir, type, rec) ?: return Decision.Pass
        if (result is Decision) return result
        val v = if (result is Wrapper) result.unwrap() else result
        return if (v != null && v !== packet && v is net.minecraft.network.protocol.Packet<*>) Decision.Replace(v) else Decision.Pass
    }

    fun condition(rule: PacketRule, packet: Any, dir: Direction, type: String, rec: PacketRecord): Boolean {
        val r = exec(rule, packet, dir, type, rec)
        return when (r) { null, is Undefined -> false; is Decision -> true; is Boolean -> r; is Number -> r.toDouble() != 0.0; is String -> r.isNotEmpty(); else -> true }
    }

    private fun exec(rule: PacketRule, packet: Any, dir: Direction, type: String, rec: PacketRecord): Any? {
        val script = compile(rule)
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal("deadline", System.nanoTime() + DEADLINE_MS * 1_000_000)
            val scope: ScriptableObject = ImporterTopLevel(cx)
            fun put(name: String, v: Any?) = ScriptableObject.putProperty(scope, name, if (v == null) null else Context.javaToJS(v, scope))
            put("packet", packet); put("type", type); put("dir", dir.name); put("id", rec.id)
            fn(scope, "get") { a -> PacketRewrite.get(unwrap(a[0])!!, a[1].toString()) }
            fn(scope, "set") { a -> PacketRewrite.set(unwrap(a[0])!!, a[1].toString(), a.getOrNull(2)) }
            fn(scope, "drop") { throw Signal(Decision.Drop) }
            fn(scope, "hold") { throw Signal(Decision.Hold) }
            fn(scope, "log") { a -> McInspectorClient.LOGGER.info("[MCInspector] rule {}: {}", rule.name, a.joinToString(" ") { Context.toString(it) }); Undefined.instance }
            return try { script.exec(cx, scope) } catch (s: Signal) { s.decision }
        } finally { Context.exit() }
    }

    private fun unwrap(v: Any?): Any? = if (v is Wrapper) v.unwrap() else v

    private fun fn(scope: Scriptable, name: String, body: (Array<Any?>) -> Any?) {
        ScriptableObject.putProperty(scope, name, object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                val r = body(args)
                return if (r == null || r is Undefined) Undefined.instance else Context.javaToJS(r, scope)
            }
        })
    }
}
