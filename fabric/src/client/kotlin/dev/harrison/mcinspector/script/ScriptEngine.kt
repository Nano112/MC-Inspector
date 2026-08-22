package dev.harrison.mcinspector.script

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.harrison.mcinspector.McInspectorClient
import dev.harrison.mcinspector.mcp.ToolRegistry
import dev.harrison.mcinspector.packets.PacketCapture
import net.minecraft.client.Minecraft
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper

/**
 * JavaScript escape hatch (Rhino, interpreted). Scripts see:
 *  - `mc`        Minecraft.getInstance()            - `player`, `level`, `window` shortcuts (may be null)
 *  - `packets`   PacketStore (captured packets)     - `panellib` PanelLibApi or null
 *  - `tools.call(name, argsObject)` → parsed JSON result of any MCP tool (except exec_script)
 *  - `log(...)`  collected into the result          - `Packages.<fqcn>` / `importClass` for Java access
 * A deadline aborts runaway scripts via Rhino's instruction observer, so the render thread never hangs.
 */
class ScriptEngine(private val registry: ToolRegistry) {
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1                   // interpreted: no class generation, works on any JVM
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 10_000
            applicationClassLoader = McInspectorClient::class.java.classLoader
        }
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = cx.getThreadLocal("deadline") as? Long ?: return
            if (System.nanoTime() > deadline) throw ScriptTimeout()
        }
    }

    class ScriptTimeout : RuntimeException("script exceeded its time budget")

    data class Outcome(val value: String, val log: List<String>)

    fun run(code: String, timeoutMs: Long): Outcome {
        val cx = factory.enterContext()
        val logs = ArrayList<String>()
        try {
            cx.putThreadLocal("deadline", System.nanoTime() + timeoutMs * 1_000_000)
            val scope: ScriptableObject = org.mozilla.javascript.ImporterTopLevel(cx)   // standard objects + importClass/importPackage
            val mc = Minecraft.getInstance()
            fun put(name: String, v: Any?) = ScriptableObject.putProperty(scope, name, if (v == null) null else Context.javaToJS(v, scope))
            put("mc", mc); put("player", mc.player); put("level", mc.level); put("window", mc.window)
            put("packets", PacketCapture.store)
            put("panellib", runCatching { dev.harrison.panellib.PanelLib.api() }.getOrNull())
            put("tools", ToolBridge(registry, cx, scope))
            ScriptableObject.putProperty(scope, "log", object : org.mozilla.javascript.BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
                    logs += args.joinToString(" ") { stringify(cx, scope, it) }; return Undefined.instance
                }
            })
            val result = cx.evaluateString(scope, code, "exec_script", 1, null)
            return Outcome(stringify(cx, scope, result), logs)
        } finally {
            Context.exit()
        }
    }

    private fun stringify(cx: Context, scope: Scriptable, v: Any?): String = when {
        v == null || v is Undefined -> "undefined"
        v is Wrapper -> v.unwrap().toString()
        v is Scriptable && v !is NativeJavaObject -> runCatching { NativeJSON.stringify(cx, scope, v, null, 2).toString() }.getOrElse { Context.toString(v) }
        else -> Context.toString(v)
    }

    /** `tools.call("get_state", {})` — lets scripts compose the other MCP tools. */
    class ToolBridge(private val registry: ToolRegistry, private val cx: Context, private val scope: Scriptable) {
        fun call(name: String): Any? = call(name, null)

        fun call(name: String, args: Any?): Any? {
            require(name != "exec_script") { "exec_script cannot call itself" }
            val tool = registry.get(name) ?: throw IllegalArgumentException("unknown tool '$name'")
            val json: JsonObject = when (args) {
                null, is Undefined -> JsonObject()
                is Scriptable -> JsonParser.parseString(NativeJSON.stringify(cx, scope, args, null, 0).toString()).asJsonObject
                is String -> JsonParser.parseString(args).asJsonObject
                else -> JsonObject()
            }
            val r = tool.call(json)
            val text = r.content.filter { it.get("type")?.asString == "text" }.joinToString("\n") { it.get("text").asString }
            if (r.isError) throw RuntimeException("tool $name failed: $text")
            // Return parsed JSON when possible so scripts can index into results.
            return runCatching { val el = JsonParser.parseString(text); NativeJSON.parse(cx, scope, el.toString()) { _, _, _, a -> a[1] } }.getOrDefault(text)
        }
    }
}
